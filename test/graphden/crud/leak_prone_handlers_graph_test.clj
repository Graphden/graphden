(ns ^:integration ^:serial graphden.crud.leak-prone-handlers-graph-test
  "`^:serial` — the list-secrets handler execution intermittently threw a
  nil-callable NPE (`invoke-fn` `(func arg)`, func=nil) only under the
  parallel runner. Serialising the smoke-pass aggressor (its missing
  ^:serial meta) cut the flake but didn't fully clear it, and the exact
  shared-state source resisted an extensive investigation (see memory
  `project_parallel_test_races` for the ruled-out candidates + repro
  recipe). Runs outside the pool until the root is pinned.

  Graph-path tests for HTTP handlers historically prone to the
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
;; GET /api/secrets  (:list-secrets-handler)
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
;;
;; Originally pinned via the `/partials/secrets-panel` handler; that
;; server-rendered list panel was retired (the sidebar list renders
;; client-side again — graph-ui §6.1), so this now points at the LIVE
;; `:list-secrets-handler` (GET /api/secrets), which parents the SAME
;; `_list-secrets-data → … → _list-secrets-leaf-rows-identities` chain
;; carrying the load-bearing pins.

(deftest list-secrets-handler-no-where-clause-leak-test
  (testing "GET /api/secrets runs the list-secrets graph chain without leaking the Ring request into the where clause"
    (let [response (via :list-secrets-handler
                        {:uri "/api/secrets"
                         :request-method :get
                         :headers {}})]
      (is (= 200 (:status response))
          "happy path returns 200; a 500 with `Unknown field 'request-method'`
           means a `:where {:value {}}` pin on a `:storage-query-identities`
           consumer in the _list-secrets-* chain was removed and the Ring
           request map leaked into the where-clause via closure capture")
      (is (some? (:body response))
          "handler produced a response body (JSON envelope), not an error stub"))))


;; =============================================================================
;; GET /partials/secret-create-form + /partials/secret-rotate-form
;; (graph-first §6-#2 — the popover form markup moved to the graph; JS mounts
;; it and owns the lifecycle. These assert the fn-defs render the fields the
;; JS querySelectors expect.)
;; =============================================================================

(deftest partial-secret-create-form-handler-renders-the-form-test
  (testing "GET /partials/secret-create-form returns the New-secret form markup"
    (let [response (via :_partial-secret-create-form-handler
                        {:uri "/partials/secret-create-form"
                         :request-method :get
                         :headers {}})
          body (str (:body response))]
      (is (= 200 (:status response)))
      (is (str/includes? body "New secret"))
      (is (str/includes? body "name=\"name\""))
      (is (str/includes? body "name=\"path\""))
      (is (str/includes? body "name=\"value\""))
      (is (str/includes? body "data-act=\"pick-ns\""))
      (is (str/includes? body "data-act=\"submit\"")))))


(deftest partial-secret-rotate-form-handler-renders-title-from-params-test
  (testing "GET /partials/secret-rotate-form?name=&path= renders the rotate form + title from the query params"
    (let [response (via :_partial-secret-rotate-form-handler
                        {:uri "/partials/secret-rotate-form"
                         :request-method :get
                         :query-string "name=db-pw&path=kv/db"
                         :headers {}})
          body (str (:body response))]
      (is (= 200 (:status response)))
      (is (str/includes? body "Rotate db-pw"))
      (is (str/includes? body "Path: kv/db"))
      (is (str/includes? body "name=\"value\""))
      (is (str/includes? body "data-act=\"submit\"")))))


(deftest partial-auth-form-handler-renders-login-fields-test
  (testing "GET /partials/auth-form renders the login/signup popover fields (a `:list` fragment — direct flex children)"
    (let [response (via :_partial-auth-form-handler
                        {:uri "/partials/auth-form"
                         :request-method :get
                         :headers {}})
          body (str (:body response))]
      (is (= 200 (:status response)))
      (is (str/includes? body "id=\"auth-username-input\""))
      (is (str/includes? body "id=\"auth-password-input\""))
      (is (str/includes? body "id=\"auth-save-btn\""))
      (is (str/includes? body "id=\"auth-cancel-btn\""))
      (is (str/includes? body "id=\"auth-mode-toggle\""))
      (is (str/includes? body "auth-input-wrap"))
      ;; fragment — no wrapping <div class="auth-form-fields"> element
      (is (not (str/includes? body "auth-form-fields"))))))


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


;; =============================================================================
;; Helper — the leak signature is the 500-status `Unknown field` error from
;; `:storage-query-identities`. The shape is the same across every leak-prone
;; handler. Each per-handler test below sends a Ring request just complete
;; enough to reach the handler's identity-query chain; we don't assert the
;; happy-path semantic outcome (some sites legitimately 404/400 on the test
;; inputs), only the no-leak invariant.

(defn- no-where-clause-leak?
  "True iff `response` is not the specific `Unknown field 'request-method'`
   500 produced when the Ring request map auto-binds into a removed-pin
   identity query."
  [response]
  (not (and (= 500 (:status response))
            (str/includes? (str (:body response))
                           "Unknown field 'request-method'"))))


;; =============================================================================
;; GET /api/secrets — list-secrets-handler
;; =============================================================================
;;
;; Reaches `_shape-secret-bindings-identities` (secrets/fns.edn:96) via the
;; per-row shaping HOF, and `_list-secrets-leaf-rows-identities`
;; (secrets/fns.edn:222) via the leaf-list lookup. Distinct call shape from
;; `_partial-secrets-panel-handler` (different json-vs-html parent), so a
;; pin removal that the partial happens to mask via short-circuit still
;; trips here.

(deftest list-secrets-handler-returns-json-without-leak-test
  (testing "GET /api/secrets — JSON list; no `:where`-via-closure leak"
    (let [response (via :list-secrets-handler
                        {:uri "/api/secrets"
                         :request-method :get
                         :headers {}})]
      (is (no-where-clause-leak? response)
          "if 500 with `Unknown field 'request-method'`, the pin on
           `_list-secrets-leaf-rows-identities` or
           `_shape-secret-bindings-identities` was removed"))))


;; =============================================================================
;; POST /api/secrets — create-secret-handler
;; =============================================================================
;;
;; Reaches `_secret-leaf-rows-identities` (secrets/fns.edn:767) via the
;; secret-leaf lookup that gates the cond chain.

(deftest create-secret-handler-reaches-identity-query-without-leak-test
  (testing "POST /api/secrets — minimal body; no `:where`-via-closure leak"
    (let [response (via :create-secret-handler
                        {:uri "/api/secrets"
                         :request-method :post
                         :headers {"content-type" "application/json"}
                         :body "{\"name\":\"leak-test-secret\",\"path\":\"kv/leak-test\",\"value\":\"x\"}"})]
      (is (no-where-clause-leak? response)
          "if 500 with `Unknown field 'request-method'`, the pin on
           `_secret-leaf-rows-identities` was removed; non-leak failures
           (400 validation / 500 OpenBao-unreachable) are accepted"))))


;; =============================================================================
;; POST /api/secrets/:fn-id/rotate — rotate-secret-handler
;; =============================================================================
;;
;; Reaches `_rotate-secret-binding-identities` (secrets/fns.edn:1220) via the
;; binding-lookup that finds the path-binding to overwrite.

(deftest rotate-secret-handler-reaches-identity-query-without-leak-test
  (testing "POST /api/secrets/:id/rotate — non-existent id; no `:where`-via-closure leak"
    (let [response (via :rotate-secret-handler
                        {:uri "/api/secrets/00000000-0000-0000-0000-000000000000/rotate"
                         :request-method :post
                         :headers {"content-type" "application/json"}
                         :body "{\"value\":\"new-val\"}"
                         :path-params {:fn-id "00000000-0000-0000-0000-000000000000"}})]
      (is (no-where-clause-leak? response)
          "if 500 with `Unknown field 'request-method'`, the pin on
           `_rotate-secret-binding-identities` was removed"))))


;; =============================================================================
;; DELETE /api/secrets/:fn-id — delete-secret-handler
;; =============================================================================
;;
;; Reaches three pin sites in one chain:
;;   - `_find-fn-usages-binding-identities` (secrets/fns.edn:1415)
;;   - `_find-fn-usages-li-identities`       (secrets/fns.edn:1436)
;;   - `_delete-secret-leaf-identities`      (web/crud/fns.edn:2077)
;; via the usage-check + cascade-delete pipeline.

(deftest delete-secret-handler-reaches-identity-queries-without-leak-test
  (testing "DELETE /api/secrets/:id — non-existent id; no `:where`-via-closure leak"
    (let [response (via :delete-secret-handler
                        {:uri "/api/secrets/00000000-0000-0000-0000-000000000000"
                         :request-method :delete
                         :headers {}
                         :path-params {:fn-id "00000000-0000-0000-0000-000000000000"}})]
      (is (no-where-clause-leak? response)
          "if 500 with `Unknown field 'request-method'`, one of the three
           pins on the usage-check + cascade chain was removed"))))


;; =============================================================================
;; POST /api/secrets/inline-bind — create-inline-binding-handler
;; =============================================================================
;;
;; Reaches `_inline-bind-existing-identities` (secrets/fns.edn:1040) via the
;; existing-binding-lookup that decides whether to insert or overwrite.

(deftest create-inline-binding-handler-reaches-identity-query-without-leak-test
  (testing "POST /api/secrets/inline-bind — minimal body; no `:where`-via-closure leak"
    (let [response (via :create-inline-binding-handler
                        {:uri "/api/secrets/inline-bind"
                         :request-method :post
                         :headers {"content-type" "application/json"}
                         :body "{\"target-fn-id\":\"00000000-0000-0000-0000-000000000000\",\"slot-id\":\"00000000-0000-0000-0000-000000000000\",\"path\":\"kv/leak-test\",\"value\":\"x\"}"})]
      (is (no-where-clause-leak? response)
          "if 500 with `Unknown field 'request-method'`, the pin on
           `_inline-bind-existing-identities` was removed"))))


;; =============================================================================
;; DELETE /api/entities/fn/:id — delete-entity-handler (for :fn entity)
;; =============================================================================
;;
;; The fn-delete cascade in web/crud reaches the version-aware reverse-ref
;; queries (`:_delete-ref-bind-*` / `:_delete-ref-item-*`) that gather
;; candidate ref-owners from the identity + version tables. Their `:where`
;; is built from the delete target id (`:_delete-ref-bind-where`), not a
;; free slot, so the Ring request can't leak into it — this asserts that.

(deftest delete-entity-fn-handler-reaches-identity-queries-without-leak-test
  (testing "DELETE /api/entities/fn/:id — non-existent id; no `:where`-via-closure leak"
    (let [response (via :delete-entity-handler
                        {:uri "/api/entities/fn/00000000-0000-0000-0000-000000000000"
                         :request-method :delete
                         :headers {}
                         :path-params {:entity-type "fn"
                                       :id "00000000-0000-0000-0000-000000000000"}})]
      (is (no-where-clause-leak? response)
          "if 500 with `Unknown field 'request-method'`, one of the two
           pins on the ref-cleanup cascade was removed"))))
