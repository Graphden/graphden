(ns graphden.executor.composition.core
  "Data-driven fn definitions and storage sync.

   ## 2-Entity Schema

   Composed functions are stored as:
   - fn entity with parent-id set (inherits from parent base-fn)
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
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.executor.registry.interface :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === Arg Value Parsing ===

(defn- valid-identifier?
  "Returns true if s looks like a valid Clojure keyword name."
  [s]
  (when (and (string? s) (seq s))
    (and (not (re-find #"\s" s))
         (re-matches #"[a-zA-Z_\-][a-zA-Z0-9_\-]*" s))))


(defn- parse-fn-ref
  "Parses a keyword that might be a fn reference.
   Returns fn-name keyword or nil if not a fn ref."
  [value]
  (when (keyword? value)
    (let [kw-name (name value)]
      (when (valid-identifier? kw-name)
        value))))


;; === Dependency Analysis ===

(defn- extract-dependencies
  "Extracts fn names that this fn-def depends on (from args and parent).
   Returns set of keywords."
  [fn-def fn-names-in-set]
  (let [args (:args fn-def {})
        parent (:parent fn-def)
        arg-deps (->> (vals args)
                      (keep parse-fn-ref)
                      (filter fn-names-in-set)
                      set)
        ;; If parent is a composed fn (in fn-names-in-set), it's a dependency
        parent-dep (when (and parent (fn-names-in-set parent))
                     #{parent})]
    (into arg-deps parent-dep)))


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
   Throws on cycles."
  [fn-defs]
  (let [dep-graph (build-dependency-graph fn-defs)
        fn-def-map (into {} (map (juxt :name identity) fn-defs))]
    (loop [sorted []
           remaining (into #{} (map :name) fn-defs)
           visited #{}]
      (if (empty? remaining)
        (mapv fn-def-map sorted)
        (if-let [ready (->> remaining
                            (filter (fn [fn-name]
                                      (let [deps (get dep-graph fn-name #{})]
                                        (every? #(contains? visited %) deps))))
                            first)]
          (recur (conj sorted ready)
                 (disj remaining ready)
                 (conj visited ready))
          (throw (ex-info "Circular dependency detected in fn definitions"
                          {:type :fn-composition/circular-dependency
                           :remaining remaining
                           :dep-graph (select-keys dep-graph remaining)})))))))


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
  [{:keys [parent args] :as fn-def}]
  (let [fn-name (:name fn-def)]
    (when-not fn-name
      (throw (ex-info "fn-def must have :name"
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))
    (when-not (keyword? fn-name)
      (throw (ex-info "fn-def :name must be a keyword"
                      {:type :fn-composition/invalid-def
                       :name fn-name})))
    (when-not parent
      (throw (ex-info (str "fn-def " fn-name " must have :parent (base-fn name)")
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
  (let [names (map :name fn-defs)
        duplicates (->> names
                        frequencies
                        (filter #(> (val %) 1))
                        keys)]
    (when (seq duplicates)
      (throw (ex-info "Duplicate fn names in definitions"
                      {:type :fn-composition/duplicate-names
                       :duplicates duplicates}))))
  (doseq [fn-def fn-defs]
    (validate-fn-def! fn-def)))


;; === Storage Sync (Batch Optimized) ===

;; === Free Argument Propagation ===

(defn- free-arg?
  "Returns true if the arg is 'free' (has no value and no ref-id)."
  [arg]
  (and (nil? (:value arg))
       (nil? (:ref-id arg))))


(defn- collect-free-args-from-fn
  "Collects all free args from a fn by following parent chain and ref-id chain.
   Returns a vector of {:arg arg-entity :path [fn-id ...]} tuples.

   Free args come from:
   1. Parent fn's free args (via parent-id)
   2. Free args from fns referenced in arg values (via ref-id)"
  [fn-cache args-cache fn-id visited-fns depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Free arg collection chain too deep"
                    {:type :fn-composition/chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (if (contains? visited-fns fn-id)
    ;; Cycle detected, return empty to avoid infinite loop
    []
    (let [visited' (conj visited-fns fn-id)
          fn-args (get args-cache fn-id [])
          own-free-args (filter free-arg? fn-args)]
      ;; Free args are:
      ;; 1. Own free args
      ;; 2. Free args from fns referenced via ref-id (recursively)
      (vec (concat own-free-args
                   (mapcat (fn [arg]
                             (when-let [ref-fn-id (:ref-id arg)]
                               (collect-free-args-from-fn fn-cache args-cache ref-fn-id visited' (inc depth))))
                           ;; Only check args that HAVE ref-id (bound to other fn)
                           (remove free-arg? fn-args)))))))


(defn- collect-parent-free-args
  "Collects free args from the parent fn chain.
   Returns vector of arg entities that are free in the parent chain."
  [fn-cache args-cache parent-fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain too deep while collecting free args"
                    {:type :fn-composition/parent-chain-too-deep
                     :parent-fn-id parent-fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [fn-args (get args-cache parent-fn-id [])
        own-free-args (filter free-arg? fn-args)
        ;; Also collect free args from referenced fns
        ref-free-args (mapcat (fn [arg]
                                (when-let [ref-fn-id (:ref-id arg)]
                                  (collect-free-args-from-fn fn-cache args-cache ref-fn-id #{} (inc depth))))
                              (remove free-arg? fn-args))]
    (vec (concat own-free-args ref-free-args))))


(defn- preload-all-args
  "Loads arg entities for given fn-ids using WHERE IN clause.
   Returns map of {fn-id -> [args]}."
  [storage fn-ids]
  (if (empty? fn-ids)
    {}
    ;; Use WHERE IN clause instead of full table scan + filter
    (let [matching-args (sp/query-entities storage :arg {:fn-id (vec fn-ids)})]
      (group-by :fn-id matching-args))))


(defn- get-parent-arg-cached
  "Gets the parent's arg entity for an arg name using cache.
   Follows inheritance chain (via parent-id) to find the arg.
   Returns the arg entity or throws if not found."
  [fn-cache args-cache parent-fn-id arg-name]
  (loop [fn-id parent-fn-id
         depth 0]
    (when (> depth sp/*max-graph-iterations*)
      (throw (ex-info "Parent chain too deep while resolving arg"
                      {:type :fn-composition/parent-chain-too-deep
                       :arg-name arg-name
                       :max-depth sp/*max-graph-iterations*})))
    (let [fn-args (get args-cache fn-id [])
          found (some #(when (= (:name %) (name arg-name)) %) fn-args)]
      (or found
          ;; Not found on this fn, check parent
          (let [fn-entity (get fn-cache fn-id)]
            (if-let [next-parent-id (:parent-id fn-entity)]
              (recur next-parent-id (inc depth))
              ;; No more parents - arg not found
              (throw (ex-info (str "Argument not found in parent chain: " arg-name)
                              {:type :fn-composition/unresolved-arg
                               :parent-fn-id parent-fn-id
                               :arg-name arg-name}))))))))


(defn- find-available-arg
  "Finds an arg by name from all available args (parent chain + propagated free args).

   This is used for pass-through args: when child fn-def sets an arg that comes
   from a nested fn (via ref-id chain), not directly from parent chain.

   Search order:
   1. Parent's own args (via parent-id chain)
   2. Propagated free args from refs (via ref-id chains)

   Returns the arg entity or throws if not found."
  [fn-cache args-cache parent-fn-id arg-name]
  (let [arg-name-str (name arg-name)
        ;; First try direct parent chain lookup
        direct-result (try
                        (get-parent-arg-cached fn-cache args-cache parent-fn-id arg-name)
                        (catch clojure.lang.ExceptionInfo e
                          (when-not (= :fn-composition/unresolved-arg (:type (ex-data e)))
                            (throw e))
                          nil))]
    (or direct-result
        ;; Not in parent chain - search in propagated free args from refs
        ;; Single pass: find free-arg match first, else first any-match
        (let [parent-free-args (collect-parent-free-args fn-cache args-cache parent-fn-id 0)
              found (reduce (fn [first-match arg]
                              (if (= (:name arg) arg-name-str)
                                (if (free-arg? arg)
                                  (reduced arg)           ; Free arg - best match, stop
                                  (or first-match arg))   ; Keep first non-free as fallback
                                first-match))
                            nil
                            parent-free-args)]
          (or found
              (throw (ex-info (str "Argument not found in available args: " arg-name
                                   ". Checked parent chain and propagated free args.")
                              {:type :fn-composition/unresolved-arg
                               :parent-fn-id parent-fn-id
                               :arg-name arg-name
                               :available-args (mapv :name parent-free-args)})))))))


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


(defn- prepare-fn-record
  "Prepares a fn record for batch upsert.
   Returns {:id :name :parent-id} or nil if already exists."
  [fn-name-cache fn-id-cache created-fns fn-def]
  (let [fn-name (:name fn-def)
        fn-name-str (clojure.core/name fn-name)]
    (if-let [existing (get fn-name-cache fn-name-str)]
      ;; Already exists
      {:existing existing}
      ;; Need to create
      (let [parent (:parent fn-def)
            parent-fn-id (resolve-parent-fn-id-cached
                           fn-name-cache fn-id-cache created-fns parent)]
        {:new {:id (random-uuid)
               :name fn-name-str
               :parent-id parent-fn-id}}))))


(defn- prepare-propagated-arg-record
  "Prepares an arg record for a propagated free arg.
   Used for free args that 'bubble up' from parent or referenced fns.
   Creates a new arg with source-id pointing to the original free arg."
  [args-cache fn-id parent-arg]
  (let [source-id (:id parent-arg)
        ;; Check if this propagated arg already exists for this fn
        fn-args (get args-cache fn-id [])
        existing (some #(when (= (:source-id %) source-id) %) fn-args)]
    (when-not existing
      ;; Create new propagated arg (free, with no value or ref-id)
      {:new {:id (random-uuid)
             :fn-id fn-id
             :source-id source-id
             :name (:name parent-arg)
             :type (:type parent-arg)
             :is-fn (:is-fn parent-arg)
             :value nil
             :ref-id nil}})))


(defn- prepare-arg-record
  "Prepares an arg record for batch upsert.
   Uses find-available-arg to support pass-through args from nested refs."
  [fn-cache args-cache fn-name-cache created-fns fn-id parent-fn-id arg-name arg-value]
  (when-not fn-id
    (throw (ex-info "fn-id cannot be nil when preparing arg record"
                    {:type :fn-composition/internal-error
                     :arg-name arg-name
                     :parent-fn-id parent-fn-id})))
  (let [;; Use find-available-arg which searches both parent chain AND propagated free args
        parent-arg (find-available-arg fn-cache args-cache parent-fn-id arg-name)
        source-id (:id parent-arg)
        ;; Check if arg already exists for this fn
        fn-args (get args-cache fn-id [])
        existing (some #(when (= (:source-id %) source-id) %) fn-args)
        ;; Resolve arg value
        resolved (cond
                   (nil? arg-value)
                   {:value nil :ref-id nil}

                   (uuid? arg-value)
                   {:value nil :ref-id arg-value}

                   (keyword? arg-value)
                   (if-let [ref-fn-name (parse-fn-ref arg-value)]
                     (let [ref-fn-id (resolve-fn-id-cached fn-name-cache created-fns ref-fn-name)]
                       {:value nil :ref-id ref-fn-id})
                     {:value arg-value :ref-id nil})

                   :else
                   {:value arg-value :ref-id nil})]
    (if existing
      ;; Check if update needed
      (when (or (not= (:value existing) (:value resolved))
                (not= (:ref-id existing) (:ref-id resolved)))
        ;; Merge full existing record with resolved to preserve all required fields
        {:update (merge existing resolved)})
      ;; Create new
      {:new (merge {:id (random-uuid)
                    :fn-id fn-id
                    :source-id source-id
                    :name (:name parent-arg)
                    :type (:type parent-arg)
                    :is-fn (:is-fn parent-arg)}
                   resolved)})))


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
  [storage fn-defs]
  (if (empty? fn-defs)
    {}
    (do
      (validate-all-defs! fn-defs)
      (let [sorted-defs (topological-sort fn-defs)
            _ (check-order-and-warn fn-defs sorted-defs)

            ;; Phase 1: Pre-load existing data (single pass over all-fns)
            all-fns (sp/query-entities storage :fn {})

            ;; Build both caches in a single pass
            {:keys [fn-id-cache fn-name-cache all-fn-ids]}
            (reduce (fn [acc fn-entity]
                      (-> acc
                          (assoc-in [:fn-id-cache (:id fn-entity)] fn-entity)
                          (assoc-in [:fn-name-cache (:name fn-entity)] fn-entity)
                          (update :all-fn-ids conj (:id fn-entity))))
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
                      result (prepare-fn-record fn-name-cache' fn-id-cache created fn-def)
                      fn-entity (or (:existing result) (:new result))
                      fn-id (:id fn-entity)
                      new-created (assoc created (:name fn-def) fn-id)
                      ;; Track full entity for parent-id lookup
                      new-created-entities (assoc created-entities fn-id fn-entity)
                      new-fns' (if (:new result)
                                 (conj new-fns (:new result))
                                 new-fns)
                      ;; Update cache with new fn
                      fn-name-cache'' (if (:new result)
                                        (assoc fn-name-cache' (name (:name fn-def)) fn-entity)
                                        fn-name-cache')]
                  (recur (rest remaining) new-created new-created-entities new-fns' fn-name-cache''))))

            ;; Update caches with newly created fns
            fn-name-cache-final (merge fn-name-cache
                                       (into {}
                                             (map (fn [[k v]] (let [n (name k)] [n {:id v :name n}])))
                                             created-fns))
            ;; Include full fn-entities with parent-id for parent chain lookup
            fn-id-cache-final (merge fn-id-cache created-fn-entities)

            ;; Phase 3: Process each fn-def's args sequentially, updating cache as we go
            ;; This allows later fn-defs to see propagated args from earlier ones
            ;;
            ;; We still collect all records for batch upsert at the end for efficiency.
            {:keys [all-new-args all-update-args]}
            (loop [remaining sorted-defs
                   args-view args-cache  ; mutable view of args, updated after each fn-def
                   new-args []
                   update-args []]
              (if (empty? remaining)
                {:all-new-args new-args
                 :all-update-args update-args}
                (let [fn-def (first remaining)
                      fn-name (:name fn-def)
                      fn-id (get created-fns fn-name)
                      parent (:parent fn-def)
                      parent-fn-id (resolve-parent-fn-id-cached
                                     fn-name-cache-final fn-id-cache-final created-fns parent)

                      ;; 3a: Explicit args from fn-def :args
                      ;; Single pass: collect source-ids, new args, and update args together
                      {:keys [explicit-source-ids explicit-new-args explicit-update-args]}
                      (reduce (fn [acc [arg-name arg-value]]
                                (if-let [record (prepare-arg-record
                                                  fn-id-cache-final args-view fn-name-cache-final
                                                  created-fns fn-id parent-fn-id arg-name arg-value)]
                                  (let [source-id (or (:source-id (:new record))
                                                      (:source-id (:update record)))]
                                    (cond-> acc
                                      source-id
                                      (update :explicit-source-ids conj source-id)
                                      (:new record)
                                      (update :explicit-new-args conj (:new record))
                                      (:update record)
                                      (update :explicit-update-args conj (:update record))))
                                  acc))
                              {:explicit-source-ids #{}
                               :explicit-new-args []
                               :explicit-update-args []}
                              (:args fn-def {}))

                      ;; 3b: Propagated free args from parent chain and referenced fns
                      parent-free-args (collect-parent-free-args
                                         fn-id-cache-final args-view parent-fn-id 0)
                      propagated-new-args
                      (into []
                            (comp
                              (remove #(contains? explicit-source-ids (:id %)))
                              (keep #(when-let [rec (prepare-propagated-arg-record args-view fn-id %)]
                                       (:new rec))))
                            parent-free-args)

                      ;; Combine explicit and propagated new args
                      fn-new-args (into explicit-new-args propagated-new-args)
                      fn-update-args explicit-update-args

                      ;; Update args-view with new args so next fn-def can see them
                      args-view' (reduce (fn [cache arg]
                                           (update cache (:fn-id arg)
                                                   (fnil conj []) arg))
                                         args-view
                                         fn-new-args)]
                  (recur (rest remaining)
                         args-view'
                         (into new-args fn-new-args)
                         (into update-args fn-update-args)))))]

        ;; Batch upsert new args
        (when (seq all-new-args)
          (sp/upsert-entities storage :arg all-new-args))

        ;; Batch update existing args (if any changed)
        (when (seq all-update-args)
          (sp/upsert-entities storage :arg all-update-args))

        created-fns))))
