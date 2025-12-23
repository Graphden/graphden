(ns graphden.graph-storage-datomic.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.graph-storage-datomic.interface :as gsd]
    [graphden.storage-protocol.interface :as sp]))


(deftest create-storage-test
  (testing "creates storage with explicit db-name"
    (let [storage (gsd/create-storage {:db-name "test-explicit-db"})]
      (try
        (is (= #{:fn-schema :arg-schema :fn :arg-value}
               (sp/current-entities storage)))
        (finally
          (sp/close storage)))))

  (testing "creates storage with graph-data-schema entities"
    (let [storage (gsd/create-storage)]
      (try
        (is (= #{:fn-schema :arg-schema :fn :arg-value}
               (sp/current-entities storage)))
        (finally
          (sp/close storage)))))

  (testing "creates storage with value-kind enum"
    (let [storage (gsd/create-storage)]
      (try
        (is (contains? (sp/current-enums storage) :value-kind))
        (is (contains? (sp/current-enum-values storage :value-kind) :null))
        (is (contains? (sp/current-enum-values storage :value-kind) :text))
        (is (contains? (sp/current-enum-values storage :value-kind) :int))
        (finally
          (sp/close storage)))))

  (testing "fn-schema has expected fields"
    (let [storage (gsd/create-storage)
          fields (sp/current-fields storage :fn-schema)]
      (try
        (is (= :text (:type (get fields :name))))
        (is (= :enum (:type (get fields :returned-type))))
        (finally
          (sp/close storage)))))

  (testing "arg-schema has expected fields"
    (let [storage (gsd/create-storage)
          fields (sp/current-fields storage :arg-schema)]
      (try
        (is (= :ref (:type (get fields :fn-schema-id))))
        (is (= :text (:type (get fields :name))))
        (is (= :enum (:type (get fields :type))))
        (finally
          (sp/close storage)))))

  (testing "fn entity has expected fields"
    (let [storage (gsd/create-storage)
          fields (sp/current-fields storage :fn)]
      (try
        (is (= :text (:type (get fields :name))))
        (is (= :ref (:type (get fields :fn-schema-id))))
        (finally
          (sp/close storage)))))

  (testing "arg-value has expected fields"
    (let [storage (gsd/create-storage)
          fields (sp/current-fields storage :arg-value)]
      (try
        (is (= :ref (:type (get fields :owner-fn-id))))
        (is (= :ref (:type (get fields :arg-schema-id))))
        (is (= :union (:type (get fields :value))))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata is populated"
    (let [storage (gsd/create-storage)
          metadata (sp/schema-metadata storage)]
      (try
        (is (some? (:entities metadata)))
        (is (some? (:fields metadata)))
        (is (some? (:enums metadata)))
        (is (some? (:enum-values metadata)))
        (finally
          (sp/close storage))))))
