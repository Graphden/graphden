(ns graphden.executor.registry.core-test
  "Tests for executor.registry.core - base function sync infrastructure."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as core]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; uuid-v5 tests (private function)
;; =============================================================================

(deftest uuid-v5-test
  (testing "generates deterministic UUIDs"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid1 (#'core/uuid-v5 ns-uuid "test-name")
          uuid2 (#'core/uuid-v5 ns-uuid "test-name")]
      (is (= uuid1 uuid2) "Same inputs should produce same UUID")))

  (testing "different names produce different UUIDs"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid1 (#'core/uuid-v5 ns-uuid "name-a")
          uuid2 (#'core/uuid-v5 ns-uuid "name-b")]
      (is (not= uuid1 uuid2))))

  (testing "throws for non-UUID namespace"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 "not-a-uuid" "test"))))

  (testing "throws for non-string name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"name-str must be a string"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" 123))))

  (testing "throws for blank name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"name-str must not be blank"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" "")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"name-str must not be blank"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" "   "))))

  (testing "throws for name exceeding max length"
    (let [long-name (str/join (repeat 300 "x"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" long-name)))))

  (testing "throws for name containing null bytes"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains null bytes"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" "test\u0000name")))))


;; =============================================================================
;; validate-identifier! tests (private function)
;; =============================================================================

(deftest validate-identifier!-test
  (testing "accepts valid identifiers"
    (is (nil? (#'core/validate-identifier! "fn-name" :my-fn)))
    (is (nil? (#'core/validate-identifier! "fn-name" :my_fn)))
    (is (nil? (#'core/validate-identifier! "fn-name" :MyFn)))
    (is (nil? (#'core/validate-identifier! "fn-name" :_private)))
    (is (nil? (#'core/validate-identifier! "fn-name" :empty?)))
    (is (nil? (#'core/validate-identifier! "fn-name" "string-name"))))

  (testing "throws for empty identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"cannot be empty"
          (#'core/validate-identifier! "fn-name" "")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"cannot be empty"
          (#'core/validate-identifier! "fn-name" nil))))

  (testing "throws for identifier exceeding max length"
    (let [long-name (keyword (str/join (repeat 130 "x")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (#'core/validate-identifier! "fn-name" long-name)))))

  (testing "throws for invalid characters (dots, starts with number)"
    ;; Dots are not allowed
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid characters"
          (#'core/validate-identifier! "fn-name" :has.dot)))
    ;; Starting with number not allowed
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"contains invalid characters"
          (#'core/validate-identifier! "fn-name" :123start))))

  (testing "accepts valid identifiers"
    ;; Valid: letters, hyphens, underscores
    (is (nil? (#'core/validate-identifier! "fn-name" :valid-name)))
    (is (nil? (#'core/validate-identifier! "fn-name" :valid_name)))
    (is (nil? (#'core/validate-identifier! "fn-name" :_starts-with-underscore)))
    ;; Namespaced keyword: only name part is used
    ;; :has/slash -> "slash" which is valid
    (is (nil? (#'core/validate-identifier! "fn-name" :has/slash)))))


;; =============================================================================
;; parse-arg-spec tests (private function)
;; =============================================================================

(deftest parse-arg-spec-test
  (testing "parses keyword type as required"
    (let [result (#'core/parse-arg-spec :arg1 :int)]
      (is (= :int (:arg-type result)))
      (is (true? (:required result)))))

  (testing "parses map with type and required"
    (let [result (#'core/parse-arg-spec :arg1 {:type :text :required false})]
      (is (= :text (:arg-type result)))
      (is (false? (:required result)))))

  (testing "defaults required to true in map"
    (let [result (#'core/parse-arg-spec :arg1 {:type :bool})]
      (is (= :bool (:arg-type result)))
      (is (true? (:required result)))))

  (testing "throws for unknown type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown arg type"
          (#'core/parse-arg-spec :arg1 :unknown-type))))

  (testing "throws for map without :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must contain :type key"
          (#'core/parse-arg-spec :arg1 {:required true}))))

  (testing "throws for non-boolean :required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":required must be a boolean"
          (#'core/parse-arg-spec :arg1 {:type :int :required "yes"}))))

  (testing "throws for invalid arg-spec type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :arg1 123)))))


;; =============================================================================
;; validate-fn-def! tests
;; =============================================================================

(deftest validate-fn-def!-test
  (testing "accepts valid function definition"
    (is (nil? (core/validate-fn-def! :my-fn {:args {:a :int :b :text}
                                             :return-type :bool}))))

  (testing "throws for non-keyword fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"fn-name must be a keyword"
          (core/validate-fn-def! "my-fn" {:return-type :int}))))

  (testing "throws when return-type is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {:a :int}}))))

  (testing "throws for unknown return type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown return type"
          (core/validate-fn-def! :my-fn {:return-type :unknown-type}))))

  (testing "throws for invalid arg in args"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown arg type"
          (core/validate-fn-def! :my-fn {:args {:bad-arg :invalid-type}
                                         :return-type :int})))))


;; =============================================================================
;; validate-all-defs! tests
;; =============================================================================

(deftest validate-all-defs!-test
  (testing "accepts valid definitions"
    (is (nil? (core/validate-all-defs!
                {:fn1 {:args {:a :int} :return-type :int}
                 :fn2 {:args {:b :text} :return-type :text}}))))

  (testing "throws on first invalid definition"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown return type"
          (core/validate-all-defs!
            {:good-fn {:args {} :return-type :int}
             :bad-fn {:args {} :return-type :invalid}})))))


;; =============================================================================
;; compute-impl-hash tests
;; =============================================================================

(deftest compute-impl-hash-test
  (testing "returns 64-character hex string"
    (let [impl-hash (core/compute-impl-hash {:args {:a :int} :return-type :int})]
      (is (string? impl-hash))
      (is (= 64 (count impl-hash)))
      (is (re-matches #"[0-9a-f]{64}" impl-hash))))

  (testing "same inputs produce same hash"
    (let [fn-def {:args {:a :int :b :text} :return-type :bool}
          hash1 (core/compute-impl-hash fn-def)
          hash2 (core/compute-impl-hash fn-def)]
      (is (= hash1 hash2))))

  (testing "different args produce different hash"
    (let [hash1 (core/compute-impl-hash {:args {:a :int} :return-type :int})
          hash2 (core/compute-impl-hash {:args {:b :int} :return-type :int})]
      (is (not= hash1 hash2))))

  (testing "different return-type produces different hash"
    (let [hash1 (core/compute-impl-hash {:args {:a :int} :return-type :int})
          hash2 (core/compute-impl-hash {:args {:a :int} :return-type :text})]
      (is (not= hash1 hash2))))

  (testing "arg order in map doesn't affect hash (sorted)"
    (let [hash1 (core/compute-impl-hash {:args {:a :int :b :text} :return-type :int})
          hash2 (core/compute-impl-hash {:args {:b :text :a :int} :return-type :int})]
      (is (= hash1 hash2))))

  (testing "impl-source affects hash"
    (let [hash1 (core/compute-impl-hash {:args {:a :int}
                                         :return-type :int
                                         :impl-source '[(+ a 1)]})
          hash2 (core/compute-impl-hash {:args {:a :int}
                                         :return-type :int
                                         :impl-source '[(+ a 2)]})]
      (is (not= hash1 hash2)))))


;; =============================================================================
;; fn-schema-uuid and arg-schema-uuid tests
;; =============================================================================

(deftest fn-schema-uuid-test
  (testing "generates deterministic UUID for fn-schema"
    (let [uuid1 (core/fn-schema-uuid :my-fn)
          uuid2 (core/fn-schema-uuid :my-fn)]
      (is (uuid? uuid1))
      (is (= uuid1 uuid2))))

  (testing "different fn-names produce different UUIDs"
    (let [uuid1 (core/fn-schema-uuid :fn-a)
          uuid2 (core/fn-schema-uuid :fn-b)]
      (is (not= uuid1 uuid2))))

  (testing "throws for invalid fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (core/fn-schema-uuid :123invalid)))))


(deftest arg-schema-uuid-test
  (testing "generates deterministic UUID for arg-schema"
    (let [uuid1 (core/arg-schema-uuid :my-fn :my-arg)
          uuid2 (core/arg-schema-uuid :my-fn :my-arg)]
      (is (uuid? uuid1))
      (is (= uuid1 uuid2))))

  (testing "different arg-names produce different UUIDs"
    (let [uuid1 (core/arg-schema-uuid :my-fn :arg-a)
          uuid2 (core/arg-schema-uuid :my-fn :arg-b)]
      (is (not= uuid1 uuid2))))

  (testing "throws for invalid fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (core/arg-schema-uuid :123invalid :arg))))

  (testing "throws for invalid arg-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (core/arg-schema-uuid :my-fn :123invalid)))))


;; =============================================================================
;; sync-defs-to-storage! tests (integration)
;; =============================================================================

(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each (setup/create-clean-db-fixture))


(deftest sync-defs-to-storage!-test
  (testing "syncs function definitions to storage"
    (let [storage (setup/create-test-storage)
          defs {:test-add {:args {:a :int :b :int}
                           :return-type :int
                           :impl (fn [_ _] nil)}}
          result (core/sync-defs-to-storage! storage defs)]
      (is (map? result))
      (is (= 1 (get-in result [:fn-schemas :created])))
      (is (= 2 (get-in result [:arg-schemas :created])))
      (sp/close storage)))

  (testing "updates existing schemas on re-sync"
    (let [storage (setup/create-test-storage)
          defs {:test-fn {:args {:x :int} :return-type :int :impl (fn [_ _] nil)}}
          result1 (core/sync-defs-to-storage! storage defs)
          result2 (core/sync-defs-to-storage! storage defs)]
      (is (= 1 (get-in result1 [:fn-schemas :created])))
      (is (= 1 (get-in result2 [:fn-schemas :updated])))
      (sp/close storage)))

  (testing "throws for batch too large"
    (let [storage (setup/create-test-storage)
          ;; Create 501 function definitions
          defs (into {} (for [i (range 501)]
                          [(keyword (str "fn-" i))
                           {:args {} :return-type :int :impl (fn [_ _] nil)}]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Too many function definitions"
            (core/sync-defs-to-storage! storage defs)))
      (sp/close storage)))

  (testing "handles function with no args"
    (let [storage (setup/create-test-storage)
          defs {:no-args-fn {:args {} :return-type :text :impl (fn [_ _] "hello")}}
          result (core/sync-defs-to-storage! storage defs)]
      (is (= 1 (get-in result [:fn-schemas :created])))
      (is (zero? (get-in result [:arg-schemas :created])))
      (sp/close storage)))

  (testing "handles optional args"
    (let [storage (setup/create-test-storage)
          defs {:optional-fn {:args {:required-arg :int
                                     :optional-arg {:type :text :required false}}
                              :return-type :int
                              :impl (fn [_ _] nil)}}
          result (core/sync-defs-to-storage! storage defs)]
      (is (= 1 (get-in result [:fn-schemas :created])))
      (is (= 2 (get-in result [:arg-schemas :created])))
      ;; Verify optional arg has required=false
      (let [arg-id (core/arg-schema-uuid :optional-fn :optional-arg)
            arg-schema (sp/read-entity storage :arg-schema arg-id)]
        (is (false? (:required arg-schema))))
      (sp/close storage)))

  (testing "sets first-class=true for :fn type args"
    (let [storage (setup/create-test-storage)
          defs {:hof-fn {:args {:f :fn :coll :jsonb}
                         :return-type :jsonb
                         :impl (fn [_ _] nil)}}
          _ (core/sync-defs-to-storage! storage defs)
          f-arg-id (core/arg-schema-uuid :hof-fn :f)
          coll-arg-id (core/arg-schema-uuid :hof-fn :coll)
          f-arg (sp/read-entity storage :arg-schema f-arg-id)
          coll-arg (sp/read-entity storage :arg-schema coll-arg-id)]
      (is (true? (:first-class f-arg)) ":fn type should have first-class=true")
      (is (false? (:first-class coll-arg)) ":jsonb type should have first-class=false")
      (sp/close storage))))


;; =============================================================================
;; register-base-fns! tests
;; =============================================================================

(deftest register-base-fns!-test
  (testing "registers base functions"
    ;; This modifies global state, so we test indirectly
    (let [defs {:test-reg-fn {:args {:x :int}
                              :return-type :int
                              :impl (fn [{:keys [x]} _] @x)}}]
      ;; Should not throw
      (is (nil? (core/register-base-fns! defs))))))


;; =============================================================================
;; Additional edge case tests
;; =============================================================================

(deftest sync-with-impl-hash-test
  (testing "stores impl-hash in fn-schema"
    (let [storage (setup/create-test-storage)
          defs {:hash-test-fn {:args {:a :int}
                               :return-type :int
                               :impl (fn [_ _] nil)
                               :impl-source '[(+ a 1)]}}
          _ (core/sync-defs-to-storage! storage defs)
          fn-schema-id (core/fn-schema-uuid :hash-test-fn)
          fn-schema (sp/read-entity storage :fn-schema fn-schema-id)]
      (is (string? (:impl-hash fn-schema)))
      (is (= 64 (count (:impl-hash fn-schema))))
      (sp/close storage)))

  (testing "updates impl-hash when implementation changes"
    (let [storage (setup/create-test-storage)
          defs1 {:changing-fn {:args {:a :int}
                               :return-type :int
                               :impl (fn [_ _] nil)
                               :impl-source '[(+ a 1)]}}
          _ (core/sync-defs-to-storage! storage defs1)
          fn-schema-id (core/fn-schema-uuid :changing-fn)
          hash1 (:impl-hash (sp/read-entity storage :fn-schema fn-schema-id))
          ;; Now sync with different impl-source
          defs2 {:changing-fn {:args {:a :int}
                               :return-type :int
                               :impl (fn [_ _] nil)
                               :impl-source '[(* a 2)]}}
          _ (core/sync-defs-to-storage! storage defs2)
          hash2 (:impl-hash (sp/read-entity storage :fn-schema fn-schema-id))]
      (is (not= hash1 hash2))
      (sp/close storage))))
