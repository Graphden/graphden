(ns graphden.packages.web.http-realize-body-test
  "Regression tests for the `realize-body` adapter step in
   `web/http/impls.clj`.

   Why this test exists: without it the `:body InputStream` →
   `String` slurp can be silently removed and the symptom is
   downstream — record-type create stops persisting `:name`,
   `:fields` becomes empty, response body says `\"name\":null`. This
   test catches it in <50 ms instead of a live regression hunt.

   Loads the impls.clj dynamically (same pattern as
   `reitit_test.clj`) so private helpers stay private to production
   but reachable from the test."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities :as entities]
    [graphden.crud.request :as request]))


(def ^:private http-impls-ns
  (let [impls-file (io/resource "packages/web/http/impls.clj")]
    (when impls-file
      (load-file (java.io.File/.getPath (io/file impls-file))))
    (find-ns 'graphden.packages.web.http.impls)))


(def ^:private realize-body
  (when http-impls-ns @(ns-resolve http-impls-ns 'realize-body)))


(def ^:private pick-encoding
  ;; `:pick-encoding` is a defbase now — generated signature is
  ;; `(fn [__args ctx])` with args resolved by keyword name.
  (let [base (when http-impls-ns @(ns-resolve http-impls-ns 'pick-encoding-fn))]
    (fn [headers] (base {:headers headers} nil))))


(deftest pick-encoding-is-case-insensitive-test
  ;; Accept-Encoding tokens are case-insensitive (RFC 7231 §5.3.4) — an
  ;; uppercase `BR`/`GZIP` from a non-standard client must still compress,
  ;; not silently fall back to identity.
  (testing "uppercase / mixed-case tokens match"
    (is (= "br"   (pick-encoding {"accept-encoding" "BR"})))
    (is (= "br"   (pick-encoding {"accept-encoding" "Br; q=1.0"})))
    (is (= "gzip" (pick-encoding {"accept-encoding" "GZIP, deflate"}))))
  (testing "lowercase still matches; absent / identity → \"identity\""
    (is (= "br"       (pick-encoding {"accept-encoding" "br"})))
    (is (= "gzip"     (pick-encoding {"accept-encoding" "gzip"})))
    (is (= "identity" (pick-encoding {"accept-encoding" "identity"})))
    (is (= "identity" (pick-encoding {})))))


(defn- stream-of
  "Wrap a UTF-8 byte stream around `s` so the helper sees an actual
   `InputStream` like http-kit hands the handler."
  [s]
  (java.io.ByteArrayInputStream. (String/.getBytes ^String s "UTF-8")))


;; =============================================================================
;; realize-body itself
;; =============================================================================

(deftest realize-body-converts-input-stream-to-string
  (testing "request with `:body` InputStream gains a String :body after realize"
    (let [json "{\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}]}"
          req {:body (stream-of json)}
          realized (realize-body req)]
      (is (string? (:body realized)) ":body is now a String")
      (is (= json (:body realized))
          "the String matches the bytes the stream carried"))))


(deftest realize-body-leaves-non-input-stream-bodies-alone
  (testing "String body passes through unchanged (idempotent)"
    (let [req {:body "{\"already\":\"a string\"}"}]
      (is (= req (realize-body req)))))

  (testing "nil body passes through unchanged"
    (let [req {:method :get}]
      (is (= req (realize-body req)))))

  (testing "map body passes through unchanged (test-only shape)"
    (let [req {:body {:k "v"}}]
      (is (= req (realize-body req))))))


;; =============================================================================
;; The fix as observed downstream
;; =============================================================================

(deftest read-json-body-handles-stream-then-string-via-realize
  (testing "without realize: second read of the stream returns nil — destructive"
    (let [stream (stream-of "{\"name\":\"alice\"}")
          req {:body stream}
          first-read (request/read-json-body req)
          second-read (request/read-json-body req)]
      (is (= {:name "alice"} first-read))
      (is (nil? second-read)
          "stream exhausted — second consumer silently sees nothing")))

  (testing "with realize: every read returns the same parsed map"
    (let [req (realize-body {:body (stream-of "{\"name\":\"alice\"}")})
          first-read (request/read-json-body req)
          second-read (request/read-json-body req)]
      (is (= {:name "alice"} first-read))
      (is (= {:name "alice"} second-read)
          "String body is reusable — the parse is now stable"))))


(deftest parse-create-record-type-stable-after-realize
  (testing "the exact crud handler that surfaced the bug now reads name+fields on every call"
    (let [json (json/generate-string
                 {:name "MyRecord"
                  :fields [{:name "title" :type "text"}
                           {:name "count" :type "int"}]})
          req (realize-body {:body (stream-of json)})
          first (entities/parse-create-record-type req)
          second (entities/parse-create-record-type req)]
      (is (= "MyRecord" (:name first)))
      (is (= 2 (count (:fields first))))
      (is (= first second)
          "without realize-body the second call returned `{:name nil :fields []}`
           and the apply persisted a row with `name:null` + zero slots")))

  (testing "without realize-body — INTENT-CAPTURE: the second call DOES lose data"
    (let [json (json/generate-string {:name "X" :fields [{:name "y" :type "int"}]})
          req {:body (stream-of json)}
          first (entities/parse-create-record-type req)
          second (entities/parse-create-record-type req)]
      (is (= "X" (:name first)))
      (is (= 1 (count (:fields first))))
      (is (nil? (:name second))
          "documents the underlying call-cache miss that realize-body papers over")
      (is (zero? (count (:fields second)))))))
