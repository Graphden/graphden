(ns graphden.executor.registry.core-test
  "Tests for executor.registry.core - base function registration infrastructure.

   Covers:
   - compute-impl-hash
   - fn-schema-uuid, arg-schema-uuid
   - validate-fn-def!, validate-all-defs!
   - uuid-v5 edge cases (via fn-schema-uuid/arg-schema-uuid)
   - validate-identifier! (via public functions)
   - parse-arg-spec (via validate-fn-def!)
   - sync-defs-to-storage! (integration)"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as core]
    [graphden.storage.age.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]))


;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; =============================================================================
;; compute-impl-hash Tests
;; =============================================================================

(deftest compute-impl-hash-basic-test
  (testing "compute-impl-hash returns 64-char hex string"
    (let [fn-def {:args {:x :int :y :int}
                  :return-type :int
                  :impl-source '[(+ x y)]}
          impl-hash (core/compute-impl-hash fn-def)]
      (is (string? impl-hash))
      (is (= 64 (count impl-hash)))
      (is (re-matches #"^[0-9a-f]+$" impl-hash))))

  (testing "compute-impl-hash is deterministic"
    (let [fn-def {:args {:x :int}
                  :return-type :int
                  :impl-source '[(* x 2)]}
          hash1 (core/compute-impl-hash fn-def)
          hash2 (core/compute-impl-hash fn-def)]
      (is (= hash1 hash2))))

  (testing "compute-impl-hash with no impl-source"
    (let [fn-def {:args {:a :text}
                  :return-type :text}
          impl-hash (core/compute-impl-hash fn-def)]
      (is (string? impl-hash))
      (is (= 64 (count impl-hash))))))


(deftest compute-impl-hash-changes-test
  (testing "hash changes when body changes"
    (let [fn-def1 {:args {:x :int} :return-type :int :impl-source '[(+ x 1)]}
          fn-def2 {:args {:x :int} :return-type :int :impl-source '[(+ x 2)]}]
      (is (not= (core/compute-impl-hash fn-def1)
                (core/compute-impl-hash fn-def2)))))

  (testing "hash changes when arg type changes"
    (let [fn-def1 {:args {:x :int} :return-type :int :impl-source '[(identity x)]}
          fn-def2 {:args {:x :text} :return-type :int :impl-source '[(identity x)]}]
      (is (not= (core/compute-impl-hash fn-def1)
                (core/compute-impl-hash fn-def2)))))

  (testing "hash changes when return type changes"
    (let [fn-def1 {:args {:x :int} :return-type :int :impl-source '[(identity x)]}
          fn-def2 {:args {:x :int} :return-type :text :impl-source '[(identity x)]}]
      (is (not= (core/compute-impl-hash fn-def1)
                (core/compute-impl-hash fn-def2)))))

  (testing "hash changes when arg added"
    (let [fn-def1 {:args {:x :int} :return-type :int :impl-source '[(identity x)]}
          fn-def2 {:args {:x :int :y :int} :return-type :int :impl-source '[(identity x)]}]
      (is (not= (core/compute-impl-hash fn-def1)
                (core/compute-impl-hash fn-def2))))))


(deftest compute-impl-hash-stability-test
  (testing "hash is stable regardless of arg map key order"
    (let [fn-def1 {:args {:a :int :b :text :c :bool}
                   :return-type :int
                   :impl-source '[(+ a 1)]}
          fn-def2 {:args {:c :bool :a :int :b :text}
                   :return-type :int
                   :impl-source '[(+ a 1)]}]
      (is (= (core/compute-impl-hash fn-def1)
             (core/compute-impl-hash fn-def2)))))

  (testing "hash with nested maps in impl-source"
    (let [fn-def {:args {:x :jsonb}
                  :return-type :jsonb
                  :impl-source '[{:a 1 :b {:c 2}}]}
          impl-hash (core/compute-impl-hash fn-def)]
      (is (= 64 (count impl-hash))))))


;; =============================================================================
;; fn-schema-uuid Tests
;; =============================================================================

(deftest fn-schema-uuid-test
  (testing "fn-schema-uuid returns UUID"
    (let [uuid (core/fn-schema-uuid :my-function)]
      (is (instance? java.util.UUID uuid))))

  (testing "fn-schema-uuid is deterministic"
    (let [uuid1 (core/fn-schema-uuid :test-fn)
          uuid2 (core/fn-schema-uuid :test-fn)]
      (is (= uuid1 uuid2))))

  (testing "different names produce different UUIDs"
    (let [uuid1 (core/fn-schema-uuid :fn-one)
          uuid2 (core/fn-schema-uuid :fn-two)]
      (is (not= uuid1 uuid2))))

  (testing "fn-schema-uuid accepts string fn-name"
    (let [uuid (core/fn-schema-uuid "string-fn")]
      (is (instance? java.util.UUID uuid)))))


(deftest fn-schema-uuid-validation-test
  (testing "fn-schema-uuid rejects empty name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (core/fn-schema-uuid ""))))

  (testing "fn-schema-uuid rejects name with invalid characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (core/fn-schema-uuid "fn@name"))))

  (testing "fn-schema-uuid rejects name starting with number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (core/fn-schema-uuid "123fn"))))

  (testing "fn-schema-uuid allows predicate names ending with ?"
    (let [uuid (core/fn-schema-uuid :empty?)]
      (is (instance? java.util.UUID uuid))))

  (testing "fn-schema-uuid allows underscore and hyphen"
    (is (instance? java.util.UUID (core/fn-schema-uuid :my_fn-name)))
    (is (instance? java.util.UUID (core/fn-schema-uuid :_private))))

  (testing "fn-schema-uuid rejects name over 128 chars"
    (let [long-name (str/join (repeat 129 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"maximum length"
            (core/fn-schema-uuid long-name))))))


;; =============================================================================
;; arg-schema-uuid Tests
;; =============================================================================

(deftest arg-schema-uuid-test
  (testing "arg-schema-uuid returns UUID"
    (let [uuid (core/arg-schema-uuid :my-fn :my-arg)]
      (is (instance? java.util.UUID uuid))))

  (testing "arg-schema-uuid is deterministic"
    (let [uuid1 (core/arg-schema-uuid :fn :arg)
          uuid2 (core/arg-schema-uuid :fn :arg)]
      (is (= uuid1 uuid2))))

  (testing "same arg name different fn produces different UUID"
    (let [uuid1 (core/arg-schema-uuid :fn-a :x)
          uuid2 (core/arg-schema-uuid :fn-b :x)]
      (is (not= uuid1 uuid2))))

  (testing "same fn different arg produces different UUID"
    (let [uuid1 (core/arg-schema-uuid :my-fn :x)
          uuid2 (core/arg-schema-uuid :my-fn :y)]
      (is (not= uuid1 uuid2)))))


(deftest arg-schema-uuid-validation-test
  (testing "arg-schema-uuid validates fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (core/arg-schema-uuid "" :arg))))

  (testing "arg-schema-uuid validates arg-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (core/arg-schema-uuid :fn ""))))

  (testing "arg-schema-uuid rejects invalid arg name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (core/arg-schema-uuid :fn "arg with spaces")))))


;; =============================================================================
;; validate-fn-def! Tests
;; =============================================================================

(deftest validate-fn-def-basic-test
  (testing "validate-fn-def! accepts valid definition"
    (is (nil? (core/validate-fn-def! :my-fn
                                     {:args {:x :int :y :text}
                                      :return-type :bool}))))

  (testing "validate-fn-def! accepts empty args"
    (is (nil? (core/validate-fn-def! :no-args-fn
                                     {:args {}
                                      :return-type :int})))))


(deftest validate-fn-def-errors-test
  (testing "validate-fn-def! rejects non-keyword fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (core/validate-fn-def! "string-name" {:return-type :int}))))

  (testing "validate-fn-def! rejects missing return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"return-type"
          (core/validate-fn-def! :fn {:args {:x :int}}))))

  (testing "validate-fn-def! rejects unknown return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown return type"
          (core/validate-fn-def! :fn {:args {} :return-type :unknown-type}))))

  (testing "validate-fn-def! rejects unknown arg type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (core/validate-fn-def! :fn {:args {:x :invalid-type}
                                      :return-type :int})))))


(deftest validate-fn-def-arg-spec-test
  (testing "validate-fn-def! accepts keyword arg type"
    (is (nil? (core/validate-fn-def! :fn
                                     {:args {:x :int}
                                      :return-type :int}))))

  (testing "validate-fn-def! accepts map arg spec with :type"
    (is (nil? (core/validate-fn-def! :fn
                                     {:args {:x {:type :int :required false}}
                                      :return-type :int}))))

  (testing "validate-fn-def! rejects map arg spec without :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must contain :type key"
          (core/validate-fn-def! :fn
                                 {:args {:x {:required false}}
                                  :return-type :int}))))

  (testing "validate-fn-def! rejects non-boolean :required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (core/validate-fn-def! :fn
                                 {:args {:x {:type :int :required "yes"}}
                                  :return-type :int}))))

  (testing "validate-fn-def! rejects invalid arg spec type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword or map"
          (core/validate-fn-def! :fn
                                 {:args {:x 123}
                                  :return-type :int}))))

  (testing "validate-fn-def! accepts :fn type for HOF"
    (is (nil? (core/validate-fn-def! :higher-order
                                     {:args {:f :fn}
                                      :return-type :any}))))

  (testing "validate-fn-def! accepts :any type"
    (is (nil? (core/validate-fn-def! :flexible
                                     {:args {:x :any}
                                      :return-type :any})))))


;; =============================================================================
;; validate-all-defs! Tests
;; =============================================================================

(deftest validate-all-defs-test
  (testing "validate-all-defs! accepts valid definitions"
    (let [defs {:add {:args {:x :int :y :int} :return-type :int}
                :concat {:args {:a :text :b :text} :return-type :text}}]
      (is (nil? (core/validate-all-defs! defs)))))

  (testing "validate-all-defs! fails on first invalid"
    (let [defs {:good {:args {:x :int} :return-type :int}
                :bad {:args {:x :unknown} :return-type :int}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
            (core/validate-all-defs! defs)))))

  (testing "validate-all-defs! accepts empty defs"
    (is (nil? (core/validate-all-defs! {})))))


;; =============================================================================
;; register-base-fns! Tests
;; =============================================================================

(deftest register-base-fns-test
  (testing "register-base-fns! registers implementations"
    (let [called (atom false)
          defs {:test-reg-fn {:impl (fn [_args _ctx]
                                      (reset! called true)
                                      42)
                              :args {}
                              :return-type :int}}]
      ;; Just verify it doesn't throw
      (is (nil? (core/register-base-fns! defs))))))


;; =============================================================================
;; sync-defs-to-storage! Tests (Integration)
;; =============================================================================

(deftest sync-defs-to-storage-test
  (testing "sync-defs-to-storage! creates fn-schema and arg-schema"
    (let [storage (setup/create-test-storage)
          defs {:sync-test-fn {:args {:x :int :y :text}
                               :return-type :int}}]
      (try
        (let [result (core/sync-defs-to-storage! storage defs)]
          (is (map? result))
          (is (= 1 (:created (:fn-schemas result))))
          (is (= 2 (:created (:arg-schemas result))))
          ;; Verify entities exist
          (let [fn-schema-id (core/fn-schema-uuid :sync-test-fn)
                fn-schema (sp/read-entity storage :fn-schema fn-schema-id)]
            (is (some? fn-schema))
            (is (= "sync-test-fn" (:name fn-schema)))
            (is (= :int (:returned-type fn-schema)))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! is idempotent (updates on second run)"
    (let [storage (setup/create-test-storage)
          defs {:idempotent-fn {:args {:a :bool}
                                :return-type :bool}}]
      (try
        ;; First sync creates
        (let [result1 (core/sync-defs-to-storage! storage defs)]
          (is (= 1 (:created (:fn-schemas result1)))))
        ;; Second sync updates
        (let [result2 (core/sync-defs-to-storage! storage defs)]
          (is (zero? (:created (:fn-schemas result2))))
          (is (= 1 (:updated (:fn-schemas result2)))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! stores impl-hash"
    (let [storage (setup/create-test-storage)
          defs {:hash-fn {:args {:x :int}
                          :return-type :int
                          :impl-source '[(* x 2)]}}]
      (try
        (core/sync-defs-to-storage! storage defs)
        (let [fn-schema-id (core/fn-schema-uuid :hash-fn)
              fn-schema (sp/read-entity storage :fn-schema fn-schema-id)]
          (is (some? (:impl-hash fn-schema)))
          (is (= 64 (count (:impl-hash fn-schema)))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates before syncing"
    (let [storage (setup/create-test-storage)
          defs {:bad-fn {:args {:x :invalid}
                         :return-type :int}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
              (core/sync-defs-to-storage! storage defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! rejects too large batch"
    (let [storage (setup/create-test-storage)
          ;; Create 501 definitions (exceeds max of 500)
          defs (into {} (for [i (range 501)]
                          [(keyword (str "fn-" i))
                           {:args {} :return-type :int}]))]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Too many function definitions"
              (core/sync-defs-to-storage! storage defs)))
        (finally
          (sp/close storage))))))


(deftest sync-defs-with-optional-args-test
  (testing "sync-defs-to-storage! handles optional args"
    (let [storage (setup/create-test-storage)
          defs {:optional-fn {:args {:required-arg :int
                                     :optional-arg {:type :text :required false}}
                              :return-type :int}}]
      (try
        (let [result (core/sync-defs-to-storage! storage defs)]
          (is (= 2 (:created (:arg-schemas result))))
          ;; Check required arg
          (let [required-id (core/arg-schema-uuid :optional-fn :required-arg)
                required-schema (sp/read-entity storage :arg-schema required-id)]
            (is (true? (:required required-schema))))
          ;; Check optional arg
          (let [optional-id (core/arg-schema-uuid :optional-fn :optional-arg)
                optional-schema (sp/read-entity storage :arg-schema optional-id)]
            (is (false? (:required optional-schema)))))
        (finally
          (sp/close storage))))))
