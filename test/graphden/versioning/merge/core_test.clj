(ns graphden.versioning.merge.core-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.merge.core :as mp]
    [graphden.versioning.storage.core :as vs]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates a versioned storage with both versioned-data-schema and value-traits-schema.
   Cleans the database before creating storage to ensure test isolation."
  []
  (th/clean-database-fast! *container*)
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vds/extend-builder)
                   (vts/extend-builder)
                   (ds/build))
        base (-> (pg/create-storage (th/get-container-config *container*))
                 (sp/initialize-with-cleanup! schema))]
    (vts/seed-traits! base)
    (vs/wrap-with-versioning base)))


;; === Basic Trait Operations ===

(deftest add-merge-protection-test
  (testing "can add merge-protected trait to arg-value"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          av (sp/create-entity storage :arg-value
                               {:arg-schema-id (:id as) :value 42})]
      (mp/add-merge-protection! storage (:id av))
      (is (true? (mp/has-merge-protected-trait? storage (:id av)))))))


(deftest add-merge-protection-idempotent-test
  (testing "adding protection twice is idempotent"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          av (sp/create-entity storage :arg-value
                               {:arg-schema-id (:id as) :value 42})]
      (mp/add-merge-protection! storage (:id av))
      (mp/add-merge-protection! storage (:id av))
      (is (true? (mp/has-merge-protected-trait? storage (:id av))))
      ;; Should have only one value-trait record
      (let [base (vs/unwrap storage)
            traits (sp/query-entities base :value-trait
                                      {:arg-value-id (:id av)
                                       :trait-id vts/merge-protected-trait-uuid})]
        (is (= 1 (count traits)))))))


(deftest remove-merge-protection-test
  (testing "can remove merge-protected trait"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          av (sp/create-entity storage :arg-value
                               {:arg-schema-id (:id as) :value 42})]
      (mp/add-merge-protection! storage (:id av))
      (is (true? (mp/has-merge-protected-trait? storage (:id av))))
      (mp/remove-merge-protection! storage (:id av))
      (is (false? (mp/has-merge-protected-trait? storage (:id av)))))))


;; === Merge Protection Detection ===

(deftest detect-no-protected-transfers-test
  (testing "detect-protected-transfers returns empty when no protected values"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "main-fn" :fn-schema-id (:id fs)})
          av (sp/create-entity storage :arg-value
                               {:arg-schema-id (:id as) :value 42})
          _ (sp/create-entity storage :fn-arg
                              {:fn-id (:id fn-rec) :arg-schema-id (:id as)
                               :arg-value-id (:id av)})
          ;; Create feature branch with new fn-arg
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          av2 (sp/create-entity feature :arg-value
                                {:arg-schema-id (:id as) :value 99})
          fn-rec2 (sp/create-entity feature :fn
                                    {:name "feat-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity feature :fn-arg
                              {:fn-id (:id fn-rec2) :arg-schema-id (:id as)
                               :arg-value-id (:id av2)})
          {:keys [protected-transfers blocked?]}
          (mp/detect-protected-transfers storage (:id branch))]
      (is (empty? protected-transfers))
      (is (false? blocked?)))))


(deftest detect-protected-transfer-fn-arg-test
  (testing "detects protected arg-value in fn-arg transfer"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          ;; Create feature branch
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          ;; Create protected arg-value on feature branch
          av-secret (sp/create-entity feature :arg-value
                                      {:arg-schema-id (:id as) :value "secret"})
          fn-rec (sp/create-entity feature :fn
                                   {:name "feat-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity feature :fn-arg
                              {:fn-id (:id fn-rec) :arg-schema-id (:id as)
                               :arg-value-id (:id av-secret)})]
      ;; Mark arg-value as protected
      (mp/add-merge-protection! feature (:id av-secret))

      (let [{:keys [protected-transfers blocked?]}
            (mp/detect-protected-transfers storage (:id branch))]
        (is (seq protected-transfers))
        (is (true? blocked?))
        (is (= (:id av-secret) (:arg-value-id (first protected-transfers))))
        (is (= :fn-arg (:entity-type (first protected-transfers))))))))


