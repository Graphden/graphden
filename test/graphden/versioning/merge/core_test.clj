(ns ^:integration graphden.versioning.merge.core-test
  "Tests for merge protection with 2-entity schema.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)
   - arg-trait: assigns trait to arg (not to old arg-value entity)"
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
  "Creates a versioned storage with graph, versioned, and traits schemas.
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
  (testing "can add merge-protected trait to arg"
    (let [storage (create-test-storage)
          ;; Create base fn (parent-id=nil)
          base-fn (sp/create-entity storage :fn
                                    {:name "schema"
                                     :parent-id nil
                                     :return-type :int})
          ;; Create arg directly on fn
          arg (sp/create-entity storage :arg
                                {:fn-id (:id base-fn)
                                 :name "x"
                                 :type :int
                                 :value 42
                                 :required true})]
      (mp/add-merge-protection! storage (:id arg))
      (is (true? (mp/has-merge-protected-trait? storage (:id arg)))))))


(deftest add-merge-protection-idempotent-test
  (testing "adding protection twice is idempotent"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "schema"
                                     :parent-id nil
                                     :return-type :int})
          arg (sp/create-entity storage :arg
                                {:fn-id (:id base-fn)
                                 :name "x"
                                 :type :int
                                 :value 42
                                 :required true})]
      (mp/add-merge-protection! storage (:id arg))
      (mp/add-merge-protection! storage (:id arg))
      (is (true? (mp/has-merge-protected-trait? storage (:id arg))))
      ;; Should have only one arg-trait record
      (let [base (vs/unwrap storage)
            traits (sp/query-entities base :arg-trait
                                      {:arg-id (:id arg)
                                       :trait-id vts/merge-protected-trait-uuid})]
        (is (= 1 (count traits)))))))


(deftest remove-merge-protection-test
  (testing "can remove merge-protected trait"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "schema"
                                     :parent-id nil
                                     :return-type :int})
          arg (sp/create-entity storage :arg
                                {:fn-id (:id base-fn)
                                 :name "x"
                                 :type :int
                                 :value 42
                                 :required true})]
      (mp/add-merge-protection! storage (:id arg))
      (is (true? (mp/has-merge-protected-trait? storage (:id arg))))
      (mp/remove-merge-protection! storage (:id arg))
      (is (false? (mp/has-merge-protected-trait? storage (:id arg)))))))


;; === Merge Protection Detection ===

(deftest detect-no-protected-transfers-test
  (testing "detect-protected-transfers returns empty when no protected args"
    (let [storage (create-test-storage)
          ;; Create base fn and composed fn on main
          base-fn (sp/create-entity storage :fn
                                    {:name "base"
                                     :parent-id nil
                                     :return-type :int})
          main-fn (sp/create-entity storage :fn
                                    {:name "main-fn"
                                     :parent-id (:id base-fn)})
          _main-arg (sp/create-entity storage :arg
                                      {:fn-id (:id main-fn)
                                       :name "x"
                                       :type :int
                                       :value 42})
          ;; Create feature branch with new fn
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          feat-fn (sp/create-entity feature :fn
                                    {:name "feat-fn"
                                     :parent-id (:id base-fn)})
          _feat-arg (sp/create-entity feature :arg
                                      {:fn-id (:id feat-fn)
                                       :name "y"
                                       :type :int
                                       :value 99})
          {:keys [protected-transfers blocked?]}
          (mp/detect-protected-transfers storage (:id branch))]
      (is (empty? protected-transfers))
      (is (false? blocked?)))))


(deftest detect-protected-transfer-arg-test
  (testing "detects protected arg in transfer"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base"
                                     :parent-id nil
                                     :return-type :int})
          ;; Create feature branch
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          ;; Create protected arg on feature branch
          feat-fn (sp/create-entity feature :fn
                                    {:name "feat-fn"
                                     :parent-id (:id base-fn)})
          secret-arg (sp/create-entity feature :arg
                                       {:fn-id (:id feat-fn)
                                        :name "password"
                                        :type :text
                                        :value "secret"})]
      ;; Mark arg as protected
      (mp/add-merge-protection! feature (:id secret-arg))

      (let [{:keys [protected-transfers blocked?]}
            (mp/detect-protected-transfers storage (:id branch))]
        (is (seq protected-transfers))
        (is (true? blocked?))
        (is (= (:id secret-arg) (:arg-id (first protected-transfers))))
        (is (= :arg (:entity-type (first protected-transfers))))))))


