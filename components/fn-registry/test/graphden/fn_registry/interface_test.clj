(ns graphden.fn-registry.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.core :as exec-core]
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

(defn literal-thunk
  "Creates a literal thunk for testing."
  [value]
  (reify exec-core/IThunk
    (force-value [_ _] value)))


;; === Wrapper Tests ===

(deftest wrap-base-fn-test
  (testing "wrap-base-fn wraps a simple function"
    (let [fn-def {:args {:a :numeric :b :numeric}
                  :return-type :numeric
                  :impl (fn [{:keys [a b]} _ctx] (+ a b))}
          wrapped (registry/wrap-base-fn fn-def)
          result (wrapped {:a (literal-thunk 3) :b (literal-thunk 4)} nil)]
      (is (= 7 result))))

  (testing "wrap-base-fn handles lazy-args"
    (let [call-count (atom 0)
          lazy-thunk (reify exec-core/IThunk
                       (force-value
                         [_ _]
                         (swap! call-count inc)
                         42))
          fn-def {:args {:a :bool :b :any}
                  :lazy-args #{:b}
                  :return-type :any
                  :impl (fn [{:keys [a b]} ctx]
                          (if a
                            (exec/force-value b ctx)
                            :skipped))}
          wrapped (registry/wrap-base-fn fn-def)]
      ;; When a is false, b should not be forced
      (reset! call-count 0)
      (is (= :skipped (wrapped {:a (literal-thunk false) :b lazy-thunk} nil)))
      (is (zero? @call-count))

      ;; When a is true, b should be forced
      (reset! call-count 0)
      (is (= 42 (wrapped {:a (literal-thunk true) :b lazy-thunk} nil)))
      (is (= 1 @call-count))))

  (testing "wrap-base-fn handles missing optional args"
    (let [fn-def {:args {:required :numeric :optional {:type :numeric :required false}}
                  :return-type :numeric
                  :impl (fn [{:keys [required optional]} _ctx]
                          (+ required (or optional 0)))}
          wrapped (registry/wrap-base-fn fn-def)]
      (is (= 5 (wrapped {:required (literal-thunk 5)} nil)))
      (is (= 8 (wrapped {:required (literal-thunk 5) :optional (literal-thunk 3)} nil))))))


;; === Registration Tests ===

(deftest register-base-fns-test
  (testing "register-base-fns! registers functions"
    (let [defs {:test-add {:args {:a :numeric :b :numeric}
                           :return-type :numeric
                           :impl (fn [{:keys [a b]} _ctx] (+ a b))}
                :test-sub {:args {:a :numeric :b :numeric}
                           :return-type :numeric
                           :impl (fn [{:keys [a b]} _ctx] (- a b))}}]
      (registry/register-base-fns! defs)
      (is (some? (exec/get-base-fn :test-add)))
      (is (some? (exec/get-base-fn :test-sub)))

      ;; Test that they work
      (let [add-fn (exec/get-base-fn :test-add)
            sub-fn (exec/get-base-fn :test-sub)]
        (is (= 7 (add-fn {:a (literal-thunk 3) :b (literal-thunk 4)} nil)))
        (is (= -1 (sub-fn {:a (literal-thunk 3) :b (literal-thunk 4)} nil)))))))


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
        (is (= :my-arg (:arg-name (ex-data e))))))))


;; === Wrapper Edge Cases ===

(deftest wrap-base-fn-edge-cases-test
  (testing "wrap-base-fn handles empty args map"
    (let [fn-def {:args {}
                  :return-type :text
                  :impl (fn [_ _] "no args")}
          wrapped (registry/wrap-base-fn fn-def)
          result (wrapped {} nil)]
      (is (= "no args" result))))

  (testing "wrap-base-fn handles nil thunk gracefully"
    (let [fn-def {:args {:a :numeric :b :numeric}
                  :return-type :numeric
                  :impl (fn [{:keys [a b]} _ctx]
                          (+ (or a 0) (or b 0)))}
          wrapped (registry/wrap-base-fn fn-def)
          result (wrapped {:a (literal-thunk 5)} nil)]  ; b is nil
      (is (= 5 result))))

  (testing "wrap-base-fn with all lazy args"
    (let [force-count (atom 0)
          fn-def {:args {:a :any :b :any}
                  :lazy-args #{:a :b}
                  :return-type :any
                  :impl (fn [{:keys [a]} ctx]
                          ;; Only force a, not b (b is intentionally not used to test lazy behavior)
                          (exec/force-value a ctx))}
          thunk-a (reify exec-core/IThunk
                    (force-value [_ _] (swap! force-count inc) 1))
          thunk-b (reify exec-core/IThunk
                    (force-value [_ _] (swap! force-count inc) 2))
          wrapped (registry/wrap-base-fn fn-def)]
      (wrapped {:a thunk-a :b thunk-b} nil)
      (is (= 1 @force-count) "Only a should be forced"))))
