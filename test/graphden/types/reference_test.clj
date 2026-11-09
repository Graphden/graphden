(ns graphden.types.reference-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(deftest identity-ref-never-needs-the-target-signature-test
  (is (= {:mode :identity :actual-type :fn-ref} (reference/classify :fn-ref nil)))
  (is (= :unresolved (:mode (reference/classify :text nil))))
  (is (= {:mode :unresolved :reason :missing-target-return} (reference/classify :text {}))))


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


(deftest wrapped-identity-never-falls-through-to-value-evaluation-test
  (let [target {:return :text :effects #{:db}}
        ctx (context/build-context {:aliases {:test/Identity [:secret :fn-ref]}})]
    (context/call-with-types
      ctx
      (fn []
        (doseq [source [:test/Identity [:refine :fn-ref [:not= nil]]]]
          (let [expected (types/resolve-alias source)]
            (is (types/well-formed? expected))
            (is (= {:mode :unresolved :reason :wrapped-identity-policy}
                   (reference/classify expected target)))))))))


(deftest authored-empty-call-site-partition-captures-structural-name-test
  (let [expected [:fn {:item :int} :bool #{}]
        actual [:fn {:item :int :config :text} :bool #{:db} #{:jvm-server}]
        info {:lambda-params [] :arg-effects {:item #{:db}}}]
    (is (= [:fn {:item :int} :bool #{} #{:jvm-server}]
           (reference/call-site-contract expected actual info)))
    (is (= [:fn {:item :int} :bool #{:db} #{:jvm-server}]
           (reference/call-site-contract expected actual {})))))


(deftest authored-call-site-names-survive-positional-type-projection-test
  (let [expected [:fn {:item :int} :bool :any]
        actual [:fn {:value :int :config :text} :bool :any :unknown]]
    (is (= [:fn {:value :int} :bool :any :unknown]
           (reference/call-site-contract expected actual {:lambda-params [:value]})))
    (is (= [:fn {} :bool :any :unknown]
           (reference/call-site-contract [:fn {} :bool :any] actual {})))))


(deftest callable-parameter-selection-retains-explicit-empty-and-positional-rules-test
  (let [base {:structural-names [:item] :free-names [:value :configuration]
              :candidate-names [:value] :own-names #{:value}}]
    (is (= [:value] (reference/select-call-site-params base)))
    (is (= [] (reference/select-call-site-params (assoc base :declared []))))
    (is (= [:item] (reference/select-call-site-params (assoc base :free-names [:item :configuration]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Ambiguous"
          (reference/select-call-site-params (assoc base :candidate-names [:configuration]))))
    (is (= [] (reference/select-call-site-params
                {:structural-names [] :free-names (delay (throw (Exception. "unneeded walk")))})))
    (is (= [:right :left]
           (reference/select-call-site-params
             {:structural-names [:left :right] :free-names [:right :configuration :left]})))))
