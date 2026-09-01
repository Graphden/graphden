(ns ^:integration graphden.crud.types-api-graph-test
  "Graph-path tests for `/api/types/candidates` + `/api/types/usages` +
   `/api/types/compatible`.

   These exercise the production GRAPH fn-def chain that the HTTP
   handler reaches — distinct from `types-api-test`'s coverage of the
   Clojure helpers (`ta/validate-types-candidates`,
   `ta/apply-types-candidates`) that share the parse/validate stages
   but bypass the graph composition.

   Regression caught at 2026-06-25 (commit `dcc11101`): Phase 5
   `apply-hof-translation` copying env-binding `rt/thunk`s to slot-id
   keys triggered a `call-with-cache` recursion on every
   `POST /api/types/candidates`. `bb test` was green because the
   Clojure-helper-based unit test bypassed the graph chain; smoke
   caught it only at `bb rebuild` against the running server. This
   ns pins the graph chain so the next class of regression surfaces
   in `bb test`."
  (:require
    [cheshire.core :as cheshire]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.graph-harness :as gh :refer [*graph*]]))


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(defn- post-via
  "POST `body` to `fn-name` via the executor — same path the production
   handler reaches. Returns the parsed JSON response body."
  [fn-name body]
  (let [response (setup/via-graph
                   *graph*
                   fn-name
                   {:uri (str "/api/" (name fn-name))
                    :request-method :post
                    :headers {"content-type" "application/json"}
                    :body (cheshire/generate-string body)})]
    (cheshire/parse-string (:body response) true)))


;; =============================================================================
;; /api/types/candidates — Phase 5 HOF translation regression coverage
;; =============================================================================

(deftest types-candidates-expected-any-returns-non-empty-test
  (testing "POST /api/types/candidates {expected: 'any'} → {:ok true :count >0 :candidates [...]}"
    (let [result (post-via :types-candidates-handler {:expected "any"})]
      (is (true? (:ok result))
          "happy path returns ok=true; if this fails with a 500, the SO
           recursion is back — check Phase 5 HOF translation isn't copying
           env-binding thunks to slot-id keys")
      (is (pos? (:count result)))
      (is (vector? (:candidates result))))))


(deftest types-candidates-missing-expected-returns-validation-error-test
  (testing "POST /api/types/candidates {} → {:ok false :error 'Request body must include expected'}"
    (let [result (post-via :types-candidates-handler {})]
      (is (false? (:ok result)))
      (is (string? (:error result))))))


(deftest types-candidates-with-effects-filter-narrows-result-test
  (testing "effects=[] should narrow candidates to pure-only — smaller than the unfiltered set"
    (let [all-result (post-via :types-candidates-handler {:expected "any"})
          pure-result (post-via :types-candidates-handler
                                {:expected "any" :effects []})]
      (is (true? (:ok pure-result)))
      (is (<= (:count pure-result) (:count all-result))
          "pure subset ≤ all"))))


(deftest types-candidates-name-prefix-narrows-result-test
  ;; The name-prefix filter had NO test, which is how it came to be applied in
  ;; the wrong place: inside the per-row callback, AFTER every fn in the registry
  ;; had already been looked up, reshaped and recursively type-checked. The
  ;; endpoint therefore cost O(whole graph) even when the caller had narrowed the
  ;; answer to one fn — and the editor's type-picker almost always has. Measured
  ;; at 3945 fns: identical ~0.11 s whether the prefix matched 57 candidates or
  ;; 1, and 45 s under concurrent load, which is what timed out the type e2e
  ;; tests and got written off as GC pauses.
  ;;
  ;; The scan is now narrowed by prefix BEFORE the map. This test pins the
  ;; BEHAVIOUR that reordering must not change.
  (testing "name-prefix returns only fns whose name starts with it"
    (let [all-result (post-via :types-candidates-handler {:expected "any"})
          prefixed   (post-via :types-candidates-handler
                               {:expected "any" :name-prefix "str-"})]
      (is (true? (:ok prefixed)))
      (is (pos? (:count prefixed))
          "the seed graph has str-* fns; an empty result means the filter now
           runs against something other than the fn name")
      (is (< (:count prefixed) (:count all-result))
          "a prefix must narrow, not pass everything through")
      (is (every? #(str/starts-with? (name (:name %)) "str-")
                  (:candidates prefixed))
          "every returned candidate matches the prefix")))

  (testing "a prefix matching nothing returns an empty candidate set, not an error"
    (let [none (post-via :types-candidates-handler
                         {:expected "any"
                          :name-prefix "zzz-no-such-fn-prefix"})]
      (is (true? (:ok none)))
      (is (zero? (:count none)))
      (is (= [] (:candidates none)))))

  (testing "no prefix still enumerates the whole registry"
    (let [all-result (post-via :types-candidates-handler {:expected "any"})]
      (is (pos? (:count all-result))
          "omitting name-prefix must mean 'no filter', not 'match nothing'"))))


;; =============================================================================
;; /api/types/usages — same env-binding-thunk pattern, separate graph chain
;; =============================================================================

(deftest types-usages-missing-target-returns-validation-error-test
  (testing "POST /api/types/usages {} → {:ok false :error '...'}"
    (let [result (post-via :types-usages-handler {})]
      (is (false? (:ok result))))))


(deftest types-usages-accepts-fn-id-synonym-test
  (testing "POST {fn-id: <uuid>} parses — the body the /api/fns/usages
            alias (inspector Used-by) sends; an unknown id is an empty
            result, not a validation error"
    (let [result (post-via :types-usages-handler {:fn-id (str (random-uuid))})]
      (is (true? (:ok result)))
      (is (zero? (:count result))))))


;; =============================================================================
;; /api/types/compatible — env-bindings + nested validation chain
;; =============================================================================

(deftest types-compatible-int-subset-of-int-returns-ok-test
  (testing "POST /api/types/compatible {expected:'int' candidate:'int'} → {:ok true}"
    (let [result (post-via :types-compatible-handler
                           {:expected "int" :candidate "int"})]
      (is (true? (:ok result))))))


(deftest types-compatible-text-not-subset-of-int-returns-incompatible-test
  (testing "POST /api/types/compatible {expected:'int' candidate:'text'} → {:ok false :reason ...}"
    (let [result (post-via :types-compatible-handler
                           {:expected "int" :candidate "text"})]
      (is (false? (:ok result)))
      (is (some? (:reason result))))))
