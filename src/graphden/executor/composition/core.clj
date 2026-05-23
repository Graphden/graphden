(ns graphden.executor.composition.core
  "Sync layer for the slot/fn-slot/binding model.

   `sync-fns-to-storage!` takes a vector of fn-def EDN maps (the
   loader's `:fn-defs` output), translates them via
   `graphden.packages.records/parse-module` into typed records, then
   batch-upserts each entity-kind to storage in dependency order:

     fn → slot → fn-slot → binding → binding-list-item

   Returns `{fn-name → fn-id}` for named fn rows. The old composition
   layer (records.clj, source-chain.clj — both stubbed) translated
   `:args` into `arg`-rows; this is replaced by the new parser
   producing records directly.

   This module also has `sync-defs-to-storage!` for primitives and
   `:base-fn` entries — same pipeline, just driven by a different
   record source."
  (:require
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.validation :as validation]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Namespace resolution
;; =============================================================================

(defn- resolve-namespace-id
  "ns-id-map maps namespace-path strings to ns-row ids. fn-def's
   `:namespace-id` field carries the path string (set by the loader);
   here we swap it for the actual UUID."
  [record ns-id-map]
  (if-let [ns-path (:namespace-id record)]
    (assoc record :namespace-id (get ns-id-map ns-path))
    record))


;; =============================================================================
;; impl-hash
;; =============================================================================

(defn- compute-impl-hash
  "Placeholder. Real impl-hashing reads the Clojure source and SHA-256s
   the canonical args+return+impl-source — see the old
   `graphden.executor.registry.core/compute-impl-hash`. Until the
   registry layer is rewritten to feed the records-parser, sync uses
   `nil` for impl-hash on every fn (records' `:impl-hash` field is set
   to a sentinel that we just clear here)."
  [_fn-record]
  nil)


;; =============================================================================
;; Sync — single entry point for any record bundle
;; =============================================================================

(defn- group-records-by-kind
  [records]
  (reduce (fn [acc r] (update acc (:kind r) (fnil conj []) r))
          {} records))


(defn- prep-fn-rows
  "Strip `:kind` and resolve namespace-id; convert record-shape to
   storage-shape for upsert."
  [fn-records ns-id-map]
  (mapv (fn [r]
          (let [resolved-ns (resolve-namespace-id r ns-id-map)
                impl-hash (when (= :sentinel/impl-hash (:impl-hash r))
                            (compute-impl-hash r))]
            (-> resolved-ns
                (dissoc :kind)
                (assoc :impl-hash (or impl-hash
                                      (when-not (= :sentinel/impl-hash (:impl-hash r))
                                        (:impl-hash r)))))))
        fn-records))


(defn- strip-kind
  "Drop the `:kind` discriminator before sending records to storage.
   Used for every entity except `:fn`, which needs extra ns / impl-hash
   massaging via `prep-fn-rows`."
  [records]
  (mapv #(dissoc % :kind) records))


(defn- reconcile-fn-bodies!
  "Make every synced fn's body — its `fn-slot` / `binding` /
   `binding-list-item` rows — match its declaration EXACTLY.

   `write-records!`'s upserts alone are additive: body rows from a
   fn's PRIOR definition survive a package refactor. A renamed slot,
   a dropped arg, a restructured inherited chain each mint new
   deterministic ids (`binding-id` keys off `(fn-id, slot-id)`), so
   the upsert writes the new row and silently leaves the old one
   behind. The layout and executor then see a live row AND its stale
   shadow — e.g. one `r404` binding rendered as two `_r404-body`
   cards.

   A fn's fn-slots and bindings (and the bindings' list-items) are
   owned by exactly that fn and wholly determined by its declaration,
   so re-syncing the fn must delete whatever it no longer declares.
   Slots are deliberately NOT reconciled — they are shared, immutable
   and content-addressed; an unreferenced slot row is inert.

   `parse-module` is a pure, deterministic function of the package
   source (no storage reads on the production 5-arity sync path), so
   re-syncing UNCHANGED source produces an identical record set and
   this reconciliation is a guaranteed no-op. It only deletes rows
   when the source genuinely changed — exactly the orphans a refactor
   leaves behind.

   Scoped to `synced-fn-ids` (the fns in THIS batch): user-created
   fns (never part of a package batch) and package fns synced in a
   different batch are left untouched."
  [storage synced-fn-ids declared-fn-slots declared-bindings declared-items]
  (when (seq synced-fn-ids)
    (let [declared-fn-slot-ids (into #{} (map :id) declared-fn-slots)
          declared-binding-ids (into #{} (map :id) declared-bindings)
          declared-item-ids    (into #{} (map :id) declared-items)
          ;; Push the synced-fn-ids set into the storage query so the
          ;; backend filters via SQL IN — earlier this full-scanned
          ;; :fn-slot / :binding / :binding-list-item and dropped 99%
          ;; in memory.
          fn-id-vec (vec synced-fn-ids)
          existing-fn-slots (sp/query-entities storage :fn-slot {:fn-id fn-id-vec})
          existing-bindings (sp/query-entities storage :binding {:fn-id fn-id-vec})
          owned-binding-ids (mapv :id existing-bindings)
          existing-items    (if (empty? owned-binding-ids)
                              []
                              (sp/query-entities storage :binding-list-item
                                                 {:binding-id owned-binding-ids}))
          stale-ids (fn [rows declared-ids]
                      (into [] (comp (remove #(contains? declared-ids (:id %)))
                                     (map :id))
                            rows))
          stale-items    (stale-ids existing-items    declared-item-ids)
          stale-bindings (stale-ids existing-bindings declared-binding-ids)
          stale-fn-slots (stale-ids existing-fn-slots declared-fn-slot-ids)]
      ;; Delete leaves first — list-item FK → binding, binding / fn-slot FK → fn.
      (when (seq stale-items)
        (sp/delete-entities storage :binding-list-item stale-items))
      (when (seq stale-bindings)
        (sp/delete-entities storage :binding stale-bindings))
      (when (seq stale-fn-slots)
        (sp/delete-entities storage :fn-slot stale-fn-slots)))))


(defn write-records!
  "Batch-upsert records of all kinds to storage in dependency order,
   then reconcile each synced fn's body so storage matches the
   declaration exactly (see `reconcile-fn-bodies!`).

   `records` is a flat vector of tagged maps (output of
   `records/parse-module` or `records/boot-primitive-records`).
   Returns `{fn-name → fn-id}` for named fn rows."
  [storage records ns-id-map]
  (let [{fns       :fn
         slots     :slot
         fn-slots  :fn-slot
         bindings  :binding
         items     :binding-list-item} (group-records-by-kind records)]
    ;; Order matters because of FK constraints:
    ;; fn → slot (slot.type-fn-id FK)
    ;;    → fn-slot (FKs to fn + slot)
    ;;    → binding (FKs to fn + slot)
    ;;       → binding-list-item (FK to binding)
    (when (seq fns)
      (sp/upsert-entities storage :fn (prep-fn-rows fns ns-id-map)))
    (when (seq slots)
      (sp/upsert-entities storage :slot (strip-kind slots)))
    (when (seq fn-slots)
      (sp/upsert-entities storage :fn-slot (strip-kind fn-slots)))
    (when (seq bindings)
      (sp/upsert-entities storage :binding (strip-kind bindings)))
    (when (seq items)
      (sp/upsert-entities storage :binding-list-item (strip-kind items)))
    ;; The upserts above are additive; this makes the sync declarative
    ;; — body rows a fn no longer declares are dropped.
    (reconcile-fn-bodies! storage
                          (into #{} (keep :id) fns)
                          fn-slots bindings items)
    ;; Build the name→id return map.
    (into {}
          (keep (fn [fr]
                  (when-let [n (:name fr)]
                    [(keyword n) (:id fr)])))
          fns)))


;; =============================================================================
;; Top-level entry points
;; =============================================================================

(defn sync-primitives!
  "Pre-seed the 14 primitive fn-rows. Idempotent (deterministic UUIDs).
   Should run once at storage init, before any other sync."
  [storage]
  (write-records! storage (records/boot-primitive-records) {}))


(defn- name->id-from-fns
  "Pure: project a vector of `:fn` rows to the name→id map."
  [fns]
  (into {}
        (keep (fn [f]
                (when-let [n (:name f)]
                  [(keyword n) (:id f)])))
        fns))


(defn- defs-by-name-from-rows
  "Pure: given pre-fetched fn / slot / fn-slot rows, reconstruct the
   minimal fn-def shapes the records-parser's slot resolver needs.
   We only carry `:args`-map (set of slot names) so the
   `type-row-arg-names` check fires on inheritance walks."
  [fns slots fn-slots]
  (let [slot-by-id (into {} (map (juxt :id identity)) slots)
        slots-by-fn (reduce (fn [acc fs]
                              (if-let [s (get slot-by-id (:slot-id fs))]
                                (update acc (:fn-id fs) (fnil conj #{}) (keyword (:name s)))
                                acc))
                            {}
                            fn-slots)]
    (into {}
          (keep (fn [f]
                  (let [n (some-> (:name f) keyword)
                        args (when (seq (get slots-by-fn (:id f)))
                               (into {}
                                     (map (fn [a] [a :any]))
                                     (get slots-by-fn (:id f))))
                        ;; Treat fn-rows with no parent and no impl as
                        ;; type-rows whose `:args` keys are their slot
                        ;; names; that's enough for the slot resolver
                        ;; to recognise the fn as declaring those slots.
                        is-type-row? (and (empty? (:parent-ids f))
                                          (or args (:base-fn-id f)
                                              (:element-fn-id f) (:constraint f)))]
                    (when (and n is-type-row? args)
                      [n {:name n :args args
                          ;; Best-effort: we don't have the ns-path
                          ;; here. Leave nil; UUIDs for fn-id were
                          ;; threaded via extra-name->id already.
                          :namespace nil}]))))
          fns)))


(defn- existing-defs-by-name
  "Reconstruct minimal fn-def shapes for every fn-row already in
   storage so the records-parser's slot resolver can reach base-fn
   args."
  [storage]
  (defs-by-name-from-rows (sp/query-entities storage :fn {})
    (sp/query-entities storage :slot {})
    (sp/query-entities storage :fn-slot {})))


(defn- discover-existing-state
  "One-shot fetch of the bits both convenience arities of
   `sync-fns-to-storage!` need from storage. Two adjacent helpers
   used to query `:fn {}` independently — this collapses to a single
   shared read, which matters on cold-start packages with hundreds
   of fns."
  [storage]
  (let [fns (sp/query-entities storage :fn {})
        slots (sp/query-entities storage :slot {})
        fn-slots (sp/query-entities storage :fn-slot {})]
    {:name->id (name->id-from-fns fns)
     :defs-by-name (defs-by-name-from-rows fns slots fn-slots)}))


(defn sync-fns-to-storage!
  "Top-level sync for a list of fn-defs. See arity-5 for full
   signature; convenience arities auto-discover `extra-name->id` from
   the existing `:fn` table.

   `extra-defs-by-name` (5-arity) carries fn-def shapes from a prior
   sync (base-fns, type-rows declared inline) so the records-parser's
   slot resolver can find their slots. Without it, a composed fn
   binding `:m` on a slot owned by a base-fn synced earlier won't
   resolve."
  ([storage fn-defs]
   (let [{:keys [name->id defs-by-name]} (discover-existing-state storage)]
     (sync-fns-to-storage! storage fn-defs {} name->id defs-by-name)))
  ([storage fn-defs ns-id-map]
   (let [{:keys [name->id defs-by-name]} (discover-existing-state storage)]
     (sync-fns-to-storage! storage fn-defs ns-id-map name->id defs-by-name)))
  ([storage fn-defs ns-id-map extra-name->id]
   (sync-fns-to-storage! storage fn-defs ns-id-map extra-name->id
                         (existing-defs-by-name storage)))
  ([storage fn-defs ns-id-map extra-name->id extra-defs-by-name]
   (validation/validate-all-defs! fn-defs)
   (let [sorted (deps/topological-sort fn-defs)
         records (records/parse-module sorted extra-name->id extra-defs-by-name)]
     (write-records! storage records ns-id-map))))
