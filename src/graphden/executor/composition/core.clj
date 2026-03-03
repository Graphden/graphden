(ns graphden.executor.composition.core
  "Data-driven fn definitions and storage sync.

   This component provides a declarative way to define fn entities
   (not base-fns) that compose base functions through the graph.

   ## Format

   Fn definitions are a vector of maps (order matters for validation):

   ```clojure
   [{:name :router-handler
     :parent :default-router-handler}  ; parent is base-fn

    {:name :web-server
     :parent :http-server              ; parent is base-fn
     :args {:handler :router-handler  ; ref to fn by name
            :port 8080}}]              ; literal value
   ```

   ## Arg Value Syntax

   At the TOP LEVEL of args, both syntaxes work:
   - `:fn-name` - ref<fn>: pass fn as callable (for HOF, won't execute)
   - `:fn-name>` - ref<fn-usage>: execute fn and use result
   - `:fn-name>result-name` - ref<fn-usage> with explicit name

   In NESTED structures (maps, vectors inside arg values), only the
   `:fn-name>` syntax works. Plain keywords are kept as-is because
   we can't distinguish `:some-fn` (intended fn ref) from `:status` (data).

   Example with nested references:
   ```clojure
   {:name :my-router
    :parent :router
    :args {:routes [[\"GET\" \"/\" {:handler :home-handler>}]
                    [\"GET\" \"/api\" {:handler :api-handler>}]]}}
   ```

   The `:home-handler>` and `:api-handler>` will be resolved recursively,
   creating fn-usages and replacing references with fn-usage-ids.
   Other keywords like `:handler` (map key) are kept as-is.

   Multiple references to `:fn-name>` create one fn-usage (same name).
   Use `:fn-name>name1`, `:fn-name>name2` for different fn-usages.

   ## Name Resolution

   Names in :parent and :args are resolved in this order:
   1. fn-schema from base-fns (by name)
   2. fn from this definition set (by name)
   3. fn already in storage (by name)

   ## Topological Sort

   Functions must be defined AFTER their dependencies.
   If order is violated, a warning is printed with suggested fix,
   but sync proceeds in correct order.

   ## Transaction

   All entities are created atomically - if any fails, all roll back."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.executor.registry.interface :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === Arg Value Parsing ===

(defn- valid-identifier?
  "Returns true if s looks like a valid Clojure keyword name.
   Valid identifiers don't contain whitespace, newlines, or other special chars."
  [s]
  (when (and (string? s) (seq s))
    ;; Simple check: no whitespace, must start with letter/underscore/dash
    (and (not (re-find #"\s" s))
         (re-matches #"[a-zA-Z_\-][a-zA-Z0-9_\-]*" s))))


(defn- parse-fn-result-ref
  "Parses a keyword or string that might be a fn-usage reference.
   :fn-name> or \"fn-name>\" means execute fn-name, result name defaults to fn-name
   :fn-name>result-name means execute fn-name, result name is result-name
   Returns [fn-name result-name] or nil if not a fn-usage ref.

   Also handles strings (for JSONB round-trip where keywords become strings)."
  [value]
  (let [[kw-name kw-ns]
        (cond
          (keyword? value) [(name value) (namespace value)]
          (string? value) [value nil]
          :else [nil nil])]
    (when (and kw-name (str/includes? kw-name ">"))
      (let [parts (str/split kw-name #">" 2)
            fn-name (first parts)
            result-name (second parts)]
        ;; Only match if fn-name looks like a valid identifier
        (when (and (seq fn-name) (valid-identifier? fn-name))
          ;; Reconstruct keyword with namespace if present
          (let [fn-kw (if kw-ns (keyword kw-ns fn-name) (keyword fn-name))
                ;; If result-name is empty (just ":fn>"), use fn-name as default
                result-kw (if (or (nil? result-name) (str/blank? result-name))
                            fn-kw
                            (if kw-ns (keyword kw-ns result-name) (keyword result-name)))]
            [fn-kw result-kw]))))))


(defn- extract-fn-ref
  "Extracts the fn name from an arg value.
   Returns [fn-name :fn nil] for keyword refs (pass as callable).
   Returns [fn-name :fn-usage result-name] for :fn-name> refs (execute and use result).
   Returns nil for literal values."
  [arg-value]
  (when (keyword? arg-value)
    (if-let [[fn-name result-name] (parse-fn-result-ref arg-value)]
      [fn-name :fn-usage result-name]
      [arg-value :fn nil])))


;; === Name Resolution ===

(defn- resolve-fn-schema-id
  "Resolves a parent name to fn-schema-id.
   Parent must be a base-fn name (fn-schema with base-fn-name set).
   Returns UUID or throws if not found."
  [storage parent-name]
  (let [fn-schema-id (registry/fn-schema-uuid parent-name)]
    ;; Verify it exists
    (when-not (sp/read-entity storage :fn-schema fn-schema-id)
      (throw (ex-info (str "Parent base-fn not found: " parent-name
                           ". Did you forget to add base-functions?")
                      {:type :fn-composition/unresolved-parent
                       :parent-name parent-name})))
    fn-schema-id))


(defn- resolve-fn-id
  "Resolves a fn name to fn-id.
   Looks in:
   1. created-fns map (fns created in current batch)
   2. storage (existing fns)
   Returns UUID or throws if not found."
  [storage created-fns fn-name]
  (or
    ;; Check fns created in this batch
    (get created-fns fn-name)
    ;; Check storage by name
    (let [existing (sp/query-entities storage :fn {:name (name fn-name)})]
      (when (seq existing)
        (:id (first existing))))
    ;; Not found
    (throw (ex-info (str "Referenced fn not found: " fn-name
                         ". It must be defined earlier in the set or exist in storage.")
                    {:type :fn-composition/unresolved-fn-ref
                     :fn-name fn-name
                     :available-fns (keys created-fns)}))))


(defn- resolve-arg-schema-id
  "Resolves arg-schema-id from parent base-fn name and arg name."
  [parent-name arg-name]
  (registry/arg-schema-uuid parent-name arg-name))


;; === Dependency Analysis ===

(defn- collect-deps-from-value
  "Collects fn dependencies from a single arg value.
   Only handles top-level refs (:fn-name and :fn-name> syntax).
   Nested structures with refs are NOT supported - use explicit
   graph functions (pair, assoc-any, conj-any) to build them."
  [value]
  (if-let [ref-info (extract-fn-ref value)]
    ;; Top-level: both :fn-name and :fn-name> work
    #{(first ref-info)}
    ;; Not a top-level ref - no nested collection (nested not supported)
    #{}))


(defn- extract-dependencies
  "Extracts fn names that this fn-def depends on (from args).
   At top-level args, handles both :fn-name and :fn-name> syntax.
   In nested structures, only :fn-name> syntax is supported.
   Returns set of keywords."
  [fn-def fn-names-in-set]
  (let [args (:args fn-def {})]
    (->> (vals args)
         (mapcat collect-deps-from-value)
         (filter fn-names-in-set)
         set)))


(defn- build-dependency-graph
  "Builds dependency graph from fn-defs.
   Returns map of {fn-name -> #{dependency-names}}."
  [fn-defs]
  (let [fn-names (set (map :name fn-defs))]
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
           remaining (set (map :name fn-defs))
           visited #{}]
      (if (empty? remaining)
        (mapv fn-def-map sorted)
        ;; Find fn with no unmet dependencies
        (if-let [ready (->> remaining
                            (filter (fn [fn-name]
                                      (let [deps (get dep-graph fn-name #{})]
                                        (every? #(contains? visited %) deps))))
                            first)]
          (recur (conj sorted ready)
                 (disj remaining ready)
                 (conj visited ready))
          ;; No ready fn - cycle detected
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
                "Suggested order:" (str/join " -> " (map name sorted-order))
                "Consider reordering for clarity."))))


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
  ;; Check for duplicate names
  (let [names (map :name fn-defs)
        duplicates (->> names
                        frequencies
                        (filter #(> (val %) 1))
                        keys)]
    (when (seq duplicates)
      (throw (ex-info "Duplicate fn names in definitions"
                      {:type :fn-composition/duplicate-names
                       :duplicates duplicates}))))
  ;; Validate each def
  (doseq [fn-def fn-defs]
    (validate-fn-def! fn-def)))


;; === Storage Sync ===

(defn- get-or-create-fn-entity!
  "Gets existing fn with same name or creates new one.
   Returns the fn entity."
  [storage fn-def]
  (let [fn-name (:name fn-def)
        fn-name-str (clojure.core/name fn-name)
        existing (sp/query-entities storage :fn {:name fn-name-str})]
    (if (seq existing)
      (first existing)
      (let [parent (:parent fn-def)
            fn-schema-id (resolve-fn-schema-id storage parent)]
        (sp/create-entity storage :fn
                          {:name fn-name-str
                           :fn-schema-id fn-schema-id})))))


(defn- get-or-create-arg-value!
  "Gets existing arg-value with same (arg-schema-id, value) or creates new one.
   Returns arg-value id.

   Deduplication: same (arg-schema-id, value) pair reuses existing arg-value."
  [storage arg-schema-id resolved-value created-arg-values]
  (let [cache-key [arg-schema-id resolved-value]]
    (if-let [existing-id (get @created-arg-values cache-key)]
      existing-id
      ;; Check storage for existing arg-value with same (arg-schema-id, value)
      (let [existing (sp/query-entities storage :arg-value
                                        {:arg-schema-id arg-schema-id
                                         :value resolved-value})]
        (if (seq existing)
          (let [id (:id (first existing))]
            (swap! created-arg-values assoc cache-key id)
            id)
          ;; Create new arg-value (no owner - pure value)
          (let [av (sp/create-entity storage :arg-value
                                     {:arg-schema-id arg-schema-id
                                      :value resolved-value})]
            (swap! created-arg-values assoc cache-key (:id av))
            (:id av)))))))


(defn- resolve-arg-value
  "Resolves an arg value, handling top-level references only.

   At top-level (the arg value itself):
   - :fn-name -> resolve to fn-id (for HOF)
   - :fn-name> -> create fn-usage and resolve to fn-usage-id

   NOTE: Nested structures with fn/fn-usage references are NOT resolved.
   Use explicit graph functions (pair, assoc-any, conj-any) to build
   structures containing fn/fn-usage references."
  [arg-value storage created-fns created-fn-usages]
  (if-let [ref-info (extract-fn-ref arg-value)]
    ;; Top-level fn reference
    (let [[fn-name ref-type result-name] ref-info]
      (condp = ref-type
        ;; :fn-name> - get or create fn-usage
        :fn-usage
        (let [result-name-str (name result-name)]
          (if-let [existing-fu-id (get @created-fn-usages result-name)]
            existing-fu-id
            ;; Check storage for existing fn-usage with same name
            (let [existing-in-db (sp/query-entities storage :fn-usage {:name result-name-str})]
              (if (seq existing-in-db)
                (let [fu-id (:id (first existing-in-db))]
                  (swap! created-fn-usages assoc result-name fu-id)
                  fu-id)
                (let [ref-fn-id (resolve-fn-id storage created-fns fn-name)
                      fn-usage (sp/create-entity storage :fn-usage
                                                 {:fn-id ref-fn-id
                                                  :name result-name-str})]
                  (swap! created-fn-usages assoc result-name (:id fn-usage))
                  (:id fn-usage))))))

        ;; :fn-name - resolve to fn id
        :fn
        (resolve-fn-id storage created-fns fn-name)

        ;; default
        arg-value))
    ;; Not a top-level ref - return literal value as-is (no nested resolution)
    arg-value))


(defn- get-or-create-fn-arg!
  "Gets existing fn-arg binding or creates/updates one.
   The unique constraint is on (fn-id, arg-schema-id).
   If existing fn-arg has different arg-value-id, updates it.
   Returns the fn-arg entity."
  [storage fn-id arg-schema-id arg-value-id]
  (let [existing (sp/query-entities storage :fn-arg
                                    {:fn-id fn-id
                                     :arg-schema-id arg-schema-id})]
    (if (seq existing)
      (let [existing-fn-arg (first existing)]
        (if (= (:arg-value-id existing-fn-arg) arg-value-id)
          existing-fn-arg
          (sp/update-entity storage :fn-arg (:id existing-fn-arg)
                            {:arg-value-id arg-value-id})))
      (sp/create-entity storage :fn-arg
                        {:fn-id fn-id
                         :arg-schema-id arg-schema-id
                         :arg-value-id arg-value-id}))))


(defn- create-arg-values!
  "Creates arg-value entities and fn-arg bindings for a fn.
   Resolves top-level references to other fns.

   At top-level args, both :fn-name and :fn-name> syntax work.
   NOTE: Nested structures with fn/fn-usage references are NOT resolved.
   Use explicit graph functions (pair, assoc-any, conj-any) to build
   structures containing fn/fn-usage references.

   With normalized schema:
   - arg-value is a pure value (no owner-fn-id)
   - fn-arg binds fn to arg-value
   - arg-values with same (arg-schema-id, value) are deduplicated

   The created-fn-usages atom tracks fn-usages created
   in this sync batch, keyed by their name (keyword).
   The created-arg-values atom tracks arg-values created for deduplication."
  [storage fn-entity fn-def created-fns created-fn-usages created-arg-values]
  (let [{:keys [parent args]} fn-def
        fn-id (:id fn-entity)]
    (doseq [[arg-name arg-value] args]
      (let [arg-schema-id (resolve-arg-schema-id parent arg-name)
            ;; Resolve the arg value (handles top-level and nested refs)
            resolved-value (resolve-arg-value arg-value storage created-fns created-fn-usages)
            ;; Get or create arg-value (deduplicated)
            arg-value-id (get-or-create-arg-value! storage arg-schema-id resolved-value created-arg-values)]
        ;; Get or create fn-arg binding (fn -> arg-value)
        (get-or-create-fn-arg! storage fn-id arg-schema-id arg-value-id)))))


(defn sync-fns-to-storage!
  "Syncs fn definitions to storage.

   Arguments:
   - storage: initialized storage with base-fn schemas already synced
   - fn-defs: vector of fn definition maps

   Process:
   1. Validates all definitions
   2. Topologically sorts by dependencies
   3. Warns if original order was wrong
   4. Creates all fn entities
   5. Creates all arg-value and fn-arg entities
      - arg-values are deduplicated by (arg-schema-id, value)
      - fn-arg binds fn to arg-value
      - fn-usages are deduplicated by name

   Returns map of {fn-name -> fn-id} for created fns.

   Throws on:
   - Invalid definitions
   - Unresolved references (parent or arg)
   - Circular dependencies"
  [storage fn-defs]
  (if (empty? fn-defs)
    {}
    (do
      ;; 1. Validate
      (validate-all-defs! fn-defs)
      ;; 2. Topological sort
      (let [sorted-defs (topological-sort fn-defs)
            ;; Track fn-usages created during sync for deduplication
            created-fn-usages (atom {})
            ;; Track arg-values created during sync for deduplication
            created-arg-values (atom {})]
        ;; 3. Warn if order was wrong
        (check-order-and-warn fn-defs sorted-defs)
        ;; 4. Create fns in sorted order, tracking created ids
        (loop [remaining sorted-defs
               created-fns {}]
          (if (empty? remaining)
            created-fns
            (let [fn-def (first remaining)
                  fn-entity (get-or-create-fn-entity! storage fn-def)
                  new-created (assoc created-fns (:name fn-def) (:id fn-entity))]
              ;; 5. Create arg-values and fn-arg bindings for this fn
              (create-arg-values! storage fn-entity fn-def new-created
                                  created-fn-usages created-arg-values)
              (recur (rest remaining) new-created))))))))
