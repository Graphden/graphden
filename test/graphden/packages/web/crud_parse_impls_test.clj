(ns graphden.packages.web.crud-parse-impls-test
  "Unit tests for the web CRUD wire codecs. Graph integration tests cover
   the form-body fallback and repeated-key policy after Ring decoding."
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
;; :_form-decode-raw — Ring's `application/x-www-form-urlencoded` codec
;; ---------------------------------------------------------------------------

(deftest form-decode-raw-preserves-codec-results
  (testing "ordinary body decodes to string keys and percent-decoded values"
    (is (= {"name" "my fn" "desc" "a+b"}
           (call :_form-decode-raw {:string "name=my%20fn&desc=a%2Bb"}))))
  (testing "repeated keys remain vectors until graph policy selects the last"
    (is (= {"name" ["first" "second"]}
           (call :_form-decode-raw {:string "name=first&name=second"}))))
  (testing "a bare string stays bare until the graph fallback"
    (is (= "junk-without-equals"
           (call :_form-decode-raw {:string "junk-without-equals"})))))


;; ---------------------------------------------------------------------------
;; :str-to-uuid
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

  (testing "a comparison operator (`>=`) keywordises too"
    (is (= [:>= 5] (call :parse-constraint {:raw "[\">=\", 5]"}))))

  (testing "`!=` canonicalises to the checker's `:not=`"
    ;; `base-allowed-ops` knows `:not=` only — a `:!=` row was
    ;; rejected as "not legal on base type" for EVERY base.
    (is (= [:not= 0] (call :parse-constraint {:raw "[\"!=\", 0]"})))
    (is (= [:not= ""] (call :parse-constraint {:raw "[\":not=\", \"\"]"})))
    (is (= [:and [:>= 1] [:not= 5]]
           (call :parse-constraint {:raw "[\"and\", [\">=\", 1], [\"!=\", 5]]"}))))

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
    (is (= [:= "x"] (call :parse-constraint {:raw "[\"=\", \"x\"]"
                                             :base-ref "text"})))
    (is (= [:in ["a" "b"]]
           (call :parse-constraint {:raw "[\"in\", [\"a\", \"b\"]]"
                                    :base-ref "text"})))
    (is (= [:>= 3.5] (call :parse-constraint {:raw "[\">=\", 3.5]"}))))

  (testing "keyword refinements decode operands without changing text refinements"
    (is (= [:in [:get :post]]
           (call :parse-constraint {:raw "[\"in\", [\"get\", \"post\"]]"
                                    :base-ref "keyword"})))
    (is (= [:not= :patch]
           (call :parse-constraint {:raw "[\":not=\", \"patch\"]"
                                    :base-ref "keyword"})))
    (is (= [:= ":literal"]
           (call :parse-constraint {:raw "[\"=\", \":literal\"]"
                                    :base-ref "text"}))))

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
