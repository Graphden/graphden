(ns graphden.crud.request-test
  "Unit tests for the pure HTTP-boundary helpers in `graphden.crud.request`.

   This namespace has no `graphden.crud.*` dependency and touches no
   storage — every fn here is a pure transform over request maps /
   strings, so the tests need no Postgres fixture."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.request :as req])
  (:import
    (java.io
      ByteArrayInputStream)))


;; ============================================================================
;; parse-query-string
;; ============================================================================

(deftest parse-query-string-test
  (testing "nil / blank input → nil"
    (is (nil? (req/parse-query-string nil)))
    (is (nil? (req/parse-query-string "")))
    (is (nil? (req/parse-query-string "   "))))

  (testing "single and multiple pairs"
    (is (= {"a" "1"} (req/parse-query-string "a=1")))
    (is (= {"a" "1" "b" "2"} (req/parse-query-string "a=1&b=2"))))

  (testing "key with no value yields empty string"
    (is (= {"a" ""} (req/parse-query-string "a")))
    (is (= {"a" ""} (req/parse-query-string "a="))))

  (testing "URL-decoding of value (percent + plus)"
    (is (= {"q" "hello world"} (req/parse-query-string "q=hello%20world")))
    (is (= {"q" "hello world"} (req/parse-query-string "q=hello+world")))
    (is (= {"q" "a+b"} (req/parse-query-string "q=a%2Bb"))))

  (testing "repeated key — last occurrence wins (into map)"
    (is (= {"a" "2"} (req/parse-query-string "a=1&a=2"))))

  (testing "malformed percent-encoding fails soft to the raw string (no throw)"
    ;; This parses UNTRUSTED public form / query input — a lone `%` or bad
    ;; hex must NOT throw (which would 500 login / registration endpoints).
    (is (= {"email" "a%"} (req/parse-query-string "email=a%")))
    (is (= {"x" "%zz"} (req/parse-query-string "x=%zz")))
    (is (= {"a%" "1"} (req/parse-query-string "a%=1"))
        "malformed KEY also fails soft")
    (is (= {"ok" "1" "bad" "%"} (req/parse-query-string "ok=1&bad=%"))
        "a good pair alongside a malformed one still parses")))


;; ============================================================================
;; require-storage
;; ============================================================================

