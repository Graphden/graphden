(ns graphden.executor.composition.core
  "Data-driven fn definitions and storage sync.

   ## 2-Entity Schema

   Composed functions are stored as:
   - fn entity with parent-ids set (inherits from one or more parent fns)
   - arg entities with source-id set (inherits from parent's arg) + value/ref-id

   ## Format

   Fn definitions are a vector of maps (order matters for validation):

   ```clojure
   [{:name :router-handler
     :parent :default-router-handler}

    {:name :web-server
     :parent :http-server
     :args {:handler :router-handler   ; ref to fn
            :port 8080}}]              ; literal value
   ```

   ## Arg Value Syntax

   - `:fn-name` - reference to fn (creates ref-id)

   Behavior (execute vs pass fn-id) is determined by is-fn field on parent arg,
   which is inherited via source-id. The executor checks is-fn to decide.

   ## Name Resolution

   Names in :parent and :args are resolved in this order:
   1. fn from this definition set (by name)
   2. fn already in storage (by name)"
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.executor.registry.interface :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === Arg Value Parsing ===

(defn- valid-identifier?
  "Returns true if s looks like a valid identifier.
   Allows dots for qualified namespace references (e.g. core.arithmetic.add)."
  [s]
  (when (and (string? s) (seq s))
    (and (not (re-find #"\s" s))
         (re-matches #"[a-zA-Z_\-][a-zA-Z0-9_.\-]*" s))))


(defn- local-fn-name?
  "Returns true if fn-name starts with _ (local/unnamed fn).
   Local fns are stored with name=nil in DB and only referenced by id."
  [fn-name]
  (when fn-name
    (let [n (if (keyword? fn-name) (name fn-name) (str fn-name))]
      (str/starts-with? n "_"))))


(defn- parse-fn-ref
  "Parses a keyword that might be a fn reference.
   Returns fn-name keyword or nil if not a fn ref."
  [value]
  (when (keyword? value)
    (let [kw-name (name value)]
      (when (valid-identifier? kw-name)
        value))))


;; === Dependency Analysis ===

(defn- arg-value-fn-ref
  "Extracts a fn-name keyword from an arg value spec, if it's a fn ref.
   Handles both simple keyword refs and map syntax with :ref or :value.
   Returns the fn-name keyword or nil."
  [arg-value]
  (cond
    (keyword? arg-value)
    (parse-fn-ref arg-value)

    (map? arg-value)
    ;; {:as :name :ref :fn-name} or {:as :name :value :fn-name}
    (or (parse-fn-ref (:ref arg-value))
        (parse-fn-ref (:value arg-value)))))


(defn- extract-dependencies
  "Extracts fn names that this fn-def depends on (from args and parents).
   Returns set of keywords."
  [fn-def fn-names-in-set]
  (let [args (:args fn-def {})
        parent-names (concat (when-let [p (:parent fn-def)] [p])
                             (:parents fn-def))
        arg-deps (->> (vals args)
                      (keep arg-value-fn-ref)
                      (filter fn-names-in-set)
                      set)
        ;; Parents that are composed fns in our set are dependencies
        parent-deps (into #{} (filter fn-names-in-set) parent-names)]
    (into arg-deps parent-deps)))


(defn- build-dependency-graph
  "Builds dependency graph from fn-defs.
   Returns map of {fn-name -> #{dependency-names}}."
  [fn-defs]
  (let [fn-names (into #{} (map :name) fn-defs)]
    (into {}
          (map (fn [fd]
                 [(:name fd) (extract-dependencies fd fn-names)])
               fn-defs))))


(defn- topological-sort
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


(defn- check-order-and-warn
  "Checks if fn-defs are in valid topological order.
   Logs warning if not, with suggested order."
  [fn-defs sorted-defs]
  (let [original-order (mapv :name fn-defs)
        sorted-order (mapv :name sorted-defs)]
    (when (not= original-order sorted-order)
      (log/warn "fn-defs are not in dependency order."
                "Current order:" (str/join " -> " (map name original-order))
                "Suggested order:" (str/join " -> " (map name sorted-order))))))


;; === Validation ===

(defn- validate-fn-def!
  "Validates a single fn definition."
  [{parent :parent parent-list :parents args :args :as fn-def}]
  (let [fn-name (:name fn-def)]
    (when-not fn-name
      (throw (ex-info "fn-def must have :name"
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))
    (when-not (keyword? fn-name)
      (throw (ex-info "fn-def :name must be a keyword"
                      {:type :fn-composition/invalid-def
                       :name fn-name})))
    (when-not (or parent (seq parent-list))
      (throw (ex-info (str "fn-def " fn-name " must have :parent (single) or :parents (vector)")
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))
    (when (and parent-list (not (sequential? parent-list)))
      (throw (ex-info (str "fn-def " fn-name " :parents must be a vector/list of keywords")
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))
    (when (and args (not (map? args)))
      (throw (ex-info (str "fn-def " fn-name " :args must be a map")
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))))


(defn- validate-all-defs!
  "Validates all fn definitions before sync."
  [fn-defs]
  (when-not (sequential? fn-defs)
    (throw (ex-info "fn-defs must be a vector/list"
                    {:type :fn-composition/invalid-defs
                     :fn-defs-type (type fn-defs)})))
  ;; Single-pass duplicate detection with early termination capability
  (let [duplicates (loop [remaining (map :name fn-defs)
                          seen #{}
                          dups #{}]
                     (if-let [n (first remaining)]
                       (if (contains? seen n)
                         (recur (rest remaining) seen (conj dups n))
                         (recur (rest remaining) (conj seen n) dups))
                       dups))]
    (when (seq duplicates)
      (throw (ex-info "Duplicate fn names in definitions"
                      {:type :fn-composition/duplicate-names
                       :duplicates (vec duplicates)}))))
  (doseq [fn-def fn-defs]
    (validate-fn-def! fn-def)))


;; === Storage Sync (Batch Optimized) ===

;; === Free Argument Propagation ===

(defn- free-arg?
  "Returns true if the arg is 'free' (has no value and no ref-id)."
  [arg]
  (and (nil? (:value arg))
       (nil? (:ref-id arg))))


(defn- partition-args-by-freedom
  "Partitions args into {:free-args [...] :bound-args [...]} in single pass.
   Free args have no value and no ref-id; bound args have either."
  [args]
  (reduce (fn [acc arg]
            (if (free-arg? arg)
              (update acc :free-args conj arg)
              (update acc :bound-args conj arg)))
          {:free-args [] :bound-args []}
          args))


(defn- resolve-arg-name-cached
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


(defn- collect-source-id-chain
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


(defn- terminal-source-id
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

   args-data contains :by-fn and :by-id indexes."
  [fn-cache args-data fn-id visited-fns depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Free arg collection chain too deep"
                    {:type :fn-composition/chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (if (contains? visited-fns fn-id)
    ;; Cycle detected, return empty to avoid infinite loop
    []
    (let [visited' (conj visited-fns fn-id)
          fn-args (get (:by-fn args-data) fn-id [])
          {:keys [free-args bound-args]} (partition-args-by-freedom fn-args)]
      ;; Free args are:
      ;; 1. Own free args
      ;; 2. Free args from fns referenced via ref-id (recursively)
      (into free-args
            (mapcat (fn [arg]
                      (when-let [ref-fn-id (:ref-id arg)]
                        (collect-free-args-from-fn fn-cache args-data ref-fn-id visited' (inc depth)))))
            bound-args))))


(defn- collect-parent-free-args-for-one
  "Collects free args from a single parent fn (internal helper)."
  [fn-cache args-data parent-fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain too deep while collecting free args"
                    {:type :fn-composition/parent-chain-too-deep
                     :parent-fn-id parent-fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [fn-args (get (:by-fn args-data) parent-fn-id [])
        {:keys [free-args bound-args]} (partition-args-by-freedom fn-args)
        ;; Collect free args from referenced fns (only bound args have ref-id)
        ref-free-args (mapcat (fn [arg]
                                (when-let [ref-fn-id (:ref-id arg)]
                                  (collect-free-args-from-fn fn-cache args-data ref-fn-id #{} (inc depth))))
                              bound-args)]
    (into free-args ref-free-args)))


(defn- collect-parent-free-args
  "Collects free args from all parent fns.
   Returns vector of arg entities that are free in the parents.

   Accepts a collection of parent fn-ids (multiple inheritance).
   args-data contains :by-fn and :by-id indexes.

   Dedupes by [terminal-source-id, resolved-name]. With diamond inheritance
   (two parents sharing a common ancestor), the same root free arg is
   reachable via both parents as different propagated arg entities — they
   must collapse to one. But the cascade pattern (pair-1, pair, triple, ...)
   intentionally creates multiple propagated copies of the same root arg
   under different rename targets (item1, item2, item3, ...), so the
   resolved name is part of the dedup key to keep them distinct.

   Dedupes by [terminal-source-id, resolved-name]. With diamond inheritance
   (two parents sharing a common ancestor), the same root free arg is
   reachable via both parents as different propagated arg entities — they
   must collapse to one. But the cascade pattern (pair-1, pair, triple, ...)
   intentionally creates multiple propagated copies of the same root arg
   under different rename targets (item1, item2, item3, ...), so the
   resolved name is part of the dedup key to keep them distinct."
  [fn-cache args-data parent-fn-ids depth]
  (let [args-by-id (:by-id args-data)
        seen (atom #{})]
    (into []
          (comp
            (mapcat (fn [pid]
                      (collect-parent-free-args-for-one fn-cache args-data pid depth)))
            (remove (fn [a]
                      (let [root (terminal-source-id args-by-id a)
                            resolved-name (resolve-arg-name-cached args-by-id a 0)
                            k [root resolved-name]]
                        (if (contains? @seen k)
                          true
                          (do (swap! seen conj k) false))))))
          (remove nil? parent-fn-ids))))


(defn- preload-all-args
  "Loads arg entities for given fn-ids using WHERE IN clause.
   Returns map with three indexes:
   - :by-fn {fn-id -> [args]} for fn-based lookup
   - :by-id {arg-id -> arg} for O(1) arg lookup by id
   - :by-fn-source {[fn-id source-id] -> arg} for O(1) lookup by fn+source"
  [storage fn-ids]
  (if (empty? fn-ids)
    {:by-fn {} :by-id {} :by-fn-source {}}
    ;; Use WHERE IN clause instead of full table scan + filter
    (let [matching-args (sp/query-entities storage :arg {:fn-id (vec fn-ids)})]
      {:by-fn (group-by :fn-id matching-args)
       :by-id (into {} (map (fn [a] [(:id a) a])) matching-args)
       :by-fn-source (into {} (keep (fn [a]
                                      (when-let [sid (:source-id a)]
                                        [[(:fn-id a) sid] a])))
                           matching-args)})))


(defn- get-parent-arg-cached
  "Gets the parent's arg entity for an arg name using cache.
   Follows inheritance graph (via parent-ids) to find the arg.
   Resolves arg names via source-id chain if arg.name is nil.
   Returns the arg entity or throws if not found.

   Accepts a collection of parent fn-ids. With multiple inheritance, walks all
   parents in BFS order (first parent has precedence for arg name conflicts).

   args-data contains :by-fn and :by-id indexes."
  [fn-cache args-data parent-fn-ids arg-name]
  (let [args-by-id (:by-id args-data)
        arg-name-str (name arg-name)
        start (vec (remove nil? parent-fn-ids))]
    (loop [queue start
           visited #{}
           iter 0]
      (when (> iter sp/*max-graph-iterations*)
        (throw (ex-info "Parent chain too deep while resolving arg"
                        {:type :fn-composition/parent-chain-too-deep
                         :arg-name arg-name
                         :max-depth sp/*max-graph-iterations*})))
      (if (empty? queue)
        (throw (ex-info (str "Argument not found in parent chain: " arg-name)
                        {:type :fn-composition/unresolved-arg
                         :parent-fn-ids start
                         :arg-name arg-name}))
        (let [fn-id (first queue)
              rest-q (rest queue)]
          (if (contains? visited fn-id)
            (recur rest-q visited (inc iter))
            (let [fn-args (get (:by-fn args-data) fn-id [])
                  ;; Match by resolved name using O(1) by-id lookup
                  found (some #(when (= (resolve-arg-name-cached args-by-id % 0) arg-name-str) %)
                              fn-args)]
              (or found
                  ;; Not found on this fn, enqueue all parent fns
                  (let [fn-entity (get fn-cache fn-id)
                        next-parent-ids (:parent-ids fn-entity)]
                    (recur (into (vec rest-q) next-parent-ids)
                           (conj visited fn-id)
                           (inc iter)))))))))))


(defn- find-available-arg
  "Finds an arg by name from all available args (parent chain + propagated free args).

   This is used for pass-through args: when child fn-def sets an arg that comes
   from a nested fn (via ref-id chain), not directly from parent chain.

   Search order:
   1. Parents' own args (via parent-ids chain, BFS over multiple inheritance)
   2. Propagated free args from refs (via ref-id chains)

   Accepts a collection of parent fn-ids (multiple inheritance).
   Resolves arg names via source-id chain if arg.name is nil.
   args-data contains :by-fn and :by-id indexes.
   Returns the arg entity or throws if not found."
  [fn-cache args-data parent-fn-ids arg-name]
  (let [arg-name-str (name arg-name)
        args-by-id (:by-id args-data)
        ;; First try direct parent chain lookup
        direct-result (try
                        (get-parent-arg-cached fn-cache args-data parent-fn-ids arg-name)
                        (catch clojure.lang.ExceptionInfo e
                          (when-not (= :fn-composition/unresolved-arg (:type (ex-data e)))
                            (throw e))
                          nil))]
    (or direct-result
        ;; Not in parent chain - search in propagated free args from refs
        ;; Single pass: find free-arg match first, else first any-match
        ;; Use resolved names for matching with O(1) by-id lookup
        (let [parent-free-args (collect-parent-free-args fn-cache args-data parent-fn-ids 0)
              found (reduce (fn [first-match arg]
                              (let [resolved-name (resolve-arg-name-cached args-by-id arg 0)]
                                (if (= resolved-name arg-name-str)
                                  (if (free-arg? arg)
                                    (reduced arg)           ; Free arg - best match, stop
                                    (or first-match arg))   ; Keep first non-free as fallback
                                  first-match)))
                            nil
                            parent-free-args)]
          (or found
              (throw (ex-info (str "Argument not found in available args: " arg-name
                                   ". Checked parent chain and propagated free args.")
                              {:type :fn-composition/unresolved-arg
                               :parent-fn-ids parent-fn-ids
                               :arg-name arg-name
                               :available-args (mapv #(resolve-arg-name-cached args-by-id % 0)
                                                     parent-free-args)})))))))


(defn- resolve-parent-fn-id-cached
  "Resolves a parent name to fn-id using caches.
   Returns UUID or throws if not found."
  [fn-name-cache fn-id-cache created-fns parent-name]
  (or (get created-fns parent-name)
      ;; Try registry (base-fns)
      (let [base-fn-id (registry/fn-uuid parent-name)]
        (when (contains? fn-id-cache base-fn-id)
          base-fn-id))
      ;; Try by name (composed fns)
      (when-let [existing (get fn-name-cache (name parent-name))]
        (:id existing))
      ;; Not found - throw
      (throw (ex-info (str "Parent fn not found: " parent-name
                           ". It must be a base-fn or defined earlier.")
                      {:type :fn-composition/unresolved-parent
                       :parent-name parent-name
                       :available-fns (keys created-fns)}))))


(defn- resolve-fn-id-cached
  "Resolves a fn name to fn-id using caches."
  [fn-name-cache created-fns fn-name]
  (or
    (get created-fns fn-name)
    (when-let [existing (get fn-name-cache (name fn-name))]
      (:id existing))
    (throw (ex-info (str "Referenced fn not found: " fn-name
                         ". It must be defined earlier or exist in storage.")
                    {:type :fn-composition/unresolved-fn-ref
                     :fn-name fn-name
                     :available-fns (keys created-fns)}))))


(defn- fn-def-parent-names
  "Returns a vector of parent names from a fn-def.
   Supports both :parent (single keyword) and :parents (vector of keywords)."
  [fn-def]
  (let [parent-list (:parents fn-def)
        parent (:parent fn-def)]
    (cond
      (seq parent-list) (vec parent-list)
      parent [parent]
      :else [])))


(defn- prepare-fn-record
  "Prepares a fn record for batch upsert.
   Returns {:id :name :parent-ids} or nil if already exists.

   Local fns (names starting with _) are stored with name=nil in DB.
   They can only be referenced within the same package by their local name."
  [fn-name-cache fn-id-cache created-fns fn-def ns-id-map]
  (let [fn-name (:name fn-def)
        fn-name-str (clojure.core/name fn-name)
        is-local? (local-fn-name? fn-name)]
    ;; Local fns are never "existing" - they're always created fresh
    ;; (since they have name=nil in DB and can't be looked up by name)
    (if (and (not is-local?)
             (get fn-name-cache fn-name-str))
      ;; Already exists (only for non-local fns)
      {:existing (get fn-name-cache fn-name-str)}
      ;; Need to create
      (let [parent-names (fn-def-parent-names fn-def)
            parent-ids (mapv #(resolve-parent-fn-id-cached
                                fn-name-cache fn-id-cache created-fns %)
                             parent-names)
            ;; Local fns get name=nil in DB
            db-name (when-not is-local? fn-name-str)
            ns-id (when-let [ns-path (:namespace fn-def)]
                    (get ns-id-map ns-path))]
        {:new (cond-> {:id (random-uuid)
                       :name db-name
                       :parent-ids (when (seq parent-ids) parent-ids)}
                ns-id (assoc :namespace-id ns-id))}))))


(defn- prepare-propagated-arg-record
  "Prepares an arg record for a propagated free arg.
   Used for free args that 'bubble up' from parent or referenced fns.
   Creates a new arg with source-id pointing to the original free arg.
   Name is nil - inherited via source-id chain.

   args-data contains :by-fn, :by-id, and :by-fn-source indexes."
  [args-data fn-id parent-arg]
  (let [source-id (:id parent-arg)
        ;; O(1) lookup via :by-fn-source index
        existing (get (:by-fn-source args-data) [fn-id source-id])]
    (when-not existing
      ;; Create new propagated arg (free, with no value or ref-id)
      ;; name is nil - will be resolved via source-id chain
      {:new {:id (random-uuid)
             :fn-id fn-id
             :source-id source-id
             ;; name is nil - inherited via source-id chain
             :type (:type parent-arg)
             :is-fn (:is-fn parent-arg)
             :value nil
             :ref-id nil}})))


(defn- parse-arg-value-spec
  "Parses arg value specification.
   Supports:
   - Simple values: 123, \"str\", :keyword, :fn-ref
   - Map with options: {:as :new-name} or {:as :new-name :value 123} or {:as :new-name :ref :fn-name}
   - Map with :type :fn to mark as HOF argument

   Returns {:rename nil-or-keyword :value-spec original-or-extracted :is-fn bool-or-nil}"
  [arg-value]
  (if (and (map? arg-value) (contains? arg-value :as))
    ;; Map with :as - extract rename and value
    (let [rename (:as arg-value)
          has-value? (contains? arg-value :value)
          has-ref? (contains? arg-value :ref)
          is-fn? (= :fn (:type arg-value))]
      (when-not (keyword? rename)
        (throw (ex-info ":as must be a keyword"
                        {:type :fn-composition/invalid-arg-spec
                         :arg-value arg-value})))
      (cond
        has-value? {:rename rename :value-spec (:value arg-value) :is-fn is-fn?}
        has-ref? {:rename rename :value-spec (:ref arg-value) :is-fn is-fn?}
        :else {:rename rename :value-spec nil :is-fn is-fn?}))
    ;; Simple value - no rename
    {:rename nil :value-spec arg-value :is-fn nil}))


(defn- prepare-arg-record
  "Prepares an arg record for batch upsert.
   Uses find-available-arg to support pass-through args from nested refs.

   Supports arg value as:
   - Simple value: literal or :fn-ref
   - Map with :as: {:as :new-name} to rename, optionally with :value or :ref

   args-data contains :by-fn, :by-id, and :by-fn-source indexes."
  [fn-cache args-data fn-name-cache created-fns fn-id parent-fn-ids arg-name arg-value]
  (when-not fn-id
    (throw (ex-info "fn-id cannot be nil when preparing arg record"
                    {:type :fn-composition/internal-error
                     :arg-name arg-name
                     :parent-fn-ids parent-fn-ids})))
  (let [;; Parse arg value spec (supports {:as :new-name ...})
        {:keys [rename value-spec is-fn]} (parse-arg-value-spec arg-value)
        ;; Use find-available-arg which searches both parent chain AND propagated free args
        parent-arg (find-available-arg fn-cache args-data parent-fn-ids arg-name)
        ;; Validate: cannot override already-bound argument
        parent-has-value (or (some? (:value parent-arg))
                             (some? (:ref-id parent-arg)))
        child-sets-value (or (some? value-spec)
                             (and (map? arg-value)
                                  (or (contains? arg-value :value)
                                      (contains? arg-value :ref))))
        _ (when (and parent-has-value child-sets-value)
            (let [args-by-id (:by-id args-data)
                  parent-arg-name (resolve-arg-name-cached args-by-id parent-arg 0)]
              (throw (ex-info (str "Cannot override already-bound argument: " parent-arg-name
                                   ". Parent already sets value=" (:value parent-arg)
                                   " ref-id=" (:ref-id parent-arg))
                              {:type :fn-composition/arg-override-forbidden
                               :arg-name arg-name
                               :parent-value (:value parent-arg)
                               :parent-ref-id (:ref-id parent-arg)
                               :child-value-spec value-spec}))))
        source-id (:id parent-arg)
        ;; O(1) lookup via :by-fn-source index
        existing (get (:by-fn-source args-data) [fn-id source-id])
        ;; Resolve arg value
        resolved (cond
                   (nil? value-spec)
                   {:value nil :ref-id nil}

                   (uuid? value-spec)
                   {:value nil :ref-id value-spec}

                   (keyword? value-spec)
                   (if-let [ref-fn-name (parse-fn-ref value-spec)]
                     (let [ref-fn-id (resolve-fn-id-cached fn-name-cache created-fns ref-fn-name)]
                       {:value nil :ref-id ref-fn-id})
                     {:value value-spec :ref-id nil})

                   :else
                   {:value value-spec :ref-id nil})
        ;; Add name override if specified
        resolved-with-name (if rename
                             (assoc resolved :name (name rename))
                             resolved)
        ;; Determine is-fn: explicit :type :fn in arg-value overrides parent
        effective-is-fn (if is-fn true (:is-fn parent-arg))]
    (if existing
      ;; Check if update needed (including name change, is-fn change)
      (when (or (not= (:value existing) (:value resolved-with-name))
                (not= (:ref-id existing) (:ref-id resolved-with-name))
                (and rename (not= (:name existing) (name rename)))
                (and is-fn (not (:is-fn existing))))
        ;; Merge full existing record with resolved to preserve all required fields
        {:update (merge existing resolved-with-name {:is-fn effective-is-fn})})
      ;; Create new - name is nil unless explicitly renamed via :as
      (let [new-arg (merge {:id (random-uuid)
                            :fn-id fn-id
                            :source-id source-id
                            ;; name is nil by default - will be resolved via source-id chain
                            ;; unless explicitly set via :as
                            :type (:type parent-arg)
                            :is-fn effective-is-fn}
                           resolved-with-name)]
        {:new new-arg}))))


(defn sync-fns-to-storage!
  "Syncs fn definitions to storage using batch operations.

   Arguments:
   - storage: initialized storage with base-fn schemas already synced
   - fn-defs: vector of fn definition maps

   Process:
   1. Validates all definitions
   2. Topologically sorts by dependencies
   3. Pre-loads existing fns and args (2 queries instead of N*M)
   4. Batch upserts all fn entities
   5. Batch upserts all arg entities

   Returns map of {fn-name -> fn-id} for created fns.

   Throws on:
   - Invalid definitions
   - Unresolved references (parent or arg)
   - Circular dependencies"
  ([storage fn-defs] (sync-fns-to-storage! storage fn-defs {}))
  ([storage fn-defs ns-id-map]
   (if (empty? fn-defs)
     {}
     (do
       (validate-all-defs! fn-defs)
       (let [sorted-defs (topological-sort fn-defs)
             _ (check-order-and-warn fn-defs sorted-defs)

             ;; Phase 1: Pre-load existing data (single pass over all-fns)
             all-fns (sp/query-entities storage :fn {})

             ;; Invert ns-id-map for qualified name resolution: {ns-uuid → ns-path}
             ns-path-by-id (into {} (map (fn [[path id]] [id path]) ns-id-map))

             ;; Build caches in a single pass.
             ;; fn-name-cache: simple name → entity (backward compat)
             ;; qualified-fn-cache: "ns.path.name" → entity (for dot-refs)
             {:keys [fn-id-cache fn-name-cache all-fn-ids]}
             (reduce (fn [acc fn-entity]
                       (let [acc (-> acc
                                     (assoc-in [:fn-id-cache (:id fn-entity)] fn-entity)
                                     (assoc-in [:fn-name-cache (:name fn-entity)] fn-entity)
                                     (update :all-fn-ids conj (:id fn-entity)))]
                         ;; Also add qualified entry if fn has namespace
                         (if-let [ns-path (get ns-path-by-id (:namespace-id fn-entity))]
                           (let [qualified (str ns-path "." (:name fn-entity))]
                             (assoc-in acc [:fn-name-cache qualified] fn-entity))
                           acc)))
                     {:fn-id-cache {}
                      :fn-name-cache {}
                      :all-fn-ids []}
                     all-fns)

             ;; Pre-load all args using WHERE IN
             args-cache (preload-all-args storage all-fn-ids)

             ;; Phase 2: Prepare and create fns in topological order
             ;; We need to do this sequentially due to dependencies
             ;; created-fns: {fn-name -> fn-id}
             ;; created-fn-entities: {fn-id -> fn-entity} - needed for parent chain lookup
             {:keys [created-fns created-fn-entities]}
             (loop [remaining sorted-defs
                    created {}
                    created-entities {}
                    new-fns []
                    fn-name-cache' fn-name-cache]
               (if (empty? remaining)
                 ;; Batch upsert new fns
                 (do
                   (when (seq new-fns)
                     (sp/upsert-entities storage :fn new-fns))
                   {:created-fns created
                    :created-fn-entities created-entities})
                 (let [fn-def (first remaining)
                       result (prepare-fn-record fn-name-cache' fn-id-cache created fn-def ns-id-map)
                       fn-entity (or (:existing result) (:new result))
                       fn-id (:id fn-entity)
                       new-created (assoc created (:name fn-def) fn-id)
                       ;; Track full entity for parent-ids lookup
                       new-created-entities (assoc created-entities fn-id fn-entity)
                       new-fns' (if (:new result)
                                  (conj new-fns (:new result))
                                  new-fns)
                       ;; Update cache with new fn
                       fn-name-cache'' (if (:new result)
                                         (assoc fn-name-cache' (name (:name fn-def)) fn-entity)
                                         fn-name-cache')]
                   (recur (rest remaining) new-created new-created-entities new-fns' fn-name-cache''))))

             ;; Update caches with newly created fns (simple + qualified names)
             fn-name-cache-final
             (reduce (fn [cache [kw-name fn-id]]
                       (let [n (name kw-name)
                             entry {:id fn-id :name n}
                             ;; Find namespace from the fn-def
                             fn-def (first (filter #(= (:name %) kw-name) sorted-defs))
                             ns-path (:namespace fn-def)]
                         (cond-> (assoc cache n entry)
                           ns-path (assoc (str ns-path "." n) entry))))
                     fn-name-cache
                     created-fns)
             ;; Include full fn-entities with parent-ids for parent chain lookup
             fn-id-cache-final (merge fn-id-cache created-fn-entities)

             ;; Phase 3: Process each fn-def's args sequentially, updating cache as we go
             ;; This allows later fn-defs to see propagated args from earlier ones
             ;;
             ;; We still collect all records for batch upsert at the end for efficiency.
             ;; args-data contains {:by-fn {fn-id -> [args]}, :by-id {arg-id -> arg}}
             {:keys [all-new-args all-update-args]}
             (loop [remaining sorted-defs
                    args-data args-cache  ; mutable view of args, updated after each fn-def
                    new-args []
                    update-args []]
               (if (empty? remaining)
                 {:all-new-args new-args
                  :all-update-args update-args}
                 (let [fn-def (first remaining)
                       fn-name (:name fn-def)
                       fn-id (get created-fns fn-name)
                       parent-names (fn-def-parent-names fn-def)
                       parent-fn-ids (mapv #(resolve-parent-fn-id-cached
                                              fn-name-cache-final fn-id-cache-final created-fns %)
                                           parent-names)

                       ;; 3a: Explicit args from fn-def :args
                       ;; Single pass: collect source-id CHAINS, new args, and update args together
                       ;; We collect full chains because explicit args shadow ALL args in their
                       ;; source-id chain, not just the immediate parent arg
                       ;; Also collect explicit arg names to filter free args with same root name
                       {:keys [explicit-source-ids explicit-new-args explicit-update-args explicit-arg-names]}
                       (reduce (fn [acc [arg-name arg-value]]
                                 (if-let [record (prepare-arg-record
                                                   fn-id-cache-final args-data fn-name-cache-final
                                                   created-fns fn-id parent-fn-ids arg-name arg-value)]
                                   (let [source-id (or (:source-id (:new record))
                                                       (:source-id (:update record)))
                                         ;; Collect FULL source-id chain to shadow transitive args
                                         source-chain (when source-id
                                                        (collect-source-id-chain (:by-id args-data) source-id))]
                                     (cond-> acc
                                       ;; Add explicit arg name (as string) to filter free args
                                       true
                                       (update :explicit-arg-names conj (name arg-name))
                                       source-chain
                                       (update :explicit-source-ids into source-chain)
                                       (:new record)
                                       (update :explicit-new-args conj (:new record))
                                       (:update record)
                                       (update :explicit-update-args conj (:update record))))
                                   acc))
                               {:explicit-source-ids #{}
                                :explicit-new-args []
                                :explicit-update-args []
                                :explicit-arg-names #{}}
                               (:args fn-def {}))

                       ;; 3b: Propagated free args from parent fns
                       ;; NOTE: We don't recursively collect from refs in explicit args (removed step 3c).
                       ;; The executor handles transitive arg propagation at runtime via trace-source-to-fn.
                       ;; This prevents internal free args (like html-response.status) from leaking
                       ;; through bound refs (like editor-route.handler).
                       parent-free-args (collect-parent-free-args
                                          fn-id-cache-final args-data parent-fn-ids 0)

                       ;; Use parent-free-args directly (no combination with explicit-ref-free-args)
                       all-free-args parent-free-args

                       ;; Helper to get root name of a free arg
                       args-by-id (:by-id args-data)
                       get-root-name (fn [arg] (resolve-arg-name-cached args-by-id arg 0))

                       propagated-new-args
                       (into []
                             (comp
                               ;; Filter 1: Remove args whose id is in explicit source chain
                               (remove #(contains? explicit-source-ids (:id %)))
                               ;; Filter 2: Remove args whose ROOT NAME matches an explicit arg name
                               ;; This handles cases like editor-routes setting item1-10 should
                               ;; prevent free args from route refs (which also resolve to "item")
                               ;; from being propagated
                               (remove (fn [arg]
                                         (when-let [root-name (get-root-name arg)]
                                           (contains? explicit-arg-names root-name))))
                               (keep #(when-let [rec (prepare-propagated-arg-record args-data fn-id %)]
                                        (:new rec))))
                             all-free-args)

                       ;; Combine explicit and propagated new args
                       fn-new-args (into explicit-new-args propagated-new-args)
                       fn-update-args explicit-update-args

                       ;; Update args-data with new args so next fn-def can see them
                       ;; Update all three indexes: :by-fn, :by-id, :by-fn-source
                       args-data' (reduce (fn [data arg]
                                            (cond-> data
                                              true
                                              (update-in [:by-fn (:fn-id arg)]
                                                         (fnil conj []) arg)
                                              true
                                              (assoc-in [:by-id (:id arg)] arg)
                                              ;; Add to by-fn-source index if has source-id
                                              (:source-id arg)
                                              (assoc-in [:by-fn-source [(:fn-id arg) (:source-id arg)]] arg)))
                                          args-data
                                          fn-new-args)]
                   (recur (rest remaining)
                          args-data'
                          (into new-args fn-new-args)
                          (into update-args fn-update-args)))))]

         ;; Batch upsert new args
         (when (seq all-new-args)
           (sp/upsert-entities storage :arg all-new-args))

         ;; Batch update existing args (if any changed)
         (when (seq all-update-args)
           (sp/upsert-entities storage :arg all-update-args))

         created-fns)))))
