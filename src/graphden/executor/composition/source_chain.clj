(ns graphden.executor.composition.source-chain
  "Pure helpers that walk arg.source-id chains and arg.ref-id chains to
   resolve arg names, collect free-arg sets, and deduplicate propagated
   copies across multiple-inheritance parents.

   Operates over `args-data` — a map produced by `preload-all-args` with
   `{:by-fn fn-id→[args], :by-id arg-id→arg}` indexes — plus a fn-cache
   that stores fn entities for cycle detection."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn free-arg?
  "Returns true if the arg is 'free' (has no value and no ref-id)."
  [arg]
  (and (nil? (:value arg))
       (nil? (:ref-id arg))))


(defn partition-args-by-freedom
  "Partitions args into {:free-args [...] :bound-args [...]} in single pass."
  [args]
  (reduce (fn [acc arg]
            (if (free-arg? arg)
              (update acc :free-args conj arg)
              (update acc :bound-args conj arg)))
          {:free-args [] :bound-args []}
          args))


(defn resolve-arg-name-cached
  "Resolves arg name by following source-id chain using args-by-id index.
   If arg has :name, returns it. Otherwise follows source-id to find name.
   Returns string name or nil if not resolvable.

   Uses O(1) lookup via args-by-id index instead of O(F×A) scan."
  [args-by-id arg depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Source-id chain too deep while resolving arg name"
                    {:type :fn-composition/source-chain-too-deep
                     :arg-id (:id arg)
                     :max-depth sp/*max-graph-iterations*})))
  (if-let [arg-name (:name arg)]
    arg-name
    (when-let [source-id (:source-id arg)]
      ;; O(1) lookup by arg-id
      (when-let [source-arg (get args-by-id source-id)]
        (recur args-by-id source-arg (inc depth))))))


(defn collect-source-id-chain
  "Follows source-id chain from an arg-id and collects all arg-ids in the chain.
   Returns a set of arg-ids including the starting arg-id.
   Used to determine which args should be shadowed when a child explicitly binds an arg."
  [args-by-id arg-id]
  (loop [current-id arg-id
         ids #{}
         depth 0]
    (if (or (nil? current-id) (> depth 100))
      ids
      (let [arg (get args-by-id current-id)]
        (recur (:source-id arg)
               (conj ids current-id)
               (inc depth))))))


(defn terminal-source-id
  "Walks the source-id chain from arg to find the root arg id (one with no source-id).
   Returns the id of the root arg, or arg's own id if it has no source-id chain.
   Used to identify free args that are propagated copies of the same root arg
   across multiple inheritance branches."
  [args-by-id arg]
  (loop [current arg
         depth 0]
    (when (> depth sp/*max-graph-iterations*)
      (throw (ex-info "Source-id chain too deep while finding terminal source"
                      {:type :fn-composition/source-chain-too-deep
                       :arg-id (:id arg)
                       :max-depth sp/*max-graph-iterations*})))
    (if-let [src-id (:source-id current)]
      (if-let [src-arg (get args-by-id src-id)]
        (recur src-arg (inc depth))
        (:id current))
      (:id current))))


(defn- collect-free-args-from-fn
  "Collects all free args from a fn by following ref-id chains.
   Returns a vector of free arg entities.

   Free args come from:
   1. Direct free args on this fn
   2. Free args from fns referenced in arg values (via ref-id)

   `walk-hof?`: when true (used by find-available-arg name resolution),
   descends through `:is-fn` ref-bindings as well — captured-from-outer
   names inside HOF subgraphs become reachable for explicit binding.
   When false (used by sync propagation), `:is-fn` refs are a BOUNDARY,
   so lambda-param names don't leak as new propagated free args of the
   caller.

   args-data contains :by-fn and :by-id indexes."
  [fn-cache args-data fn-id visited-fns depth walk-hof?]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Free arg collection chain too deep"
                    {:type :fn-composition/chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (if (contains? visited-fns fn-id)
    []
    (let [visited' (conj visited-fns fn-id)
          fn-args (get (:by-fn args-data) fn-id [])
          {:keys [free-args bound-args]} (partition-args-by-freedom fn-args)]
      (into free-args
            (mapcat (fn [arg]
                      (when (and (:ref-id arg)
                                 (or walk-hof? (not (:is-fn arg))))
                        (collect-free-args-from-fn fn-cache args-data (:ref-id arg)
                                                   visited' (inc depth) walk-hof?))))
            bound-args))))


(defn- collect-parent-free-args-for-one
  "Collects free args from a single parent fn (internal helper)."
  [fn-cache args-data parent-fn-id depth walk-hof?]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain too deep while collecting free args"
                    {:type :fn-composition/parent-chain-too-deep
                     :parent-fn-id parent-fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [fn-args (get (:by-fn args-data) parent-fn-id [])
        {:keys [free-args bound-args]} (partition-args-by-freedom fn-args)
        ref-free-args (mapcat (fn [arg]
                                (when (and (:ref-id arg)
                                           (or walk-hof? (not (:is-fn arg))))
                                  (collect-free-args-from-fn fn-cache args-data (:ref-id arg)
                                                             #{} (inc depth) walk-hof?)))
                              bound-args)]
    (into free-args ref-free-args)))


(defn collect-parent-free-args
  "Collects free args from all parent fns.
   Returns vector of arg entities that are free in the parents.

   `walk-hof?` (default true): see `collect-free-args-from-fn`. Sync's
   propagation pass passes false to keep lambda-param names sealed
   inside their HOF target; name-resolution callers (find-available-arg)
   use the default to traverse cross-HOF for explicit captures.

   Dedupes by [terminal-source-id, resolved-name] across MI parents."
  ([fn-cache args-data parent-fn-ids depth]
   (collect-parent-free-args fn-cache args-data parent-fn-ids depth true))
  ([fn-cache args-data parent-fn-ids depth walk-hof?]
   (let [args-by-id (:by-id args-data)
         seen (atom #{})]
     (into []
           (comp
             (mapcat (fn [pid]
                       (collect-parent-free-args-for-one fn-cache args-data pid depth walk-hof?)))
             (remove (fn [a]
                       (let [root (terminal-source-id args-by-id a)
                             resolved-name (resolve-arg-name-cached args-by-id a 0)
                             k [root resolved-name]]
                         (if (contains? @seen k)
                           true
                           (do (swap! seen conj k) false))))))
           (remove nil? parent-fn-ids)))))
