(ns ^:integration ^:serial graphden.crud.types-api-graph-test
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
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *graph* nil)


(defn- graph-fixture
  [t]
  (exec/with-clean-registry
    #(let [graph (setup/bootstrap-crud-graph-from-golden!)
           storage (:storage graph)]
       (try
         (binding [*graph* graph]
           (t))
         (finally (sp/close storage))))))


(use-fixtures :once
  (setup/create-container-fixture)
  graph-fixture)


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


;; =============================================================================
;; /api/types/usages — same env-binding-thunk pattern, separate graph chain
;; =============================================================================

(deftest types-usages-missing-target-returns-validation-error-test
  (testing "POST /api/types/usages {} → {:ok false :error '...'}"
    (let [result (post-via :types-usages-handler {})]
      (is (false? (:ok result))))))


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
