(ns graphden.storage-protocol.constraints
  "Shared constraint helper implementations.

   These functions use the ConstraintHelpers protocol and can be called
   from any storage implementation. Storage backends should:
   1. Implement ConstraintHelpers with storage-specific data fetching
   2. Call these shared -impl functions for constraint validation logic

   This separation allows consistent validation across all backends while
   each backend handles data access differently.

   Note: This namespace does NOT require interface.clj to avoid circular deps.
   Protocol methods are passed as arguments or called via protocol dispatch.")


;; === Shared constraint helper implementations ===
;;
;; Default implementations for ConstraintHelpers methods.
;; Storage implementations can use these or provide their own optimized versions.
;;
;; These functions receive a helpers object that implements ConstraintHelpers.
;; Protocol methods are resolved via Clojure's protocol dispatch mechanism.

(defn collect-parent-chain-impl
  "Default implementation of collect-parent-chain.
   Uses get-parent-fn-id to traverse the chain.
   Returns a set of all ancestor fn-ids (not including fn-id itself).

   Performance: O(N) queries where N is the chain depth.
   For deep hierarchies, consider implementing a custom version using
   recursive CTEs or bulk fetching.

   Arguments:
   - get-parent-fn-id-fn: function (fn [helpers fn-id] -> parent-fn-id)
   - helpers: ConstraintHelpers implementation
   - fn-id: starting fn UUID"
  [get-parent-fn-id-fn helpers fn-id]
  (loop [current-id (get-parent-fn-id-fn helpers fn-id)
         ancestor-ids #{}]
    (if (or (nil? current-id) (contains? ancestor-ids current-id))
      ancestor-ids
      (recur (get-parent-fn-id-fn helpers current-id)
             (conj ancestor-ids current-id)))))


;; === Shared constraint validation functions ===
;; These take protocol method functions as explicit arguments to avoid circular deps.

(defn validate-parent-same-schema-impl
  "Shared implementation of parent-same-schema validation.

   Arguments:
   - get-fn-schema-id-for-fn-fn: function (fn [helpers fn-id] -> fn-schema-id)
   - helpers: ConstraintHelpers implementation
   - fn-id: UUID of the fn being created/updated
   - parent-fn-id: UUID of the proposed parent fn"
  [get-fn-schema-id-for-fn-fn helpers fn-id parent-fn-id]
  (when parent-fn-id
    (let [fn-schema-id (get-fn-schema-id-for-fn-fn helpers fn-id)
          parent-schema-id (get-fn-schema-id-for-fn-fn helpers parent-fn-id)]
      (when (and fn-schema-id parent-schema-id
                 (not= fn-schema-id parent-schema-id))
        (throw (ex-info "Parent fn has different fn-schema-id"
                        {:type :constraint-violation/parent-schema-mismatch
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :fn-schema-id fn-schema-id
                         :parent-schema-id parent-schema-id}))))))


(defn validate-no-arg-override-impl
  "Shared implementation of no-arg-override validation.

   Arguments:
   - collect-arg-schema-ids-in-chain-fn: function (fn [helpers fn-id] -> #{arg-schema-ids})
   - helpers: ConstraintHelpers implementation
   - fn-id: UUID of the fn that owns this arg-value
   - arg-schema-id: UUID of the arg-schema being set"
  [collect-arg-schema-ids-in-chain-fn helpers fn-id arg-schema-id]
  (let [parent-arg-schema-ids (collect-arg-schema-ids-in-chain-fn helpers fn-id)]
    (when (contains? parent-arg-schema-ids arg-schema-id)
      (throw (ex-info "Argument already defined in parent chain"
                      {:type :constraint-violation/arg-already-defined
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id})))))


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


(defn validate-no-inheritance-cycle-impl
  "Shared implementation of no-inheritance-cycle validation.

   Arguments:
   - collect-parent-chain-fn: function (fn [helpers fn-id] -> #{ancestor-fn-ids})
   - helpers: ConstraintHelpers implementation
   - fn-id: UUID of the fn being created/updated
   - parent-fn-id: UUID of the proposed parent fn"
  [collect-parent-chain-fn helpers fn-id parent-fn-id]
  (when parent-fn-id
    ;; Check self-reference
    (when (= fn-id parent-fn-id)
      (throw (ex-info "Cannot set self as parent"
                      {:type :constraint-violation/inheritance-cycle
                       :fn-id fn-id
                       :parent-fn-id parent-fn-id})))
    ;; Check if fn-id appears in parent's ancestor chain
    (let [parent-ancestors (collect-parent-chain-fn helpers parent-fn-id)]
      (when (contains? parent-ancestors fn-id)
        (throw (ex-info "Setting parent would create inheritance cycle"
                        {:type :constraint-violation/inheritance-cycle
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :cycle-through (conj parent-ancestors parent-fn-id)}))))))


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
