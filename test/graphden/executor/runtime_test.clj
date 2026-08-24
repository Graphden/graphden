(ns graphden.executor.runtime-test
  "Unit tests for the runtime arg-resolution helpers plus
   `hof-callable`.

   Parallel-safe: no `with-redefs`. The UUID branches of `hof-callable`
   drive the REAL `make-single-arg-callable` over a container-backed
   storage (a registered `double` base-fn + a composed fn with one free
   arg), asserting the wrap actually executes — instead of stubbing the
   interface fn, which root-rebound a per-execute hot-path var
   process-globally and pinned this NS `^:serial` (serial-reduction
   cluster B). `register-base-fn!` writes go through the plugin's
   per-NS-thread `*registry-override*` seam, same as
   `compile-runtime-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as rt]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest resolve-arg-literal
  (testing "returns literal value as-is"
    (is (= 42 (rt/resolve-arg {:x 42} :x)))
    (is (= "hello" (rt/resolve-arg {:x "hello"} :x)))
    (is (nil? (rt/resolve-arg {:x nil} :x)))
    (is (nil? (rt/resolve-arg {} :missing)))))


(deftest resolve-arg-thunk
  (testing "calls thunk wrapped via rt/thunk"
    (let [t (rt/thunk (fn [] 42))]
      (is (= 42 (rt/resolve-arg {:x t} :x)))))
  (testing "ONCE semantics — repeated reads see one value, one effect.
            Load-bearing: a ref thunk may wrap an effectful subtree (the
            whole handler chain behind `:_call-base-handler`); before
            once, a diverged call-cache key re-ran that handler inside
            one HTTP request (drained-body 400 corrupting the encoded
            response). See `rt/thunk`'s docstring."
    (let [call-count (atom 0)
          t (rt/thunk (fn [] (swap! call-count inc)))]
      (is (= 1 (rt/resolve-arg {:x t} :x)))
      (is (= 1 (rt/resolve-arg {:x t} :x)) "second read returns the FIRST value")
      (is (= 1 @call-count) "the wrapped fn ran exactly once")))
  (testing "raw 0-arity invocation (impls reading `((:x args))`) shares
            the same once cell"
    (let [call-count (atom 0)
          t (rt/thunk (fn [] (swap! call-count inc)))]
      (is (= 1 (t)))
      (is (= 1 (rt/resolve-arg {:x t} :x)))
      (is (= 1 @call-count))))
  (testing "once is thread-safe — concurrent first forces run the body
            exactly once (delay semantics)"
    (let [call-count (atom 0)
          t (rt/thunk (fn [] (Thread/sleep 20) (swap! call-count inc)))
          results (->> (repeatedly 8 #(future (t)))
                       (doall)
                       (mapv deref))]
      (is (every? #(= 1 %) results))
      (is (= 1 @call-count))))
  (testing "a nil result is memoized too — no re-run on later reads"
    (let [call-count (atom 0)
          t (rt/thunk (fn [] (swap! call-count inc) nil))]
      (is (nil? (rt/resolve-arg {:x t} :x)))
      (is (nil? (rt/resolve-arg {:x t} :x)))
      (is (= 1 @call-count)))))


(deftest raw-fn-not-resolved
  (testing "raw fn (without thunk marker) returned as-is — HOF callables"
    (let [raw-fn (fn [item] (* 2 item))]
      (is (identical? raw-fn (rt/resolve-arg {:x raw-fn} :x))))))


(deftest thunk-predicate
  (is (rt/thunk? (rt/thunk (fn [] 1))))
  (is (not (rt/thunk? (fn [] 1))))
  (is (not (rt/thunk? 42)))
  (is (not (rt/thunk? nil))))


(deftest resolve-arg-ideref
  (testing "forces a Delay and returns the underlying value"
    (is (= 7 (rt/resolve-arg {:x (delay 7)} :x))))
  (testing "forces an atom — atoms are IDeref too"
    (is (= 99 (rt/resolve-arg {:x (atom 99)} :x))))
  (testing "thunk takes precedence over IDeref when both would match"
    ;; Thunks happen to also be IDeref via future/delay machinery in some
    ;; JVM paths, so the order in `resolve-arg`'s cond matters. We assert
    ;; the thunk branch wins by checking the fn is called rather than
    ;; treated as a deref target.
    (let [t (rt/thunk (fn [] :thunk-was-called))]
      (is (= :thunk-was-called (rt/resolve-arg {:x t} :x))))))


;; ============================================================================
;; `hof-callable` — normalises a `:fn`-type arg into an invokable callable.
;; ============================================================================

(deftest hof-callable-passes-through-fn
  (testing "when value is already a fn, returns it unchanged"
    (let [f (fn [item] (* 2 item))]
      (is (identical? f (rt/hof-callable {:func f} :func nil))))))


(deftest hof-callable-passes-through-non-uuid
  (testing "non-UUID, non-IDeref, non-fn values pass through as-is"
    (is (= :raw-keyword (rt/hof-callable {:func :raw-keyword} :func nil)))
    (is (= 42 (rt/hof-callable {:func 42} :func nil)))
    (is (nil? (rt/hof-callable {:func nil} :func nil)))))


(deftest hof-callable-ideref-with-fn-value
  (testing "IDeref wrapping a non-UUID returns the dereffed value as-is"
    (let [inner (fn [x] (str "echo " x))
          wrapped (delay inner)]
      ;; Per `hof-callable`'s :else branch inside the IDeref clause: if
      ;; the derefed value isn't a UUID, it's returned. Deref-and-return.
      (is (= inner (rt/hof-callable {:func wrapped} :func nil))))))


(defn- doubler-ctx
  "Register a `double` base-fn impl, build the base + composed fn rows
   in `storage`, and return `[ctx composed-fn-id]` — a real graph for
   `hof-callable`'s UUID branch to wrap through the genuine
   `make-single-arg-callable` (one free arg `x`, so the wrap yields a
   single-arg callable)."
  [storage]
  (exec/register-base-fn! :double (setup/fn-impl [x] (* 2 x)))
  (let [base-fn (setup/create-base-fn! storage "double" :int)
        _ (setup/create-arg! storage (:id base-fn)
                             {:name "x" :type :int :required true})
        composed (setup/create-composed-fn! storage "my-double" (:id base-fn))]
    [(exec/create-context {:storage storage}) (:id composed)]))


(deftest hof-callable-uuid-resolves-via-make-callable
  (testing "raw UUID arg → wrapped via the REAL make-single-arg-callable"
    (let [storage (setup/create-test-storage)]
      (try
        (let [[ctx fn-id] (doubler-ctx storage)
              result (rt/hof-callable {:func fn-id} :func ctx)]
          (is (fn? result) "the UUID took the wrap path — a callable came back")
          (is (= 10 (result 5))
              "the callable executes the composed fn (item bound to the free arg)"))
        (finally (sp/close storage))))))


(deftest hof-callable-ideref-of-uuid
  (testing "IDeref-wrapped UUID also routes through make-callable"
    (let [storage (setup/create-test-storage)]
      (try
        (let [[ctx fn-id] (doubler-ctx storage)
              result (rt/hof-callable {:func (delay fn-id)} :func ctx)]
          (is (fn? result))
          (is (= 14 (result 7))
              "deref-then-wrap — same live execution as the raw-UUID path"))
        (finally (sp/close storage))))))
