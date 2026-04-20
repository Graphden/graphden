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
    [graphden.executor.test-setup :as setup]
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


(defn- resolve-arg-name
  "Resolves arg name by following source-id chain.
   Returns the name from the first arg in chain that has a non-nil name."
  [storage arg]
  (if-let [arg-name (:name arg)]
    arg-name
    (when-let [source-id (:source-id arg)]
      ;; Use query-entities instead of read-entities for versioned storage compatibility
      (let [source-args (sp/query-entities storage :arg {:id source-id})
            source-arg (first source-args)]
        (when source-arg
          (recur storage source-arg))))))


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
                               :parent-ids [const-fn-id]})
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


;; =============================================================================
;; Additional edge case tests for uncovered branches
;; =============================================================================

(deftest valid-identifier-edge-cases-test
  (testing "returns falsy for empty string"
    (is (not (#'core/valid-identifier? ""))))

  (testing "returns falsy for string with whitespace"
    (is (not (#'core/valid-identifier? "hello world"))))

  (testing "returns falsy for non-string input"
    (is (not (#'core/valid-identifier? nil)))
    (is (not (#'core/valid-identifier? 123))))

  (testing "returns truthy for valid identifiers"
    (is (#'core/valid-identifier? "hello"))
    (is (#'core/valid-identifier? "my-fn"))
    (is (#'core/valid-identifier? "_private"))
    (is (#'core/valid-identifier? "fn123"))))


(deftest extract-dependencies-with-parent-dep-test
  (testing "extracts parent as dependency when parent is in fn-names set"
    (let [fn-def {:name :child-fn
                  :parent :parent-fn  ; parent is another fn-def
                  :args {:a 1}}
          fn-names #{:parent-fn :child-fn}
          deps (#'core/extract-dependencies fn-def fn-names)]
      (is (contains? deps :parent-fn)))))


(deftest arg-with-nil-value-test
  (testing "handles nil arg value correctly"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:nullable-fn {:args {:x {:type :any :required false}}
                                                      :return-type :any
                                                      :impl (fn [_ _] nil)}}])
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :my-fn :parent :nullable-fn :args {:x nil}}])
          fn-id (:my-fn result)
          args (sp/query-entities storage :arg {:fn-id fn-id})]
      (is (= 1 (count args)))
      (is (nil? (:value (first args))))
      (is (nil? (:ref-id (first args)))))))


(deftest arg-with-uuid-value-test
  (testing "handles UUID arg value as ref-id"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:ref-fn {:args {:ref :fn}
                                                 :return-type :any
                                                 :impl (fn [_ _] nil)}}])
          ;; Create a target fn to reference
          target-fn-id (random-uuid)
          _ (sp/create-entity storage :fn {:id target-fn-id :name "target-fn" :parent-ids [(registry/fn-uuid :ref-fn)]})
          ;; Sync with UUID directly
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :wrapper :parent :ref-fn :args {:ref target-fn-id}}])
          fn-id (:wrapper result)
          args (sp/query-entities storage :arg {:fn-id fn-id})]
      (is (= 1 (count args)))
      (is (nil? (:value (first args))))
      (is (= target-fn-id (:ref-id (first args)))))))


(deftest free-arg-propagation-test
  (testing "propagates free args from parent to child"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:two-arg-fn {:args {:a :int :b :int}
                                                     :return-type :int
                                                     :impl (setup/fn-impl [a b] (+ a b))}}])
          ;; Sync partial-fn that only binds :a, leaving :b free
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :partial-fn :parent :two-arg-fn :args {:a 10}}])
          fn-id (:partial-fn result)
          args (sp/query-entities storage :arg {:fn-id fn-id})]
      ;; Should have 2 args: :a with value 10, and :b as free arg (propagated)
      ;; Names are resolved via source-id chain (not stored directly on composed fn args)
      (is (= 2 (count args)))
      (let [a-arg (first (filter #(= "a" (resolve-arg-name storage %)) args))
            b-arg (first (filter #(= "b" (resolve-arg-name storage %)) args))]
        (is (= 10 (:value a-arg)))
        (is (nil? (:value b-arg)))
        (is (nil? (:ref-id b-arg)) "free arg should have no ref-id")))))


(deftest unresolved-arg-error-test
  (testing "throws on unresolved arg name"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:one-arg-fn {:args {:x :int}
                                                     :return-type :int
                                                     :impl (fn [_ _] 1)}}])]
      ;; Try to bind non-existent arg :y
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not found"
            (fn-composition/sync-fns-to-storage! storage
                                                 [{:name :bad-fn :parent :one-arg-fn :args {:y 42}}]))))))


;; =============================================================================
;; Free args collection from ref-id chains tests
;; =============================================================================

(deftest collect-free-args-from-ref-chain-test
  (testing "collects free args from fns referenced via ref-id"
    (let [storage (create-test-storage)
          ;; Base fns:
          ;; - adder: takes two ints, returns int
          ;; - caller: takes a fn reference and an int, calls the fn
          _ (registry/initialize-all! storage
                                      [{:adder {:args {:a :int :b :int}
                                                :return-type :int
                                                :impl (setup/fn-impl [a b] (+ a b))}}
                                       {:caller {:args {:f :fn :x :int}
                                                 :return-type :int
                                                 :impl (setup/fn-impl [f x]
                                                                      ;; `f` arrives pre-wrapped as a single-arg
                                                                      ;; callable; feeding `x` routes it to the
                                                                      ;; target's single free slot.
                                                                      (f x))}}])
          ;; Composed fns:
          ;; - add-10: partial application of adder with a=10, b is free
          ;; - call-add-10: calls add-10 passing :x as :b
          ;; The free arg :b from add-10 should propagate through the ref chain
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :add-10
                                                        :parent :adder
                                                        :args {:a 10}}
                                                       {:name :call-add-10
                                                        :parent :caller
                                                        :args {:f :add-10 :x 5}}])
          add-10-id (:add-10 result)
          call-add-10-id (:call-add-10 result)]

      ;; Verify add-10 has free arg :b (name resolved via source-id chain)
      (let [add-10-args (sp/query-entities storage :arg {:fn-id add-10-id})
            b-arg (first (filter #(= "b" (resolve-arg-name storage %)) add-10-args))]
        (is (some? b-arg) "add-10 should have :b arg")
        (is (nil? (:value b-arg)) ":b should be free (no value)")
        (is (nil? (:ref-id b-arg)) ":b should be free (no ref-id)"))

      ;; Verify call-add-10 has args for :f and :x
      (let [call-args (sp/query-entities storage :arg {:fn-id call-add-10-id})]
        ;; Should have :f (ref to add-10) and :x (value 5)
        (is (>= (count call-args) 2))))))


(deftest nested-ref-chain-free-args-test
  (testing "collects free args from deeply nested ref chains"
    (let [storage (create-test-storage)
          ;; Create a chain: wrapper -> middle -> inner
          ;; inner has a free arg that should propagate through
          _ (registry/initialize-all! storage
                                      [{:three-arg {:args {:a :int :b :int :c :int}
                                                    :return-type :int
                                                    :impl (setup/fn-impl [a b c] (+ a b c))}}
                                       {:ref-holder {:args {:fn-ref :fn}
                                                     :return-type :any
                                                     :impl (fn [{:keys [fn-ref]} _] fn-ref)}}])
          ;; inner: binds :a, leaves :b and :c free
          ;; middle: wraps inner via ref-holder
          ;; outer: wraps middle via ref-holder
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :inner
                                                        :parent :three-arg
                                                        :args {:a 1}}
                                                       {:name :middle
                                                        :parent :ref-holder
                                                        :args {:fn-ref :inner}}
                                                       {:name :outer
                                                        :parent :ref-holder
                                                        :args {:fn-ref :middle}}])
          inner-id (:inner result)
          ;; inner should have 3 args: :a (bound), :b (free), :c (free)
          inner-args (sp/query-entities storage :arg {:fn-id inner-id})
          free-args (filter #(and (nil? (:value %)) (nil? (:ref-id %))) inner-args)]
      (is (= 2 (count free-args)) "inner should have 2 free args (:b and :c)"))))


