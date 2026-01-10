(ns graphden.graph-storage-postgres.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.graph-storage-postgres.interface :as gsp]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.postgres-test-helpers :as pth]))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(use-fixtures :once (pth/create-container-fixture #'*container*))
(use-fixtures :each (pth/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates a test storage with a clean database."
  []
  (pth/clean-database-fast! *container*)
  (gsp/create-storage (pth/get-container-config *container*)))


(deftest create-storage-test
  (testing "creates storage with graph-data-schema entities"
    (let [storage (create-test-storage)]
      (try
        (is (= #{:fn-schema :arg-schema :fn :arg-value :fn-result-value}
               (sp/current-entities storage)))
        (finally
          (sp/close storage)))))

  (testing "creates storage with value-kind enum"
    (let [storage (create-test-storage)]
      (try
        (is (contains? (sp/current-enums storage) :value-kind))
        (is (contains? (sp/current-enum-values storage :value-kind) :null))
        (is (contains? (sp/current-enum-values storage :value-kind) :text))
        (is (contains? (sp/current-enum-values storage :value-kind) :int))
        (finally
          (sp/close storage)))))

  (testing "fn-schema has expected fields"
    (let [storage (create-test-storage)
          fields (sp/current-fields storage :fn-schema)]
      (try
        (is (= :text (:type (get fields :name))))
        (is (= :enum (:type (get fields :returned-type))))
        (finally
          (sp/close storage)))))

  (testing "arg-schema has expected fields"
    (let [storage (create-test-storage)
          fields (sp/current-fields storage :arg-schema)]
      (try
        (is (= :ref (:type (get fields :fn-schema-id))))
        (is (= :text (:type (get fields :name))))
        (is (= :enum (:type (get fields :type))))
        (finally
          (sp/close storage)))))

  (testing "fn entity has expected fields"
    (let [storage (create-test-storage)
          fields (sp/current-fields storage :fn)]
      (try
        (is (= :text (:type (get fields :name))))
        (is (= :ref (:type (get fields :fn-schema-id))))
        (finally
          (sp/close storage)))))

  (testing "arg-value has expected fields"
    (let [storage (create-test-storage)
          fields (sp/current-fields storage :arg-value)]
      (try
        (is (= :ref (:type (get fields :owner-fn-id))))
        (is (= :ref (:type (get fields :arg-schema-id))))
        (is (= :union (:type (get fields :value))))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata is populated"
    (let [storage (create-test-storage)
          metadata (sp/schema-metadata storage)]
      (try
        (is (some? (:entities metadata)))
        (is (some? (:fields metadata)))
        (is (some? (:enums metadata)))
        (is (some? (:enum-values metadata)))
        (finally
          (sp/close storage)))))

  (testing "cleans up storage on initialization error"
    (let [closed? (atom false)
          original-create pg/create-storage]
      ;; Mock pg/create-storage to return a wrapped storage that tracks close
      ;; and throws on initialize
      (with-redefs [pg/create-storage
                    (fn [opts]
                      (let [storage (original-create opts)]
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
              (gsp/create-storage (pth/get-container-config *container*))))
        (is @closed? "Storage should be closed on init failure")))))
