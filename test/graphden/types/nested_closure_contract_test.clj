(ns graphden.types.nested-closure-contract-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(def ^:private loaded (loader/load-packages ["core"]))


(use-fixtures :each
  (fn [t]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (t))))))


(deftest authored-call-site-parameters-and-lexical-captures
  (check/check-fn-def! {:name :closure-cell :parent :get
                        :lambda-params [:column]
                        :args {:coll {:as :record :type :keyword-map}
                               :key {:as :column :type :keyword}
                               :default nil}})
  (check/check-fn-def! {:name :closure-columns :parent :map
                        :args {:func :closure-cell
                               :coll {:as :columns :type [:list :keyword]}}})
  (check/check-fn-def! {:name :closure-renamed-columns :parent :map
                        :args {:func :closure-cell
                               :record {:as :source :type :keyword-map}
                               :coll {:as :columns :type [:list :keyword]}}})
  (is (= #{:source :columns}
         (set (keys (:args (registry/rich-type-of :closure-renamed-columns))))))
  (check/check-fn-def! {:name :closure-record :parent :const
                        :args {:value {:as :row :type :keyword-map}}})
  (check/check-fn-def! {:name :closure-row :parent :vec
                        :lambda-params [:row]
                        :args {:record :closure-record :coll :closure-columns}})
  (check/check-fn-def! {:name :closure-table :parent :map
                        :args {:func {:ref :closure-row}
                               :coll {:as :records :type [:list :keyword-map]}}})
  (is (= {:columns [:list :keyword] :record (types/resolve-alias :keyword-map)}
         (:args (registry/rich-type-of :closure-columns))))
  (is (= {:columns [:list :keyword] :row (types/resolve-alias :keyword-map)}
         (:args (registry/rich-type-of :closure-row))))
  (is (= {:columns [:list :keyword] :records [:list (types/resolve-alias :keyword-map)]}
         (:args (registry/rich-type-of :closure-table))))
  (testing "a secret nested in record content remains marked through composition"
    (registry/record-rich-types-raw!
      :closure-secret-records {:args {} :return [:list [:map :keyword [:secret :text]]]})
    (check/check-fn-def! {:name :closure-secret-table :parent :closure-table
                          :args {:records :closure-secret-records}})
    (is (types/contains-secret? (:return (registry/rich-type-of :closure-secret-table))))
    (registry/record-rich-types-raw!
      :closure-hidden-records {:args {} :return [:secret [:list :keyword-map]]})
    (is (thrown? clojure.lang.ExceptionInfo
          (check/check-fn-def! {:name :closure-hidden-table :parent :closure-table
                                :args {:records :closure-hidden-records}})))
    (is (= #{} (:effects (registry/rich-type-of :closure-secret-table))))))


(deftest explicit-empty-lambda-keeps-structural-names-captured
  (check/check-fn-def! {:name :closure-constant :parent :const
                        :lambda-params []
                        :args {:value {:as :item :type :text}}})
  (check/check-fn-def! {:name :closure-repeat :parent :map
                        :args {:func :closure-constant :coll {:value [1 2]}}})
  (is (= {:item :text} (:args (registry/rich-type-of :closure-repeat)))))


(deftest binding-own-ref-with-the-same-free-name-keeps-the-input
  (check/check-fn-def! {:name :closure-inner-get :parent :get
                        :args {:key {:value :foo}}})
  (check/check-fn-def! {:name :closure-outer-get :parent :get
                        :args {:coll :closure-inner-get
                               :key {:value :bar}}})
  (is (contains? (:args (registry/rich-type-of :closure-outer-get)) :coll)))


(deftest authored-parameter-domain-survives-capture-projection
  ;; A concrete callback return avoids the unrelated gradual :any escape
  ;; of the get-based callback above. Its second argument is captured.
  (registry/record-rich-types-raw!
    :closure-keyword-label {:args {:column :keyword :record :keyword-map}
                            :lambda-params [:column]
                            :return :text})
  (check/check-fn-def! {:name :closure-valid-domain :parent :map
                        :args {:func :closure-keyword-label
                               :coll {:value [:a :b]}}})
  (is (= #{:record} (set (keys (:args (registry/rich-type-of :closure-valid-domain))))))
  (is (thrown? clojure.lang.ExceptionInfo
        (check/check-fn-def! {:name :closure-wrong-domain :parent :map
                              :args {:func :closure-keyword-label
                                     :coll {:value [42]}}}))))
