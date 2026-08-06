(ns ^:integration graphden.executor.composition.interface-test
  "Tests for fn composition sync against the slot/binding model.

   A composed fn carries `:parent-ids` and `:binding` rows that
   overlay the inherited slots; sync turns the EDN `:args` shape
   into binding/list-item rows."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.composition.parsing :as parsing]
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


(deftest sync-fns-to-storage-3-arity-accepts-ns-id-map
  (testing "3-arity variant threads ns-id-map through to core — exercised by system init-key"
    (let [storage (create-test-storage)]
      (try
        (exec/register-base-fn! :const-1 (fn [_ _] 1))
        (registry/sync-defs-to-storage! storage {:const-1 {:args {}
                                                           :return-type :int
                                                           :impl (fn [_ _] 1)}})
        (let [result (fn-composition/sync-fns-to-storage!
                       storage
                       [{:name :my-const :parent :const-1}]
                       {})]
          (is (map? result))
          (is (contains? result :my-const)))
        (finally
          (sp/close storage))))))


(deftest sync-fns-to-storage-4-arity-accepts-extra-name-id-map
  (testing "4-arity threads `extra-name->id` (pre-resolved refs from a sibling package)"
    (let [storage (create-test-storage)]
      (try
        (exec/register-base-fn! :_outer-const (fn [_ _] 1))
        (registry/sync-defs-to-storage! storage {:_outer-const {:args {}
                                                                :return-type :int
                                                                :impl (fn [_ _] 1)}})
        ;; Pre-resolve the parent ref into extra-name->id (as packages-loader
        ;; does for cross-package references). Empty `ns-id-map` + non-empty
        ;; extra-name->id is the wrapper variant exclusive to this arity.
        (let [outer-id (registry/fn-uuid :_outer-const)
              result (fn-composition/sync-fns-to-storage!
                       storage
                       [{:name :_uses-outer :parent :_outer-const}]
                       {}
                       {:_outer-const outer-id})]
          (is (map? result))
          (is (contains? result :_uses-outer)))
        (finally
          (sp/close storage))))))


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

      (testing "throws on duplicate names"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
              (fn-composition/sync-fns-to-storage! storage
                                                   [{:name :foo :parent :bar}
                                                    {:name :foo :parent :baz}]))))))

  ;; Note: parentless fn-defs without `:type` / `:refine` / `:list`
  ;; markers are no longer rejected — they sync as primitive fn-rows.
  ;; The 2-entity-era "must have :parent" rule is gone.

  (testing "throws on unresolved parent"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown parent"
            (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :foo :parent :nonexistent}])))))

  (testing "handles empty definitions"
    (let [storage (create-test-storage)
          result (fn-composition/sync-fns-to-storage! storage [])]
      (is (= {} result)))))


(deftest topological-sort-test
  (testing "sorts by dependencies even when fn-defs arrive out of order"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:base-a {:args {} :return-type :int :impl (fn [_ _] 1)}
                                        :base-b {:args {:ref :fn} :return-type :any :impl (fn [_ _] nil)}}])
          ;; Wrong order: wrapper depends on target but appears first.
          ;; Sync's topo-sort handles it transparently.
          fn-composition-data [{:name :wrapper :parent :base-b :args {:ref :target}}
                               {:name :target :parent :base-a}]
          result (fn-composition/sync-fns-to-storage! storage fn-composition-data)]
      (is (contains? result :wrapper))
      (is (contains? result :target)))))


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
    (is (= :my-fn (parsing/parse-fn-ref :my-fn))))

  (testing "parses valid identifier keywords"
    (is (= :handler (parsing/parse-fn-ref :handler)))
    (is (= :my-fn-123 (parsing/parse-fn-ref :my-fn-123))))

  (testing "returns nil for non-fn refs"
    (is (nil? (parsing/parse-fn-ref "not-a-keyword")))
    (is (nil? (parsing/parse-fn-ref 123))))

  (testing "returns nil for invalid identifiers"
    (is (nil? (parsing/parse-fn-ref :>)))
    (is (nil? (parsing/parse-fn-ref :123-starts-with-digit)))))


;; === extract-dependencies edge case tests ===

