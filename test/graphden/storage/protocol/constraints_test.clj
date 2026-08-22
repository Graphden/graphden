(ns graphden.storage.protocol.constraints-test
  "Tests for storage-protocol constraint helpers and implementations.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.constraints :as constraints]
    [graphden.storage.protocol.core :as storage]))


;; === Mock ConstraintHelpers for testing shared implementations ===

(defrecord MockConstraintHelpers
  [dependency-chain-map]

  storage/ConstraintHelpers

  (collect-dependency-chain
    [_this fn-id]
    (get dependency-chain-map fn-id #{})))


;; === validate-no-dependency-cycle-impl tests ===

(deftest validate-no-dependency-cycle-impl-test
  (testing "nil ref-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers (random-uuid) nil)))))

  (testing "self-reference is allowed (recursion is intended; depth bounded by executor)"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {})]
      ;; docs/CONSTRAINTS.md § Self-reference carves this case out so
      ;; recursive fn-defs (the only way to express recursion in the
      ;; slot/binding model) stay legal at the storage layer. The
      ;; executor's *max-depth* bounds the runtime cost.
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers fn-id fn-id)))))

  (testing "non-cyclic dependency doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-a depends on fn-b (not a cycle)
          helpers (->MockConstraintHelpers {fn-b #{}})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)))))

  (testing "cycle through dependency chain throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          ;; Dependency chain: fn-c -> fn-b -> fn-a
          ;; Trying to add fn-a -> fn-c (would create cycle)
          helpers (->MockConstraintHelpers {fn-c #{fn-a fn-b}})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Reference would create dependency cycle"
            (storage/validate-no-dependency-cycle-impl helpers fn-a fn-c)))))

  (testing "exception contains data for chain cycle"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-b #{fn-a}})]
      (try
        (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)
        (is false "expected storage/validate-no-dependency-cycle-impl to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
          (is (= fn-a (:owner-fn-id (ex-data e))))
          (is (= fn-b (:ref-fn-id (ex-data e)))))))))


;; === collect-dependency-chain-impl tests ===

(defrecord MockDependencyHelpers
  [dependencies-map]

  storage/ConstraintHelpers

  (collect-dependency-chain
    [_this fn-id]
    (constraints/collect-dependency-chain-impl
      (fn [fid] (get dependencies-map fid #{}))
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
        (is false "expected storage/collect-dependency-chain to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/chain-too-deep (:type (ex-data e))))
          (is (= :dependency (:chain-type (ex-data e))))
          (is (= 1000 (:max-depth (ex-data e)))))))))


;; === Chain depth limit constants tests ===

(deftest chain-depth-limit-test
  (testing "default-max-dependency-chain-depth is defined"
    (is (= 1000 storage/default-max-dependency-chain-depth))))


;; === StorageBatchCRUD protocol tests ===

(deftest storage-batch-crud-protocol-test
  (testing "StorageBatchCRUD protocol is defined"
    (is (some? storage/StorageBatchCRUD))
    (is (contains? (:sigs storage/StorageBatchCRUD) :create-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :read-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :delete-entities))))
