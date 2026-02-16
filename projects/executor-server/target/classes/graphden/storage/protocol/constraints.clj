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
   - get-fn-dependencies-fn: function (fn [helpers fn-id] -> #{dep-fn-ids})
     Returns immediate fn dependencies for a given fn-id.
   - helpers: ConstraintHelpers implementation
   - fn-id: starting fn UUID

   Throws if total visited nodes exceed default-max-dependency-chain-depth."
  [get-fn-dependencies-fn helpers fn-id]
  (loop [to-visit (get-fn-dependencies-fn helpers fn-id)
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
          (let [new-deps (get-fn-dependencies-fn helpers current-id)
                unvisited-deps (remove visited new-deps)]
            (recur (into rest-queue unvisited-deps)
                   (conj visited current-id)
                   (inc iter-count))))))))


;; === Shared constraint validation functions ===

(defn validate-arg-schema-belongs-to-fn-impl
  "Shared implementation of arg-schema-belongs-to-fn validation.

   Arguments:
   - get-fn-schema-id-for-fn-fn: function (fn [helpers fn-id] -> fn-schema-id)
   - get-fn-schema-id-for-arg-schema-fn: function (fn [helpers arg-schema-id] -> fn-schema-id)
   - helpers: ConstraintHelpers implementation
   - fn-id: UUID of the fn that owns this arg-value
   - arg-schema-id: UUID of the arg-schema"
  [get-fn-schema-id-for-fn-fn get-fn-schema-id-for-arg-schema-fn helpers fn-id arg-schema-id]
  (let [fn-schema-id (get-fn-schema-id-for-fn-fn helpers fn-id)
        arg-fn-schema-id (get-fn-schema-id-for-arg-schema-fn helpers arg-schema-id)]
    (when (and fn-schema-id arg-fn-schema-id
               (not= fn-schema-id arg-fn-schema-id))
      (throw (ex-info "Arg-schema does not belong to fn's schema"
                      {:type :constraint-violation/arg-schema-mismatch
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :fn-schema-id fn-schema-id
                       :arg-fn-schema-id arg-fn-schema-id})))))


(defn validate-no-dependency-cycle-impl
  "Shared implementation of no-dependency-cycle validation.

   Arguments:
   - collect-dependency-chain-fn: function (fn [helpers fn-id] -> #{dep-fn-ids})
   - helpers: ConstraintHelpers implementation
   - owner-fn-id: UUID of the fn that owns this arg-value
   - value-fn-id: UUID of the fn being referenced as value"
  [collect-dependency-chain-fn helpers owner-fn-id value-fn-id]
  (when value-fn-id
    ;; Early check for self-reference (avoids expensive dependency chain query)
    (when (= owner-fn-id value-fn-id)
      (throw (ex-info "Reference would create dependency cycle"
                      {:type :constraint-violation/dependency-cycle
                       :owner-fn-id owner-fn-id
                       :value-fn-id value-fn-id})))
    ;; Check if owner-fn-id is in the dependency chain of value-fn-id
    (let [value-deps (collect-dependency-chain-fn helpers value-fn-id)]
      (when (contains? value-deps owner-fn-id)
        (throw (ex-info "Reference would create dependency cycle"
                        {:type :constraint-violation/dependency-cycle
                         :owner-fn-id owner-fn-id
                         :value-fn-id value-fn-id}))))))
