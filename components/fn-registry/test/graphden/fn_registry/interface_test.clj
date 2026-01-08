(ns graphden.fn-registry.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.core :as core]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util
      UUID)))


(use-fixtures :each exec/with-clean-registry)


;; === Helper Functions ===

(defn literal-delay
  "Creates a delay wrapping a literal value for testing."
  [value]
  (delay value))


;; === Registration Tests ===

(deftest register-base-fns-test
  (testing "register-base-fns! registers functions"
    ;; impl functions receive delays, use @ to deref
    (let [defs {:test-add {:args {:a :numeric :b :numeric}
                           :return-type :numeric
                           :impl (fn [{:keys [a b]} _ctx] (+ @a @b))}
                :test-sub {:args {:a :numeric :b :numeric}
                           :return-type :numeric
                           :impl (fn [{:keys [a b]} _ctx] (- @a @b))}}]
      (registry/register-base-fns! defs)
      (is (some? (exec/get-base-fn :test-add)))
      (is (some? (exec/get-base-fn :test-sub)))

      ;; Test that they work with delays
      (let [add-fn (exec/get-base-fn :test-add)
            sub-fn (exec/get-base-fn :test-sub)]
        (is (= 7 (add-fn {:a (literal-delay 3) :b (literal-delay 4)} nil)))
        (is (= -1 (sub-fn {:a (literal-delay 3) :b (literal-delay 4)} nil))))))

  (testing "register-base-fns! handles empty defs map"
    ;; This tests the doseq with empty input
    (registry/register-base-fns! {})
    ;; Should not throw, just do nothing
    (is true))

  (testing "register-base-fns! handles nil defs map"
    ;; This tests the doseq with nil input
    (registry/register-base-fns! nil)
    ;; Should not throw, just do nothing
    (is true))

  (testing "register-base-fns! handles def with nil :impl"
    ;; When :impl is nil, register-base-fn! receives nil function
    ;; This should work (registers nil), caller is responsible for valid impl
    (registry/register-base-fns! {:nil-impl {:args {} :return-type :any :impl nil}})
    (is (nil? (exec/get-base-fn :nil-impl))))

  (testing "register-base-fns! handles def missing :impl key"
    ;; When :impl key is missing, register-base-fn! receives nil
    (registry/register-base-fns! {:missing-impl {:args {} :return-type :any}})
    (is (nil? (exec/get-base-fn :missing-impl)))))


;; === UUID Generation Tests ===

(deftest uuid-generation-test
  (testing "fn-schema-uuid is deterministic"
    (let [uuid1 (registry/fn-schema-uuid :test-fn)
          uuid2 (registry/fn-schema-uuid :test-fn)
          uuid3 (registry/fn-schema-uuid :other-fn)]
      (is (= uuid1 uuid2) "Same name should produce same UUID")
      (is (not= uuid1 uuid3) "Different names should produce different UUIDs")))

  (testing "arg-schema-uuid is deterministic"
    (let [uuid1 (registry/arg-schema-uuid :test-fn :arg-a)
          uuid2 (registry/arg-schema-uuid :test-fn :arg-a)
          uuid3 (registry/arg-schema-uuid :test-fn :arg-b)
          uuid4 (registry/arg-schema-uuid :other-fn :arg-a)]
      (is (= uuid1 uuid2) "Same fn+arg should produce same UUID")
      (is (not= uuid1 uuid3) "Different arg should produce different UUID")
      (is (not= uuid1 uuid4) "Different fn should produce different UUID"))))


;; === Storage Sync Tests ===