(deftest extract-dependencies-test
  (testing "extracts dependencies from fn-def args"
    (let [fn-def {:name :my-fn
                  :parent :base
                  :args {:a :other-fn   ; fn ref
                         :b :third-fn   ; fn ref
                         :c 42}}        ; literal (ignored)
          fn-names #{:other-fn :third-fn}
          deps (#'deps/extract-dependencies fn-def fn-names)]
      (is (= #{:other-fn :third-fn} deps))))

  (testing "ignores refs to fns not in the set"
    (let [fn-def {:name :my-fn
                  :parent :base
                  :args {:a :external-fn}}  ; not in fn-names set
          fn-names #{:other-fn}
          deps (#'deps/extract-dependencies fn-def fn-names)]
      (is (empty? deps))))

  (testing "handles empty args"
    (let [fn-def {:name :my-fn :parent :base}
          deps (#'deps/extract-dependencies fn-def #{})]
      (is (empty? deps)))))


;; === build-dependency-graph test ===

(deftest build-dependency-graph-test
  (testing "builds correct dependency graph"
    (let [fn-defs [{:name :a :parent :base :args {:x :b}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base}]
          graph (#'deps/build-dependency-graph fn-defs)]
      (is (= {:a #{:b} :b #{:c} :c #{}} graph)))))


;; === topological-sort edge cases ===

(deftest topological-sort-edge-cases-test
  (testing "handles single element"
    (let [fn-defs [{:name :single :parent :base}]
          sorted (deps/topological-sort fn-defs)]
      (is (= [:single] (mapv :name sorted)))))

  (testing "handles independent elements (no dependencies)"
    (let [fn-defs [{:name :a :parent :base}
                   {:name :b :parent :base}
                   {:name :c :parent :base}]
          sorted (deps/topological-sort fn-defs)]
      ;; Order doesn't matter for independent elements
      (is (= #{:a :b :c} (set (mapv :name sorted))))))

  (testing "throws on self-reference cycle"
    (let [fn-defs [{:name :self-ref :parent :base :args {:x :self-ref}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (deps/topological-sort fn-defs)))))

  (testing "throws on three-way cycle"
    (let [fn-defs [{:name :a :parent :base :args {:x :b}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base :args {:x :a}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
            (deps/topological-sort fn-defs))))))


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

  ;; "throws on unresolved fn reference in args" was removed — sync
  ;; now tolerates dangling :ref-fn-id targets so multi-package syncs
  ;; can land in any order; missing refs surface at call time.
  )


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
                               :parent-ids [const-fn-id]})
          ;; Now sync a new fn that references the existing one
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :wrapper-fn
                                                        :parent :base-fn
                                                        :args {:ref :existing-fn}}])]
      ;; Should succeed - existing-fn is resolved from storage
      (is (uuid? (:wrapper-fn result))))))


;; === arg entity tests for 2-entity schema ===

;; =============================================================================
;; Additional edge case tests for uncovered branches
;; =============================================================================

(deftest valid-identifier-edge-cases-test
  (testing "returns falsy for empty string"
    (is (not (parsing/valid-identifier? ""))))

  (testing "returns falsy for string with whitespace"
    (is (not (parsing/valid-identifier? "hello world"))))

  (testing "returns falsy for non-string input"
    (is (not (parsing/valid-identifier? nil)))
    (is (not (parsing/valid-identifier? 123))))

  (testing "returns truthy for valid identifiers"
    (is (parsing/valid-identifier? "hello"))
    (is (parsing/valid-identifier? "my-fn"))
    (is (parsing/valid-identifier? "_private"))
    (is (parsing/valid-identifier? "fn123"))))


(deftest extract-dependencies-with-parent-dep-test
  (testing "extracts parent as dependency when parent is in fn-names set"
    (let [fn-def {:name :child-fn
                  :parent :parent-fn  ; parent is another fn-def
                  :args {:a 1}}
          fn-names #{:parent-fn :child-fn}
          deps (#'deps/extract-dependencies fn-def fn-names)]
      (is (contains? deps :parent-fn)))))


;; `unresolved-arg-error-test` removed — binding a non-existent slot
;; name silently no-ops in the new model (sync doesn't track which
;; slot names a fn defines vs. which are caller free args).
