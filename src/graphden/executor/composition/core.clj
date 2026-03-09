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
     :args {:handler :router-handler>   ; ref to fn (execute)
            :port 8080}}]               ; literal value
   ```

   ## Arg Value Syntax

   - `:fn-name` - pass fn-id directly (for HOF with is-fn=true)
   - `:fn-name>` - execute fn and use result (creates ref-id)

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
   :fn-name> means execute fn-name and use result
   :fn-name means pass fn-id directly (for HOF)
   Returns [fn-name execute?] or nil if not a fn ref."
  [value]
  (when (keyword? value)
    (let [kw-name (name value)]
      (if (str/ends-with? kw-name ">")
        (let [fn-name (subs kw-name 0 (dec (count kw-name)))]
          (when (valid-identifier? fn-name)
            [(keyword fn-name) true]))
        (when (valid-identifier? kw-name)
          [value false])))))


;; === Name Resolution ===

(defn- resolve-parent-fn-id
  "Resolves a parent name to fn-id.
   Parent must be a base-fn (fn with parent-id=nil).
   Returns UUID or throws if not found."
  [storage parent-name]
  (let [fn-id (registry/fn-uuid parent-name)]
    (when-not (sp/read-entity storage :fn fn-id)
      (throw (ex-info (str "Parent base-fn not found: " parent-name
                           ". Did you forget to add base-functions?")
                      {:type :fn-composition/unresolved-parent
                       :parent-name parent-name})))
    fn-id))


(defn- resolve-fn-id
  "Resolves a fn name to fn-id.
   Looks in:
   1. created-fns map (fns created in current batch)
   2. storage (existing fns)
   Returns UUID or throws if not found."
  [storage created-fns fn-name]
  (or
    (get created-fns fn-name)
    (let [existing (sp/query-entities storage :fn {:name (name fn-name)})]
      (when (seq existing)
        (:id (first existing))))
    (throw (ex-info (str "Referenced fn not found: " fn-name
                         ". It must be defined earlier or exist in storage.")
                    {:type :fn-composition/unresolved-fn-ref
                     :fn-name fn-name
                     :available-fns (keys created-fns)}))))


;; === Dependency Analysis ===

(defn- extract-dependencies
  "Extracts fn names that this fn-def depends on (from args).
   Returns set of keywords."
  [fn-def fn-names-in-set]
  (let [args (:args fn-def {})]
    (->> (vals args)
         (keep (fn [v]
                 (when-let [[fn-name _] (parse-fn-ref v)]
                   fn-name)))
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
            parent-fn-id (resolve-parent-fn-id storage parent)]
        (sp/create-entity storage :fn
                          {:name fn-name-str
                           :parent-id parent-fn-id})))))


(defn- get-parent-arg
  "Gets the parent's arg entity for an arg name.
   Returns the arg entity or throws if not found."
  [storage parent-fn-id arg-name]
  (let [args (sp/query-entities storage :arg {:fn-id parent-fn-id
                                               :name (name arg-name)})]
    (when (empty? args)
      (throw (ex-info (str "Argument not found in parent: " arg-name)
                      {:type :fn-composition/unresolved-arg
                       :parent-fn-id parent-fn-id
                       :arg-name arg-name})))
    (first args)))


(defn- create-arg!
  "Creates or updates an arg entity for a composed fn.
   Inherits from parent's arg via source-id."
  [storage fn-id parent-fn-id arg-name arg-value created-fns]
  (let [;; Get parent's arg to inherit from
        parent-arg (get-parent-arg storage parent-fn-id arg-name)
        source-id (:id parent-arg)
        ;; Check if arg already exists
        existing (sp/query-entities storage :arg {:fn-id fn-id
                                                   :source-id source-id})
        ;; Resolve arg value
        resolved (cond
                   (nil? arg-value)
                   {:value nil :ref-id nil}

                   (keyword? arg-value)
                   (if-let [[ref-fn-name execute?] (parse-fn-ref arg-value)]
                     (let [ref-fn-id (resolve-fn-id storage created-fns ref-fn-name)]
                       (if execute?
                         {:value nil :ref-id ref-fn-id}
                         {:value (str ref-fn-id) :ref-id nil}))
                     {:value arg-value :ref-id nil})

                   :else
                   {:value arg-value :ref-id nil})]
    (if (seq existing)
      (let [existing-arg (first existing)]
        (when (or (not= (:value existing-arg) (:value resolved))
                  (not= (:ref-id existing-arg) (:ref-id resolved)))
          (sp/update-entity storage :arg (:id existing-arg) resolved))
        existing-arg)
      ;; Copy name and type from parent arg for argument resolution
      (sp/create-entity storage :arg
                        (merge {:fn-id fn-id
                                :source-id source-id
                                :name (:name parent-arg)
                                :type (:type parent-arg)}
                               resolved)))))


(defn- create-args!
  "Creates arg entities for a composed fn."
  [storage fn-entity fn-def created-fns]
  (let [{:keys [parent args]} fn-def
        fn-id (:id fn-entity)
        parent-fn-id (resolve-parent-fn-id storage parent)]
    (doseq [[arg-name arg-value] args]
      (create-arg! storage fn-id parent-fn-id arg-name arg-value created-fns))))


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
   5. Creates all arg entities

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
      (let [sorted-defs (topological-sort fn-defs)]
        (check-order-and-warn fn-defs sorted-defs)
        (loop [remaining sorted-defs
               created-fns {}]
          (if (empty? remaining)
            created-fns
            (let [fn-def (first remaining)
                  fn-entity (get-or-create-fn-entity! storage fn-def)
                  new-created (assoc created-fns (:name fn-def) (:id fn-entity))]
              (create-args! storage fn-entity fn-def new-created)
              (recur (rest remaining) new-created))))))))
