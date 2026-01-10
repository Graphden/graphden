(ns graphden.fn-registry.core-test
  "Tests for fn-registry.core - base function registration and storage sync."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.core :as core]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util
      UUID)))


;; Fixture to clean executor registry between tests
(defn clean-executor-registry-fixture
  [f]
  (exec/clear-base-fns!)
  (try
    (f)
    (finally
      (exec/clear-base-fns!))))


(use-fixtures :each clean-executor-registry-fixture)


;; === register-base-fns! tests ===

(deftest register-base-fns!-test
  (testing "registers functions to executor registry"
    (let [add-fn (fn [args _ctx] (+ @(:a args) @(:b args)))
          defs {:add {:args {:a :int :b :int}
                      :return-type :int
                      :impl add-fn}}]
      (core/register-base-fns! defs)
      (is (= add-fn (exec/get-base-fn :add)))))

  (testing "registers multiple functions"
    (let [fn1 (fn [_ _] 1)
          fn2 (fn [_ _] 2)
          defs {:fn1 {:args {} :return-type :int :impl fn1}
                :fn2 {:args {} :return-type :int :impl fn2}}]
      (core/register-base-fns! defs)
      (is (= fn1 (exec/get-base-fn :fn1)))
      (is (= fn2 (exec/get-base-fn :fn2)))))

  (testing "handles empty defs"
    (is (nil? (core/register-base-fns! {})))))


;; === fn-schema-uuid tests ===

(deftest fn-schema-uuid-test
  (testing "generates deterministic UUID"
    (let [uuid1 (core/fn-schema-uuid :add)
          uuid2 (core/fn-schema-uuid :add)]
      (is (uuid? uuid1))
      (is (= uuid1 uuid2))))

  (testing "different names produce different UUIDs"
    (let [uuid1 (core/fn-schema-uuid :add)
          uuid2 (core/fn-schema-uuid :subtract)]
      (is (not= uuid1 uuid2))))

  (testing "rejects invalid identifiers"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"cannot be empty"
          (core/fn-schema-uuid "")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (core/fn-schema-uuid "has spaces")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid characters"
          (core/fn-schema-uuid "123starts-with-digit")))))


;; === arg-schema-uuid tests ===

(deftest arg-schema-uuid-test
  (testing "generates deterministic UUID"
    (let [uuid1 (core/arg-schema-uuid :add :a)
          uuid2 (core/arg-schema-uuid :add :a)]
      (is (uuid? uuid1))
      (is (= uuid1 uuid2))))

  (testing "different arg names produce different UUIDs"
    (let [uuid1 (core/arg-schema-uuid :add :a)
          uuid2 (core/arg-schema-uuid :add :b)]
      (is (not= uuid1 uuid2))))

  (testing "different fn names produce different UUIDs"
    (let [uuid1 (core/arg-schema-uuid :add :a)
          uuid2 (core/arg-schema-uuid :subtract :a)]
      (is (not= uuid1 uuid2))))

  (testing "rejects invalid fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"cannot be empty"
          (core/arg-schema-uuid "" :a))))

  (testing "rejects invalid arg-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"cannot be empty"
          (core/arg-schema-uuid :add ""))))

  (testing "accepts predicate function names with ?"
    (let [uuid (core/arg-schema-uuid :empty? :coll)]
      (is (uuid? uuid)))))


;; === validate-fn-def! tests ===

(deftest validate-fn-def!-test
  (testing "passes for valid definition"
    (is (nil? (core/validate-fn-def! :add {:args {:a :int :b :int}
                                           :return-type :int}))))

  (testing "passes for definition with optional args"
    (is (nil? (core/validate-fn-def! :greet {:args {:name :text
                                                    :greeting {:type :text :required false}}
                                             :return-type :text}))))

  (testing "rejects non-keyword fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"fn-name must be a keyword"
          (core/validate-fn-def! "add" {:args {} :return-type :int}))))

  (testing "rejects missing return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must include :return-type"
          (core/validate-fn-def! :add {:args {}}))))

  (testing "rejects unknown return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown return type"
          (core/validate-fn-def! :add {:args {} :return-type :unknown-type}))))

  (testing "rejects unknown arg type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown arg type"
          (core/validate-fn-def! :add {:args {:a :unknown-type} :return-type :int}))))

  (testing "rejects non-boolean :required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":required must be a boolean"
          (core/validate-fn-def! :add {:args {:a {:type :int :required "yes"}}
                                       :return-type :int}))))

  (testing "rejects arg-spec map without :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must contain :type key"
          (core/validate-fn-def! :add {:args {:a {:required true}}
                                       :return-type :int}))))

  (testing "rejects invalid arg-spec type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a keyword or map"
          (core/validate-fn-def! :add {:args {:a [1 2 3]}
                                       :return-type :int})))))


