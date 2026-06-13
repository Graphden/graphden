(ns ^:integration graphden.packages.app.layout-api-errors-test
  "Regression tests for the API-boundary error handling in the layout
   pipeline. Before the fix, raw exceptions bubbled up — the client got
   a 500 with a Jackson parse-error stack tail (the `Source: REDACTED`
   marker) or a bare `:execution-error/not-found` message. Each error
   case is now pinned to the cleaned-up `{:ok false :error <message>}`
   shape that travels through `:_layout-place` and the JSON encoder.

   The pipeline lives entirely in graph fn-defs (`:_parse-layout-request`
   wraps `:try`+`:cond` over the three exception classes;
   `:_layout-build-elements` / `:_layout-place` use `:if` pass-through
   guards). These tests run those fn-defs through the executor against
   a real package-synced graph."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [t]
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
         (try
           (binding [*context* (:ctx graph)
                     *storage* (:storage graph)]
             (t))
           (finally (sp/close (:storage graph))))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(defn- run
  [nm args]
  (exec/execute *context* (fn-id nm) args))


(defn- stream-of
  [s]
  (java.io.ByteArrayInputStream. (String/.getBytes ^String s "UTF-8")))


;; =============================================================================
;; :_parse-layout-request catches its three user-input failure modes
;; =============================================================================

(deftest parse-malformed-json-returns-ok-false
  (testing "an unparseable body produces a clean {:ok false :error} map, not a bubbled Jackson exception"
    (let [result (run "_parse-layout-request" {:request {:body (stream-of "not json")}})]
      (is (false? (:ok result)))
      (is (= "Request body is not valid JSON" (:error result))))))


(deftest parse-missing-root-id-returns-ok-false
  (testing "a well-formed body without `root-id` is a clean rejection"
    (let [result (run "_parse-layout-request" {:request {:body (stream-of "{}")}})]
      (is (false? (:ok result)))
      (is (= "Request body must contain 'root-id'" (:error result))))))


(deftest parse-bad-uuid-returns-ok-false
  (testing "a root-id string that isn't a UUID is also a clean rejection"
    (let [result (run "_parse-layout-request"
                      {:request {:body (stream-of "{\"root-id\":\"not-a-uuid\"}")}})]
      (is (false? (:ok result)))
      (is (re-find #"(?i)invalid" (:error result))))))


(deftest parse-non-map-expansions-returns-ok-false
  (testing "expansions sent as a number leaks `Don't know how to create ISeq from: java.lang.Long` without the guard"
    (let [result (run "_parse-layout-request"
                      {:request {:body (stream-of
                                         "{\"root-id\":\"00000000-0000-0000-0000-000000000000\",\"expansions\":99999}")}})]
      (is (false? (:ok result)))
      (is (re-find #"expansions.*must be a map" (:error result)))))

  (testing "expansions sent as a string leaks `nth not supported on this type: Character` without the guard"
    (let [result (run "_parse-layout-request"
                      {:request {:body (stream-of
                                         "{\"root-id\":\"00000000-0000-0000-0000-000000000000\",\"expansions\":\"oops\"}")}})]
      (is (false? (:ok result)))
      (is (re-find #"expansions.*must be a map" (:error result)))))

  (testing "omitted expansions still allowed (defaults to empty map)"
    (let [result (run "_parse-layout-request"
                      {:request {:body (stream-of
                                         "{\"root-id\":\"00000000-0000-0000-0000-000000000000\"}")}})]
      (is (not (false? (:ok result))))
      (is (= {} (:expansions result))))))


;; =============================================================================
;; :_layout-build-elements / :_layout-place pass an upstream error through
;; =============================================================================

(deftest build-elements-passes-parse-error-through
  (testing "an `{:ok false}` parsed value travels through build untouched"
    (let [parsed-error {:ok false :error "boom"}
          result (run "_layout-build-elements" {:graph {} :parsed parsed-error})]
      (is (= parsed-error result)))))


(deftest place-elements-passes-error-through
  (testing "_layout-place forwards `:ok false` payload to the JSON encoder unchanged"
    (let [err {:ok false :error "boom"}]
      (is (= err (run "_layout-place" {:elements err}))))))