(deftest validate-merge-throws-test
  (testing "validate-merge! throws when protected args would transfer"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base"
                                     :parent-id nil
                                     :return-type :int})
          ;; Create feature branch with protected arg
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          feat-fn (sp/create-entity feature :fn
                                    {:name "feat-fn"
                                     :parent-id (:id base-fn)})
          arg (sp/create-entity feature :arg
                                {:fn-id (:id feat-fn)
                                 :name "creds"
                                 :type :text
                                 :value "prod-creds"})]
      (mp/add-merge-protection! feature (:id arg))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Merge blocked: protected args would be transferred"
            (mp/validate-merge! storage (:id branch)))))))


;; === Safe Merge ===

(deftest safe-merge-blocks-protected-test
  (testing "safe-merge-branch! blocks merge with protected args"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base"
                                     :parent-id nil
                                     :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          feat-fn (sp/create-entity feature :fn
                                    {:name "feat-fn"
                                     :parent-id (:id base-fn)})
          arg (sp/create-entity feature :arg
                                {:fn-id (:id feat-fn)
                                 :name "secret"
                                 :type :text
                                 :value "secret"})]
      (mp/add-merge-protection! feature (:id arg))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"protected args would be transferred"
            (mp/safe-merge-branch! storage (:id branch)))))))


(deftest safe-merge-skip-protection-test
  (testing "safe-merge-branch! with skip-protection-check allows merge"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base"
                                     :parent-id nil
                                     :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          feat-fn (sp/create-entity feature :fn
                                    {:name "feat-fn"
                                     :parent-id (:id base-fn)})
          arg (sp/create-entity feature :arg
                                {:fn-id (:id feat-fn)
                                 :name "secret"
                                 :type :text
                                 :value "secret"})]
      (mp/add-merge-protection! feature (:id arg))

      ;; With skip-protection-check, merge should succeed
      (let [merge-rec (mp/safe-merge-branch! storage (:id branch)
                                             {:skip-protection-check true})]
        (is (some? merge-rec))
        (is (uuid? (:id merge-rec)))))))


(deftest safe-merge-allows-unprotected-test
  (testing "safe-merge-branch! allows merge without protected args"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base"
                                     :parent-id nil
                                     :return-type :int})
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          feat-fn (sp/create-entity feature :fn
                                    {:name "feat-fn"
                                     :parent-id (:id base-fn)})
          _arg (sp/create-entity feature :arg
                                 {:fn-id (:id feat-fn)
                                  :name "normal"
                                  :type :text
                                  :value "normal-value"})
          ;; No protection added - merge should succeed
          merge-rec (mp/safe-merge-branch! storage (:id branch))]
      (is (some? merge-rec))
      (is (uuid? (:id merge-rec)))
      ;; Entity now visible on main
      (is (some? (sp/read-entity storage :fn (:id feat-fn)))))))


;; === Edge Cases ===

(deftest protected-arg-on-target-not-blocked-test
  (testing "protected arg already on target doesn't block merge"
    (let [storage (create-test-storage)
          base-fn (sp/create-entity storage :fn
                                    {:name "base"
                                     :parent-id nil
                                     :return-type :int})
          ;; Create protected arg on main
          main-fn (sp/create-entity storage :fn
                                    {:name "main-fn"
                                     :parent-id (:id base-fn)})
          protected-arg (sp/create-entity storage :arg
                                          {:fn-id (:id main-fn)
                                           :name "main-secret"
                                           :type :text
                                           :value "main-secret"})
          _ (mp/add-merge-protection! storage (:id protected-arg))
          ;; Create feature branch with unrelated entity
          branch (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id branch))
          feat-fn (sp/create-entity feature :fn
                                    {:name "feat-fn"
                                     :parent-id (:id base-fn)})
          _feat-arg (sp/create-entity feature :arg
                                      {:fn-id (:id feat-fn)
                                       :name "feat-value"
                                       :type :text
                                       :value "feat-value"})
          ;; Merge should succeed - protected arg stays on main
          merge-rec (mp/safe-merge-branch! storage (:id branch))]
      (is (some? merge-rec)))))
