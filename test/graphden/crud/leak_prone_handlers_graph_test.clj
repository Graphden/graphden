(ns ^:integration ^:serial graphden.crud.leak-prone-handlers-graph-test
  "Graph-path tests for HTTP handlers historically prone to the
   `feedback_optional_slot_free_arg_leak` failure mode — fn-defs that
   parent on `:storage-query-identities` (or similar `:required false`
   slot owners) inside a Ring handler closure.

   The leak surfaces when a defensive `:where {:value {}}` pin is
   removed: the optional slot propagates up as a free arg, the
   caller's Ring request map auto-binds it via closure capture, and
   storage rejects the query with `Unknown field 'request-method' in
   where clause`.

   These tests exercise the production GRAPH fn-def chain (not the
   Clojure helpers `bb test` historically covered), so a future pin
   removal triggers `bb test` instead of only `bb test-e2e`. See the
   2026-06-25 update in `feedback_optional_slot_free_arg_leak.md`."
  (:require
    [cheshire.core :as cheshire]
    [clojure.string :as str]
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


(defn- via
  [fn-name request]
  (setup/via-graph *graph* fn-name request))


;; =============================================================================
;; GET /partials/secrets-panel
;; =============================================================================
;;
;; Exercised the original failure during 2026-06-25 `bb test-e2e`:
;;
;;   Unknown field 'request-method' in where clause for entity 'fn'
;;   {:type :validation-error/unknown-field, :entity :fn,
;;    :field :request-method, ...}
;;
;; after the c49f577c pin sweep. Restoring the `:where {:value {}}`
;; pin on `:_list-secrets-leaf-rows-identities` (and 10 siblings)
;; closed the leak. This test invokes the graph chain directly so a
;; future pin removal trips `bb test` immediately.

(deftest partial-secrets-panel-handler-returns-html-without-leak-test
  (testing "GET /partials/secrets-panel renders Secrets section as text/html — no `:where`-via-closure leak"
    (let [response (via :_partial-secrets-panel-handler
                        {:uri "/partials/secrets-panel"
                         :request-method :get
                         :headers {}})]
      (is (= 200 (:status response))
          "happy path returns 200; if 500 with `Unknown field 'request-method'`,
           the `:where {:value {}}` pin on a `:storage-query-identities`
           consumer was removed and the Ring request map leaked into the
           where-clause via closure capture")
      (is (str/starts-with? (or (get-in response [:headers :Content-Type])
                                (get-in response [:headers "Content-Type"])
                                "")
                            "text/html")))))


;; =============================================================================
;; GET /api/services
;; =============================================================================
;;
;; Originally the `_list-services-rows :parent :list-entities` site that
;; surfaced the leak (per the memory). Migrated to direct `:pg-query`
;; chain in Block 1 Step 2; the surviving `:_list-services-fn-identities`
;; pin is still load-bearing for the same closure-capture reason.

(deftest list-services-handler-returns-services-without-leak-test
  (testing "GET /api/services returns the services list — no `:where`-via-closure leak"
    (let [response (via :list-services-handler
                        {:uri "/api/services"
                         :request-method :get
                         :headers {}})
          body (cheshire/parse-string (:body response) true)]
      (is (= 200 (:status response))
          "happy path returns 200; if 500 with `Unknown field 'request-method'`,
           the `:where {:value {}}` pin on `:_list-services-fn-identities`
           was removed and the Ring request map leaked into the storage
           query")
      (is (vector? (:services body))
          "response body parses + has :services vector"))))