(deftest fn-name-cache-lookup-test
  (testing "resolves fn by name from cache for existing base fns"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:cached-fn {:args {:x :int}
                                                    :return-type :int
                                                    :impl (fn [_ _] 1)}}
                                       {:ref-base {:args {:ref :fn}
                                                   :return-type :any
                                                   :impl (fn [_ _] nil)}}])
          ;; Sync fn that references a base fn by name
          ;; This exercises the fn-name-cache lookup path
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :user-of-cached
                                                        :parent :ref-base
                                                        :args {:ref :cached-fn}}])
          user-id (:user-of-cached result)
          args (sp/query-entities storage :arg {:fn-id user-id})]

      (is (= 1 (count args)))
      (let [ref-arg (first args)]
        ;; Should have ref-id pointing to cached-fn
        (is (uuid? (:ref-id ref-arg)))
        (is (= (registry/fn-uuid :cached-fn) (:ref-id ref-arg)))))))


;; =============================================================================
;; Arg rename (:as) syntax tests
;; =============================================================================

(deftest arg-rename-with-as-syntax-test
  (testing "renames arg using {:as :new-name}"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:two-arg-fn {:args {:a :int :b :int}
                                                     :return-type :int
                                                     :impl (setup/fn-impl [a b] (+ a b))}}])
          ;; Rename :a to :first and :b to :second without binding values
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :renamed-fn
                                                        :parent :two-arg-fn
                                                        :args {:a {:as :first}
                                                               :b {:as :second}}}])
          fn-id (:renamed-fn result)
          args (sp/query-entities storage :arg {:fn-id fn-id})]
      ;; Should have 2 args with the new names
      (is (= 2 (count args)))
      (let [arg-names (set (map :name args))]
        (is (contains? arg-names "first") "Should have arg named 'first'")
        (is (contains? arg-names "second") "Should have arg named 'second'")
        (is (not (contains? arg-names "a")) "Should NOT have arg named 'a'")
        (is (not (contains? arg-names "b")) "Should NOT have arg named 'b'"))
      ;; All args should be free (no value or ref-id)
      (doseq [arg args]
        (is (nil? (:value arg)) "Renamed arg should have no value")
        (is (nil? (:ref-id arg)) "Renamed arg should have no ref-id"))))

  (testing "renames arg and binds value with {:as :new-name :value x}"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:two-arg-fn {:args {:a :int :b :int}
                                                     :return-type :int
                                                     :impl (setup/fn-impl [a b] (+ a b))}}])
          ;; Rename :a to :first and bind value 42
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :renamed-with-value
                                                        :parent :two-arg-fn
                                                        :args {:a {:as :first :value 42}
                                                               :b {:as :second}}}])
          fn-id (:renamed-with-value result)
          args (sp/query-entities storage :arg {:fn-id fn-id})
          first-arg (first (filter #(= "first" (:name %)) args))]
      (is (some? first-arg) "Should have arg named 'first'")
      (is (= 42 (:value first-arg)) "First arg should have value 42")))

  (testing "renames arg and binds ref with {:as :new-name :ref :fn-name}"
    (let [storage (create-test-storage)
          _ (registry/initialize-all! storage
                                      [{:const {:args {:x :any}
                                                :return-type :any
                                                :impl (fn [_ _] nil)}}
                                       {:ref-fn {:args {:f :fn}
                                                 :return-type :any
                                                 :impl (fn [_ _] nil)}}])
          ;; Create a target fn, then create fn with renamed ref
          result (fn-composition/sync-fns-to-storage! storage
                                                      [{:name :target-fn
                                                        :parent :const
                                                        :args {:x 100}}
                                                       {:name :ref-with-rename
                                                        :parent :ref-fn
                                                        :args {:f {:as :handler :ref :target-fn}}}])
          fn-id (:ref-with-rename result)
          target-fn-id (:target-fn result)
          args (sp/query-entities storage :arg {:fn-id fn-id})
          handler-arg (first (filter #(= "handler" (:name %)) args))]
      (is (some? handler-arg) "Should have arg named 'handler'")
      (is (= target-fn-id (:ref-id handler-arg)) "Handler arg should ref target-fn"))))
