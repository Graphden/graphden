(ns graphden.storage.postgres.codec-test
  "Unit tests for PostgreSQL value codec."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.protocol.interface :as sp])
  (:import
    (org.postgresql.util
      PGobject)))


;; === Helper to create PGobject ===

(defn- make-pgobject
  "Creates a PGobject with given type and value."
  [pg-type pg-value]
  (doto (PGobject.)
    (PGobject/.setType pg-type)
    (PGobject/.setValue pg-value)))


;; === Codec instance for testing ===

(def ^:private test-codec (codec/create-codec))


;; === encode-value Tests ===

(deftest encode-value-jsonb-test
  (testing "encodes map as JSONB PGobject"
    (let [result (sp/encode-value test-codec {:key "value"} {:type :jsonb})]
      (is (instance? PGobject result))
      (is (= "jsonb" (PGobject/.getType result)))
      (is (= "{\"key\":\"value\"}" (PGobject/.getValue result)))))

  (testing "encodes vector as JSONB PGobject"
    (let [result (sp/encode-value test-codec [1 2 3] {:type :jsonb})]
      (is (instance? PGobject result))
      (is (= "[1,2,3]" (PGobject/.getValue result)))))

  (testing "returns nil unchanged"
    (is (nil? (sp/encode-value test-codec nil {:type :jsonb}))))

  (testing "returns already-wrapped PGobject unchanged"
    (let [pg (make-pgobject "jsonb" "{\"existing\": true}")
          result (sp/encode-value test-codec pg {:type :jsonb})]
      (is (identical? pg result)))))


(deftest encode-value-union-test
  (testing "encodes union type as JSONB"
    (let [result (sp/encode-value test-codec {:type :ref :value "123"} {:type :union})]
      (is (instance? PGobject result))
      (is (= "jsonb" (PGobject/.getType result))))))


(deftest encode-value-enum-test
  (testing "encodes keyword as enum PGobject"
    (let [result (sp/encode-value test-codec :active {:type :enum :enum-name :user-status})]
      (is (instance? PGobject result))
      (is (= "user_status" (PGobject/.getType result)))
      (is (= "active" (PGobject/.getValue result)))))

  (testing "converts kebab-case to snake_case"
    (let [result (sp/encode-value test-codec :in-progress {:type :enum :enum-name :task-status})]
      (is (= "in_progress" (PGobject/.getValue result)))))

  (testing "returns nil unchanged"
    (is (nil? (sp/encode-value test-codec nil {:type :enum :enum-name :status}))))

  (testing "non-keyword enum value passes through unchanged"
    (is (= "already-string" (sp/encode-value test-codec "already-string" {:type :enum :enum-name :status})))))


(deftest encode-value-passthrough-test
  (testing "passes through non-special types"
    (is (= "text" (sp/encode-value test-codec "text" {:type :text})))
    (is (= 42 (sp/encode-value test-codec 42 {:type :int})))
    (is (true? (sp/encode-value test-codec true {:type :bool})))))


;; === decode-value Tests ===

(deftest decode-value-jsonb-test
  (testing "decodes JSONB PGobject to map"
    (let [pg (make-pgobject "jsonb" "{\"key\": \"value\", \"num\": 42}")
          result (sp/decode-value test-codec pg {:type :jsonb})]
      (is (= {:key "value" :num 42} result))))

  (testing "decodes JSONB array"
    (let [pg (make-pgobject "jsonb" "[1, 2, 3]")
          result (sp/decode-value test-codec pg nil)]
      (is (= [1 2 3] result))))

  (testing "decodes JSONB null"
    (let [pg (make-pgobject "jsonb" "null")
          result (sp/decode-value test-codec pg nil)]
      (is (nil? result))))

  (testing "decodes nested JSONB"
    (let [pg (make-pgobject "jsonb" "{\"nested\": {\"deep\": true}}")
          result (sp/decode-value test-codec pg nil)]
      (is (= {:nested {:deep true}} result)))))


(deftest decode-value-enum-test
  (testing "decodes enum PGobject to keyword"
    (let [pg (make-pgobject "user_status" "active")
          result (sp/decode-value test-codec pg nil)]
      (is (= :active result))))

  (testing "converts snake_case to kebab-case"
    (let [pg (make-pgobject "task_status" "in_progress")
          result (sp/decode-value test-codec pg nil)]
      (is (= :in-progress result)))))


(deftest decode-value-passthrough-test
  (testing "passes through non-PGobject values"
    (is (= "string" (sp/decode-value test-codec "string" nil)))
    (is (= 42 (sp/decode-value test-codec 42 nil)))
    (is (nil? (sp/decode-value test-codec nil nil)))
    (is (= [1 2 3] (sp/decode-value test-codec [1 2 3] nil)))))


(deftest decode-value-invalid-jsonb-test
  (testing "throws parse error for invalid JSONB"
    (let [pg (make-pgobject "jsonb" "not valid json")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to parse JSONB value"
            (sp/decode-value test-codec pg nil)))))

  (testing "includes context in error for invalid JSONB"
    (let [pg (make-pgobject "jsonb" "{invalid")]
      (try
        (sp/decode-value test-codec pg nil)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :parse-error/jsonb (:type data)))
            (is (some? (:raw-value data)))
            (is (some? (:cause data))))))))

  (testing "truncates long values in error message"
    (let [long-value (str/join (repeat 200 "x"))
          pg (make-pgobject "jsonb" long-value)]
      (try
        (sp/decode-value test-codec pg nil)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [raw-value (:raw-value (ex-data e))]
            (is (str/ends-with? raw-value "...") "should be truncated")
            (is (<= (count raw-value) 104))))))))


;; === encode-row / decode-row Tests ===

(deftest encode-row-test
  (testing "converts kebab-case keys to snake_case"
    (let [result (codec/encode-row {:user-name "john"} nil)]
      (is (= {:user_name "john"} result))))

  (testing "encodes JSONB fields"
    (let [fields {:data {:type :jsonb}}
          result (codec/encode-row {:data {:a 1} :name "test"} fields)]
      (is (= "test" (:name result)))
      (is (instance? PGobject (:data result)))))

  (testing "encodes enum fields"
    (let [fields {:status {:type :enum :enum-name :user-status}}
          result (codec/encode-row {:status :active} fields)]
      (is (instance? PGobject (:status result)))
      (is (= "user_status" (PGobject/.getType (:status result))))))

  (testing "uses fallback for :value column"
    (let [result (codec/encode-row {:value {:any "data"}} nil)]
      (is (instance? PGobject (:value result)))
      (is (= "jsonb" (PGobject/.getType (:value result)))))))


(deftest decode-row-test
  (testing "converts snake_case keys to kebab-case"
    (let [result (codec/decode-row {:user_name "john"} nil)]
      (is (= {:user-name "john"} result))))

  (testing "decodes PGobject values"
    (let [pg (make-pgobject "jsonb" "{\"a\": 1}")
          result (codec/decode-row {:data pg :name "test"} nil)]
      (is (= {:a 1} (:data result)))
      (is (= "test" (:name result))))))


;; === Round-trip Tests ===

(deftest roundtrip-test
  (testing "encode then decode returns original data"
    (let [fields {:config {:type :jsonb}
                  :status {:type :enum :enum-name :user-status}
                  :name {:type :text}}
          original {:config {:nested {:data [1 2 3]}}
                    :status :active
                    :name "test"}
          encoded (codec/encode-row original fields)
          decoded (codec/decode-row encoded fields)]
      (is (= original decoded)))))
