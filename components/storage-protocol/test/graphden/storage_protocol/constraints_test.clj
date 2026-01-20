(ns graphden.storage-protocol.constraints-test
  "Tests for storage-protocol constraint helpers and implementations."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.constraints :as constraints]
    [graphden.storage-protocol.interface :as storage]))


;; === Mock ConstraintHelpers for testing shared implementations ===

(defrecord MockConstraintHelpers
  [fn-schema-map arg-schema-fn-schema-map parent-map arg-schema-ids-in-chain-map dependency-chain-map]

  storage/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (get fn-schema-map fn-id))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (get arg-schema-fn-schema-map arg-schema-id))


  (get-parent-fn-id
    [_this fn-id]
    (get parent-map fn-id))


  (collect-parent-chain
    [this fn-id]
    (storage/collect-parent-chain-impl this fn-id))


  (collect-arg-schema-ids-in-chain
    [_this fn-id]
    (get arg-schema-ids-in-chain-map fn-id #{}))


  (collect-dependency-chain
    [_this fn-id]
    (get dependency-chain-map fn-id #{})))


;; === collect-parent-chain-impl tests ===

(deftest collect-parent-chain-impl-test
  (testing "returns empty set for fn with no parent"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (= #{} (storage/collect-parent-chain-impl helpers (random-uuid))))))

  (testing "returns single ancestor for fn with one parent"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {} {} {fn-b fn-a} {} {})]
      (is (= #{fn-a} (storage/collect-parent-chain-impl helpers fn-b)))))

  (testing "returns all ancestors for deep chain"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          fn-d (random-uuid)
          helpers (->MockConstraintHelpers {} {} {fn-b fn-a, fn-c fn-b, fn-d fn-c} {} {})]
      (is (= #{fn-a fn-b fn-c} (storage/collect-parent-chain-impl helpers fn-d)))))

  (testing "handles cycle in parent chain (stops when revisiting)"
    ;; This shouldn't happen in valid data, but the impl should handle it gracefully
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {} {} {fn-a fn-b, fn-b fn-a} {} {})]
      ;; Should return both without infinite loop
      (is (= #{fn-a fn-b} (storage/collect-parent-chain-impl helpers fn-a))))))


;; === validate-parent-same-schema-impl tests ===

(deftest validate-parent-same-schema-impl-test
  (testing "nil parent-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers (random-uuid) nil)))))

  (testing "same schema doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-id (random-uuid)
          helpers (->MockConstraintHelpers {fn-a schema-id, fn-b schema-id} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers fn-a fn-b)))))

  (testing "different schema throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-a schema-a, fn-b schema-b} {} {} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Parent fn has different fn-schema-id"
            (storage/validate-parent-same-schema-impl helpers fn-a fn-b)))))

  (testing "exception contains correct data"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-a schema-a, fn-b schema-b} {} {} {} {})]
      (try
        (storage/validate-parent-same-schema-impl helpers fn-a fn-b)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/parent-schema-mismatch (:type (ex-data e))))
          (is (= fn-a (:fn-id (ex-data e))))
          (is (= fn-b (:parent-fn-id (ex-data e))))))))

  (testing "missing fn returns nil (fn not found)"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers (random-uuid) (random-uuid))))))

  (testing "fn-schema-id nil but parent-schema-id present returns nil"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-b (random-uuid)
          ;; fn-a has no schema, fn-b has schema
          helpers (->MockConstraintHelpers {fn-b schema-b} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers fn-a fn-b)))))

  (testing "fn-schema-id present but parent-schema-id nil returns nil"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-a (random-uuid)
          ;; fn-a has schema, fn-b has no schema
          helpers (->MockConstraintHelpers {fn-a schema-a} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers fn-a fn-b))))))


;; === validate-no-arg-override-impl tests ===