(deftest sync-defs-to-storage-test
  (testing "sync-defs-to-storage! creates fn-schemas and arg-schemas"
    (let [storage (gsm/create-storage)
          defs {:my-add {:args {:a :numeric :b :numeric}
                         :return-type :numeric
                         :impl (fn [{:keys [a b]} _ctx] (+ a b))}
                :my-neg {:args {:n :numeric}
                         :return-type :numeric
                         :impl (fn [{:keys [n]} _ctx] (- n))}}]
      (try
        (let [result (registry/sync-defs-to-storage! storage defs)]
          (is (= 2 (:created (:fn-schemas result))))
          (is (zero? (:updated (:fn-schemas result))))
          (is (= 3 (:created (:arg-schemas result)))) ; 2 for my-add, 1 for my-neg
          (is (zero? (:updated (:arg-schemas result)))))

        ;; Verify fn-schemas exist
        (let [all-schemas (sp/query-entities storage :fn-schema {})]
          (is (= 2 (count all-schemas)))
          (is (some #(= "my-add" (:name %)) all-schemas))
          (is (some #(= "my-neg" (:name %)) all-schemas)))

        ;; Verify arg-schemas exist
        (let [all-args (sp/query-entities storage :arg-schema {})
              add-schema (first (sp/query-entities storage :fn-schema {:name "my-add"}))
              add-args (sp/query-entities storage :arg-schema {:fn-schema-id (:id add-schema)})]
          (is (= 3 (count all-args)))
          (is (= 2 (count add-args)))
          (is (= #{"a" "b"} (set (map :name add-args)))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! is idempotent"
    (let [storage (gsm/create-storage)
          defs {:idempotent-fn {:args {:x :any}
                                :return-type :any
                                :impl (fn [{:keys [x]} _ctx] x)}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs)
        (let [count-after-first (count (sp/query-entities storage :fn-schema {}))]
          ;; Second sync
          (let [result (registry/sync-defs-to-storage! storage defs)]
            (is (zero? (:created (:fn-schemas result))))
            (is (= 1 (:updated (:fn-schemas result))))
            (is (zero? (:created (:arg-schemas result))))
            (is (= 1 (:updated (:arg-schemas result)))))
          ;; Same count
          (is (= count-after-first (count (sp/query-entities storage :fn-schema {})))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - missing :type in map"
    (let [storage (gsm/create-storage)
          invalid-defs {:bad-fn {:args {:x {:required false}} ; missing :type
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec map must contain :type key"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - invalid type"
    (let [storage (gsm/create-storage)
          invalid-defs {:bad-fn {:args {:x "not-a-keyword"} ; invalid
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - nil"
    (let [storage (gsm/create-storage)
          invalid-defs {:bad-fn {:args {:x nil}
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! validates arg-spec - number"
    (let [storage (gsm/create-storage)
          invalid-defs {:bad-fn {:args {:x 123}
                                 :return-type :any
                                 :impl (fn [_ _] nil)}}]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
              (registry/sync-defs-to-storage! storage invalid-defs)))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! handles update when data actually changed"
    (let [storage (gsm/create-storage)
          defs-v1 {:changing-fn {:args {:x :int}
                                 :return-type :int
                                 :impl (fn [{:keys [x]} _ctx] x)}}
          defs-v2 {:changing-fn {:args {:x :int :y :int}  ; Added arg
                                 :return-type :numeric    ; Changed return type
                                 :impl (fn [{:keys [x y]} _ctx] (+ x y))}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [schema-v1 (sp/read-entity storage :fn-schema (registry/fn-schema-uuid :changing-fn))]
          (is (= :int (:returned-type schema-v1))))

        ;; Second sync with changes
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [schema-v2 (sp/read-entity storage :fn-schema (registry/fn-schema-uuid :changing-fn))
              all-args (sp/query-entities storage :arg-schema {:fn-schema-id (:id schema-v2)})]
          (is (= :numeric (:returned-type schema-v2)))
          (is (= 2 (count all-args)))
          (is (= #{"x" "y"} (set (map :name all-args)))))
        (finally
          (sp/close storage)))))

  (testing "sync-defs-to-storage! handles optional args"
    (let [storage (gsm/create-storage)
          defs {:opt-fn {:args {:required :int
                                :optional {:type :text :required false}}
                         :return-type :any
                         :impl (fn [_ _] nil)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :opt-fn)
              args (sp/query-entities storage :arg-schema {:fn-schema-id fn-id})
              req-arg (first (filter #(= "required" (:name %)) args))
              opt-arg (first (filter #(= "optional" (:name %)) args))]
          (is (true? (:required req-arg)))
          (is (false? (:required opt-arg))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! updates only when return-type changes"
    (let [storage (gsm/create-storage)
          defs-v1 {:ret-change {:args {:x :int}
                                :return-type :int
                                :impl (fn [_ _] 1)}}
          defs-v2 {:ret-change {:args {:x :int}
                                :return-type :numeric  ; Only return-type changed
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [schema-v1 (sp/read-entity storage :fn-schema (registry/fn-schema-uuid :ret-change))]
          (is (= :int (:returned-type schema-v1))))
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [schema-v2 (sp/read-entity storage :fn-schema (registry/fn-schema-uuid :ret-change))]
          (is (= :numeric (:returned-type schema-v2))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! updates when required changes"
    (let [storage (gsm/create-storage)
          defs-v1 {:req-change {:args {:x {:type :int :required true}}
                                :return-type :int
                                :impl (fn [_ _] 1)}}
          defs-v2 {:req-change {:args {:x {:type :int :required false}}  ; required changed
                                :return-type :int
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [fn-id (registry/fn-schema-uuid :req-change)
              arg-v1 (first (sp/query-entities storage :arg-schema {:fn-schema-id fn-id}))]
          (is (true? (:required arg-v1))))
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [fn-id (registry/fn-schema-uuid :req-change)
              arg-v2 (first (sp/query-entities storage :arg-schema {:fn-schema-id fn-id}))]
          (is (false? (:required arg-v2))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! updates when type changes"
    (let [storage (gsm/create-storage)
          defs-v1 {:type-change {:args {:x :int}
                                 :return-type :any
                                 :impl (fn [_ _] 1)}}
          defs-v2 {:type-change {:args {:x :numeric}  ; type changed
                                 :return-type :any
                                 :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs-v1)
        (let [fn-id (registry/fn-schema-uuid :type-change)
              arg-v1 (first (sp/query-entities storage :arg-schema {:fn-schema-id fn-id}))]
          (is (= :int (:type arg-v1))))
        (registry/sync-defs-to-storage! storage defs-v2)
        (let [fn-id (registry/fn-schema-uuid :type-change)
              arg-v2 (first (sp/query-entities storage :arg-schema {:fn-schema-id fn-id}))]
          (is (= :numeric (:type arg-v2))))
        (finally
          (sp/close storage))))))


;; === UUID-v5 Tests ===

(deftest uuid-v5-test
  (testing "uuid-v5 is deterministic"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid1 (#'core/uuid-v5 ns-uuid "test-name")
          uuid2 (#'core/uuid-v5 ns-uuid "test-name")]
      (is (= uuid1 uuid2))))

  (testing "uuid-v5 produces different UUIDs for different names"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid1 (#'core/uuid-v5 ns-uuid "name1")
          uuid2 (#'core/uuid-v5 ns-uuid "name2")]
      (is (not= uuid1 uuid2))))

  (testing "uuid-v5 produces different UUIDs for different namespaces"
    (let [ns1 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          ns2 #uuid "11111111-2222-3333-4444-555555555555"
          uuid1 (#'core/uuid-v5 ns1 "same-name")
          uuid2 (#'core/uuid-v5 ns2 "same-name")]
      (is (not= uuid1 uuid2))))

  (testing "uuid-v5 handles empty string"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid (#'core/uuid-v5 ns-uuid "")]
      (is (uuid? uuid))))

  (testing "uuid-v5 handles Unicode strings"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid-cyrillic (#'core/uuid-v5 ns-uuid "тест")
          uuid-emoji (#'core/uuid-v5 ns-uuid "test🎉")]
      (is (uuid? uuid-cyrillic))
      (is (uuid? uuid-emoji))
      (is (not= uuid-cyrillic uuid-emoji))))

  (testing "uuid-v5 handles special characters"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid (#'core/uuid-v5 ns-uuid "test:with/special-chars!@#$%")]
      (is (uuid? uuid))))

  (testing "uuid-v5 throws on non-UUID namespace"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 "not-a-uuid" "name"))))

  (testing "uuid-v5 throws on non-string name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" :keyword))))

  (testing "uuid-v5 throws on nil name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" nil))))

  (testing "uuid-v5 produces version 5 UUID"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid (#'core/uuid-v5 ns-uuid "test")]
      ;; Version is in bits 12-15 of time_hi_and_version (byte 6)
      ;; For version 5, the version nibble should be 5
      (is (= 5 (UUID/.version uuid))))))


;; === parse-arg-spec Tests ===

(deftest parse-arg-spec-test
  (testing "parses keyword arg-spec"
    (let [result (#'core/parse-arg-spec :x :int)]
      (is (= {:arg-type :int :required true} result))))

  (testing "parses map arg-spec with required true"
    (let [result (#'core/parse-arg-spec :x {:type :text :required true})]
      (is (= {:arg-type :text :required true} result))))

  (testing "parses map arg-spec with required false"
    (let [result (#'core/parse-arg-spec :x {:type :bool :required false})]
      (is (= {:arg-type :bool :required false} result))))

  (testing "parses map arg-spec with default required (true)"
    (let [result (#'core/parse-arg-spec :x {:type :numeric})]
      (is (= {:arg-type :numeric :required true} result))))

  (testing "throws on nil arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x nil))))

  (testing "throws on string arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x "string"))))

  (testing "throws on number arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x 42))))

  (testing "throws on vector arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x [:type :int]))))

  (testing "throws on map without :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec map must contain :type key"
          (#'core/parse-arg-spec :x {:required false}))))

  (testing "includes arg-name in error data"
    (try
      (#'core/parse-arg-spec :my-arg nil)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :my-arg (:arg-name (ex-data e)))))))

  (testing "validates known arg types - keyword spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/parse-arg-spec :x :unknown-type))))

  (testing "validates known arg types - map spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/parse-arg-spec :x {:type :invalid-type :required true}))))

  (testing "accepts all valid storage types"
    (doseq [valid-type [:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes]]
      (is (= {:arg-type valid-type :required true}
             (#'core/parse-arg-spec :x valid-type)))))

  (testing "accepts executor-specific types"
    (is (= {:arg-type :any :required true} (#'core/parse-arg-spec :x :any)))
    (is (= {:arg-type :fn :required true} (#'core/parse-arg-spec :x :fn))))

  (testing "invalid type error includes valid types"
    (try
      (#'core/parse-arg-spec :x :bad-type)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-arg-type (:type (ex-data e))))
        (is (= :x (:arg-name (ex-data e))))
        (is (= :bad-type (:arg-type (ex-data e))))
        (is (set? (:valid-types (ex-data e))))))))


;; === Wrapper Edge Cases ===

;; === Short-circuit Coverage Tests ===
;; These tests ensure all branches of `or` conditions are evaluated

(deftest sync-fn-schema-or-branches-test
  (testing "sync-fn-schema! triggers update when only base-fn-name differs"
    ;; This tests the third branch of the `or` in sync-fn-schema!
    ;; We manually modify base-fn-name in storage, then sync to trigger update
    (let [storage (gsm/create-storage)
          defs {:base-fn-test {:args {:x :int}
                               :return-type :int
                               :impl (fn [_ _] 1)}}]
      (try
        ;; First sync creates the fn-schema
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :base-fn-test)
              schema-before (sp/read-entity storage :fn-schema fn-id)]
          (is (= "base-fn-test" (:base-fn-name schema-before)))
          ;; Manually corrupt base-fn-name in storage
          (sp/update-entity storage :fn-schema fn-id
                            {:name "base-fn-test"
                             :returned-type :int
                             :base-fn-name "corrupted-name"})
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :fn-schema fn-id)]
            (is (= "corrupted-name" (:base-fn-name corrupted))))
          ;; Re-sync - should update because base-fn-name differs
          (registry/sync-defs-to-storage! storage defs)
          (let [schema-after (sp/read-entity storage :fn-schema fn-id)]
            (is (= "base-fn-test" (:base-fn-name schema-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! triggers update when only name differs"
    ;; This tests the first branch of the `or` in sync-fn-schema!
    (let [storage (gsm/create-storage)
          defs {:name-test {:args {:x :int}
                            :return-type :int
                            :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :name-test)]
          ;; Manually corrupt name in storage
          (sp/update-entity storage :fn-schema fn-id
                            {:name "wrong-name"
                             :returned-type :int
                             :base-fn-name "name-test"})
          ;; Re-sync - should update because name differs
          (registry/sync-defs-to-storage! storage defs)
          (let [schema-after (sp/read-entity storage :fn-schema fn-id)]
            (is (= "name-test" (:name schema-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! triggers update when only returned-type differs"
    ;; This tests the second branch of the `or` in sync-fn-schema!
    (let [storage (gsm/create-storage)
          defs {:ret-test {:args {:x :int}
                           :return-type :numeric
                           :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :ret-test)]
          ;; Manually corrupt returned-type in storage (keep name and base-fn-name correct)
          (sp/update-entity storage :fn-schema fn-id
                            {:name "ret-test"
                             :returned-type :text  ; Wrong type
                             :base-fn-name "ret-test"})
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :fn-schema fn-id)]
            (is (= :text (:returned-type corrupted))))
          ;; Re-sync - should update because only returned-type differs
          (registry/sync-defs-to-storage! storage defs)
          (let [schema-after (sp/read-entity storage :fn-schema fn-id)]
            (is (= :numeric (:returned-type schema-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-fn-schema! does NOT update when nothing differs"
    ;; This tests when all three `or` branches are false
    (let [storage (gsm/create-storage)
          defs {:no-change {:args {:x :int}
                            :return-type :int
                            :impl (fn [_ _] 1)}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs)
        ;; Second sync with same data - should not trigger update
        (let [result (registry/sync-defs-to-storage! storage defs)]
          ;; updated should be 1 because the function goes through update path
          ;; but sp/update-entity shouldn't be called (when condition is false)
          (is (= 1 (:updated (:fn-schemas result)))))
        (finally
          (sp/close storage))))))


(deftest sync-arg-schema-or-branches-test
  (testing "sync-arg-schemas! triggers update when only fn-schema-id differs"
    ;; This tests the first branch of the `or` in sync-arg-schemas!
    (let [storage (gsm/create-storage)
          defs {:arg-fn-test {:args {:x :int}
                              :return-type :int
                              :impl (fn [_ _] 1)}}
          ;; Create a different fn-schema to use as wrong reference
          wrong-schema (sp/create-entity storage :fn-schema
                                         {:name "wrong-schema"
                                          :returned-type :text})]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :arg-fn-test)
              arg-id (registry/arg-schema-uuid :arg-fn-test :x)]
          ;; Manually corrupt fn-schema-id in arg-schema
          (sp/update-entity storage :arg-schema arg-id
                            {:fn-schema-id (:id wrong-schema)
                             :name "x"
                             :type :int
                             :required true})
          ;; Re-sync - should update because fn-schema-id differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg-schema arg-id)]
            (is (= fn-id (:fn-schema-id arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! triggers update when only name differs"
    ;; This tests the second branch of the `or` in sync-arg-schemas!
    (let [storage (gsm/create-storage)
          defs {:arg-name-test {:args {:myarg :int}
                                :return-type :int
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :arg-name-test)
              arg-id (registry/arg-schema-uuid :arg-name-test :myarg)]
          ;; Manually corrupt name in arg-schema
          (sp/update-entity storage :arg-schema arg-id
                            {:fn-schema-id fn-id
                             :name "wrong-name"
                             :type :int
                             :required true})
          ;; Re-sync - should update because name differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg-schema arg-id)]
            (is (= "myarg" (:name arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! triggers update when only type differs"
    ;; This tests the third branch of the `or` in sync-arg-schemas!
    (let [storage (gsm/create-storage)
          defs {:arg-type-test {:args {:z :numeric}
                                :return-type :int
                                :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :arg-type-test)
              arg-id (registry/arg-schema-uuid :arg-type-test :z)]
          ;; Manually corrupt type in arg-schema (keep fn-schema-id, name, required correct)
          (sp/update-entity storage :arg-schema arg-id
                            {:fn-schema-id fn-id
                             :name "z"
                             :type :text  ; Wrong type
                             :required true})
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :arg-schema arg-id)]
            (is (= :text (:type corrupted))))
          ;; Re-sync - should update because only type differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg-schema arg-id)]
            (is (= :numeric (:type arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! triggers update when only required differs"
    ;; This tests the fourth branch of the `or` in sync-arg-schemas!
    (let [storage (gsm/create-storage)
          defs {:arg-req-test {:args {:w {:type :int :required false}}
                               :return-type :int
                               :impl (fn [_ _] 1)}}]
      (try
        (registry/sync-defs-to-storage! storage defs)
        (let [fn-id (registry/fn-schema-uuid :arg-req-test)
              arg-id (registry/arg-schema-uuid :arg-req-test :w)]
          ;; Manually corrupt required in arg-schema (keep fn-schema-id, name, type correct)
          (sp/update-entity storage :arg-schema arg-id
                            {:fn-schema-id fn-id
                             :name "w"
                             :type :int
                             :required true})  ; Wrong required
          ;; Verify corruption
          (let [corrupted (sp/read-entity storage :arg-schema arg-id)]
            (is (true? (:required corrupted))))
          ;; Re-sync - should update because only required differs
          (registry/sync-defs-to-storage! storage defs)
          (let [arg-after (sp/read-entity storage :arg-schema arg-id)]
            (is (false? (:required arg-after)))))
        (finally
          (sp/close storage)))))

  (testing "sync-arg-schemas! does NOT update when nothing differs"
    ;; This tests when all four `or` branches are false
    (let [storage (gsm/create-storage)
          defs {:arg-nochange {:args {:p :int}
                               :return-type :int
                               :impl (fn [_ _] 1)}}]
      (try
        ;; First sync
        (registry/sync-defs-to-storage! storage defs)
        ;; Second sync with same data
        (let [result (registry/sync-defs-to-storage! storage defs)]
          ;; updated should be 1 because the arg goes through update path
          (is (= 1 (:updated (:arg-schemas result)))))
        (finally
          (sp/close storage))))))


;; wrap-base-fn was removed - base functions now receive delays directly
;; and use @ to deref. The defbase macro handles this automatically.


;; === initialize-with-base-fns! Tests ===

(deftest initialize-with-base-fns-test
  (testing "initializes storage with all base functions"
    (let [storage (gsm/create-storage)]
      (try
        (let [result (registry/initialize-with-base-fns! storage)]
          ;; Should return the same storage
          (is (= storage result))
          ;; Base functions should be registered in executor
          (is (some? (exec/get-base-fn :add)))
          (is (some? (exec/get-base-fn :map)))
          ;; fn-schemas should be in storage
          (is (some? (sp/read-entity storage :fn-schema (registry/fn-schema-uuid :add))))
          (is (some? (sp/read-entity storage :fn-schema (registry/fn-schema-uuid :map)))))
        (finally
          (sp/close storage)))))

  (testing "can execute functions after initialization"
    (let [storage (gsm/create-storage)]
      (try
        (registry/initialize-with-base-fns! storage)
        ;; Create a function that uses :add
        (let [add-schema (sp/read-entity storage :fn-schema (registry/fn-schema-uuid :add))
              add-args (sp/query-entities storage :arg-schema {:fn-schema-id (:id add-schema)})
              nums-arg (first (filter #(= "nums" (:name %)) add-args))
              my-fn (sp/create-entity storage :fn
                                      {:name "test-add"
                                       :fn-schema-id (:id add-schema)})
              _ (sp/create-entity storage :arg-value
                                  {:owner-fn-id (:id my-fn)
                                   :arg-schema-id (:id nums-arg)
                                   :value [1 2 3 4 5]})
              ctx (exec/create-context {:storage storage})]
          (is (= 15 (exec/execute ctx (:id my-fn) nil))))
        (finally
          (sp/close storage))))))


;; === validate-identifier! Tests ===

(deftest validate-identifier-test
  (testing "accepts valid identifiers"
    ;; These should not throw
    (is (nil? (#'core/validate-identifier! "fn-name" :my-fn)))
    (is (nil? (#'core/validate-identifier! "fn-name" :add)))
    (is (nil? (#'core/validate-identifier! "fn-name" :my_func)))
    (is (nil? (#'core/validate-identifier! "fn-name" :_private)))
    (is (nil? (#'core/validate-identifier! "fn-name" :camelCase123)))
    (is (nil? (#'core/validate-identifier! "fn-name" :empty?))))  ; predicates allowed

  (testing "rejects empty identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" "")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "arg-name" (keyword "")))))  ; keyword with empty name

  (testing "rejects nil identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" nil))))

  (testing "rejects identifier exceeding max length (128 chars)"
    (let [long-name (keyword (str/join (repeat 129 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds maximum length"
            (#'core/validate-identifier! "fn-name" long-name)))))

  (testing "accepts identifier at exact max length (128 chars)"
    (let [max-name (keyword (str/join (repeat 128 "a")))]
      (is (nil? (#'core/validate-identifier! "fn-name" max-name)))))

  (testing "rejects identifier starting with number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" :123abc))))

  (testing "rejects identifier with spaces"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my func")))))

  (testing "rejects identifier with special characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my@fn"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my!fn"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my.fn")))))

  (testing "error includes name-type and name-value"
    (try
      (#'core/validate-identifier! "arg-name" :123invalid)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-identifier (:type (ex-data e))))
        (is (= "arg-name" (:name-type (ex-data e))))
        (is (= "123invalid" (:name-value (ex-data e))))))))


;; === validate-fn-def! Tests ===

(deftest validate-fn-def-test
  (testing "accepts valid function definition"
    ;; Should not throw
    (is (nil? (core/validate-fn-def! :my-fn {:args {:x :int} :return-type :int}))))

  (testing "rejects non-keyword fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! "string-name" {:args {:x :int} :return-type :int})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! 123 {:args {:x :int} :return-type :int})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! nil {:args {:x :int} :return-type :int}))))

  (testing "fn-name type error includes actual type"
    (try
      (core/validate-fn-def! "not-keyword" {:args {} :return-type :int})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-fn-def (:type (ex-data e))))
        (is (= "not-keyword" (:fn-name (ex-data e))))
        (is (= java.lang.String (:fn-name-type (ex-data e)))))))

  (testing "rejects missing :return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {:x :int}}))))

  (testing "rejects nil :return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {:x :int} :return-type nil}))))

  (testing "rejects unknown :return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown return type"
          (core/validate-fn-def! :my-fn {:args {:x :int} :return-type :not-a-type}))))

  (testing "return-type error includes valid types"
    (try
      (core/validate-fn-def! :my-fn {:args {} :return-type :invalid})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-return-type (:type (ex-data e))))
        (is (= :my-fn (:fn-name (ex-data e))))
        (is (= :invalid (:return-type (ex-data e))))
        (is (set? (:valid-types (ex-data e)))))))

  (testing "validates all arg specs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (core/validate-fn-def! :my-fn {:args {:x :unknown-type} :return-type :int})))))


;; === parse-arg-spec Error Case Tests ===

(deftest parse-arg-spec-required-validation-test
  (testing "throws when :required is not a boolean"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required "true"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required nil}))))

  (testing "accepts valid boolean :required values"
    (is (= {:arg-type :int :required true}
           (#'core/parse-arg-spec :x {:type :int :required true})))
    (is (= {:arg-type :int :required false}
           (#'core/parse-arg-spec :x {:type :int :required false}))))

  (testing ":required defaults to true when not specified"
    (is (= {:arg-type :int :required true}
           (#'core/parse-arg-spec :x {:type :int})))))


;; === uuid-v5 Error Case Tests ===

(deftest uuid-v5-validation-test
  (testing "throws when namespace-uuid is not a UUID"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 "not-a-uuid" "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 123 "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 nil "test"))))

  (testing "throws when name-str is not a string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 (random-uuid) 123)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 (random-uuid) :keyword)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 (random-uuid) nil))))

  (testing "error data contains correct info"
    (try
      (#'core/uuid-v5 "bad-uuid" "test")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-argument (:type (ex-data e))))
        (is (= "bad-uuid" (:namespace-uuid (ex-data e))))))
    (try
      (#'core/uuid-v5 (random-uuid) :bad-name)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-argument (:type (ex-data e))))
        (is (= :bad-name (:name-str (ex-data e)))))))

  (testing "generates deterministic UUIDs"
    (let [ns-uuid (random-uuid)
          result1 (#'core/uuid-v5 ns-uuid "test-name")
          result2 (#'core/uuid-v5 ns-uuid "test-name")]
      (is (uuid? result1))
      (is (= result1 result2))))

  (testing "different names generate different UUIDs"
    (let [ns-uuid (random-uuid)
          result1 (#'core/uuid-v5 ns-uuid "name1")
          result2 (#'core/uuid-v5 ns-uuid "name2")]
      (is (not= result1 result2))))

  (testing "different namespaces generate different UUIDs"
    (let [ns-uuid1 (random-uuid)
          ns-uuid2 (random-uuid)
          result1 (#'core/uuid-v5 ns-uuid1 "same-name")
          result2 (#'core/uuid-v5 ns-uuid2 "same-name")]
      (is (not= result1 result2)))))


;; === sync-defs-to-storage! Core Function Tests ===

(deftest sync-defs-to-storage-core-test
  (testing "creates new fn-schema and arg-schema entries"
    (let [storage (gsm/create-storage)]
      ;; gsm/create-storage returns storage already initialized with graph schema
      (try
        (let [defs {:test-fn {:args {:x :int :y :int}
                              :return-type :int}}
              result (core/sync-defs-to-storage! storage defs)]
          (is (= 1 (:created (:fn-schemas result))))
          (is (zero? (:updated (:fn-schemas result))))
          (is (= 2 (:created (:arg-schemas result))))
          (is (zero? (:updated (:arg-schemas result)))))
        (finally
          (sp/close storage)))))

  (testing "updates existing fn-schema when re-syncing"
    (let [storage (gsm/create-storage)]
      (try
        ;; First sync
        (core/sync-defs-to-storage! storage {:test-fn {:args {:x :int} :return-type :int}})
        ;; Second sync (update)
        (let [result (core/sync-defs-to-storage! storage {:test-fn {:args {:x :int} :return-type :int}})]
          (is (zero? (:created (:fn-schemas result))))
          (is (= 1 (:updated (:fn-schemas result))))
          (is (zero? (:created (:arg-schemas result))))
          (is (= 1 (:updated (:arg-schemas result)))))
        (finally
          (sp/close storage)))))

  (testing "sync is idempotent with deterministic UUIDs"
    (let [storage (gsm/create-storage)]
      (try
        (let [defs {:add {:args {:a :int :b :int} :return-type :int}}
              fn-id-before (core/fn-schema-uuid :add)]
          (core/sync-defs-to-storage! storage defs)
          ;; Verify the fn-schema was created with expected UUID
          (let [entity (sp/read-entity storage :fn-schema fn-id-before)]
            (is (some? entity))
            (is (= "add" (:name entity)))))
        (finally
          (sp/close storage)))))

  (testing "handles empty defs map"
    (let [storage (gsm/create-storage)]
      (try
        (let [result (core/sync-defs-to-storage! storage {})]
          (is (= {:fn-schemas {:created 0 :updated 0}
                  :arg-schemas {:created 0 :updated 0}}
                 result)))
        (finally
          (sp/close storage)))))

  (testing "handles optional args correctly"
    (let [storage (gsm/create-storage)]
      (try
        (let [defs {:opt-fn {:args {:x :int
                                    :y {:type :int :required false}}
                             :return-type :int}}]
          (core/sync-defs-to-storage! storage defs)
          ;; Verify the optional arg was stored correctly
          (let [arg-id (core/arg-schema-uuid :opt-fn :y)
                arg-entity (sp/read-entity storage :arg-schema arg-id)]
            (is (some? arg-entity))
            (is (false? (:required arg-entity)))))
        (finally
          (sp/close storage)))))

  (testing "fails fast on invalid definition"
    (let [storage (gsm/create-storage)]
      (try
        ;; Should throw before any storage operations
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown return type"
              (core/sync-defs-to-storage! storage {:bad-fn {:args {} :return-type :invalid}})))
        (finally
          (sp/close storage))))))


;; === validate-identifier! Core Path Tests ===

(deftest validate-identifier-core-test
  (testing "rejects nil identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" nil))))

  (testing "rejects empty string identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" ""))))

  (testing "rejects identifier exceeding max length"
    (let [long-name (str/join (repeat 129 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds maximum length"
            (#'core/validate-identifier! "fn-name" long-name)))))

  (testing "accepts identifier at exactly max length"
    (let [max-name (str/join (repeat 128 "a"))]
      ;; Should not throw
      (#'core/validate-identifier! "fn-name" max-name)
      (is true)))

  (testing "rejects identifier with invalid characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "has space")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "has.dot")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "123starts-with-number")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "-starts-with-hyphen"))))

  (testing "accepts valid identifiers"
    (#'core/validate-identifier! "fn-name" "valid_name")
    (#'core/validate-identifier! "fn-name" "valid-name")
    (#'core/validate-identifier! "fn-name" "ValidName123")
    (#'core/validate-identifier! "fn-name" "_private")
    (#'core/validate-identifier! "fn-name" "predicate?")
    (#'core/validate-identifier! "fn-name" :keyword-name)
    (is true))

  (testing "error data contains context"
    (try
      (#'core/validate-identifier! "arg-name" "bad name")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-identifier (:type (ex-data e))))
        (is (= "arg-name" (:name-type (ex-data e))))
        (is (= "bad name" (:name-value (ex-data e))))))))


;; === validate-fn-def! Core Path Tests ===

(deftest validate-fn-def-core-test
  (testing "rejects non-keyword fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! "string-name" {:args {} :return-type :int})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! 123 {:args {} :return-type :int}))))

  (testing "rejects missing return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {}}))))

  (testing "rejects nil return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {} :return-type nil}))))

  (testing "error includes fn-name-type for non-keyword"
    (try
      (core/validate-fn-def! "string" {:args {} :return-type :int})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-fn-def (:type (ex-data e))))
        (is (= "string" (:fn-name (ex-data e))))
        (is (= java.lang.String (:fn-name-type (ex-data e)))))))

  (testing "accepts valid function definitions"
    (core/validate-fn-def! :valid-fn {:args {:x :int} :return-type :text})
    (core/validate-fn-def! :no-args {:args {} :return-type :bool})
    (core/validate-fn-def! :any-type {:args {:x :any} :return-type :any})
    (core/validate-fn-def! :fn-type {:args {:f :fn} :return-type :int})
    (is true)))


;; === validate-arg-type! Tests ===

(deftest validate-arg-type-test
  (testing "rejects unknown types"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/validate-arg-type! :x :unknown-type)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/validate-arg-type! :y :custom))))

  (testing "error includes valid types"
    (try
      (#'core/validate-arg-type! :x :bad-type)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-arg-type (:type (ex-data e))))
        (is (= :x (:arg-name (ex-data e))))
        (is (= :bad-type (:arg-type (ex-data e))))
        (is (set? (:valid-types (ex-data e))))
        (is (contains? (:valid-types (ex-data e)) :int)))))

  (testing "accepts all standard field types"
    (#'core/validate-arg-type! :x :int)
    (#'core/validate-arg-type! :x :numeric)
    (#'core/validate-arg-type! :x :text)
    (#'core/validate-arg-type! :x :bool)
    (#'core/validate-arg-type! :x :uuid)
    (#'core/validate-arg-type! :x :timestamptz)
    (#'core/validate-arg-type! :x :jsonb)
    (#'core/validate-arg-type! :x :bytes)
    (is true))

  (testing "accepts executor-specific types"
    (#'core/validate-arg-type! :x :any)
    (#'core/validate-arg-type! :x :fn)
    (is true)))


;; === sync-fn-schema! Update Path Tests ===

(deftest sync-fn-schema-update-test
  (testing "updates fn-schema when returned-type changes"
    (let [storage (gsm/create-storage)]
      (try
        ;; First sync with :int return type
        (core/sync-defs-to-storage! storage {:my-fn {:args {} :return-type :int}})
        (let [fn-id (core/fn-schema-uuid :my-fn)
              before (sp/read-entity storage :fn-schema fn-id)]
          (is (= :int (:returned-type before)))
          ;; Second sync with :text return type
          (core/sync-defs-to-storage! storage {:my-fn {:args {} :return-type :text}})
          (let [after (sp/read-entity storage :fn-schema fn-id)]
            (is (= :text (:returned-type after)))))
        (finally
          (sp/close storage)))))

  (testing "does not update fn-schema when nothing changed"
    (let [storage (gsm/create-storage)]
      (try
        (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})
        ;; Same sync again - should report as update but no actual change
        (let [result (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})]
          (is (= 1 (:updated (:fn-schemas result)))))
        (finally
          (sp/close storage))))))


;; === sync-arg-schemas! Update Path Tests ===

(deftest sync-arg-schemas-update-test
  (testing "updates arg-schema when type changes"
    (let [storage (gsm/create-storage)]
      (try
        (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})
        (let [arg-id (core/arg-schema-uuid :my-fn :x)
              before (sp/read-entity storage :arg-schema arg-id)]
          (is (= :int (:type before)))
          ;; Change arg type to :text
          (core/sync-defs-to-storage! storage {:my-fn {:args {:x :text} :return-type :int}})
          (let [after (sp/read-entity storage :arg-schema arg-id)]
            (is (= :text (:type after)))))
        (finally
          (sp/close storage)))))

  (testing "updates arg-schema when required changes"
    (let [storage (gsm/create-storage)]
      (try
        (core/sync-defs-to-storage! storage {:my-fn {:args {:x :int} :return-type :int}})
        (let [arg-id (core/arg-schema-uuid :my-fn :x)
              before (sp/read-entity storage :arg-schema arg-id)]
          (is (true? (:required before)))
          ;; Change to optional
          (core/sync-defs-to-storage! storage {:my-fn {:args {:x {:type :int :required false}} :return-type :int}})
          (let [after (sp/read-entity storage :arg-schema arg-id)]
            (is (false? (:required after)))))
        (finally
          (sp/close storage))))))


;; === validate-all-defs! Tests ===

(deftest validate-all-defs-test
  (testing "validates multiple definitions"
    ;; Should not throw for valid defs
    (core/validate-all-defs! {:fn1 {:args {:x :int} :return-type :int}
                              :fn2 {:args {:y :text} :return-type :text}})
    (is true))

  (testing "fails on first invalid definition"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown return type"
          (core/validate-all-defs! {:good-fn {:args {} :return-type :int}
                                    :bad-fn {:args {} :return-type :invalid}}))))

  (testing "handles empty defs"
    (core/validate-all-defs! {})
    (is true))

  (testing "handles nil defs"
    (core/validate-all-defs! nil)
    (is true)))


;; === parse-arg-spec Error Path Tests ===

(deftest parse-arg-spec-error-test
  (testing "parses keyword type (shorthand)"
    (let [result (#'core/parse-arg-spec :x :int)]
      (is (= :int (:arg-type result)))
      (is (true? (:required result)))))

  (testing "parses map with :type and :required true"
    (let [result (#'core/parse-arg-spec :x {:type :text :required true})]
      (is (= :text (:arg-type result)))
      (is (true? (:required result)))))

  (testing "parses map with :type and :required false"
    (let [result (#'core/parse-arg-spec :x {:type :int :required false})]
      (is (= :int (:arg-type result)))
      (is (false? (:required result)))))

  (testing "defaults :required to true when not specified in map"
    (let [result (#'core/parse-arg-spec :x {:type :bool})]
      (is (= :bool (:arg-type result)))
      (is (true? (:required result)))))

  (testing "rejects map without :type key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must contain :type key"
          (#'core/parse-arg-spec :x {:required false}))))

  (testing "rejects non-boolean :required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required "yes"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required 1}))))

  (testing "rejects invalid arg-spec type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword or map"
          (#'core/parse-arg-spec :x "string")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword or map"
          (#'core/parse-arg-spec :x 123)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword or map"
          (#'core/parse-arg-spec :x [:int]))))

  (testing "error data includes arg info"
    (try
      (#'core/parse-arg-spec :my-arg {:required false})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-arg-spec (:type (ex-data e))))
        (is (= :my-arg (:arg-name (ex-data e))))
        (is (= {:required false} (:arg-spec (ex-data e))))))))


;; === register-base-fns! Core Path Tests ===

(deftest register-base-fns-core-test
  (testing "registers multiple base functions"
    (let [called (atom #{})
          defs {:fn1 {:args {:x :int}
                      :return-type :int
                      :impl (fn [_ _] (swap! called conj :fn1) 1)}
                :fn2 {:args {:y :text}
                      :return-type :text
                      :impl (fn [_ _] (swap! called conj :fn2) "ok")}}]
      (core/register-base-fns! defs)
      ;; Verify functions are registered
      (is (some? (exec/get-base-fn :fn1)))
      (is (some? (exec/get-base-fn :fn2)))
      ;; Call them to verify impl is correct
      ((exec/get-base-fn :fn1) {} nil)
      ((exec/get-base-fn :fn2) {} nil)
      (is (= #{:fn1 :fn2} @called))))

  (testing "handles empty defs"
    (core/register-base-fns! {})
    (is true)))


;; === memoized-uuid-v5 Tests ===

(deftest memoized-uuid-v5-test
  (testing "returns same UUID for same input"
    (let [uuid1 (core/fn-schema-uuid :test-fn)
          uuid2 (core/fn-schema-uuid :test-fn)]
      (is (= uuid1 uuid2))))

  (testing "returns different UUIDs for different inputs"
    (let [uuid1 (core/fn-schema-uuid :fn-a)
          uuid2 (core/fn-schema-uuid :fn-b)]
      (is (not= uuid1 uuid2))))

  (testing "arg-schema-uuid is different from fn-schema-uuid"
    (let [fn-uuid (core/fn-schema-uuid :my-fn)
          arg-uuid (core/arg-schema-uuid :my-fn :x)]
      (is (not= fn-uuid arg-uuid))))

  (testing "same arg-name on different functions produces different UUIDs"
    (let [uuid1 (core/arg-schema-uuid :fn-a :x)
          uuid2 (core/arg-schema-uuid :fn-b :x)]
      (is (not= uuid1 uuid2)))))
