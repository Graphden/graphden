(ns graphden.packages.core.system-test
  "Unit tests for `core.system` base-fn impls — focused on the
   no-arg invocation primitive `:call-noargs` (companion to `:call` /
   `:invoke`; ships in the same module as part of the closure-capture
   work that re-composed `:schedule` as a fn-def).

   Mirrors `refinements_test` / `concurrency_test`: the package's
   impls.clj is slurp+eval'd via the loader's private
   `load-module-impls` so the defbase-generated symbols become
   reachable WITHOUT a normal require — same path the runtime takes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]))


(def ^:dynamic *impls* nil)


(defn- load-system-impls-fixture
  [f]
  (binding [*impls* ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                     "core" "system")]
    (f)))


(use-fixtures :once load-system-impls-fixture)


(defn- impl-of
  [kw]
  (let [entry (get *impls* kw)]
    (or (and (map? entry) (:impl entry))
        (and (fn? entry) entry)
        (throw (ex-info (str "No impl for " kw) {:available (keys *impls*)})))))


;; ============================================================================
;; :call-noargs — invoke a 0-arg callable
;;
;; Companion to `:call` / `:invoke` for the no-arg case. The slot's
;; structural type `[:fn {} a]` makes the binding-site hof-wrap with
;; variadic-ignore semantics (closure-capture; docs/CLOSURE_CAPTURE.md).
;; This unit test exercises the impl in isolation — the wrap mechanism
;; is covered end-to-end by `cron-schedule-service-test` (the full
;; closure-capture chain through `:schedule` → `:future` → `:_fire-target`
;; → `:call-noargs`).
;; ============================================================================

(deftest call-noargs-invokes-the-callable-test
  (testing "(func) is invoked; its return is the impl's return"
    (let [impl (impl-of :call-noargs)
          callable (fn [] :ok)]
      (is (= :ok (impl {:func (delay callable)} nil))))))


(deftest call-noargs-propagates-callable-return-test
  (testing "return value is whatever the callable returns — any shape"
    (let [impl (impl-of :call-noargs)]
      (is (= 42 (impl {:func (delay (fn [] 42))} nil)))
      (is (= [1 2 3] (impl {:func (delay (fn [] [1 2 3]))} nil)))
      (is (nil? (impl {:func (delay (fn [] nil))} nil)))
      (is (= {:a 1} (impl {:func (delay (fn [] {:a 1}))} nil))))))


(deftest call-noargs-propagates-callable-exception-test
  (testing "if the callable throws, the impl re-throws (no swallow)"
    (let [impl (impl-of :call-noargs)
          thrown (try (impl {:func (delay (fn [] (throw (ex-info "boom" {:k 1}))))}
                            nil)
                      :no-throw
                      (catch clojure.lang.ExceptionInfo e
                        (ex-data e)))]
      (is (= {:k 1} thrown) "boom's ex-data reaches the caller"))))


(deftest call-noargs-invokes-fresh-each-time-test
  (testing "each invocation calls the callable again — no result caching"
    (let [impl (impl-of :call-noargs)
          counter (atom 0)
          ticking (fn [] (swap! counter inc))]
      (impl {:func (delay ticking)} nil)
      (impl {:func (delay ticking)} nil)
      (impl {:func (delay ticking)} nil)
      (is (= 3 @counter)
          "3 invocations → 3 calls; impl itself doesn't memoise"))))


;; ============================================================================
;; :render-prometheus — metrics map → OpenMetrics/Prometheus text exposition.
;; ============================================================================

(deftest render-prometheus-formats-numeric-leaves-test
  (let [impl (impl-of :render-prometheus)]
    (testing "numeric leaves flatten + prefix graphden_; nested maps join with _"
      (is (= "graphden_heap_mb 125\ngraphden_counters_registry_rebuild 59"
             (impl {:m (delay (array-map "heap_mb" 125
                                         "counters" (array-map "registry_rebuild" 59)))}
                   nil))))
    (testing "non-numeric labels (strings) are dropped — samples are numeric"
      (is (= "graphden_threads 42"
             (impl {:m (delay (array-map "threads" 42 "hostname" "abc"))} nil))))
    (testing "keys are sanitised to [a-z0-9_] and lower-cased"
      (is (= "graphden_os_load_avg 1.5"
             (impl {:m (delay (array-map "OS load-avg" 1.5))} nil))))
    (testing "empty / nil map → empty exposition"
      (is (= "" (impl {:m (delay {})} nil)))
      (is (= "" (impl {:m (delay nil)} nil))))))
