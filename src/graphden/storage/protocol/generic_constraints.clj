(ns graphden.storage.protocol.generic-constraints
  "Generic constraint helpers using StorageCRUD.

   Provides a single ConstraintHelpers implementation that works with any
   storage backend through the StorageCRUD protocol. Backends no longer need
   to implement ConstraintHelpers themselves — they can use these generic
   functions for GraphConstraints validation.

   Usage in backend core.clj:
   ```clojure
   (require '[graphden.storage.protocol.generic-constraints :as gc])

   sp/GraphConstraints
   (validate-no-dependency-cycle! [this owner-fn-id ref-fn-id]
     (gc/validate-no-dependency-cycle! this owner-fn-id ref-fn-id))
   ```"
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]))


;; Operators / kind heads inside a constraint vector. The write-time
;; check (`crud.validation`) and this chain walk MUST agree on "which
;; keywords inside a constraint vector are type-row references", so
;; there is exactly one set — it lives here, the lower layer, and CRUD
;; reads it from here. A new constraint operator added to only one of
;; two copies used to leave the cycle walker blind to half the
;; references it should follow.
(def constraint-op-keywords
  #{:union :variant :fn :refine :map :tuple :and :or :not
    :> :>= :< :<= := :not= :matches :in :exists :every})


(defn constraint-type-ref-names
  "Walk a constraint vector and collect every keyword nested anywhere
   inside it as a bare-name string set, minus the operator heads in
   `constraint-op-keywords`. Finds the type-row references hidden in
   `[:union T1 T2 …]` / `[:variant :tag1 T1 …]` / `[:fn {…} T]` shapes
   — those are stored as keywords, NOT FK columns, so an FK-only walker
   misses them.

   Deliberately over-inclusive: a variant TAG that happens to share a
   name with a type-row is collected too. For the cycle walker that
   only means visiting an unrelated fn (it widens the dependency
   closure, it never claims a cycle that isn't there); for the
   write-time check it can only over-reject, and under-rejection would
   let real cycles through."
  [c]
  (let [walk (fn walk
               [x acc]
               (cond
                 (keyword? x)
                 (if (constraint-op-keywords x)
                   acc
                   (conj acc (name x)))
                 (map? x)
                 (reduce-kv (fn [a _k v] (walk v a)) acc x)
                 (sequential? x)
                 (reduce (fn [a el] (walk el a)) acc x)
                 :else acc))]
    (walk c #{})))


(defn- closure-index
  "Index an `ExecutionGraphResult` for the in-memory dependency walk."
  [g]
  {:fns-by-id (or (:fns g) {})
   :bindings-by-fn (group-by :fn-id (:bindings g))
   :items-by-binding (group-by :binding-id (:list-items g))
   :slots-by-id (into {} (map (juxt :id identity)) (:slots g))})


(defn- merge-index
  [a b]
  {:fns-by-id (merge (:fns-by-id a) (:fns-by-id b))
   :bindings-by-fn (merge (:bindings-by-fn a) (:bindings-by-fn b))
   :items-by-binding (merge (:items-by-binding a) (:items-by-binding b))
   :slots-by-id (merge (:slots-by-id a) (:slots-by-id b))})


(defn- fn-dependency-edges
  "The immediate DEPENDENCY targets of `fn-id` read off the index —
   parent-ids, the fn-row type FKs, ref / type-override / resolver
   bindings, list-item refs — plus the constraint-vector type NAMES the
   walk still has to resolve. An IDENTITY edge (a ref into a `:fn-ref`
   slot: the target is named, never evaluated) is not a dependency, so
   a cycle that closes only through one is legal — two services may
   each hold the other's identity."
  [{:keys [fns-by-id bindings-by-fn items-by-binding slots-by-id]} fn-id]
  (let [fn-rec (get fns-by-id fn-id)
        bindings (get bindings-by-fn fn-id)
        binding-refs (into #{}
                           (comp (mapcat (fn [b]
                                           [(when-not (ids/identity-edge?
                                                        b (get slots-by-id (:slot-id b)))
                                              (:ref-fn-id b))
                                            (:type-override-fn-id b)
                                            (:resolver-fn-id b)]))
                                 (filter some?))
                           bindings)
        item-refs (into #{}
                        (comp (mapcat #(get items-by-binding (:id %)))
                              (keep :ref-fn-id))
                        bindings)
        parent-ids (remove nil? (:parent-ids fn-rec))
        type-refs (keep #(get fn-rec %)
                        [:base-fn-id :element-fn-id :return-type-fn-id])]
    {:ids (reduce into #{} [binding-refs item-refs parent-ids type-refs])
     :constraint-names (some-> fn-rec :constraint constraint-type-ref-names)}))


(defn- load-closure
  "The storage's own execution-graph closure of `fn-id` — ONE recursive
   CTE plus a handful of bulk loads, whatever the graph's size (the
   path the executor compiles from). Empty when the fn does not exist."
  [storage fn-id]
  (closure-index
    (try
      (sp/resolve-execution-graph storage fn-id)
      (catch clojure.lang.ExceptionInfo e
        (when-not (= :not-found (:type (ex-data e))) (throw e))
        nil))))


(defn dependency-closure
  "Every fn `owner-fn-id` depends on, transitively — the set the cycle
   check asks \"does the ref's closure contain the owner?\" of.

   The old walk issued four queries per fn visited and capped itself at
   `default-max-dependency-chain-depth` visits, so binding a fn with a
   large closure (the editor's own listener, its router) took seconds
   and then failed `chain-too-deep`. This one loads the closure through
   `sp/resolve-execution-graph` (the recursive-CTE resolver — O(1)
   round trips) and walks it in memory, following exactly the edges
   `forward-deps-of` follows and skipping identity edges. Constraint-
   vector type names (`[:union :a :b]` — keywords, not FKs, which the
   resolver does not chase) are resolved in one batched name query per
   round; a fn reached only that way gets its own closure loaded and
   the walk continues, until no new fn appears."
  [storage owner-fn-id]
  (loop [idx (load-closure storage owner-fn-id)
         to-visit (conj clojure.lang.PersistentQueue/EMPTY owner-fn-id)
         visited #{}
         pending-names #{}]
    (if-let [current (peek to-visit)]
      (if (contains? visited current)
        (recur idx (pop to-visit) visited pending-names)
        (let [{:keys [ids constraint-names]} (fn-dependency-edges idx current)]
          (recur idx
                 (into (pop to-visit) (remove visited) ids)
                 (conj visited current)
                 (into pending-names constraint-names))))
      ;; Round done — resolve the constraint-named type-rows seen so far;
      ;; anything new is a fresh frontier (with its closure loaded).
      (let [name-ids (if (empty? pending-names)
                       #{}
                       (into #{}
                             (keep :id)
                             (sp/query-entities storage :fn {:name (vec pending-names)})))
            fresh (remove visited name-ids)]
        (if (empty? fresh)
          (disj visited owner-fn-id)
          (recur (reduce (fn [i fid] (merge-index i (load-closure storage fid))) idx fresh)
                 (into clojure.lang.PersistentQueue/EMPTY fresh)
                 visited
                 #{}))))))


(defrecord GenericConstraintHelpers
  [storage]

  sp/ConstraintHelpers

  (collect-dependency-chain
    [_this owner-fn-id]
    (dependency-closure storage owner-fn-id)))


(defn validate-no-dependency-cycle!
  "Validates that referencing ref-fn does not create dependency cycle.
   Uses StorageCRUD to traverse dependency chain — works with any backend."
  [storage owner-fn-id ref-fn-id]
  (sp/validate-no-dependency-cycle-impl
    (->GenericConstraintHelpers storage) owner-fn-id ref-fn-id))
