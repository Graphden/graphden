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
                   (:binding :binding-version) (= fn-id (:fn-id row))
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
