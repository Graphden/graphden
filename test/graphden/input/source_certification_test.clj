(ns ^:serial graphden.input.source-certification-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source-certification :as certification]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(deftest callable-ambiguity-stays-local-to-its-source-candidate
  (let [definitions [{:namespace "fixture" :name :sink :args {:left :any} :return-type :text}
                     {:namespace "fixture" :name :target :args {:text :text} :return-type :text}
                     {:namespace "fixture" :name :candidate :parent :sink :args {:left :target :text "bound"}}
                     {:namespace "fixture" :name :independent :args {} :return-type :text}]
        prepared (parse/prepare-module definitions {} {})
        owner (ids/fn-id "fixture" :candidate)
        run #(certification/verify-plan prepared definitions {} {})
        result (with-redefs [snapshot/with-migrated-bindings
                             (fn [_ _ _] (throw (ex-info "Unknown callable parameter" {:type :compile/ambiguous-lambda-params})))]
                 (run))
        entries (get-in result [:plan :definitions])]
    (is (= :compile/ambiguous-lambda-params (get-in result [:blocked owner :code])))
    (is (= [:supported :supported :blocked :supported] (mapv :status entries)))
    (is (= [:ref-scope-required] (mapv :code (:blockers (nth entries 2)))))
    (is (thrown-with-msg? IllegalStateException #"unexpected internal failure"
          (with-redefs [snapshot/with-migrated-bindings (fn [_ _ _] (throw (IllegalStateException. "unexpected internal failure")))]
            (run))))))
