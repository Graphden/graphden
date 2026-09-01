(ns graphden.versioning.identity-repair
  "IDENTITY-PLANE repair primitives for the ghost-identity class: a
   package fn's deterministic id is `uuid-v5(ns-path, name)`, so a
   namespace move / rename mints a NEW id and abandons the old row —
   with every pre-move ref still pointing at it (the live-demo outage
   class, 2026-07-23).

   Two primitives, shared by the sync-time reconciler
   (`system.core/reconcile-moved-identities!` — the ROOT fix: heal at
   the moment of the move) and the operator tool
   (`graphden.dev.integrity` — the escape hatch for DBs that lived
   through older versions):

   - `repoint-refs!` — every ref targeting an old id is filled
     in-place with the new id, across BOTH planes (identity rows AND
     every branch's *-version rows; the field is the same logical
     content, so a plain column fill is correct and reaches diverging
     branches — a versioned-wrapper write would land on one branch
     only).
   - `purge-fn-subgraph!` — after repoint nothing references the
     ghost; its own subgraph (bindings, list-items, fn-slots, version
     rows, the fn row) is removed at the base plane.

   OPERATIONAL NOTE: the in-place fills bypass NOTIFY/delta
   invalidation by design. The sync-time caller runs BEFORE the
   compiled-registry build (nothing stale to invalidate); any other
   caller must restart executors after."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]))


(defn base-of
  "The identity-plane storage — unwraps a VersionedStorage."
  [storage]
  (or (:base-storage storage) storage))


(def ^:private ref-fields
  {:binding [:ref-fn-id :type-override-fn-id :resolver-fn-id]
   :binding-list-item [:ref-fn-id]
   :binding-version [:ref-fn-id :type-override-fn-id :resolver-fn-id]
   :binding-list-item-version [:ref-fn-id]
   ;; The :fn-version mirror carries VERSIONED fn-type refs that can
   ;; diverge from the identity row on a branch — without this row a
   ;; branch-divergent return/element/base ref was invisible to every
   ;; scanner here (2026-08-31 audit hole).
   :fn-version [:base-fn-id :element-fn-id :return-type-fn-id]
   :slot [:type-fn-id]})


(defn repoint-refs!
  "Fill every ref targeting a key of `old->new` with its replacement,
   in place, across identity AND version planes; `:fn.parent-ids` and
   the fn type-FKs included. Returns the number of rows touched.
   `plan!` (optional) is called with `{:op :repoint …}` per change —
   pass a collector for dry-run planning; when `dry-run?` is true no
   write happens."
  ([storage old->new] (repoint-refs! storage old->new nil false))
  ([storage old->new plan! dry-run?]
   (let [base (base-of storage)
         n (volatile! 0)
         fill! (fn [entity row field]
                 (when-let [target (old->new (get row field))]
                   (when plan!
                     (plan! {:op :repoint :entity entity :id (:id row)
                             :field field :from (get row field)
                             :to target}))
                   (vswap! n inc)
                   (when-not dry-run?
                     (sp/update-entity base entity (:id row)
                                       {field target}))))]
     (doseq [[entity fields] ref-fields
             row (sp/query-entities base entity {})
             field fields]
       (fill! entity row field))
     (doseq [f (sp/query-entities base :fn {})]
       (doseq [field [:base-fn-id :element-fn-id :return-type-fn-id]]
         (fill! :fn f field))
       (let [pids (:parent-ids f)]
         (when (some old->new pids)
           (let [pids' (mapv #(get old->new % %) pids)]
             (when plan!
               (plan! {:op :repoint :entity :fn :id (:id f)
                       :field :parent-ids :from pids :to pids'}))
             (vswap! n inc)
             (when-not dry-run?
               (sp/update-entity base :fn (:id f)
                                 {:parent-ids pids'}))))))
     @n)))


(defn inbound-refs
  "Rows OUTSIDE `fn-id`'s own owned subgraph that reference `fn-id`.
   Returns a seq of `{:entity :id :field}` descriptors — EMPTY means the
   fn is unreferenced and can be purged safely. Mirrors the exact ref
   surface `repoint-refs!` fills, minus the fn's own rows (which vanish
   with the purge and so are not real inbound refs):

   - `:binding`/`:binding-version` owned iff `:fn-id` == fn-id;
   - list-item (+ version) owned iff its `:binding-id` is one of fn-id's
     own bindings;
   - `:slot` is globally shared — never owned, always an external ref;
   - other `:fn` rows' type-FKs + `:parent-ids` (the fn's own outbound
     refs, i.e. the row with `:id` == fn-id, are skipped).

   Conservative by construction: it errs toward REPORTING a ref (leave
   the fn) rather than missing one (which would purge a live target)."
  [storage fn-id]
  (let [base (base-of storage)
        own-binding-ids (into #{} (map :id)
                              (sp/query-entities base :binding {:fn-id fn-id}))
        owned? (fn [entity row]
                 (case entity
                   (:binding :binding-version :fn-version) (= fn-id (:fn-id row))
                   (:binding-list-item :binding-list-item-version)
                   (contains? own-binding-ids (:binding-id row))
                   false))
        hits (volatile! [])]
    (doseq [[entity fields] ref-fields
            row (sp/query-entities base entity {})
            :when (not (owned? entity row))
            field fields
            :when (= fn-id (get row field))]
      (vswap! hits conj {:entity entity :id (:id row) :field field}))
    (doseq [f (sp/query-entities base :fn {})
            :when (not= (:id f) fn-id)]
      (doseq [field [:base-fn-id :element-fn-id :return-type-fn-id]
              :when (= fn-id (get f field))]
        (vswap! hits conj {:entity :fn :id (:id f) :field field}))
      (when (some #{fn-id} (:parent-ids f))
        (vswap! hits conj {:entity :fn :id (:id f) :field :parent-ids})))
    @hits))


(defn inbound-refs-many
  "Batch `inbound-refs` for a SET of fn-ids in ONE pass over the ref
   surface. Returns `{fn-id → [{:entity :id :field :owner-fn-id}]}`
   (ids absent from the map have no inbound refs). `:owner-fn-id` is
   the fn OWNING the referencing row (binding/list-item/version →
   their fn; another fn row → itself; a shared `:slot` → nil), so a
   caller removing a whole SET can tell internal refs (owner in the
   set — gone once the owner is purged) from external ones.

   Exists because the per-fn `inbound-refs` re-scans every table per
   call: a boot that reconciles N removed package fns paid N full
   scans (~1 s each against a remote managed PG), which blew the
   deploy health window when a large fn-def section was retired
   (2026-08-31). Same conservative surface as `inbound-refs`."
  [storage fn-ids]
  (let [base (base-of storage)
        targets (set fn-ids)
        ;; binding-id → owning fn-id. Both list-item planes key on the
        ;; IDENTITY binding's id (version rows carry :binding-id of the
        ;; identity row), so one :binding scan is the whole map.
        binding-owner (into {}
                            (map (juxt :id :fn-id))
                            (sp/query-entities base :binding {}))
        hits (volatile! {})
        hit! (fn [target m] (vswap! hits update target (fnil conj []) m))
        owner-of (fn [entity row]
                   (case entity
                     (:binding :binding-version :fn-version) (:fn-id row)
                     (:binding-list-item :binding-list-item-version)
                     (get binding-owner (:binding-id row))
                     :slot nil))]
    (doseq [[entity fields] ref-fields
            row (sp/query-entities base entity {})
            :let [owner (owner-of entity row)]
            field fields
            :let [target (get row field)]
            :when (and (contains? targets target)
                       ;; the target's own rows vanish with its purge —
                       ;; not real inbound refs (same rule as inbound-refs)
                       (not= owner target))]
      (hit! target {:entity entity :id (:id row) :field field
                    :owner-fn-id owner}))
    (doseq [f (sp/query-entities base :fn {})]
      (doseq [field [:base-fn-id :element-fn-id :return-type-fn-id]
              :let [target (get f field)]
              :when (and (contains? targets target) (not= (:id f) target))]
        (hit! target {:entity :fn :id (:id f) :field field
                      :owner-fn-id (:id f)}))
      (doseq [target (distinct (:parent-ids f))
              :when (and (contains? targets target) (not= (:id f) target))]
        (hit! target {:entity :fn :id (:id f) :field :parent-ids
                      :owner-fn-id (:id f)})))
    @hits))


(defn purge-fn-subgraphs-many!
  "Batch `purge-fn-subgraph!` for a SET of fn-ids: one scan per table
   instead of four unfiltered scans per fn (the per-fn cascade re-read
   :binding-list-item(-version)/:binding-version/:fn-slot-version in
   full for every removal — ~4×N scans on the first boot after a large
   retirement, the same class as the 2026-08-31 deploy blowup).
   Returns the number of rows removed. Also removes slots that the
   purge fully ORPHANS (every fn-slot exposing the slot belonged to
   the set AND no surviving binding/binding-version row references it)
   — the per-fn variant leaves them behind to pin their type-fns
   forever."
  [storage fn-ids]
  (let [base (base-of storage)
        targets (set fn-ids)
        n (volatile! 0)
        zap! (fn [entity rows]
               (doseq [r rows]
                 (vswap! n inc)
                 (sp/delete-entity base entity (:id r))))
        all-bindings (sp/query-entities base :binding {})
        own-binding-ids (into #{} (comp (filter #(contains? targets (:fn-id %)))
                                        (map :id))
                              all-bindings)
        by-binding (fn [entity]
                     (filter #(contains? own-binding-ids (:binding-id %))
                             (sp/query-entities base entity {})))]
    (zap! :binding-list-item-version (by-binding :binding-list-item-version))
    (zap! :binding-list-item (by-binding :binding-list-item))
    (zap! :binding-version
          (filter #(contains? targets (:fn-id %))
                  (sp/query-entities base :binding-version {})))
    (zap! :binding (filter #(contains? targets (:fn-id %)) all-bindings))
    (let [all-fn-slots (sp/query-entities base :fn-slot {})
          own-fn-slots (filter #(contains? targets (:fn-id %)) all-fn-slots)
          own-fs-ids (into #{} (map :id) own-fn-slots)
          ;; Slots whose EVERY exposure died with the set — orphans.
          touched-slot-ids (into #{} (map :slot-id) own-fn-slots)
          surviving-slot-ids (into #{} (comp (remove #(contains? own-fs-ids (:id %)))
                                             (map :slot-id))
                                   all-fn-slots)
          bound-slot-ids (into #{} (comp (remove #(contains? targets (:fn-id %)))
                                         (map :slot-id))
                               (concat all-bindings
                                       (sp/query-entities base :binding-version {})))
          ;; A rename-view slot points at its declaring slot through
          ;; `source-slot-id` — deleting the source out from under a
          ;; SURVIVING view slot dangles the rename chain (the rename
          ;; root resolution walks it). Protect any slot a surviving
          ;; slot still sources from.
          all-slots (sp/query-entities base :slot {})
          doomed-so-far (set touched-slot-ids)
          sourced-by-survivor (into #{}
                                    (comp (remove #(contains? doomed-so-far (:id %)))
                                          (keep :source-slot-id))
                                    all-slots)
          orphan-slot-ids (remove #(or (contains? surviving-slot-ids %)
                                       (contains? bound-slot-ids %)
                                       (contains? sourced-by-survivor %))
                                  touched-slot-ids)]
      (zap! :fn-slot-version
            (filter #(contains? own-fs-ids (:fn-slot-id %))
                    (sp/query-entities base :fn-slot-version {})))
      (zap! :fn-slot own-fn-slots)
      (zap! :slot (map (fn [sid] {:id sid}) orphan-slot-ids)))
    (zap! :fn-version (filter #(contains? targets (:fn-id %))
                              (sp/query-entities base :fn-version {})))
    (zap! :fn (map (fn [id] {:id id}) targets))
    (log/info "purged retired fn subgraphs (batch)"
              {:fns (count targets) :rows @n})
    @n))


(defn purge-fn-subgraph!
  "Remove `fn-id`'s whole owned subgraph at the base plane: its
   bindings (+ their list-items and version rows), fn-slots (+
   versions), fn-versions, and the fn row itself. Assumes inbound
   refs were repointed first. Returns the number of rows removed;
   `plan!`/`dry-run?` as in `repoint-refs!`."
  ([storage fn-id] (purge-fn-subgraph! storage fn-id nil false))
  ([storage fn-id plan! dry-run?]
   (let [base (base-of storage)
         n (volatile! 0)
         zap! (fn [entity rows]
                (doseq [r rows]
                  (when plan!
                    (plan! {:op :remove :entity entity :id (:id r)}))
                  (vswap! n inc)
                  (when-not dry-run?
                    (sp/delete-entity base entity (:id r)))))
         own-bindings (sp/query-entities base :binding {:fn-id fn-id})
         own-binding-ids (into #{} (map :id) own-bindings)
         by-binding (fn [entity key-field]
                      (filter #(contains? own-binding-ids (key-field %))
                              (sp/query-entities base entity {})))]
     (zap! :binding-list-item-version
           (by-binding :binding-list-item-version :binding-id))
     (zap! :binding-list-item (by-binding :binding-list-item :binding-id))
     (zap! :binding-version (by-binding :binding-version :binding-id))
     (zap! :binding own-bindings)
     (let [own-fn-slots (sp/query-entities base :fn-slot {:fn-id fn-id})
           own-fs-ids (into #{} (map :id) own-fn-slots)]
       (zap! :fn-slot-version
             (filter #(contains? own-fs-ids (:fn-slot-id %))
                     (sp/query-entities base :fn-slot-version {})))
       (zap! :fn-slot own-fn-slots))
     (zap! :fn-version (sp/query-entities base :fn-version {:fn-id fn-id}))
     (zap! :fn [{:id fn-id}])
     (when-not dry-run?
       (log/info "purged ghost fn subgraph" {:fn-id fn-id :rows @n}))
     @n)))