;; === validate-all-defs! tests ===

(deftest validate-all-defs!-test
  (testing "passes for valid definitions"
    (is (nil? (core/validate-all-defs!
                {:add {:args {:a :int :b :int} :return-type :int}
                 :concat {:args {:a :text :b :text} :return-type :text}}))))

  (testing "fails on first invalid definition"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must include :return-type"
          (core/validate-all-defs!
            {:valid {:args {:a :int} :return-type :int}
             :invalid {:args {:a :int}}})))))


;; === sync-defs-to-storage! tests ===

(deftest sync-defs-to-storage!-test
  (testing "creates fn-schema and arg-schemas in storage"
    (let [storage (mem/create-storage)
          defs {:add {:args {:a :int :b :int}
                      :return-type :int
                      :impl (fn [_ _] nil)}}
          result (core/sync-defs-to-storage! storage defs)]
      (is (= 1 (get-in result [:fn-schemas :created])))
      (is (= 2 (get-in result [:arg-schemas :created])))
      ;; Verify fn-schema exists
      (let [fn-schema-id (core/fn-schema-uuid :add)
            fn-schema (sp/read-entity storage :fn-schema fn-schema-id)]
        (is (some? fn-schema))
        (is (= "add" (:name fn-schema)))
        (is (= :int (:returned-type fn-schema))))
      ;; Verify arg-schemas exist
      (let [arg-a-id (core/arg-schema-uuid :add :a)
            arg-a (sp/read-entity storage :arg-schema arg-a-id)]
        (is (some? arg-a))
        (is (= "a" (:name arg-a)))
        (is (= :int (:type arg-a))))))

  (testing "is idempotent - second sync updates instead of creates"
    (let [storage (mem/create-storage)
          defs {:add {:args {:a :int} :return-type :int :impl (fn [_ _] nil)}}
          result1 (core/sync-defs-to-storage! storage defs)
          result2 (core/sync-defs-to-storage! storage defs)]
      (is (= 1 (get-in result1 [:fn-schemas :created])))
      (is (zero? (get-in result2 [:fn-schemas :created])))
      (is (= 1 (get-in result2 [:fn-schemas :updated])))))

  (testing "handles optional arguments"
    (let [storage (mem/create-storage)
          defs {:greet {:args {:name :text
                               :greeting {:type :text :required false}}
                        :return-type :text
                        :impl (fn [_ _] nil)}}
          _ (core/sync-defs-to-storage! storage defs)
          greeting-id (core/arg-schema-uuid :greet :greeting)
          greeting-arg (sp/read-entity storage :arg-schema greeting-id)]
      (is (false? (:required greeting-arg)))))

  (testing "validates before syncing - no partial sync on error"
    (let [storage (mem/create-storage)
          defs {:valid {:args {:a :int} :return-type :int :impl (fn [_ _] nil)}
                :invalid {:args {:a :int}}}]  ; missing return-type
      (is (thrown? clojure.lang.ExceptionInfo
            (core/sync-defs-to-storage! storage defs)))
      ;; Neither should be in storage
      (is (nil? (sp/read-entity storage :fn-schema (core/fn-schema-uuid :valid))))))

  (testing "updates fn-schema when return-type changes"
    (let [storage (mem/create-storage)
          defs1 {:compute {:args {:a :int} :return-type :int :impl (fn [_ _] nil)}}
          defs2 {:compute {:args {:a :int} :return-type :numeric :impl (fn [_ _] nil)}}]
      (core/sync-defs-to-storage! storage defs1)
      (let [before (sp/read-entity storage :fn-schema (core/fn-schema-uuid :compute))]
        (is (= :int (:returned-type before))))
      (core/sync-defs-to-storage! storage defs2)
      (let [after (sp/read-entity storage :fn-schema (core/fn-schema-uuid :compute))]
        (is (= :numeric (:returned-type after))))))

  (testing "updates arg-schema when type changes"
    (let [storage (mem/create-storage)
          defs1 {:compute {:args {:x :int} :return-type :int :impl (fn [_ _] nil)}}
          defs2 {:compute {:args {:x :text} :return-type :int :impl (fn [_ _] nil)}}]
      (core/sync-defs-to-storage! storage defs1)
      (let [before (sp/read-entity storage :arg-schema (core/arg-schema-uuid :compute :x))]
        (is (= :int (:type before))))
      (core/sync-defs-to-storage! storage defs2)
      (let [after (sp/read-entity storage :arg-schema (core/arg-schema-uuid :compute :x))]
        (is (= :text (:type after))))))

  (testing "updates arg-schema when required changes"
    (let [storage (mem/create-storage)
          defs1 {:compute {:args {:x :int} :return-type :int :impl (fn [_ _] nil)}}
          defs2 {:compute {:args {:x {:type :int :required false}} :return-type :int :impl (fn [_ _] nil)}}]
      (core/sync-defs-to-storage! storage defs1)
      (let [before (sp/read-entity storage :arg-schema (core/arg-schema-uuid :compute :x))]
        (is (true? (:required before))))
      (core/sync-defs-to-storage! storage defs2)
      (let [after (sp/read-entity storage :arg-schema (core/arg-schema-uuid :compute :x))]
        (is (false? (:required after)))))))


