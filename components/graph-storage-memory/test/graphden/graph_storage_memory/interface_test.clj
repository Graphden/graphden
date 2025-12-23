(ns graphden.graph-storage-memory.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


(deftest create-storage-test
  (testing "creates storage with graph-data-schema entities"
    (let [storage (gsm/create-storage)]
      (is (= #{:fn-schema :arg-schema :fn :arg-value}
             (sp/current-entities storage)))
      (sp/close storage)))

  (testing "creates storage with value-kind enum"
    (let [storage (gsm/create-storage)]
      (is (contains? (sp/current-enums storage) :value-kind))
      (is (contains? (sp/current-enum-values storage :value-kind) :null))
      (is (contains? (sp/current-enum-values storage :value-kind) :text))
      (is (contains? (sp/current-enum-values storage :value-kind) :int))
      (sp/close storage)))

  (testing "fn-schema has expected fields"
    (let [storage (gsm/create-storage)
          fields (sp/current-fields storage :fn-schema)]
      (is (= :text (:type (get fields :name))))
      (is (= :enum (:type (get fields :returned-type))))
      (sp/close storage)))

  (testing "arg-schema has expected fields"
    (let [storage (gsm/create-storage)
          fields (sp/current-fields storage :arg-schema)]
      (is (= :ref (:type (get fields :fn-schema-id))))
      (is (= :text (:type (get fields :name))))
      (is (= :enum (:type (get fields :type))))
      (sp/close storage)))

  (testing "fn entity has expected fields"
    (let [storage (gsm/create-storage)
          fields (sp/current-fields storage :fn)]
      (is (= :text (:type (get fields :name))))
      (is (= :ref (:type (get fields :fn-schema-id))))
      (sp/close storage)))

  (testing "arg-value has expected fields"
    (let [storage (gsm/create-storage)
          fields (sp/current-fields storage :arg-value)]
      (is (= :ref (:type (get fields :owner-fn-id))))
      (is (= :ref (:type (get fields :arg-schema-id))))
      (is (= :union (:type (get fields :value))))
      (sp/close storage)))

  (testing "schema-metadata is populated"
    (let [storage (gsm/create-storage)
          metadata (sp/schema-metadata storage)]
      (is (some? (:entities metadata)))
      (is (some? (:fields metadata)))
      (is (some? (:enums metadata)))
      (is (some? (:enum-values metadata)))
      (sp/close storage))))


(deftest initialization-error-handling-test
  (testing "storage is closed if initialization fails"
    (let [storage-closed? (atom false)
          original-close sp/close]
      (with-redefs [sp/initialize (fn [_ _] (throw (ex-info "Test error" {})))
                    sp/close (fn [storage]
                               (reset! storage-closed? true)
                               (original-close storage))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Test error"
              (gsm/create-storage)))
        (is (true? @storage-closed?) "Storage should be closed after initialization failure")))))
