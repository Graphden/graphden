(ns graphden.packages.app.forms-impls-test
  "Storage preconditions of the app.forms native boundaries.
   Descriptor and renderer policy is exercised through the real graph in
   graphden.crud.value-form-graph-test."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "app" "forms"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays.
   `delay` is a macro, so the map cannot be built with `update-vals`."
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(deftest storage-backed-shims-refuse-a-context-without-storage
  (testing "each raises the typed :execution-error/missing-storage, never an NPE"
    ;; The web layer maps this `:type` to a clean error response; a bare
    ;; NullPointerException from inside the resolver would surface as an
    ;; untyped 500 and page the operator.
    (doseq [[kw args] [[:_slot-effective-type-raw {:parsed {}}]
                       [:current-slot-value {:parsed {}}]
                       [:_slot-type-provenance-facts {:parsed {}}]]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (call kw args {}))
                  (str kw " must refuse a storage-less ctx"))]
        (is (= :execution-error/missing-storage (:type (ex-data e))))))))
