(ns graphden.executor.registry-test
  "Tests for executor.registry - base function registration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry :as registry]))


;; Fixture to clean registry between tests.
;;
;; Each test runs inside `*registry-override*` bound to its own
;; empty atom, so `clear-base-fns!` / `register-base-fn!` mutate
;; that thread-local atom instead of the process-global
;; `default-registry`. Without this, every test of this NS would
;; nuke the global registry under any parallel NS that depends on
;; it (e.g. `value-form-test`'s `forms-ctx` does
;; `register-base-fn! :vf-const` and immediately snapshots the
;; registry — the snapshot races a parallel `clear-base-fns!` and
;; comes back without :vf-const, breaking compilation of every
;; composed fn whose root is :vf-const).
(defn clean-registry-fixture
  [f]
  (binding [registry/*registry-override* (atom {})]
    (f)))


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
  ;; The fixture binds `*registry-override*` to a fresh empty atom
  ;; (so parallel test runs can't race on the global default-registry
  ;; via `clear-base-fns!`). `get-default-registry` returns the
  ;; MERGED view (`@default-registry` + `@override`) — so assertions
  ;; about counts / exact-equality must filter to the override-side
  ;; entries (production base-fns loaded into the global aren't this
  ;; NS's concern).
  (testing "exposes registered functions on the merged view"
    (let [fn1 (fn [_ _] 1)
          fn2 (fn [_ _] 2)]
      (registry/register-base-fn! :fn1 fn1)
      (registry/register-base-fn! :fn2 fn2)
      (let [reg (registry/get-default-registry)]
        (is (= fn1 (:fn1 reg)))
        (is (= fn2 (:fn2 reg))))
      (is (= {:fn1 fn1 :fn2 fn2} @registry/*registry-override*)
          "override-scope entries are exactly the ones this test registered")))

  (testing "override drops to empty after clear-base-fns!"
    (registry/register-base-fn! :fn1 (fn [_ _] 1))
    (registry/clear-base-fns!)
    (is (= {} @registry/*registry-override*))))


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
