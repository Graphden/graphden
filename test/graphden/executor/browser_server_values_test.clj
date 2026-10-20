(ns graphden.executor.browser-server-values-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server-values :as values]))


(deftest strict-recursive-output-projection
  (let [t (values/contract {:items [:list {:name :text}] :count :int})
        result (values/project t {:items [{:name "visible" :secret "private"}]
                                  :count 1 :hidden {:token "private"}})]
    (is (= {:items [{:name "visible"}] :count 1} result))
    (is (= result (wire/decode-value (wire/encode-value result))))))


(deftest closed-ingress-rejects-extra-record-fields-and-type-confusion
  (let [t (values/contract {:query :text})]
    (is (= {:query "main"} (values/input t (wire/encode-value {:query "main"}))))
    (doseq [input [{:query "main" :capture "forged"} {:query 1}
                   {"query" "main"} {:query {:ref "forged"}}]]
      (is (thrown? clojure.lang.ExceptionInfo (values/input t (wire/encode-value input)))))))


(deftest unsupported-and-secret-contracts-fail-closed
  (doseq [t [:any :jsonb [:secret :text] {:nested [:list [:secret :text]]}
             [:fn {:input :text} :text] [:union :text :any]]]
    (is (thrown? clojure.lang.ExceptionInfo (values/contract t)))))


(deftest invalid-results-and-unbounded-lazy-values-are-refused
  (is (thrown? clojure.lang.ExceptionInfo (values/project {:name :text} {:name 42})))
  (is (thrown? clojure.lang.ExceptionInfo (values/project [:list :int] (range)))))
