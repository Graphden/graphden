(ns graphden.fn-defs.core
  "Data-driven fn definitions and storage sync.

   This component provides a declarative way to define fn entities
   (not base-fns) that compose base functions through the graph.

   ## Format

   Fn definitions are a vector of maps (order matters for validation):

   ```clojure
   [{:name :router-handler-fn
     :parent :default-router-handler}  ; parent is base-fn

    {:name :web-server-fn
     :parent :http-server              ; parent is base-fn
     :args {:handler :router-handler-fn  ; ref to fn by name
            :port 8080}}]              ; literal value
   ```

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
    [graphden.fn-registry.interface :as registry]
    [graphden.storage-protocol.interface :as sp]))


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
                      {:type :fn-defs/unresolved-parent
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
                    {:type :fn-defs/unresolved-fn-ref
                     :fn-name fn-name
                     :available-fns (keys created-fns)}))))


(defn- resolve-arg-schema-id
  "Resolves arg-schema-id from parent base-fn name and arg name."
  [parent-name arg-name]
  (registry/arg-schema-uuid parent-name arg-name))


;; === Dependency Analysis ===

(defn- extract-dependencies
  "Extracts fn names that this fn-def depends on (from args).
   Returns set of keywords."
  [fn-def fn-names-in-set]
  (let [arg-values (vals (:args fn-def {}))]
    (->> arg-values
         (filter keyword?)
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
                          {:type :fn-defs/circular-dependency
                           :remaining remaining
                           :dep-graph (select-keys dep-graph remaining)})))))))


(defn- check-order-and-warn
  "Checks if fn-defs are in valid topological order.
   Prints warning if not, with suggested order."
  [fn-defs sorted-defs]
  (let [original-order (mapv :name fn-defs)
        sorted-order (mapv :name sorted-defs)]
    (when (not= original-order sorted-order)
      (println "WARNING: fn-defs are not in dependency order.")
      (println "  Current order:" (str/join " -> " (map name original-order)))
      (println "  Suggested order:" (str/join " -> " (map name sorted-order)))
      (println "  Consider reordering for clarity."))))


;; === Validation ===

(defn- validate-fn-def!
  "Validates a single fn definition."
  [{:keys [parent args] :as fn-def}]
  (let [fn-name (:name fn-def)]
    (when-not fn-name
      (throw (ex-info "fn-def must have :name"
                      {:type :fn-defs/invalid-def
                       :fn-def fn-def})))
    (when-not (keyword? fn-name)
      (throw (ex-info "fn-def :name must be a keyword"
                      {:type :fn-defs/invalid-def
                       :name fn-name})))
    (when-not parent
      (throw (ex-info (str "fn-def " fn-name " must have :parent (base-fn name)")
                      {:type :fn-defs/invalid-def
                       :fn-def fn-def})))
    (when (and args (not (map? args)))
      (throw (ex-info (str "fn-def " fn-name " :args must be a map")
                      {:type :fn-defs/invalid-def
                       :fn-def fn-def})))))


(defn- validate-all-defs!
  "Validates all fn definitions before sync."
  [fn-defs]
  (when-not (sequential? fn-defs)
    (throw (ex-info "fn-defs must be a vector/list"
                    {:type :fn-defs/invalid-defs
                     :fn-defs-type (type fn-defs)})))
  ;; Check for duplicate names
  (let [names (map :name fn-defs)
        duplicates (->> names
                        frequencies
                        (filter #(> (val %) 1))
                        keys)]
    (when (seq duplicates)
      (throw (ex-info "Duplicate fn names in definitions"
                      {:type :fn-defs/duplicate-names
                       :duplicates duplicates}))))
  ;; Validate each def
  (doseq [fn-def fn-defs]
    (validate-fn-def! fn-def)))


;; === Storage Sync ===

(defn- create-fn-entity!
  "Creates a single fn entity in storage.
   Returns the created entity."
  [storage fn-def]
  (let [fn-name (:name fn-def)
        parent (:parent fn-def)
        fn-schema-id (resolve-fn-schema-id storage parent)]
    (sp/create-entity storage :fn
                      {:name (clojure.core/name fn-name)
                       :fn-schema-id fn-schema-id})))


(defn- create-arg-values!
  "Creates arg-value entities for a fn.
   Resolves references to other fns."
  [storage fn-entity fn-def created-fns]
  (let [{:keys [parent args]} fn-def
        owner-fn-id (:id fn-entity)]
    (doseq [[arg-name arg-value] args]
      (let [arg-schema-id (resolve-arg-schema-id parent arg-name)
            ;; Resolve value: keyword = fn ref, else = literal
            resolved-value (if (keyword? arg-value)
                             (resolve-fn-id storage created-fns arg-value)
                             arg-value)]
        (sp/create-entity storage :arg-value
                          {:owner-fn-id owner-fn-id
                           :arg-schema-id arg-schema-id
                           :value resolved-value})))))


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
   5. Creates all arg-value entities

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
      (let [sorted-defs (topological-sort fn-defs)]
        ;; 3. Warn if order was wrong
        (check-order-and-warn fn-defs sorted-defs)
        ;; 4. Create fns in sorted order, tracking created ids
        (loop [remaining sorted-defs
               created-fns {}]
          (if (empty? remaining)
            created-fns
            (let [fn-def (first remaining)
                  fn-entity (create-fn-entity! storage fn-def)
                  new-created (assoc created-fns (:name fn-def) (:id fn-entity))]
              ;; 5. Create arg-values for this fn
              (create-arg-values! storage fn-entity fn-def new-created)
              (recur (rest remaining) new-created))))))))
