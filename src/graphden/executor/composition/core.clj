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
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.records :as records]
    [graphden.executor.composition.source-chain :as sc]
    [graphden.executor.composition.validation :as validation]
    [graphden.storage.protocol.core :as sp]))


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
       (validation/validate-all-defs! fn-defs)
       (let [sorted-defs (deps/topological-sort fn-defs)
             _ (deps/check-order-and-warn fn-defs sorted-defs)

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
                       result (records/prepare-fn-record fn-name-cache' fn-id-cache created fn-def ns-id-map)
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
             {:keys [all-new-args all-update-args all-delete-items]}
             (loop [remaining sorted-defs
                    args-data args-cache  ; mutable view of args, updated after each fn-def
                    new-args []
                    update-args []
                    delete-items []]
               (if (empty? remaining)
                 {:all-new-args new-args
                  :all-update-args update-args
                  :all-delete-items delete-items}
                 (let [fn-def (first remaining)
                       fn-name (:name fn-def)
                       fn-id (get created-fns fn-name)
                       parent-names (records/fn-def-parent-names fn-def)
                       parent-fn-ids (mapv #(records/resolve-parent-fn-id-cached
                                              fn-name-cache-final fn-id-cache-final created-fns %)
                                           parent-names)

                       ;; 3a: Explicit args from fn-def :args
                       ;; Single pass: collect source-id CHAINS, new args, update args,
                       ;; and sequence-chain deletions (items orphaned by re-sync).
                       {:keys [explicit-source-ids explicit-new-args explicit-update-args
                               explicit-arg-names explicit-delete-items]}
                       (reduce (fn [acc [arg-name arg-value]]
                                 (if-let [record (records/prepare-arg-record
                                                   fn-id-cache-final args-data fn-name-cache-final
                                                   created-fns fn-id parent-fn-ids arg-name arg-value)]
                                   (let [source-id (or (:source-id (:new record))
                                                       (:source-id (:update record))
                                                       (:source-id record))
                                         ;; Collect FULL source-id chain to shadow transitive args
                                         source-chain (when source-id
                                                        (sc/collect-source-id-chain (:by-id args-data) source-id))]
                                     (cond-> acc
                                       ;; Add explicit arg name (as string) to filter free args
                                       true
                                       (update :explicit-arg-names conj (name arg-name))
                                       source-chain
                                       (update :explicit-source-ids into source-chain)
                                       (:new record)
                                       (update :explicit-new-args conj (:new record))
                                       (:update record)
                                       (update :explicit-update-args conj (:update record))
                                       (:new-chain record)
                                       (update :explicit-new-args into (:new-chain record))
                                       (seq (:delete-items record))
                                       (update :explicit-delete-items into (:delete-items record))))
                                   acc))
                               {:explicit-source-ids #{}
                                :explicit-new-args []
                                :explicit-update-args []
                                :explicit-arg-names #{}
                                :explicit-delete-items []}
                               (:args fn-def {}))

                       ;; 3b: Propagated free args from parent fns
                       ;; NOTE: We don't recursively collect from refs in explicit args (removed step 3c).
                       ;; The executor handles transitive arg propagation at runtime via trace-source-to-fn.
                       ;; This prevents internal free args (like html-response.status) from leaking
                       ;; through bound refs (like editor-route.handler).
                       parent-free-args (sc/collect-parent-free-args
                                          fn-id-cache-final args-data parent-fn-ids 0)

                       ;; Use parent-free-args directly (no combination with explicit-ref-free-args)
                       all-free-args parent-free-args

                       ;; Helper to get root name of a free arg
                       args-by-id (:by-id args-data)
                       get-root-name (fn [arg] (sc/resolve-arg-name-cached args-by-id arg 0))

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
                               (keep #(when-let [rec (records/prepare-propagated-arg-record args-data fn-id %)]
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
                          (into update-args fn-update-args)
                          (into delete-items explicit-delete-items)))))]

         ;; Reap orphaned sequence items from prior syncs before writing new chain.
         (when (seq all-delete-items)
           (sp/delete-entities storage :arg all-delete-items))

         ;; Batch upsert new args
         (when (seq all-new-args)
           (sp/upsert-entities storage :arg all-new-args))

         ;; Batch update existing args (if any changed)
         (when (seq all-update-args)
           (sp/upsert-entities storage :arg all-update-args))

         created-fns)))))
