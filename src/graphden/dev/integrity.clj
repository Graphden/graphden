(ns graphden.dev.integrity
  "Graph integrity checker — detectors for the LEGACY-SHAPE classes a
   long-lived database accumulates and fresh test DBs never see
   (audit-3, 2026-07-24). The live-demo outage shape motivating this:
   fn identity rows abandoned by historical namespace moves (a new
   deterministic id is minted; the old row and every resolved ref to
   it survive un-tombstoned), with the rich-types registry keyed
   under the CURRENT id only.

   DETECTORS (storage-level; pure reads):
     :stale-identities   — >1 non-tombstoned :fn row per
                           (namespace-id, name); the extras are
                           ns-move / rename / delete-recreate orphans.
     :dangling-refs      — binding.ref-fn-id / type-override-fn-id,
                           binding-list-item.ref-fn-id, slot.type-fn-id,
                           fn.{base,element,return-type}-fn-id and
                           parent-ids pointing at a STALE extra or at
                           no row at all.
     :orphan-versions    — *-version rows whose identity row is gone,
                           or whose :branch-id has no :branch row
                           (delete-branch! children-refusal leftovers).
     :orphan-anons       — anonymous fn rows (:anonymous-hash set or
                           _anon- name) with zero inbound refs —
                           pre-ns-salt leftovers accumulate here.
     :orphan-slots       — :slot rows no :fn-slot references (slots
                           are deliberately never reconciled; inert
                           but enumerable).

   REPAIR (explicit, per finding class — see `repair-stale-identities!`):
     bindings/parents referencing a stale extra are REPOINTED to the
     canonical same-named row, then the extra is soft-deleted. Matches
     the compile-time rescue's prescription ('repoint or tombstone the
     legacy fn row', registry/rich-type-of-id-or-stale-name).

   Run from the REPL / a dev alias:
     (report storage)              ;; full detect map
     (repair-stale-identities! storage {:dry-run? true})"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Detectors
;; =============================================================================

(defn- all-rows
  [storage entity]
  (sp/query-entities storage entity {}))


(defn stale-identities
  "Groups of same-(namespace-id, name) :fn rows. Returns
   `[{:namespace-id … :name … :canonical row :extras [row …]} …]` —
   canonical = the row the most live refs point at (ties: the row with
   the most recent version activity wins, else arbitrary-but-stable by
   id). Anonymous rows (nil :name) are excluded — dedup by
   :anonymous-hash is their identity, not the name."
  [storage]
  (let [fns (remove #(nil? (:name %)) (all-rows storage :fn))
        by-key (group-by (juxt :namespace-id :name) fns)
        dups (filter #(> (count (val %)) 1) by-key)
        ;; inbound ref counts over the live graph
        inbound (volatile! {})
        count! (fn [id] (when id (vswap! inbound update id (fnil inc 0))))]
    (when (seq dups)
      (doseq [b (all-rows storage :binding)]
        (count! (:ref-fn-id b))
        (count! (:type-override-fn-id b))
        (count! (:resolver-fn-id b)))
      (doseq [i (all-rows storage :binding-list-item)]
        (count! (:ref-fn-id i)))
      (doseq [s (all-rows storage :slot)]
        (count! (:type-fn-id s)))
      (doseq [f fns]
        (doseq [p (:parent-ids f)] (count! p))
        (count! (:base-fn-id f))
        (count! (:element-fn-id f))
        (count! (:return-type-fn-id f))))
    (mapv (fn [[[ns-id nm] rows]]
            (let [ranked (sort-by (fn [r]
                                    [(- (get @inbound (:id r) 0))
                                     (str (:id r))])
                                  rows)]
              {:namespace-id ns-id
               :name nm
               :canonical (first ranked)
               :extras (vec (rest ranked))}))
          dups)))


(defn dangling-refs
  "Refs pointing at a MISSING fn row, plus refs pointing at a stale
   extra (when `stale` — the `stale-identities` result — is given).
   Returns `[{:entity … :id … :field … :target …} …]`."
  ([storage] (dangling-refs storage (stale-identities storage)))
  ([storage stale]
   (let [fn-ids (into #{} (map :id) (all-rows storage :fn))
         extra-ids (into #{} (comp (mapcat :extras) (map :id)) stale)
         hit (fn [entity row field target]
               (when (and target
                          (or (not (contains? fn-ids target))
                              (contains? extra-ids target)))
                 {:entity entity :id (:id row) :field field :target target
                  :kind (if (contains? extra-ids target)
                          :stale-extra
                          :missing)}))]
     (vec
       (concat
         (for [b (all-rows storage :binding)
               [f t] [[:ref-fn-id (:ref-fn-id b)]
                      [:type-override-fn-id (:type-override-fn-id b)]
                      [:resolver-fn-id (:resolver-fn-id b)]]
               :let [d (hit :binding b f t)]
               :when d]
           d)
         (for [i (all-rows storage :binding-list-item)
               :let [d (hit :binding-list-item i :ref-fn-id (:ref-fn-id i))]
               :when d]
           d)
         (for [s (all-rows storage :slot)
               :let [d (hit :slot s :type-fn-id (:type-fn-id s))]
               :when d]
           d)
         (for [f (all-rows storage :fn)
               [fld t] (concat (map (fn [p] [:parent-ids p]) (:parent-ids f))
                               [[:base-fn-id (:base-fn-id f)]
                                [:element-fn-id (:element-fn-id f)]
                                [:return-type-fn-id (:return-type-fn-id f)]])
               :let [d (hit :fn f fld t)]
               :when d]
           d))))))


(defn orphan-versions
  "*-version rows whose identity row is gone, or whose branch row is
   gone. `[{:entity … :id … :reason :no-identity|:no-branch} …]`."
  [storage]
  (let [branch-ids (into #{} (map :id) (all-rows storage :branch))
        check (fn [ventity id-field identity-entity]
                (let [ids (into #{} (map :id)
                                (all-rows storage identity-entity))]
                  (for [v (all-rows storage ventity)
                        :let [reason (cond
                                       (not (contains? ids (id-field v)))
                                       :no-identity
                                       (not (contains? branch-ids
                                                       (:branch-id v)))
                                       :no-branch)]
                        :when reason]
                    {:entity ventity :id (:id v) :reason reason})))]
    (vec (concat (check :fn-version :fn-id :fn)
                 (check :binding-version :binding-id :binding)
                 (check :fn-slot-version :fn-slot-id :fn-slot)
                 (check :binding-list-item-version :item-id
                        :binding-list-item)))))


(defn orphan-anons
  "Anonymous fn rows with zero inbound refs — pre-ns-salt leftovers
   accumulate here (the salt change re-minted every fn-def anon id)."
  [storage]
  (let [inbound (volatile! #{})
        mark! (fn [id] (when id (vswap! inbound conj id)))]
    (doseq [b (all-rows storage :binding)]
      (mark! (:ref-fn-id b))
      (mark! (:type-override-fn-id b))
      (mark! (:resolver-fn-id b)))
    (doseq [i (all-rows storage :binding-list-item)] (mark! (:ref-fn-id i)))
    (doseq [s (all-rows storage :slot)] (mark! (:type-fn-id s)))
    (doseq [f (all-rows storage :fn)]
      (doseq [p (:parent-ids f)] (mark! p))
      (mark! (:base-fn-id f))
      (mark! (:element-fn-id f))
      (mark! (:return-type-fn-id f)))
    (vec (for [f (all-rows storage :fn)
               :when (and (or (:anonymous-hash f)
                              (some-> (:name f) (str/starts-with? "_anon-")))
                          (not (contains? @inbound (:id f))))]
           {:id (:id f) :name (:name f)
            :anonymous-hash (:anonymous-hash f)}))))


(defn orphan-slots
  "Slot rows no fn-slot junction references (never reconciled by
   design; inert, listed for completeness)."
  [storage]
  (let [used (into #{} (map :slot-id) (all-rows storage :fn-slot))]
    (vec (for [s (all-rows storage :slot)
               :when (not (contains? used (:id s)))]
           {:id (:id s) :name (:name s)}))))


(defn report
  "Full detect pass. Counts in `:summary`; details per key."
  [storage]
  (let [stale (stale-identities storage)
        result {:stale-identities stale
                :dangling-refs (dangling-refs storage stale)
                :orphan-versions (orphan-versions storage)
                :orphan-anons (orphan-anons storage)
                :orphan-slots (orphan-slots storage)}]
    (assoc result :summary
           (into {} (map (fn [[k v]] [k (count v)])) result))))


;; =============================================================================
;; Repair — stale-identity class only (the outage class). Other
;; classes are inert (orphans) or need per-case judgement.
;; =============================================================================

(defn repair-stale-identities!
  "For every stale-identity group: repoint each ref that targets an
   EXTRA at the CANONICAL row, then soft-delete the extras. `:dry-run?
   true` (default) only reports the plan. Returns
   `{:groups n :repointed n :tombstoned n :plan […]}`.

   Repointing goes through the storage protocol (`update-entity`), so
   versioned storages write proper version rows; the soft-delete like
   any editor delete. Run OFF-PEAK: touched fns recompile on the next
   delta."
  ([storage] (repair-stale-identities! storage {:dry-run? true}))
  ([storage {:keys [dry-run?] :or {dry-run? true}}]
   (let [stale (stale-identities storage)
         extra->canonical (into {}
                                (for [{:keys [canonical extras]} stale
                                      e extras]
                                  [(:id e) (:id canonical)]))
         plan (volatile! [])
         repoint! (fn [entity row field]
                    (when-let [target (extra->canonical (get row field))]
                      (vswap! plan conj {:op :repoint :entity entity
                                         :id (:id row) :field field
                                         :from (get row field) :to target})
                      (when-not dry-run?
                        (sp/update-entity storage entity (:id row)
                                          {field target}))))]
     (doseq [b (all-rows storage :binding)
             f [:ref-fn-id :type-override-fn-id :resolver-fn-id]]
       (repoint! :binding b f))
     (doseq [i (all-rows storage :binding-list-item)]
       (repoint! :binding-list-item i :ref-fn-id))
     (doseq [s (all-rows storage :slot)]
       (repoint! :slot s :type-fn-id))
     (doseq [f (all-rows storage :fn)]
       (doseq [fld [:base-fn-id :element-fn-id :return-type-fn-id]]
         (repoint! :fn f fld))
       ;; parent-ids is a ref-many — rewrite the whole vector when any
       ;; member is an extra.
       (let [pids (:parent-ids f)]
         (when (some extra->canonical pids)
           (let [pids' (mapv #(get extra->canonical % %) pids)]
             (vswap! plan conj {:op :repoint :entity :fn :id (:id f)
                                :field :parent-ids
                                :from pids :to pids'})
             (when-not dry-run?
               (sp/update-entity storage :fn (:id f)
                                 {:parent-ids pids'}))))))
     (let [tombstoned (volatile! 0)]
       (doseq [{:keys [extras]} stale
               e extras]
         (vswap! plan conj {:op :tombstone :entity :fn :id (:id e)
                            :name (:name e)})
         (when-not dry-run?
           (sp/delete-entity storage :fn (:id e))
           (vswap! tombstoned inc)))
       (when-not dry-run?
         (log/info "integrity repair applied"
                   {:groups (count stale)
                    :repointed (count (filter #(= :repoint (:op %)) @plan))
                    :tombstoned @tombstoned}))
       {:groups (count stale)
        :repointed (count (filter #(= :repoint (:op %)) @plan))
        :tombstoned (count (filter #(= :tombstone (:op %)) @plan))
        :dry-run? dry-run?
        :plan @plan}))))
