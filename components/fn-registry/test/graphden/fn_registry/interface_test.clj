(ns graphden.fn-registry.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.core :as core]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util
      UUID)))


;; === Test Fixtures ===

(defn with-clean-registry
  [f]
  (exec/clear-base-fns!)
  (try
    (f)
    (finally
      (exec/clear-base-fns!))))


(use-fixtures :each with-clean-registry)


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
        (is (= -1 (sub-fn {:a (literal-delay 3) :b (literal-delay 4)} nil)))))))


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
