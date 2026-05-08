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


(defn write-records!
  "Batch-upsert records of all kinds to storage in dependency order.
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


(defn- existing-name->id
  "Discover the name→id map for fn-rows already in storage. Lets
   `sync-fns-to-storage!` resolve cross-module references without
   the caller threading them in by hand."
  [storage]
  (into {}
        (keep (fn [f]
                (when-let [n (:name f)]
                  [(keyword n) (:id f)])))
        (sp/query-entities storage :fn {})))


(defn- existing-defs-by-name
  "Reconstruct minimal fn-def shapes for every fn-row already in
   storage so the records-parser's slot resolver can reach base-fn
   args. We only need the `:args`-map (set of slot names) so the
   `type-row-arg-names` check fires on inheritance walks; everything
   else can stay nil."
  [storage]
  (let [fns (sp/query-entities storage :fn {})
        slots (sp/query-entities storage :slot {})
        fn-slots (sp/query-entities storage :fn-slot {})
        slot-by-id (into {} (map (juxt :id identity)) slots)
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
   (sync-fns-to-storage! storage fn-defs {} (existing-name->id storage)
                         (existing-defs-by-name storage)))
  ([storage fn-defs ns-id-map]
   (sync-fns-to-storage! storage fn-defs ns-id-map (existing-name->id storage)
                         (existing-defs-by-name storage)))
  ([storage fn-defs ns-id-map extra-name->id]
   (sync-fns-to-storage! storage fn-defs ns-id-map extra-name->id
                         (existing-defs-by-name storage)))
  ([storage fn-defs ns-id-map extra-name->id extra-defs-by-name]
   (validation/validate-all-defs! fn-defs)
   (let [sorted (deps/topological-sort fn-defs)
         records (records/parse-module sorted extra-name->id extra-defs-by-name)]
     (write-records! storage records ns-id-map))))
