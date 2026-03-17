(ns graphden.storage.protocol.constraints
  "Shared constraint helper implementations.

   These functions use the ConstraintHelpers protocol and can be called
   from any storage implementation. Storage backends should:
   1. Implement ConstraintHelpers with storage-specific data fetching
   2. Call these shared -impl functions for constraint validation logic

   This separation allows consistent validation across all backends while
   each backend handles data access differently.

   Note: This namespace does NOT require interface.clj to avoid circular deps.
   Protocol methods are passed as arguments or called via protocol dispatch.")


;; === Chain depth limits ===
;;
;; These limits prevent DoS attacks via extremely deep graphs.

(def default-max-dependency-chain-depth
  "Maximum depth for dependency chains (fn references).
   Prevents DoS via deeply nested function dependencies.
   Value: 1000 - matches default-max-depth for execution."
  1000)


;; === Shared constraint helper implementations ===
;;
;; Default implementations for ConstraintHelpers methods.
;; Storage implementations can use these or provide their own optimized versions.
;;
;; These functions receive a helpers object that implements ConstraintHelpers.
;; Protocol methods are resolved via Clojure's protocol dispatch mechanism.

(defn collect-dependency-chain-impl
  "Default implementation of collect-dependency-chain.
   Uses BFS to collect all fn-ids that a function depends on.
   Returns a set of all dependent fn-ids (not including fn-id itself).

   Arguments:
   - get-fn-dependencies-fn: function (fn [fn-id] -> #{dep-fn-ids})
     Returns immediate fn dependencies for a given fn-id (via ref-id and parent-id).
   - fn-id: starting fn UUID

   Throws if total visited nodes exceed default-max-dependency-chain-depth."
  [get-fn-dependencies-fn fn-id]
  (loop [to-visit (get-fn-dependencies-fn fn-id)
         visited #{}
         iter-count 0]
    (when (> iter-count default-max-dependency-chain-depth)
      (throw (ex-info "Dependency chain exceeds maximum allowed depth"
                      {:type :constraint-violation/chain-too-deep
                       :fn-id fn-id
                       :max-depth default-max-dependency-chain-depth
                       :chain-type :dependency})))
    (if (empty? to-visit)
      visited
      (let [current-id (first to-visit)
            rest-queue (disj to-visit current-id)]
        (if (contains? visited current-id)
          (recur rest-queue visited (inc iter-count))
          (let [new-deps (get-fn-dependencies-fn current-id)
                unvisited-deps (remove visited new-deps)]
            (recur (into rest-queue unvisited-deps)
                   (conj visited current-id)
                   (inc iter-count))))))))


;; === Shared constraint validation functions ===

(defn validate-no-dependency-cycle-impl
  "Shared implementation of no-dependency-cycle validation.

   Arguments:
   - collect-dependency-chain-fn: function (fn [helpers fn-id] -> #{dep-fn-ids})
   - helpers: ConstraintHelpers implementation
   - owner-fn-id: UUID of the fn that owns this arg
   - ref-fn-id: UUID of the fn being referenced via ref-id"
  [collect-dependency-chain-fn helpers owner-fn-id ref-fn-id]
  (when ref-fn-id
    ;; Early check for self-reference (avoids expensive dependency chain query)
    (when (= owner-fn-id ref-fn-id)
      (throw (ex-info "Reference would create dependency cycle"
                      {:type :constraint-violation/dependency-cycle
                       :owner-fn-id owner-fn-id
                       :ref-fn-id ref-fn-id})))
    ;; Check if owner-fn-id is in the dependency chain of ref-fn-id
    (let [ref-deps (collect-dependency-chain-fn helpers ref-fn-id)]
      (when (contains? ref-deps owner-fn-id)
        (throw (ex-info "Reference would create dependency cycle"
                        {:type :constraint-violation/dependency-cycle
                         :owner-fn-id owner-fn-id
                         :ref-fn-id ref-fn-id}))))))


;; === Arg Descendant Constraints ===

(defn validate-no-arg-descendants-impl
  "Validates that an arg has no descendants before update/delete.
   Descendants are args that have source-id pointing to this arg's id.

   Arguments:
   - query-descendants-fn: function (fn [arg-id] -> seq of descendant args)
     Should query for args where source-id = arg-id
   - arg-id: UUID of the arg being modified/deleted
   - operation: :update or :delete (for error message)

   Throws if arg has descendants."
  [query-descendants-fn arg-id operation]
  (let [desc-args (query-descendants-fn arg-id)]
    (when (seq desc-args)
      (throw (ex-info (str "Cannot " (name operation) " arg: it has "
                           (count desc-args) " descendant(s). "
                           "Delete descendants first.")
                      {:type :constraint-violation/has-descendants
                       :arg-id arg-id
                       :operation operation
                       :descendant-count (count desc-args)
                       :descendant-ids (mapv :id desc-args)})))))
