(ns ^:integration graphden.executor.composition.interface-test
  "Tests for fn composition sync.

   ## 2-Entity Schema

   Composed functions use:
   - fn entity with parent-id set (inherits from parent base-fn)
   - arg entities with source-id set (inherits from parent's arg) + value/ref-id"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.tools.logging]
    [graphden.executor.composition.core :as core]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (pth/create-container-fixture #'*container*))


(use-fixtures :each
  (pth/create-clean-db-fixture #'*container*)
  exec/with-clean-registry)


(defn- create-test-storage
  "Creates a PostgreSQL storage from the current test container.
   Cleans the database and initializes schema before creating storage."
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))
        schema (gds/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    storage))


(deftest sync-fns-to-storage!-test
  (testing "syncs fn definitions to storage"
    (let [storage (create-test-storage)
          ;; First sync base-fns so we have parent fns
          _ (registry/initialize-all! storage
                                      [{:test-base-fn
                                        {:args {:a :int :b :int}
                                         :return-type :int
                                         :impl (fn [_ _] 42)}}
                                       {:other-base-fn
                                        {:args {:x :fn}
                                         :return-type :any
                                         :impl (fn [_ _] nil)}}])
          ;; Define fns
          fn-composition-data [{:name :my-fn
                                :parent :test-base-fn
                                :args {:a 1 :b 2}}
                               {:name :wrapper-fn
                                :parent :other-base-fn
                                :args {:x :my-fn}}]
          ;; Sync
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)]

      (testing "returns map of fn-name -> fn-id"
        (is (map? result))
        (is (= #{:my-fn :wrapper-fn} (set (keys result))))
        (is (uuid? (:my-fn result)))
        (is (uuid? (:wrapper-fn result))))

      (testing "creates fn entities in storage"
        (let [my-fn-id (:my-fn result)
              wrapper-fn-id (:wrapper-fn result)]
          (is (some? (sp/read-entity storage :fn my-fn-id)))
          (is (some? (sp/read-entity storage :fn wrapper-fn-id)))))))

  (testing "validates definitions"
    (let [storage (create-test-storage)]
      (testing "throws on missing :name"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :name"
              (fn-composition/sync-fns-to-storage! storage [{:parent :foo}]))))

      (testing "throws on missing :parent"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :parent"
              (fn-composition/sync-fns-to-storage! storage [{:name :foo}]))))

      (testing "throws on duplicate names"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
              (fn-composition/sync-fns-to-storage! storage
                                                   [{:name :foo :parent :bar}
                                                    {:name :foo :parent :baz}]))))))

  (testing "throws on unresolved parent"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :foo :parent :nonexistent}])))))

  (testing "handles empty definitions"
    (let [storage (create-test-storage)
          result (fn-composition/sync-fns-to-storage! storage [])]
      (is (= {} result)))))


(deftest topological-sort-test
  (testing "sorts by dependencies and warns on wrong order"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:base-a {:args {} :return-type :int :impl (fn [_ _] 1)}
                                        :base-b {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])
          ;; Wrong order: wrapper depends on target, but defined first
          fn-composition-data [{:name :wrapper :parent :base-b :args {:ref :target}}
                               {:name :target :parent :base-a}]
          ;; Capture log messages
          logged-messages (atom [])
          _ (with-redefs [clojure.tools.logging/log*
                          (fn [_logger level _throwable message]
                            (swap! logged-messages conj {:level level :message message}))]
              (fn-composition/sync-fns-to-storage! storage fn-composition-data))]

      (testing "logs warning about order"
        (is (= 1 (count @logged-messages)))
        (let [{:keys [level message]} (first @logged-messages)]
          (is (= :warn level))
          (is (str/includes? message "not in dependency order"))
          (is (str/includes? message "Suggested order")))))))


(deftest circular-dependency-test
  (testing "throws on circular dependencies"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])
          ;; A -> B -> A
          fn-composition-data [{:name :fn-a :parent :base-fn :args {:ref :fn-b}}
                               {:name :fn-b :parent :base-fn :args {:ref :fn-a}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (fn-composition/sync-fns-to-storage! storage fn-composition-data))))))


;; === Test internal parsing functions via core namespace ===