(deftest validate-merge-throws-test
  (testing "validate-merge! throws when protected values would transfer"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          ;; Create feature branch with protected value
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          av (sp/create-entity feature :arg-value
                               {:arg-schema-id (:id as) :value "prod-creds"})
          fn-rec (sp/create-entity feature :fn
                                   {:name "feat-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity feature :fn-arg
                              {:fn-id (:id fn-rec) :arg-schema-id (:id as)
                               :arg-value-id (:id av)})]
      (mp/add-merge-protection! feature (:id av))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Merge blocked: protected arg-values would be transferred"
            (mp/validate-merge! storage (:id branch)))))))


;; === Safe Merge ===

(deftest safe-merge-blocks-protected-test
  (testing "safe-merge-branch! blocks merge with protected values"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          av (sp/create-entity feature :arg-value
                               {:arg-schema-id (:id as) :value "secret"})
          fn-rec (sp/create-entity feature :fn
                                   {:name "feat-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity feature :fn-arg
                              {:fn-id (:id fn-rec) :arg-schema-id (:id as)
                               :arg-value-id (:id av)})]
      (mp/add-merge-protection! feature (:id av))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"protected arg-values would be transferred"
            (mp/safe-merge-branch! storage (:id branch)))))))


(deftest safe-merge-skip-protection-test
  (testing "safe-merge-branch! with skip-protection-check allows merge"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          av (sp/create-entity feature :arg-value
                               {:arg-schema-id (:id as) :value "secret"})
          fn-rec (sp/create-entity feature :fn
                                   {:name "feat-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity feature :fn-arg
                              {:fn-id (:id fn-rec) :arg-schema-id (:id as)
                               :arg-value-id (:id av)})]
      (mp/add-merge-protection! feature (:id av))

      ;; With skip-protection-check, merge should succeed
      (let [merge-rec (mp/safe-merge-branch! storage (:id branch)
                                             {:skip-protection-check true})]
        (is (some? merge-rec))
        (is (uuid? (:id merge-rec)))))))


(deftest safe-merge-allows-unprotected-test
  (testing "safe-merge-branch! allows merge without protected values"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          av (sp/create-entity feature :arg-value
                               {:arg-schema-id (:id as) :value "normal-value"})
          fn-rec (sp/create-entity feature :fn
                                   {:name "feat-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity feature :fn-arg
                              {:fn-id (:id fn-rec) :arg-schema-id (:id as)
                               :arg-value-id (:id av)})
          ;; No protection added - merge should succeed
          merge-rec (mp/safe-merge-branch! storage (:id branch))]
      (is (some? merge-rec))
      (is (uuid? (:id merge-rec)))
      ;; Entity now visible on main
      (is (some? (sp/read-entity storage :fn (:id fn-rec)))))))


;; === Edge Cases ===

(deftest protected-value-on-target-not-blocked-test
  (testing "protected value already on target doesn't block merge"
    (let [storage (create-test-storage)
          fs (sp/create-entity storage :fn-schema
                               {:name "schema" :returned-type :int})
          as (sp/create-entity storage :arg-schema
                               {:fn-schema-id (:id fs) :name "x" :type :int :required true :first-class false})
          ;; Create protected value on main
          av-protected (sp/create-entity storage :arg-value
                                         {:arg-schema-id (:id as) :value "main-secret"})
          fn-main (sp/create-entity storage :fn
                                    {:name "main-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity storage :fn-arg
                              {:fn-id (:id fn-main) :arg-schema-id (:id as)
                               :arg-value-id (:id av-protected)})
          _ (mp/add-merge-protection! storage (:id av-protected))
          ;; Create feature branch with unrelated entity
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          av2 (sp/create-entity feature :arg-value
                                {:arg-schema-id (:id as) :value "feat-value"})
          fn-feat (sp/create-entity feature :fn
                                    {:name "feat-fn" :fn-schema-id (:id fs)})
          _ (sp/create-entity feature :fn-arg
                              {:fn-id (:id fn-feat) :arg-schema-id (:id as)
                               :arg-value-id (:id av2)})
          ;; Merge should succeed - protected value stays on main
          merge-rec (mp/safe-merge-branch! storage (:id branch))]
      (is (some? merge-rec)))))
