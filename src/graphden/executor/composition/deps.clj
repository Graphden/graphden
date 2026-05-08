(ns graphden.executor.composition.deps
  "Dependency analysis and topological sort over fn-defs. Used by
   `sync-fns-to-storage!` to (a) warn when the caller supplied an order
   that's not dependency-safe, and (b) produce a safe order to process
   records in."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.executor.composition.parsing :as parsing]))


(defn- arg-value-fn-refs
  "Extracts the SET of fn-name keywords this arg value depends on.
   Sequence-typed slots can carry a vector of refs (`[:a :b {:ref :c}]`),
   so this returns a SET — single bindings yield a singleton.
   Handles:

     - keyword          → that ref
     - {:ref X}/{:value X}  → X if it's a fn-ref
     - vector of items  → union of item refs (each via the rules above)"
  [arg-value]
  (cond
    (keyword? arg-value)
    (some-> (parsing/parse-fn-ref arg-value) hash-set)

    (vector? arg-value)
    (into #{} (mapcat arg-value-fn-refs) arg-value)

    (map? arg-value)
    ;; {:as :name :ref :fn-name} or {:as :name :value :fn-name}
    (let [r (parsing/parse-fn-ref (:ref arg-value))
          v (parsing/parse-fn-ref (:value arg-value))]
      (cond-> #{}
        r (conj r)
        v (conj v)))

    :else #{}))


(defn- type-ref-deps
  "Pulls fn-name keywords referenced by a `:type` / `:refine` / `:list`
   declaration or a `:return-type` annotation. Inline composite maps
   (`{:k T}`) recurse so each field's type is collected.

   Primitives and unknown keywords pass through; the caller filters
   them against the in-module fn-name set."
  [t]
  (cond
    (keyword? t)  #{t}
    (map? t)      (into #{} (mapcat type-ref-deps) (vals t))
    (vector? t)   (case (first t)
                    :refine (into #{} (mapcat type-ref-deps) (rest t))
                    :list   (recur (second t))
                    :fn     (set (concat (mapcat type-ref-deps (vals (or (second t) {})))
                                         (type-ref-deps (last t))))
                    :union  (into #{} (mapcat type-ref-deps) (rest t))
                    #{})
    :else         #{}))


(defn- extract-dependencies
  "Extracts fn names that this fn-def depends on (parents, arg refs,
   type-row references, return-type). Returns set of keywords filtered
   to only those that name an in-module fn."
  [fn-def fn-names-in-set]
  (let [args (:args fn-def {})
        parent-names (concat (when-let [p (:parent fn-def)] [p])
                             (:parents fn-def))
        arg-deps (->> (vals args)
                      (mapcat arg-value-fn-refs)
                      (filter fn-names-in-set)
                      set)
        parent-deps (into #{} (filter fn-names-in-set) parent-names)
        ;; Type-row references — :type / :refine / :list / :return-type.
        ;; Each may name another fn-def in the same module.
        type-deps (cond-> #{}
                    (:type fn-def)        (into (type-ref-deps (:type fn-def)))
                    (:refine fn-def)      (into (type-ref-deps (:base (:refine fn-def))))
                    (:list fn-def)        (into (type-ref-deps (:list fn-def)))
                    (:return-type fn-def) (into (type-ref-deps (:return-type fn-def)))
                    ;; Base-fn `:args` shape — values may be `{:type T}` maps
                    ;; or bare type keywords; collect those too.
                    true                  (into (mapcat (fn [v]
                                                          (cond
                                                            (keyword? v) [v]
                                                            (and (map? v) (:type v)) (type-ref-deps (:type v))
                                                            :else nil))
                                                        (vals args))))]
    (into arg-deps (concat parent-deps (filter fn-names-in-set type-deps)))))


(defn- build-dependency-graph
  "Builds dependency graph from fn-defs.
   Returns map of {fn-name -> #{dependency-names}}."
  [fn-defs]
  (let [fn-names (into #{} (map :name) fn-defs)]
    (into {}
          (map (fn [fd]
                 [(:name fd) (extract-dependencies fd fn-names)])
               fn-defs))))


(defn topological-sort
  "Topologically sorts fn-defs by dependencies.
   Returns sorted vector of fn-defs.
   Throws on cycles.

   Uses Kahn's algorithm with O(V+E) complexity:
   - Tracks in-degree (unvisited deps count) for each node
   - Maintains ready-set of nodes with zero in-degree
   - Each node enters/exits ready-set exactly once"
  [fn-defs]
  (let [dep-graph (build-dependency-graph fn-defs)
        fn-def-map (into {} (map (juxt :name identity) fn-defs))
        all-names (into #{} (map :name) fn-defs)
        ;; Build reverse dependency map: {fn-name -> #{fns-that-depend-on-it}}
        reverse-deps (reduce-kv
                       (fn [acc fn-name deps]
                         (reduce (fn [m dep]
                                   (update m dep (fnil conj #{}) fn-name))
                                 acc deps))
                       {}
                       dep-graph)
        ;; Initial in-degree: count of unvisited dependencies
        ;; Only count deps that are in our set (external deps are pre-satisfied)
        initial-in-degree (into {}
                                (map (fn [fn-name]
                                       [fn-name (count (set/intersection
                                                         (get dep-graph fn-name #{})
                                                         all-names))]))
                                all-names)
        ;; Initial ready-set: nodes with no dependencies within the set
        initial-ready (into #{} (filter #(zero? (get initial-in-degree % 0))) all-names)]
    (loop [sorted []
           ready-set initial-ready
           in-degree initial-in-degree]
      (if (empty? ready-set)
        (if (= (count sorted) (count fn-defs))
          (mapv fn-def-map sorted)
          ;; Not all processed - cycle detected
          (let [remaining (set/difference all-names (set sorted))]
            (throw (ex-info "Circular dependency detected in fn definitions"
                            {:type :fn-composition/circular-dependency
                             :remaining remaining
                             :dep-graph (select-keys dep-graph remaining)}))))
        ;; Pick any ready node (first for determinism)
        (let [current (first ready-set)
              rest-ready (disj ready-set current)
              ;; Update in-degree for all dependents
              dependents (get reverse-deps current #{})
              {:keys [new-ready new-in-degree]}
              (reduce (fn [{:keys [new-ready new-in-degree]} dependent]
                        (let [old-deg (get new-in-degree dependent)
                              new-deg (dec old-deg)]
                          {:new-in-degree (assoc new-in-degree dependent new-deg)
                           :new-ready (if (zero? new-deg)
                                        (conj new-ready dependent)
                                        new-ready)}))
                      {:new-ready rest-ready :new-in-degree in-degree}
                      dependents)]
          (recur (conj sorted current) new-ready new-in-degree))))))


(defn check-order-and-warn
  "Checks if fn-defs are in valid topological order.
   Logs warning if not, with suggested order."
  [fn-defs sorted-defs]
  (let [original-order (mapv :name fn-defs)
        sorted-order (mapv :name sorted-defs)]
    (when (not= original-order sorted-order)
      (log/warn "fn-defs are not in dependency order."
                "Current order:" (str/join " -> " (map name original-order))
                "Suggested order:" (str/join " -> " (map name sorted-order))))))