(deftest parse-fn-ref-test
  (testing "parses :fn-name as fn reference"
    (is (= :my-fn (#'core/parse-fn-ref :my-fn))))

  (testing "parses valid identifier keywords"
    (is (= :handler (#'core/parse-fn-ref :handler)))
    (is (= :my-fn-123 (#'core/parse-fn-ref :my-fn-123))))

  (testing "returns nil for non-fn refs"
    (is (nil? (#'core/parse-fn-ref "not-a-keyword")))
    (is (nil? (#'core/parse-fn-ref 123))))

  (testing "returns nil for invalid identifiers"
    (is (nil? (#'core/parse-fn-ref :>)))
    (is (nil? (#'core/parse-fn-ref :123-starts-with-digit)))))


;; === extract-dependencies edge case tests ===

(deftest extract-dependencies-test
  (testing "extracts dependencies from fn-def args"
    (let [fn-def {:name :my-fn
                  :parent :base
                  :args {:a :other-fn   ; fn ref
                         :b :third-fn   ; fn ref
                         :c 42}}        ; literal (ignored)
          fn-names #{:other-fn :third-fn}
          deps (#'core/extract-dependencies fn-def fn-names)]
      (is (= #{:other-fn :third-fn} deps))))

  (testing "ignores refs to fns not in the set"
    (let [fn-def {:name :my-fn
                  :parent :base
                  :args {:a :external-fn}}  ; not in fn-names set
          fn-names #{:other-fn}
          deps (#'core/extract-dependencies fn-def fn-names)]
      (is (empty? deps))))

  (testing "handles empty args"
    (let [fn-def {:name :my-fn :parent :base}
          deps (#'core/extract-dependencies fn-def #{})]
      (is (empty? deps)))))


;; === build-dependency-graph test ===

(deftest build-dependency-graph-test
  (testing "builds correct dependency graph"
    (let [fn-defs [{:name :a :parent :base :args {:x :b}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base}]
          graph (#'core/build-dependency-graph fn-defs)]
      (is (= {:a #{:b} :b #{:c} :c #{}} graph)))))


;; === topological-sort edge cases ===

(deftest topological-sort-edge-cases-test
  (testing "handles single element"
    (let [fn-defs [{:name :single :parent :base}]
          sorted (#'core/topological-sort fn-defs)]
      (is (= [:single] (mapv :name sorted)))))

  (testing "handles independent elements (no dependencies)"
    (let [fn-defs [{:name :a :parent :base}
                   {:name :b :parent :base}
                   {:name :c :parent :base}]
          sorted (#'core/topological-sort fn-defs)]
      ;; Order doesn't matter for independent elements
      (is (= #{:a :b :c} (set (mapv :name sorted))))))

  (testing "throws on self-reference cycle"
    (let [fn-defs [{:name :self-ref :parent :base :args {:x :self-ref}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (#'core/topological-sort fn-defs)))))

  (testing "throws on three-way cycle"
    (let [fn-defs [{:name :a :parent :base :args {:x :b}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base :args {:x :a}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (#'core/topological-sort fn-defs))))))


(deftest validation-edge-cases-test
  (testing "throws on non-keyword name"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
            (fn-composition/sync-fns-to-storage! storage [{:name "string-name" :parent :foo}])))))

  (testing "throws on non-map args"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:a :int} :return-type :int :impl (fn [_ _] 1)}}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be a map"
            (fn-composition/sync-fns-to-storage! storage [{:name :foo :parent :base-fn :args [1 2 3]}])))))

  (testing "throws on non-sequential fn-composition"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
            (fn-composition/sync-fns-to-storage! storage {:not "a vector"})))))

  (testing "throws on unresolved fn reference in args"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :foo :parent :base-fn :args {:ref :nonexistent-fn}}]))))))


;; === resolve-fn-id edge case tests ===

(deftest resolve-fn-id-from-storage-test
  (testing "resolves fn by name from storage when not in created-fns"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:base-fn {:args {:ref :fn}
                                                  :return-type :any
                                                  :impl (fn [_ _] nil)}}
                                       {:const {:args {:x :any}
                                                :return-type :int
                                                :impl (fn [_ _] 42)}}])
          ;; First, create an fn entity directly in storage
          const-fn-id (registry/fn-uuid :const)
          _ (sp/create-entity storage :fn
                              {:name "existing-fn"
                               :parent-id const-fn-id})
          ;; Now sync a new fn that references the existing one
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :wrapper-fn
                                                        :parent :base-fn
                                                        :args {:ref :existing-fn}}])]
      ;; Should succeed - existing-fn is resolved from storage
      (is (uuid? (:wrapper-fn result))))))


;; === arg entity tests for 2-entity schema ===

(deftest arg-creation-test
  (testing "creates arg entities with source-id inheritance"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:const-fn {:args {:x :any}
                                                   :return-type :any
                                                   :impl (fn [_ _] nil)}}])
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :my-fn :parent :const-fn :args {:x 42}}])
          fn-id (:my-fn result)
          ;; Get composed fn's args
          composed-args (sp/query-entities storage :arg {:fn-id fn-id})]
      ;; Should have one arg
      (is (= 1 (count composed-args)))
      (let [arg (first composed-args)]
        ;; Arg should have source-id set (inheriting from parent)
        (is (uuid? (:source-id arg)))
        ;; Arg should have value set
        (is (= 42 (:value arg))))))

  (testing "creates arg with ref-id for fn references"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:const {:args {:x :any}
                                                :return-type :fn
                                                :impl (fn [_ _] (fn [_] nil))}}
                                       {:use-fn {:args {:f :any}
                                                 :return-type :any
                                                 :impl (fn [_ _] {})}}])
          ;; Define fns with ref
          fn-composition-data [{:name :handler-fn
                                :parent :const
                                :args {:x {:status 200}}}
                               {:name :use-handler
                                :parent :use-fn
                                :args {:f :handler-fn}}]  ; fn reference (behavior determined by is-fn on parent arg)
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)
          handler-fn-id (:handler-fn result)
          use-handler-id (:use-handler result)
          ;; Get arg from use-handler
          args (sp/query-entities storage :arg {:fn-id use-handler-id})]
      (is (= 1 (count args)))
      (let [arg (first args)]
        ;; Should have ref-id set to handler-fn-id
        (is (= handler-fn-id (:ref-id arg)))))))


(deftest arg-update-on-value-change-test
  (testing "updates arg when value changes on re-sync"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:const-fn {:args {:x :any}
                                                   :return-type :any
                                                   :impl (fn [_ _] nil)}}])
          ;; First sync with value 42
          result1 (fn-composition/sync-fns-to-storage! storage
                                                       [{:name :my-fn :parent :const-fn :args {:x 42}}])
          fn-id (:my-fn result1)
          args-before (sp/query-entities storage :arg {:fn-id fn-id})
          arg-id-before (:id (first args-before))]
      ;; Verify initial value
      (is (= 42 (:value (first args-before))))

      ;; Re-sync with changed value 99
      (fn-composition/sync-fns-to-storage! storage
                                           [{:name :my-fn :parent :const-fn :args {:x 99}}])
      ;; arg should be updated
      (let [args-after (sp/query-entities storage :arg {:fn-id fn-id})]
        ;; Should still have exactly one arg
        (is (= 1 (count args-after)))
        ;; Same arg entity, but value updated
        (is (= arg-id-before (:id (first args-after))))
        (is (= 99 (:value (first args-after)))))))

  (testing "does not update arg when value is same"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:const-fn {:args {:x :any}
                                                   :return-type :any
                                                   :impl (fn [_ _] nil)}}])
          _ (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :my-fn :parent :const-fn :args {:x 42}}])
          fn-id (first (map :id (sp/query-entities storage :fn {:name "my-fn"})))
          args-before (sp/query-entities storage :arg {:fn-id fn-id})]
      ;; Re-sync with same value
      (fn-composition/sync-fns-to-storage! storage
                                           [{:name :my-fn :parent :const-fn :args {:x 42}}])
      (let [args-after (sp/query-entities storage :arg {:fn-id fn-id})]
        ;; arg should be the same
        (is (= (:id (first args-before)) (:id (first args-after))))
        (is (= 42 (:value (first args-after))))))))
