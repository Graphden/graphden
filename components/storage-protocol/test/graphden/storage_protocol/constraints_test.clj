(ns graphden.storage-protocol.constraints-test
  "Tests for storage-protocol.constraints - shared constraint implementations and macro."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.constraints :as c]))


;; === Default depth limits tests ===

(deftest default-limits-test
  (testing "default-max-parent-chain-depth is reasonable"
    (is (pos-int? c/default-max-parent-chain-depth))
    (is (= 100 c/default-max-parent-chain-depth)))

  (testing "default-max-dependency-chain-depth is reasonable"
    (is (pos-int? c/default-max-dependency-chain-depth))
    (is (= 1000 c/default-max-dependency-chain-depth))))


;; === collect-parent-chain-impl tests ===

(deftest collect-parent-chain-impl-test
  (let [;; Mock parent chain: a -> b -> c -> nil
        parent-map {:a :b, :b :c, :c nil}
        get-parent-fn (fn [_helpers fn-id] (get parent-map fn-id))]

    (testing "returns empty set for fn with no parent"
      (is (= #{} (c/collect-parent-chain-impl get-parent-fn {} :c))))

    (testing "returns all ancestors"
      (is (= #{:b :c} (c/collect-parent-chain-impl get-parent-fn {} :a)))
      (is (= #{:c} (c/collect-parent-chain-impl get-parent-fn {} :b))))

    (testing "handles circular reference gracefully"
      (let [circular-map {:a :b, :b :a}
            get-circular-parent (fn [_helpers fn-id] (get circular-map fn-id))]
        ;; Should stop when cycle detected, not throw
        (is (= #{:b :a} (c/collect-parent-chain-impl get-circular-parent {} :a))))))

  (testing "throws when chain exceeds max depth"
    (let [;; Create a chain that exceeds max depth
          deep-chain (into {} (map (fn [i] [(keyword (str "n" i)) (keyword (str "n" (inc i)))])
                                   (range (inc c/default-max-parent-chain-depth))))
          get-deep-parent (fn [_helpers fn-id] (get deep-chain fn-id))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Parent chain exceeds maximum allowed depth"
            (c/collect-parent-chain-impl get-deep-parent {} :n0))))))


;; === collect-dependency-chain-impl tests ===

(deftest collect-dependency-chain-impl-test
  (let [;; Mock dependency graph: a depends on b,c; b depends on d; c depends on d
        deps-map {:a #{:b :c}, :b #{:d}, :c #{:d}, :d #{}}
        get-deps-fn (fn [_helpers fn-id] (get deps-map fn-id #{}))]

    (testing "returns empty set for fn with no dependencies"
      (is (= #{} (c/collect-dependency-chain-impl get-deps-fn {} :d))))

    (testing "returns all transitive dependencies"
      (is (= #{:b :c :d} (c/collect-dependency-chain-impl get-deps-fn {} :a)))
      (is (= #{:d} (c/collect-dependency-chain-impl get-deps-fn {} :b))))

    (testing "handles diamond dependencies correctly"
      ;; Both b and c depend on d, but d should only appear once
      (let [result (c/collect-dependency-chain-impl get-deps-fn {} :a)]
        (is (contains? result :d))
        (is (= 3 (count result)))))))


;; === validate-parent-same-schema-impl tests ===

(deftest validate-parent-same-schema-impl-test
  (let [schema-map {:fn1 :schema-a, :fn2 :schema-a, :fn3 :schema-b}
        get-schema-fn (fn [_helpers fn-id] (get schema-map fn-id))]

    (testing "passes for nil parent"
      (is (nil? (c/validate-parent-same-schema-impl get-schema-fn {} :fn1 nil))))

    (testing "passes for same schema"
      (is (nil? (c/validate-parent-same-schema-impl get-schema-fn {} :fn1 :fn2))))

    (testing "throws for different schema"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Parent fn has different fn-schema-id"
            (c/validate-parent-same-schema-impl get-schema-fn {} :fn1 :fn3))))

    (testing "error contains schema details"
      (try
        (c/validate-parent-same-schema-impl get-schema-fn {} :fn1 :fn3)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/parent-schema-mismatch (:type (ex-data e))))
          (is (= :fn1 (:fn-id (ex-data e))))
          (is (= :fn3 (:parent-fn-id (ex-data e)))))))))


;; === validate-no-arg-override-impl tests ===

(deftest validate-no-arg-override-impl-test
  (let [;; Mock: fn1's parent chain has arg-schemas :arg-a and :arg-b defined
        chain-args {:fn1 #{:arg-a :arg-b}}
        collect-args-fn (fn [_helpers fn-id] (get chain-args fn-id #{}))]

    (testing "passes for new arg-schema"
      (is (nil? (c/validate-no-arg-override-impl collect-args-fn {} :fn1 :arg-c))))

    (testing "throws for already defined arg-schema"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Argument already defined in parent chain"
            (c/validate-no-arg-override-impl collect-args-fn {} :fn1 :arg-a))))

    (testing "error contains details"
      (try
        (c/validate-no-arg-override-impl collect-args-fn {} :fn1 :arg-b)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/arg-already-defined (:type (ex-data e))))
          (is (= :fn1 (:fn-id (ex-data e))))
          (is (= :arg-b (:arg-schema-id (ex-data e)))))))))


;; === validate-arg-schema-belongs-to-fn-impl tests ===

(deftest validate-arg-schema-belongs-to-fn-impl-test
  (let [fn-schema-map {:fn1 :schema-a, :fn2 :schema-b}
        arg-schema-map {:arg1 :schema-a, :arg2 :schema-b}
        get-fn-schema (fn [_helpers fn-id] (get fn-schema-map fn-id))
        get-arg-schema (fn [_helpers arg-id] (get arg-schema-map arg-id))]

    (testing "passes when arg belongs to fn's schema"
      (is (nil? (c/validate-arg-schema-belongs-to-fn-impl
                  get-fn-schema get-arg-schema {} :fn1 :arg1))))

    (testing "throws when arg belongs to different schema"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Arg-schema does not belong to fn's schema"
            (c/validate-arg-schema-belongs-to-fn-impl
              get-fn-schema get-arg-schema {} :fn1 :arg2))))

    (testing "error contains details"
      (try
        (c/validate-arg-schema-belongs-to-fn-impl
          get-fn-schema get-arg-schema {} :fn1 :arg2)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/arg-schema-mismatch (:type (ex-data e))))
          (is (= :fn1 (:fn-id (ex-data e))))
          (is (= :arg2 (:arg-schema-id (ex-data e)))))))))


;; === validate-no-inheritance-cycle-impl tests ===

(deftest validate-no-inheritance-cycle-impl-test
  (let [;; Mock: parent-b's ancestors are #{parent-c}
        ancestors-map {:parent-b #{:parent-c}}
        collect-chain-fn (fn [_helpers fn-id] (get ancestors-map fn-id #{}))]

    (testing "passes for nil parent"
      (is (nil? (c/validate-no-inheritance-cycle-impl collect-chain-fn {} :fn1 nil))))

    (testing "passes for valid parent"
      (is (nil? (c/validate-no-inheritance-cycle-impl collect-chain-fn {} :fn1 :parent-b))))

    (testing "throws for self-reference"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot set self as parent"
            (c/validate-no-inheritance-cycle-impl collect-chain-fn {} :fn1 :fn1))))

    (testing "throws when would create cycle"
      ;; If :parent-c wants to set :parent-b as parent, and :parent-b's ancestors
      ;; include :parent-c, that would create a cycle
      (let [ancestors-with-cycle {:parent-b #{:parent-c}}
            collect-fn (fn [_helpers fn-id] (get ancestors-with-cycle fn-id #{}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Setting parent would create inheritance cycle"
              (c/validate-no-inheritance-cycle-impl collect-fn {} :parent-c :parent-b)))))

    (testing "error contains cycle info"
      (let [ancestors-with-cycle {:parent-b #{:parent-c}}
            collect-fn (fn [_helpers fn-id] (get ancestors-with-cycle fn-id #{}))]
        (try
          (c/validate-no-inheritance-cycle-impl collect-fn {} :parent-c :parent-b)
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :constraint-violation/inheritance-cycle (:type (ex-data e))))
            (is (= :parent-c (:fn-id (ex-data e))))
            (is (= :parent-b (:parent-fn-id (ex-data e))))))))))


;; === validate-no-dependency-cycle-impl tests ===

(deftest validate-no-dependency-cycle-impl-test
  (let [;; Mock: :fn-b depends on #{:fn-c}, :fn-c depends on #{}
        deps-map {:fn-b #{:fn-c}, :fn-c #{}}
        collect-deps-fn (fn [_helpers fn-id] (get deps-map fn-id #{}))]

    (testing "passes for nil value-fn-id"
      (is (nil? (c/validate-no-dependency-cycle-impl collect-deps-fn {} :fn-a nil))))

    (testing "passes for valid reference"
      (is (nil? (c/validate-no-dependency-cycle-impl collect-deps-fn {} :fn-a :fn-b))))

    (testing "throws for self-reference"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Reference would create dependency cycle"
            (c/validate-no-dependency-cycle-impl collect-deps-fn {} :fn-a :fn-a))))

    (testing "throws when would create cycle"
      ;; If :fn-c wants to reference :fn-a, but :fn-a (via :fn-b) depends on :fn-c
      (let [deps-with-cycle {:fn-b #{:fn-c :fn-a}}
            collect-fn (fn [_helpers fn-id] (get deps-with-cycle fn-id #{}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Reference would create dependency cycle"
              (c/validate-no-dependency-cycle-impl collect-fn {} :fn-c :fn-b)))))

    (testing "error contains cycle info"
      (try
        (c/validate-no-dependency-cycle-impl collect-deps-fn {} :fn-a :fn-a)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
          (is (= :fn-a (:owner-fn-id (ex-data e))))
          (is (= :fn-a (:value-fn-id (ex-data e)))))))))


;; === defconstraint-wrappers macro test ===
;;
;; This tests that the macro generates valid code.
;; We use eval to actually expand and define functions in a test namespace.

(deftest defconstraint-wrappers-macro-test
  (testing "macro expands to valid code structure"
    ;; Use macroexpand with fully qualified symbol
    (let [expansion (macroexpand-1
                      `(c/defconstraint-wrappers (identity ~'helpers) c))]
      ;; Should expand to (do ...)
      (is (= 'do (first expansion)))
      ;; Should define 5 functions
      (is (= 6 (count expansion))) ; do + 5 defns
      ;; Each should be a defn
      (is (every? #(= 'clojure.core/defn (first %)) (rest expansion)))
      ;; Should define the expected function names
      (let [fn-names (set (map second (rest expansion)))]
        (is (contains? fn-names 'validate-parent-same-schema!))
        (is (contains? fn-names 'validate-no-arg-override!))
        (is (contains? fn-names 'validate-arg-schema-belongs-to-fn!))
        (is (contains? fn-names 'validate-no-inheritance-cycle!))
        (is (contains? fn-names 'validate-no-dependency-cycle!))))))
