(ns graphden.executor.composition.deps
  "Dependency analysis and topological sort over fn-defs. Used by
   `sync-fns-to-storage!` to produce a dependency-safe order to
   process records in (file order is free; cycles are rejected)."
  (:require
    [clojure.set :as set]
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
    ;; {:as :name :ref :fn-name} / {:as :name :value :fn-name} /
    ;; {:resolver :fn-name ...} -- the resolver runs at
    ;; arg-resolution time, so it must be synced BEFORE its consumer
    ;; (same topo-sort dependency as a plain ref).
    (let [r (parsing/parse-fn-ref (:ref arg-value))
          v (parsing/parse-fn-ref (:value arg-value))
          rz (parsing/parse-fn-ref (:resolver arg-value))]
      (cond-> #{}
        r (conj r)
        v (conj v)
        rz (conj rz)))

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


(declare ^:private extract-dependencies* build-dependency-graph*)


(defn- extract-dependencies
  "Extracts fn names that this fn-def depends on (parents, arg refs,
   type-row references, return-type). Returns set of keywords filtered
   to only those that name an in-module fn.

   `identity-arg?` — `(fn [fn-def arg-name] → bool)`: true for an arg
   whose slot is `:fn-ref`-typed. Such a binding names its target
   without evaluating it, so it is NOT an ordering dependency (a pair
   of services may name each other)."
  ([fn-def fn-names-in-set]
   (extract-dependencies fn-def fn-names-in-set (constantly false)))
  ([fn-def fn-names-in-set identity-arg?]
   (extract-dependencies* fn-def fn-names-in-set identity-arg?)))


(defn- extract-dependencies*
  [fn-def fn-names-in-set identity-arg?]
  (let [args (:args fn-def {})
        parent-names (concat (when-let [p (:parent fn-def)] [p])
                             (:parents fn-def))
        arg-deps (->> args
                      (remove (fn [[arg-name _]] (identity-arg? fn-def arg-name)))
                      (mapcat (comp arg-value-fn-refs val))
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
                    ;; or bare type keywords; collect those too. An identity
                    ;; arg's bare keyword is the NAMED fn, not a type — skip
                    ;; it here as well.
                    true                  (into (mapcat (fn [[arg-name v]]
                                                          (cond
                                                            (identity-arg? fn-def arg-name) nil
                                                            (keyword? v) [v]
                                                            (and (map? v) (:type v)) (type-ref-deps (:type v))
                                                            :else nil))
                                                        args)))]
    (into arg-deps (concat parent-deps (filter fn-names-in-set type-deps)))))


(defn- build-dependency-graph
  "Builds dependency graph from fn-defs.
   Returns map of {fn-name -> #{dependency-names}}."
  ([fn-defs] (build-dependency-graph fn-defs (constantly false)))
  ([fn-defs identity-arg?]
   (build-dependency-graph* fn-defs identity-arg?)))


(defn- build-dependency-graph*
  [fn-defs identity-arg?]
  (let [fn-names (into #{} (map :name) fn-defs)]
    (into {}
          (map (fn [fd]
                 [(:name fd) (extract-dependencies fd fn-names identity-arg?)])
               fn-defs))))


(declare ^:private topological-sort*)


(defn topological-sort
  "Topologically sorts fn-defs by dependencies.
   Returns sorted vector of fn-defs.
   Throws on cycles.

   `identity-arg?` (optional, default: nothing is an identity arg) —
   `(fn [fn-def arg-name] → bool)` marking args whose slot is
   `:fn-ref`-typed; those refs are skipped as dependencies (see
   `extract-dependencies`). The caller resolves slot types, since only
   it holds the cross-module def index.

   Uses Kahn's algorithm with O(V+E) complexity:
   - Tracks in-degree (unvisited deps count) for each node
   - Maintains ready-set of nodes with zero in-degree
   - Each node enters/exits ready-set exactly once"
  ([fn-defs]
   (topological-sort fn-defs (constantly false)))
  ([fn-defs identity-arg?]
   (topological-sort* fn-defs identity-arg?)))


(defn- topological-sort*
  [fn-defs identity-arg?]
  (let [dep-graph (build-dependency-graph fn-defs identity-arg?)
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
