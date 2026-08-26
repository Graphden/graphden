(ns graphden.executor.compile.inherited-rename-binding-test
  "Regression: an fn-def binding addressed at an ANCESTOR's scalar
   rename of a POSITIONAL anchor must reach the anchor slot.

   `:wrap-custom-script` renames `content → body` (a scalar
   rename-view over `:wrap-element`'s positional `{:as :content}`
   anchor); its descendants bind `:body`. Before the fix the parser
   anchored the binding on the scalar VIEW slot, which the positional
   item's runtime reader never consults — the value silently vanished
   and the composed tag came out body-less (`[:script {} nil]`). The
   parser now normalizes exactly this rename shape to the positional
   anchor (`records/slot-resolution/scalar-over-positional-hit`),
   matching the slot the editor writes for the same edit; every other
   rename shape keeps the rename-view slot as its anchor.

   Known residual gap (out of scope here): CALL-TIME args addressed
   by the ancestor's renamed name (`execute` with `{:body …}` on a
   descendant with a FREE body) still miss — the free-arg surface of
   a descendant advertises the SOURCE name (`content`), which also
   is what call-time resolution honours."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(deftest inherited-rename-binding-test
  (harness/sync! [{:name :irb-src
                   :parent :const
                   :return-type :js-source
                   :args {:value "REF();"}}
                  {:name :irb-bound :parent :wrap-custom-script
                   :args {:body {:value "AAA();"}}}
                  {:name :irb-bare :parent :wrap-custom-script
                   :args {:body "BBB();"}}
                  {:name :irb-ref :parent :wrap-custom-script
                   :args {:body :irb-src}}
                  {:name :irb-free :parent :wrap-custom-script}])

  (testing "a descendant's literal binding on the ancestor's renamed name lands in the tag"
    (is (= [:script {} "AAA();"]
           (exec/execute-by-name harness/*context* "irb-bound" {})))
    (is (= [:script {} "BBB();"]
           (exec/execute-by-name harness/*context* "irb-bare" {}))))

  (testing "a ref binding through the renamed name resolves too"
    (is (= [:script {} "REF();"]
           (exec/execute-by-name harness/*context* "irb-ref" {}))))

  (testing "the parser anchors the binding on the positional source slot (what the editor writes)"
    (let [b (first (sp/query-entities harness/*storage* :binding
                                      {:fn-id (harness/fn-id "irb-bound")}))
          slot (sp/read-entity harness/*storage* :slot (:slot-id b))]
      (is (= "content" (:name slot)))))

  (testing "call-time args by the SOURCE name reach a free descendant"
    (is (= [:script {} "YY();"]
           (exec/execute-by-name harness/*context* "irb-free" {:content "YY();"})))))
