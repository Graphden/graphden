(ns graphden.storage.age.core-test
  "Tests for AGE storage core module (AgeStorage record).

   Covers:
   - create-storage function
   - Storage protocol (initialize, close)
   - StorageIntrospection protocol
   - StorageErrorClassifier protocol"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.age.interface :as age]
    [graphden.storage.age.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; =============================================================================
;; Storage lifecycle tests
;; =============================================================================

(deftest create-storage-test
  (testing "create-storage creates AgeStorage with pool"
    (let [config (setup/get-container-config setup/*container*)
          storage (age/create-storage config)]
      (try
        (is (some? storage))
        (is (some? (:pool storage)))
        (is (= "graphden" (:graph-name storage)))
        (is (some? (:metadata-cache storage)))
        (is (some? (:rw-lock storage)))
        (finally
          (sp/close storage)))))

  (testing "create-storage with custom graph-name"
    (let [config (-> (setup/get-container-config setup/*container*)
                     (assoc :graph-name "custom_graph"))
          storage (age/create-storage config)]
      (try
        (is (= "custom_graph" (:graph-name storage)))
        (finally
          (sp/close storage))))))


(deftest initialize-and-close-test
  (testing "initialize creates tables and AGE graph"
    (let [storage (setup/create-raw-storage)
          schema (setup/make-graph-schema)]
      (try
        (sp/initialize storage schema)
        ;; After initialization, we should have entities
        (is (seq (sp/current-entities storage)))
        (finally
          (sp/close storage)))))

  (testing "close can be called multiple times safely"
    (let [storage (setup/create-raw-storage)
          schema (setup/make-graph-schema)]
      (sp/initialize storage schema)
      (sp/close storage)
      ;; Second close should not throw
      (is (nil? (sp/close storage))))))


;; =============================================================================
;; StorageIntrospection tests
;; =============================================================================

(deftest current-entities-test
  (testing "current-entities returns list of entity names after initialization"
    (let [storage (setup/create-test-storage)]
      (try
        (let [entities (sp/current-entities storage)]
          (is (set? entities))
          (is (some #(= % :fn-schema) entities))
          (is (some #(= % :fn) entities))
          (is (some #(= % :arg-schema) entities)))
        (finally
          (sp/close storage))))))


(deftest current-fields-test
  (testing "current-fields returns field metadata for entity"
    (let [storage (setup/create-test-storage)]
      (try
        (let [fields (sp/current-fields storage :fn-schema)]
          (is (map? fields))
          ;; id is the PK and may not be in fields metadata
          (is (contains? fields :name)))
        (finally
          (sp/close storage))))))


(deftest current-enums-test
  (testing "current-enums returns set of enum names"
    (let [storage (setup/create-test-storage)]
      (try
        (let [enums (sp/current-enums storage)]
          (is (or (nil? enums) (set? enums) (sequential? enums))))
        (finally
          (sp/close storage))))))


(deftest current-enum-values-test
  (testing "current-enum-values returns nil for non-existent enum"
    (let [storage (setup/create-test-storage)]
      (try
        (let [values (sp/current-enum-values storage :non-existent-enum)]
          (is (nil? values)))
        (finally
          (sp/close storage)))))

  (testing "current-enum-values returns values for value-kind enum"
    (let [storage (setup/create-test-storage)]
      (try
        (let [values (sp/current-enum-values storage :value-kind)]
          (is (some? values))
          (is (or (set? values) (sequential? values))))
        (finally
          (sp/close storage))))))


(deftest schema-metadata-test
  (testing "schema-metadata returns cached metadata map"
    (let [storage (setup/create-test-storage)]
      (try
        (let [metadata (sp/schema-metadata storage)]
          (is (map? metadata))
          ;; Metadata has :entities key
          (is (contains? metadata :entities)))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; StorageErrorClassifier tests
;; =============================================================================

(deftest classify-error-test
  (testing "classify-error returns keyword for generic exception"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ex (Exception. "Generic error")
              classification (sp/classify-error storage ex)]
          (is (keyword? classification)))
        (finally
          (sp/close storage))))))


(deftest wrap-error-test
  (testing "wrap-error creates wrapped exception with context"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ex (Exception. "Original error")
              wrapped (sp/wrap-error storage ex :create {:entity :user})]
          (is (instance? clojure.lang.ExceptionInfo wrapped))
          (is (= :create (:operation (ex-data wrapped))))
          (is (= :user (:entity (ex-data wrapped)))))
        (finally
          (sp/close storage))))))
