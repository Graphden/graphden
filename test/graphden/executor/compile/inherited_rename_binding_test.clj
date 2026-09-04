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

   The call-time half of the same contract lives in
   `inherited-rename-surface-test` below
   (docs/adr/ADR-inherited-rename-surface.md): a descendant's public
   free-arg surface advertises the ancestor's renamed name, and
   call-time args are accepted under BOTH the renamed and the raw
   source name."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.compile-runtime :as cr]
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


(deftest inherited-rename-surface-test
  (harness/sync! [{:name :irs-free :parent :wrap-custom-script}])

  (testing "the public free-arg surface advertises the ancestor's renamed name"
    (is (= [:body] (vec (cr/free-arg-ext-names harness/*context*
                                               (harness/fn-id "irs-free"))))))

  (testing "call-time args by the RENAMED name reach the descendant"
    (is (= [:script {} "ZZ();"]
           (exec/execute-by-name harness/*context* "irs-free" {:body "ZZ();"}))))

  (testing "the raw source name stays accepted (compatibility)"
    (is (= [:script {} "YY();"]
           (exec/execute-by-name harness/*context* "irs-free" {:content "YY();"})))
    (is (contains? (cr/free-arg-accepted-names harness/*context*
                                               (harness/fn-id "irs-free"))
                   :content)))

  (testing "execute-with-named-args validation accepts the renamed name"
    (is (= [:script {} "VV();"]
           (exec/execute-with-named-args harness/*context*
                                         (harness/fn-id "irs-free")
                                         {:body "VV();"}))))

  (testing "the crud (Run-form) surface and the executor surface AGREE on the name"
    ;; The whole point of the contract: one public name, everywhere.
    (is (= #{:body}
           (set (keys (lookup/free-arg-slot-map harness/*context*
                                                (harness/fn-id "irs-free"))))
           (set (cr/free-arg-ext-names harness/*context*
                                       (harness/fn-id "irs-free"))))))

  (testing "surfaces without inherited renames are untouched (regression pin)"
    ;; Root-slot renames were already chain-aware; a plain composed fn
    ;; keeps its raw surface verbatim.
    (harness/sync! [{:name :irs-plain :parent :wrap-script}])
    (is (= [:content] (vec (cr/free-arg-ext-names harness/*context*
                                                  (harness/fn-id "irs-plain")))))))


(deftest renamed-list-slot-binding-test
  ;; A pure `{:as}` rename over a base-fn LIST slot (`:do`'s lazy
  ;; `:steps`), then a descendant binding a bare vector of refs under
  ;; the new name — the `:migrate` template's `:migrations` shape.
  ;; Two halves had to agree for the refs to RUN: the loader must type
  ;; the rename slot from its source (so the vector becomes list-item
  ;; refs, not a JSON literal), and the runtime list-chain reader must
  ;; pick the descendant's items up from the rename-view slot.
  (harness/sync! [{:name :rls-a :parent :const :args {:value "a"}}
                  {:name :rls-b :parent :const :args {:value "b"}}
                  {:name :rls-run :parent :do :args {:steps {:as :items}}}
                  {:name :rls-bound :parent :rls-run :args {:items [:rls-a :rls-b]}}
                  {:name :rls-deeper :parent :rls-bound :args {:items {:append [:rls-a]}}}
                  ;; The same list bound from OUTSIDE a HOF boundary: the
                  ;; renamed `:do` is the wrapped body, the descendant's
                  ;; list is a deep (env) binding captured at wrap time.
                  {:name :rls-hof :parent :call-noargs :args {:func :rls-run}}
                  {:name :rls-hof-bound :parent :rls-hof :args {:items [:rls-a :rls-b]}}])

  (testing "the descendant's vector is stored as list-item REFS on the rename slot"
    (let [b (first (sp/query-entities harness/*storage* :binding
                                      {:fn-id (harness/fn-id "rls-bound")}))
          slot (sp/read-entity harness/*storage* :slot (:slot-id b))
          items (sp/query-entities harness/*storage* :binding-list-item
                                   {:binding-id (:id b)})]
      (is (= "items" (:name slot)))
      (is (true? (:list-append b)))
      (is (= [(harness/fn-id "rls-a") (harness/fn-id "rls-b")]
             (map :ref-fn-id (sort-by :position items))))))

  (testing "the renamed :do runs the descendant's steps — last result wins"
    (is (= "b" (exec/execute-by-name harness/*context* "rls-bound" {}))))

  (testing "a further `{:append}` extends the chain through the rename"
    (is (= "a" (exec/execute-by-name harness/*context* "rls-deeper" {}))))

  (testing "the list crosses a HOF boundary as a captured env binding"
    (is (= "b" (exec/execute-by-name harness/*context* "rls-hof-bound" {})))))
