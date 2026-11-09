(ns graphden.input.closure-ir-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.closure-fixture :as closure]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.input.invocation :as invocation]
    [graphden.input.ir :as ir]))


(defn- plan
  []
  ;; Pure plan routing proof only; native closure tests use the actual checker.
  (let [{:keys [analysis context definitions]}
        (closure/model closure/definitions (fn [{:keys [substitution]}]
                                             {:status :checked :substitution substitution}))]
    (ir/lower-checked analysis {:snapshot-id (:snapshot-id context)
                                :implementations (fixture/implementations definitions
                                                                          {:root (fn [_ _]) :body (fn [_ _]) :factor (fn [_ _])})})))


(deftest closure-call-site-is-not-an-external-required-input
  (let [ir (plan)
        body (fixture/address :root [] :root [:body])
        parameter (fixture/address :root [:body-use] :body [:item])
        closure (get-in ir [:closures body])]
    (is (= :closure (get-in ir [:expressions body :op])))
    (is (= #{} (:parameters ir) (:required-addresses ir)))
    (is (= #{parameter} (:invocation-parameters ir) (:required-parameters closure)))
    (is (every? #(= [(fixture/id :body-use)] (second (first %))) (:owned-addresses closure)))
    (is (= {parameter 3} (invocation/call-values closure {:item 3})))
    (is (= {parameter nil} (invocation/call-values closure {:item nil})))
    (is (= {} (invocation/call-values closure {})))))


(deftest call-values-shadow-only-their-own-invocation-addresses
  (let [ir (plan)
        closure (get-in ir [:closures (fixture/address :root [] :root [:body])])
        parameter (fixture/address :root [:body-use] :body [:item])
        captured (fixture/address :root [] :root [:factor])]
    (is (= {captured false parameter nil}
           (invocation/supplied-values {captured false parameter 9} closure {:item nil})))
    (is (= {captured false}
           (invocation/supplied-values {captured false parameter 9} closure {})))
    (is (thrown? clojure.lang.ExceptionInfo (invocation/call-values closure {:factor 1})))))
