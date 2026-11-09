(ns graphden.types.reference-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.types.reference :as reference]))


(deftest identity-ref-never-needs-the-target-signature-test
  (is (= {:mode :identity :actual-type :fn-ref} (reference/classify :fn-ref nil)))
  (is (= :unresolved (:mode (reference/classify :text nil)))))


(deftest callable-producers-keep-construction-inputs-separate-test
  (let [callable [:fn {:message :text} :bool #{} :unknown]
        target {:args {:configuration :text} :return [:secret callable]
                :effects #{:db} :call-time-effects #{}}
        result (reference/classify callable target)]
    (is (= :callable-producer (:mode result)))
    (is (= [:secret callable] (:actual-type result)))
    (is (= [:fn {:message :text} [:secret :bool] #{} :unknown] (:callable-type result)))
    (is (= :value (:mode (reference/classify :any target))))))


(deftest graph-callables-use-call-time-effects-and-known-execution-test
  (let [target {:args {:item :int :config :text} :return :bool
                :effects #{:db} :call-time-effects #{} :execution #{:jvm-server}}]
    (is (= {:mode :graph-callable
            :actual-type [:fn {:item :int :config :text} :bool #{} #{:jvm-server}]}
           (reference/classify :fn target)))))