(deftest validate-no-arg-override-impl-test
  (testing "arg not in parent chain doesn't throw"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {fn-id #{}} {})]
      (is (nil? (storage/validate-no-arg-override-impl helpers fn-id arg-schema-id)))))

  (testing "arg in parent chain throws"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {fn-id #{arg-schema-id}} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Argument already defined in parent chain"
            (storage/validate-no-arg-override-impl helpers fn-id arg-schema-id)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {fn-id #{arg-schema-id}} {})]
      (try
        (storage/validate-no-arg-override-impl helpers fn-id arg-schema-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/arg-already-defined (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= arg-schema-id (:arg-schema-id (ex-data e)))))))))


;; === validate-arg-schema-belongs-to-fn-impl tests ===

(deftest validate-arg-schema-belongs-to-fn-impl-test
  (testing "arg-schema belongs to fn-schema doesn't throw"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-id (random-uuid)
          helpers (->MockConstraintHelpers {fn-id schema-id} {arg-schema-id schema-id} {} {} {})]
      (is (nil? (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id)))))

  (testing "arg-schema from different fn-schema throws"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-id schema-a} {arg-schema-id schema-b} {} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Arg-schema does not belong to fn's schema"
            (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-id schema-a} {arg-schema-id schema-b} {} {} {})]
      (try
        (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/arg-schema-mismatch (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= arg-schema-id (:arg-schema-id (ex-data e))))
          (is (= schema-a (:fn-schema-id (ex-data e))))
          (is (= schema-b (:arg-fn-schema-id (ex-data e))))))))

  (testing "missing fn-schema returns nil"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-arg-schema-belongs-to-fn-impl helpers (random-uuid) (random-uuid))))))

  (testing "fn-schema-id present but arg-fn-schema-id nil returns nil"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-id (random-uuid)
          ;; fn has schema-id, but arg-schema has no fn-schema-id
          helpers (->MockConstraintHelpers {fn-id schema-id} {} {} {} {})]
      (is (nil? (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id))))))


;; === validate-no-inheritance-cycle-impl tests ===

(deftest validate-no-inheritance-cycle-impl-test
  (testing "nil parent-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-no-inheritance-cycle-impl helpers (random-uuid) nil)))))

  (testing "self-reference throws"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Cannot set self as parent"
            (storage/validate-no-inheritance-cycle-impl helpers fn-id fn-id)))))

  (testing "non-cyclic parent chain doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          ;; Chain: fn-c -> fn-b -> fn-a (no cycle)
          helpers (->MockConstraintHelpers {} {} {fn-b fn-a} {} {})]
      (is (nil? (storage/validate-no-inheritance-cycle-impl helpers fn-c fn-b)))))

  (testing "cycle through parent chain throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          ;; Current chain: fn-c -> fn-b -> fn-a
          ;; Trying to set fn-a -> fn-c (would create cycle)
          helpers (->MockConstraintHelpers {} {} {fn-c fn-b, fn-b fn-a} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Setting parent would create inheritance cycle"
            (storage/validate-no-inheritance-cycle-impl helpers fn-a fn-c)))))

  (testing "exception contains correct data for self-reference"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {})]
      (try
        (storage/validate-no-inheritance-cycle-impl helpers fn-id fn-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/inheritance-cycle (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= fn-id (:parent-fn-id (ex-data e)))))))))


;; === validate-no-dependency-cycle-impl tests ===

(deftest validate-no-dependency-cycle-impl-test
  (testing "nil value-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers (random-uuid) nil)))))

  (testing "self-reference throws"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Reference would create dependency cycle"
            (storage/validate-no-dependency-cycle-impl helpers fn-id fn-id)))))

  (testing "exception contains correct data for self-reference"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {})]
      (try
        (storage/validate-no-dependency-cycle-impl helpers fn-id fn-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
          (is (= fn-id (:owner-fn-id (ex-data e))))
          (is (= fn-id (:value-fn-id (ex-data e))))))))

  (testing "non-cyclic dependency doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-a depends on fn-b (not a cycle)
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{}})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)))))

  (testing "cycle through dependency chain throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          ;; Dependency chain: fn-c -> fn-b -> fn-a
          ;; Trying to add fn-a -> fn-c (would create cycle)
          helpers (->MockConstraintHelpers {} {} {} {} {fn-c #{fn-a fn-b}})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Reference would create dependency cycle"
            (storage/validate-no-dependency-cycle-impl helpers fn-a fn-c)))))

  (testing "exception contains data for chain cycle"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{fn-a}})]
      (try
        (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
          (is (= fn-a (:owner-fn-id (ex-data e))))
          (is (= fn-b (:value-fn-id (ex-data e)))))))))