(deftest require-storage-test
  (testing "returns the storage when present"
    (let [storage (Object.)]
      (is (identical? storage (req/require-storage {:storage storage})))))

  (testing "throws typed error when storage missing or nil"
    (doseq [ctx [{} {:storage nil}]]
      (let [ex (try (req/require-storage ctx)
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (= :execution-error/missing-storage (:type (ex-data ex))))))))


;; ============================================================================
;; entity-type-from-string
;; ============================================================================

(deftest entity-type-from-string-test
  (testing "every recognised entity type maps to its keyword"
    (is (= :fn (req/entity-type-from-string "fn")))
    (is (= :ns (req/entity-type-from-string "ns")))
    (is (= :slot (req/entity-type-from-string "slot")))
    (is (= :fn-slot (req/entity-type-from-string "fn-slot")))
    (is (= :binding (req/entity-type-from-string "binding")))
    (is (= :binding-list-item (req/entity-type-from-string "binding-list-item"))))

  (testing "unknown / nil → nil"
    (is (nil? (req/entity-type-from-string "bogus")))
    (is (nil? (req/entity-type-from-string "")))
    (is (nil? (req/entity-type-from-string nil)))))


;; ============================================================================
;; parse-uri-segments
;; ============================================================================

(deftest parse-uri-segments-test
  (testing "nil uri → nil"
    (is (nil? (req/parse-uri-segments nil))))

  (testing "/api/entities/:type — no id"
    (is (= {:type-str "fn" :id-str nil}
           (req/parse-uri-segments "/api/entities/fn"))))

  (testing "/api/entities/:type/:id"
    (is (= {:type-str "slot" :id-str "abc-123"}
           (req/parse-uri-segments "/api/entities/slot/abc-123"))))

  (testing "/api/sequence/append/:fn-id"
    (is (= {:fn-id-str "FID"}
           (req/parse-uri-segments "/api/sequence/append/FID"))))

  (testing "/api/sequence/item/:item-id"
    (is (= {:item-id-str "IID"}
           (req/parse-uri-segments "/api/sequence/item/IID"))))

  (testing "/api/bindings/:id/tighten-fn-effects"
    (is (= {:binding-id-str "BID"}
           (req/parse-uri-segments "/api/bindings/BID/tighten-fn-effects"))))

  (testing "unrecognised shapes → empty map"
    (is (= {} (req/parse-uri-segments "/health")))
    (is (= {} (req/parse-uri-segments "/")))
    (is (= {} (req/parse-uri-segments "/api/other/thing")))))


;; ============================================================================
;; extract-entity-params
;; ============================================================================

(deftest extract-entity-params-test
  (testing "prefers reitit :path-params when present"
    (is (= {:type-str "fn" :id-str "x" :entity-type :fn}
           (req/extract-entity-params {:path-params {:type "fn" :id "x"}
                                       :uri "/ignored"}))))

  (testing "falls back to URI parsing when :path-params absent"
    (is (= {:type-str "slot" :id-str "y" :entity-type :slot}
           (req/extract-entity-params {:uri "/api/entities/slot/y"}))))

  (testing "no path-params and no recognisable uri → all nil"
    (is (= {:type-str nil :id-str nil :entity-type nil}
           (req/extract-entity-params {})))))


;; ============================================================================
;; read-json-body
;; ============================================================================

(deftest read-json-body-test
  (testing "nil body → nil"
    (is (nil? (req/read-json-body {:body nil}))))

  (testing "already-parsed map passes through unchanged"
    (is (= {:a 1} (req/read-json-body {:body {:a 1}}))))

  (testing "JSON string body is parsed with keyword keys"
    (is (= {:a 1 :b "two"}
           (req/read-json-body {:body "{\"a\":1,\"b\":\"two\"}"}))))

  (testing "blank string body → nil"
    (is (nil? (req/read-json-body {:body ""})))
    (is (nil? (req/read-json-body {:body "   "}))))

  (testing "InputStream body is parsed with keyword keys"
    (let [stream (ByteArrayInputStream.
                   (String/.getBytes "{\"x\":2}" "UTF-8"))]
      (is (= {:x 2} (req/read-json-body {:body stream})))))

  (testing "non-string/stream/map body → nil"
    (is (nil? (req/read-json-body {:body 42}))))

  (testing "malformed JSON string → typed :validation-error/malformed-json (→400, not 500)"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                   (req/read-json-body {:body "{"})))]
      (is (= :validation-error/malformed-json (:type (ex-data ex))))))

  (testing "malformed JSON InputStream → typed error, not raw JsonParseException"
    (let [stream (ByteArrayInputStream. (String/.getBytes "{not json" "UTF-8"))
          ex (is (thrown? clojure.lang.ExceptionInfo
                   (req/read-json-body {:body stream})))]
      (is (= :validation-error/malformed-json (:type (ex-data ex)))))))


;; ============================================================================
;; parse-uuid-or-clear
;; ============================================================================

(deftest parse-uuid-or-clear-test
  (testing "blank / nil → nil (clear)"
    (is (nil? (req/parse-uuid-or-clear nil)))
    (is (nil? (req/parse-uuid-or-clear "")))
    (is (nil? (req/parse-uuid-or-clear "   "))))

  (testing "valid UUID string → UUID value"
    (let [u (random-uuid)]
      (is (= u (req/parse-uuid-or-clear (str u))))))

  (testing "malformed UUID string → nil (was: throws — caused 500s on HTTP path)"
    (is (nil? (req/parse-uuid-or-clear "not-a-uuid"))))

  (testing "non-string input → nil"
    (is (nil? (req/parse-uuid-or-clear 12345)))
    (is (nil? (req/parse-uuid-or-clear :keyword)))
    (is (nil? (req/parse-uuid-or-clear {:nested true})))))
