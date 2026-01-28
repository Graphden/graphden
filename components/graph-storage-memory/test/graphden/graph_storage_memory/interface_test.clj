(ns graphden.graph-storage-memory.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


(deftest create-storage-test
  (testing "creates storage with graph-data-schema entities"
    (let [storage (gsm/create-storage)]
      (is (= #{:fn-schema :arg-schema :fn :arg-value :fn-arg :call-site :call-site-arg}
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
      (is (= :ref (:type (get fields :arg-schema-id))))
      (is (= :union (:type (get fields :value))))
      (sp/close storage)))

  (testing "fn-arg has expected fields (binding from fn to arg-value)"
    (let [storage (gsm/create-storage)
          fields (sp/current-fields storage :fn-arg)]
      (is (= :ref (:type (get fields :fn-id))))
      (is (= :ref (:type (get fields :arg-schema-id))))
      (is (= :ref (:type (get fields :arg-value-id))))
      (sp/close storage)))

  (testing "schema-metadata is populated"
    (let [storage (gsm/create-storage)
          metadata (sp/schema-metadata storage)]
      (is (some? (:entities metadata)))
      (is (some? (:fields metadata)))
      (is (some? (:enums metadata)))
      (is (some? (:enum-values metadata)))
      (sp/close storage)))

  (testing "cleans up storage on initialization error"
    (let [closed? (atom false)
          original-create mem/create-storage]
      ;; Mock mem/create-storage to return a wrapped storage that tracks close
      ;; and throws on initialize
      (with-redefs [mem/create-storage
                    (fn []
                      (let [storage (original-create)]
                        (reify
                          graphden.storage_protocol.interface.Storage
                          (initialize
                            [_ _schema]
                            (throw (ex-info "Init error" {:test true})))

                          (close
                            [_]
                            (reset! closed? true)
                            (sp/close storage))


                          graphden.storage_protocol.interface.StorageIntrospection

                          (current-entities [_] (sp/current-entities storage))

                          (current-fields [_ e] (sp/current-fields storage e))

                          (current-enums [_] (sp/current-enums storage))

                          (current-enum-values [_ e] (sp/current-enum-values storage e))

                          (schema-metadata [_] (sp/schema-metadata storage)))))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Init error"
              (gsm/create-storage)))
        (is @closed? "Storage should be closed on init failure")))))