;; === collect-dependency-chain-impl tests ===

(defrecord MockDependencyHelpers
  [dependencies-map]

  storage/ConstraintHelpers

  (get-fn-schema-id-for-fn [_this _fn-id] nil)


  (get-fn-schema-id-for-arg-schema [_this _arg-schema-id] nil)


  (get-parent-fn-id [_this _fn-id] nil)


  (collect-parent-chain [_this _fn-id] #{})


  (collect-arg-schema-ids-in-chain [_this _fn-id] #{})


  (collect-dependency-chain
    [this fn-id]
    (constraints/collect-dependency-chain-impl
      (fn [_helpers fid] (get dependencies-map fid #{}))
      this
      fn-id)))


(deftest collect-dependency-chain-impl-test
  (testing "returns empty set for fn with no dependencies"
    (let [fn-a (random-uuid)
          helpers (->MockDependencyHelpers {fn-a #{}})]
      (is (= #{} (storage/collect-dependency-chain helpers fn-a)))))

  (testing "returns single dependency"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockDependencyHelpers {fn-a #{fn-b} fn-b #{}})]
      (is (= #{fn-b} (storage/collect-dependency-chain helpers fn-a)))))

  (testing "returns all transitive dependencies (linear chain)"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          fn-d (random-uuid)
          ;; fn-a -> fn-b -> fn-c -> fn-d
          helpers (->MockDependencyHelpers {fn-a #{fn-b}
                                            fn-b #{fn-c}
                                            fn-c #{fn-d}
                                            fn-d #{}})]
      (is (= #{fn-b fn-c fn-d} (storage/collect-dependency-chain helpers fn-a)))))

  (testing "returns all dependencies for diamond graph"
    ;; Diamond: A -> B, A -> C, B -> D, C -> D
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          fn-d (random-uuid)
          helpers (->MockDependencyHelpers {fn-a #{fn-b fn-c}
                                            fn-b #{fn-d}
                                            fn-c #{fn-d}
                                            fn-d #{}})]
      (is (= #{fn-b fn-c fn-d} (storage/collect-dependency-chain helpers fn-a)))))

  (testing "handles already visited nodes (no duplicates)"
    ;; Dense graph with shared dependencies
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          fn-shared (random-uuid)
          helpers (->MockDependencyHelpers {fn-a #{fn-b fn-c}
                                            fn-b #{fn-shared}
                                            fn-c #{fn-shared}
                                            fn-shared #{}})]
      (is (= #{fn-b fn-c fn-shared} (storage/collect-dependency-chain helpers fn-a)))))

  (testing "handles wide graph (many direct dependencies)"
    (let [root (random-uuid)
          deps (repeatedly 50 random-uuid)
          deps-set (set deps)
          deps-map (into {root deps-set}
                         (map (fn [d] [d #{}]) deps))
          helpers (->MockDependencyHelpers deps-map)]
      (is (= deps-set (storage/collect-dependency-chain helpers root)))))

  (testing "handles deep chain at boundary (999 nodes - under limit)"
    ;; Create chain of 999 nodes (just under default limit of 1000)
    (let [nodes (vec (repeatedly 999 random-uuid))
          root (random-uuid)
          ;; root -> node0 -> node1 -> ... -> node998
          deps-map (reduce (fn [m i]
                             (assoc m (nth nodes i)
                                    (if (< i 998)
                                      #{(nth nodes (inc i))}
                                      #{})))
                           {root #{(first nodes)}}
                           (range 999))
          helpers (->MockDependencyHelpers deps-map)]
      (is (= (set nodes) (storage/collect-dependency-chain helpers root))))))


(deftest collect-dependency-chain-depth-limit-test
  (testing "throws when dependency chain exceeds max depth"
    ;; Create chain longer than default-max-dependency-chain-depth (1000)
    (let [nodes (vec (repeatedly 1002 random-uuid))
          root (random-uuid)
          deps-map (reduce (fn [m i]
                             (assoc m (nth nodes i)
                                    (if (< i 1001)
                                      #{(nth nodes (inc i))}
                                      #{})))
                           {root #{(first nodes)}}
                           (range 1002))
          helpers (->MockDependencyHelpers deps-map)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Dependency chain exceeds maximum allowed depth"
            (storage/collect-dependency-chain helpers root)))))

  (testing "depth limit exception contains correct data"
    (let [nodes (vec (repeatedly 1002 random-uuid))
          root (random-uuid)
          deps-map (reduce (fn [m i]
                             (assoc m (nth nodes i)
                                    (if (< i 1001)
                                      #{(nth nodes (inc i))}
                                      #{})))
                           {root #{(first nodes)}}
                           (range 1002))
          helpers (->MockDependencyHelpers deps-map)]
      (try
        (storage/collect-dependency-chain helpers root)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/chain-too-deep (:type (ex-data e))))
          (is (= :dependency (:chain-type (ex-data e))))
          (is (= 1000 (:max-depth (ex-data e)))))))))


(deftest collect-parent-chain-depth-limit-test
  (testing "throws when parent chain exceeds max depth"
    ;; Create chain longer than default-max-parent-chain-depth (100)
    (let [nodes (vec (repeatedly 102 random-uuid))
          ;; node0 -> node1 -> ... -> node101
          parent-map (reduce (fn [m i]
                               (assoc m (nth nodes i)
                                      (when (< i 101)
                                        (nth nodes (inc i)))))
                             {}
                             (range 102))
          helpers (->MockConstraintHelpers {} {} parent-map {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Parent chain exceeds maximum allowed depth"
            (storage/collect-parent-chain-impl helpers (first nodes))))))

  (testing "depth limit exception contains correct data"
    (let [nodes (vec (repeatedly 102 random-uuid))
          parent-map (reduce (fn [m i]
                               (assoc m (nth nodes i)
                                      (when (< i 101)
                                        (nth nodes (inc i)))))
                             {}
                             (range 102))
          helpers (->MockConstraintHelpers {} {} parent-map {} {})]
      (try
        (storage/collect-parent-chain-impl helpers (first nodes))
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/chain-too-deep (:type (ex-data e))))
          (is (= :parent (:chain-type (ex-data e))))
          (is (= 100 (:max-depth (ex-data e))))))))

  (testing "parent chain at boundary (99 nodes) succeeds"
    (let [nodes (vec (repeatedly 99 random-uuid))
          parent-map (reduce (fn [m i]
                               (assoc m (nth nodes i)
                                      (when (< i 98)
                                        (nth nodes (inc i)))))
                             {}
                             (range 99))
          helpers (->MockConstraintHelpers {} {} parent-map {} {})]
      (is (= (set (rest nodes))
             (storage/collect-parent-chain-impl helpers (first nodes)))))))


;; === Chain depth limit constants tests ===

(deftest chain-depth-limit-test
  (testing "default-max-parent-chain-depth is defined"
    (is (= 100 storage/default-max-parent-chain-depth)))

  (testing "default-max-dependency-chain-depth is defined"
    (is (= 1000 storage/default-max-dependency-chain-depth))))


;; === StorageBatchCRUD protocol tests ===

(deftest storage-batch-crud-protocol-test
  (testing "StorageBatchCRUD protocol is defined"
    (is (some? storage/StorageBatchCRUD))
    (is (contains? (:sigs storage/StorageBatchCRUD) :create-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :read-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :delete-entities))))
