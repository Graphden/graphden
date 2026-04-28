(ns graphden.executor.registry-test
  "Tests for executor.registry - base function registration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry :as registry]))


;; Fixture to clean registry between tests
(defn clean-registry-fixture
  [f]
  (registry/clear-base-fns!)
  (try
    (f)
    (finally
      (registry/clear-base-fns!))))


(use-fixtures :each clean-registry-fixture)


;; === register-base-fn! tests ===

(deftest register-base-fn!-test
  (testing "registers a function by name"
    (let [add-fn (fn [args _ctx] (+ @(:a args) @(:b args)))]
      (registry/register-base-fn! :add add-fn)
      (is (= add-fn (registry/get-base-fn :add)))))

  (testing "overwrites existing function with same name"
    (let [old-fn (fn [_ _] "old")
          new-fn (fn [_ _] "new")]
      (registry/register-base-fn! :my-fn old-fn)
      (is (= old-fn (registry/get-base-fn :my-fn)))
      (registry/register-base-fn! :my-fn new-fn)
      (is (= new-fn (registry/get-base-fn :my-fn)))))

  (testing "returns nil"
    (is (nil? (registry/register-base-fn! :test (fn [_ _] nil))))))


;; === get-base-fn tests ===

(deftest get-base-fn-test
  (testing "returns nil for unregistered function"
    (is (nil? (registry/get-base-fn :nonexistent))))

  (testing "returns registered function"
    (let [my-fn (fn [_ _] 42)]
      (registry/register-base-fn! :answer my-fn)
      (is (= my-fn (registry/get-base-fn :answer)))))

  (testing "handles multiple registrations"
    (registry/register-base-fn! :fn1 (fn [_ _] 1))
    (registry/register-base-fn! :fn2 (fn [_ _] 2))
    (registry/register-base-fn! :fn3 (fn [_ _] 3))
    (is (some? (registry/get-base-fn :fn1)))
    (is (some? (registry/get-base-fn :fn2)))
    (is (some? (registry/get-base-fn :fn3)))
    (is (nil? (registry/get-base-fn :fn4)))))


;; === clear-base-fns! tests ===

(deftest clear-base-fns!-test
  (testing "clears all registered functions"
    (registry/register-base-fn! :fn1 (fn [_ _] 1))
    (registry/register-base-fn! :fn2 (fn [_ _] 2))
    (is (some? (registry/get-base-fn :fn1)))
    (is (some? (registry/get-base-fn :fn2)))
    (registry/clear-base-fns!)
    (is (nil? (registry/get-base-fn :fn1)))
    (is (nil? (registry/get-base-fn :fn2))))

  (testing "returns nil"
    (is (nil? (registry/clear-base-fns!))))

  (testing "can be called when registry is empty"
    (registry/clear-base-fns!)
    (is (nil? (registry/clear-base-fns!)))))


;; === get-default-registry tests ===

(deftest get-default-registry-test
  (testing "returns empty map when registry is empty"
    (is (= {} (registry/get-default-registry))))

  (testing "returns map of registered functions"
    (let [fn1 (fn [_ _] 1)
          fn2 (fn [_ _] 2)]
      (registry/register-base-fn! :fn1 fn1)
      (registry/register-base-fn! :fn2 fn2)
      (let [reg (registry/get-default-registry)]
        (is (= fn1 (:fn1 reg)))
        (is (= fn2 (:fn2 reg)))
        (is (= 2 (count reg)))))))


;; === get-base-fn-from-context tests ===

(deftest get-base-fn-from-context-test
  (testing "returns function from context's base-fns"
    (let [my-fn (fn [_ _] 42)
          ctx {:base-fns {:my-fn my-fn}}]
      (is (= my-fn (registry/get-base-fn-from-context ctx :my-fn)))))

  (testing "returns nil for missing function"
    (let [ctx {:base-fns {:other (fn [_ _] nil)}}]
      (is (nil? (registry/get-base-fn-from-context ctx :missing)))))

  (testing "returns nil for context without base-fns"
    (is (nil? (registry/get-base-fn-from-context {} :my-fn)))
    (is (nil? (registry/get-base-fn-from-context {:other :data} :my-fn))))

  (testing "handles nil context"
    (is (nil? (registry/get-base-fn-from-context nil :my-fn)))))