;; === Additional validation edge case tests ===

(deftest validate-identifier-edge-cases-test
  (testing "rejects identifier exceeding 128 chars"
    (let [long-name (keyword (str/join (repeat 130 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (core/fn-schema-uuid long-name)))))

  (testing "accepts predicate names ending with ?"
    (is (uuid? (core/fn-schema-uuid :valid?)))
    (is (uuid? (core/fn-schema-uuid :empty?)))
    (is (uuid? (core/arg-schema-uuid :every? :pred))))

  (testing "accepts underscore prefix"
    (is (uuid? (core/fn-schema-uuid :_private)))
    (is (uuid? (core/arg-schema-uuid :_helper :_arg))))

  (testing "accepts hyphens in names"
    (is (uuid? (core/fn-schema-uuid :my-func)))
    (is (uuid? (core/arg-schema-uuid :compute-value :input-data))))

  (testing "accepts mixed case"
    (is (uuid? (core/fn-schema-uuid :myFunc)))
    (is (uuid? (core/arg-schema-uuid :getValue :argName)))))


;; === UUID v5 internal tests ===

(deftest uuid-v5-internal-test
  (testing "uuid-v5 throws for non-UUID namespace"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 "not-a-uuid" "name"))))

  (testing "uuid-v5 throws for non-string name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"name-str must be a string"
          (#'core/uuid-v5 (random-uuid) :not-a-string))))

  (testing "uuid-v5 throws for name exceeding max length"
    (let [long-str (str/join (repeat 300 "x"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"name-str exceeds maximum length"
            (#'core/uuid-v5 (random-uuid) long-str)))))

  (testing "uuid-v5 throws for name with null bytes"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"name-str contains null bytes"
          (#'core/uuid-v5 (random-uuid) "has\u0000null"))))

  (testing "uuid-v5 generates consistent UUIDs"
    (let [ns-uuid (random-uuid)]
      (is (= (#'core/uuid-v5 ns-uuid "test")
             (#'core/uuid-v5 ns-uuid "test")))))

  (testing "uuid-v5 generates version 5 UUIDs"
    (let [uuid (#'core/uuid-v5 (random-uuid) "test")]
      ;; UUID version is encoded in bits 12-15 of time_hi_and_version (byte 6)
      ;; For version 5, this should be 0x5
      (is (= 5 (UUID/.version uuid))))))
