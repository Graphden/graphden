(ns graphden.packages.web.crud-parse-impls-test
  "Unit tests for the `web/crud-parse` base-fn impls — the wire-format
   boundary every editor form POST crosses before a row is written.

   These four primitives decide what an UNTRUSTED string becomes:
   a form body → a `{string string}` map, a constraint JSON blob →
   a keyword-headed type vector, a `:return-type` form field → a FK
   uuid. Each has a documented soft-failure contract (blank → nil,
   malformed → pass through rather than throw); a regression there
   turns a 400 into a 500 on an unauthenticated path, or silently
   binds the wrong shape into storage."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.packages.records.ids :as ids]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "crud-parse"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays,
   plus the execution context."
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


;; ---------------------------------------------------------------------------
;; :form-decode — `application/x-www-form-urlencoded` → {string string}
;; ---------------------------------------------------------------------------

(deftest form-decode-always-answers-a-string-map
  (testing "ordinary body decodes to string keys and percent-decoded values"
    (is (= {"name" "my fn" "desc" "a+b"}
           (call :form-decode {:string "name=my%20fn&desc=a%2Bb"}))))

  (testing "a REPEATED key collapses to the LAST occurrence, never a vector"
    ;; ring returns a vector for a repeated key. Every :parse-form-body
    ;; consumer binds the result into a `:text` field — a vector there
    ;; is a type error at the storage boundary, not a parse detail.
    (let [decoded (call :form-decode {:string "name=first&name=second"})]
      (is (= {"name" "second"} decoded))
      (is (string? (get decoded "name")))))

  (testing "a body with no `=` at all decodes to {} — not a bare string"
    ;; ring hands back the raw string in that case; a string would blow
    ;; up the `get`-based field readers downstream.
    (is (= {} (call :form-decode {:string "junk-without-equals"}))))

  (testing "nil / empty body → {} (a GET-shaped request with no body)"
    (is (= {} (call :form-decode {:string nil})))
    (is (= {} (call :form-decode {:string ""}))))

  (testing "an EMPTY field is present-and-blank, not absent"
    ;; The editor clears a text field by submitting it empty; the write
    ;; path distinguishes \"\" (clear it) from a missing key (leave it).
    (let [decoded (call :form-decode {:string "description=&name=x"})]
      (is (contains? decoded "description"))
      (is (= "" (get decoded "description")))))

  (testing "form keys stay STRINGS — a form body is never keywordized"
    ;; The JSON body path (`read-json-body`) keywordizes; this one must
    ;; not, or every form consumer's string lookup misses.
    (is (= ["name"] (keys (call :form-decode {:string "name=x"}))))))


;; ---------------------------------------------------------------------------
;; :str-to-uuid
;; ---------------------------------------------------------------------------

(deftest str-to-uuid-fails-soft-on-anything-that-is-not-a-uuid
  (let [u (random-uuid)]
    (testing "a well-formed uuid string parses"
      (is (= u (call :str-to-uuid {:string (str u)}))))
    (testing "garbage / blank / nil → nil instead of IllegalArgumentException"
      ;; This runs on path segments from untrusted URLs; a throw here is
      ;; a 500 on `/api/entities/fn/<garbage>`.
      (is (nil? (call :str-to-uuid {:string "not-a-uuid"})))
      (is (nil? (call :str-to-uuid {:string ""})))
      (is (nil? (call :str-to-uuid {:string nil}))))))


;; ---------------------------------------------------------------------------
;; :parse-constraint — JSON wire form → keyword-headed constraint vector
;; ---------------------------------------------------------------------------

(deftest parse-constraint-rekeywordises-the-wire-form
  (testing "blank input → nil (an absent constraint field)"
    (is (nil? (call :parse-constraint {:raw nil})))
    (is (nil? (call :parse-constraint {:raw ""})))
    (is (nil? (call :parse-constraint {:raw "   "}))))

  (testing "a union's head AND its member type names become keywords"
    ;; The type checker dispatches on `:union` / `:int` as KEYWORDS; if
    ;; these stayed strings the row would store an unusable constraint
    ;; and every check against it would silently pass.
    (is (= [:union :int :null]
           (call :parse-constraint {:raw "[\"union\", \"int\", \"null\"]"}))))

  (testing "a comparison operator (`>=`, `!=`) keywordises too"
    (is (= [:>= 5] (call :parse-constraint {:raw "[\">=\", 5]"})))
    (is (= [:!= 0] (call :parse-constraint {:raw "[\"!=\", 0]"}))))

  (testing "an explicit leading colon is stripped, not doubled"
    (is (= [:union :int] (call :parse-constraint {:raw "[\":union\", \":int\"]"}))))

  (testing "nested constraints recurse"
    (is (= [:and [:>= 1] [:union :int :text]]
           (call :parse-constraint
                 {:raw "[\"and\", [\">=\", 1], [\"union\", \"int\", \"text\"]]"}))))

  (testing "non-identifier strings and numbers survive untouched"
    ;; A refinement's literal payload (a regex, a sentence) must NOT be
    ;; turned into a keyword.
    (is (= [:= "hello world"] (call :parse-constraint {:raw "[\"=\", \"hello world\"]"})))
    (is (= [:>= 3.5] (call :parse-constraint {:raw "[\">=\", 3.5]"}))))

  (testing "unparseable JSON is swallowed — the raw text comes back, no throw"
    ;; Parse failure must not 500 the form POST; the downstream type
    ;; check rejects the junk with a 400 instead.
    (is (= "[\"union\"," (call :parse-constraint {:raw "[\"union\","})))))


;; ---------------------------------------------------------------------------
;; :resolve-type-fn-id — the storage-free arms
;; ---------------------------------------------------------------------------

(deftest resolve-type-fn-id-handles-the-arms-that-never-query
  ;; `:storage` is a sentinel: on each arm below the resolver answers
  ;; before it ever touches storage. If a refactor made any of these
  ;; hit the name query, the sentinel would blow up the test.
  (let [ctx {:storage ::never-queried}]
    (testing "blank → nil (the form field was left empty)"
      (is (nil? (call :resolve-type-fn-id {:v nil} ctx)))
      (is (nil? (call :resolve-type-fn-id {:v ""} ctx))))

    (testing "a raw uuid string passes through as a UUID"
      (let [u (random-uuid)]
        (is (= u (call :resolve-type-fn-id {:v (str u)} ctx)))))

    (testing "a PRIMITIVE name resolves deterministically, never by name query"
      ;; Deterministic ids are what stop a user fn named `int` from
      ;; shadowing the primitive in the type position.
      (is (= (get (ids/primitive-fn-ids) :int)
             (call :resolve-type-fn-id {:v "int"} ctx)))
      (is (= (get (ids/primitive-fn-ids) :fn)
             (call :resolve-type-fn-id {:v "fn"} ctx))))))
