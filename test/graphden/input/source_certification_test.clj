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


(deftest only-certified-inline-descendants-of-a-verified-source-cross-the-package-boundary
  (let [sources [{:namespace "fixture" :name :sink :args {:value :text} :return-type :text}
                 {:namespace "fixture" :name :read-text :args {:deep :text} :return-type :text}
                 {:namespace "fixture" :name :owner :parent :sink
                  :args {:value {:parent :sink :args {:value :read-text :deep "bound"}}}}]
        prepared (parse/prepare-module sources {} {})
        [sink reader owner] (map #(ids/fn-id "fixture" %) [:sink :read-text :owner])
        roots {sink {:name :sink :args {:value :text} :return :text :effects #{} :execution #{:jvm-server}}
               reader {:name :read-text :args {:deep :text} :return :text :effects #{} :execution #{:jvm-server}}}
        result (certification/verify-plan prepared sources {} roots)
        proof (:source-proof result)
        child (first (vals (get-in proof [:baselines owner :v2 :inline-definitions])))
        id (:fn-id child)]
    (is (= :authored-source-equivalent (get-in proof [:baselines owner :verification])))
    (is (uuid? id))
    (is (= :authored-source-equivalent (get-in proof [:baselines id :verification])))
    (is (= #{sink reader owner id} (set (keys (:baselines proof)))))))
