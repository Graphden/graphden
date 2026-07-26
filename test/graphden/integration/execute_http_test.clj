(ns ^:integration graphden.integration.execute-http-test
  "End-to-end coverage for POST /api/execute through the actual Ring
   handler chain (NOT a direct executor invocation).

   Why this test exists: integration tests that called
   `exec/execute` directly OR `setup/via-graph` (which manually
   injects `:storage-query`) silently bypassed the storage-protocol
   injection point. A misplacement of `:storage-query :pg-query`
   onto a wrap that the per-branch dispatcher doesn't traverse
   would NPE every real /api/execute call yet pass every direct-
   executor test. This test closes that gap by going through
   `br/dispatch` — the same code path http-kit feeds requests
   into — so a regression of the wrap-vs-_app-ring-response
   placement re-trips here.

   Test surface: dispatch a fake Ring request whose body is the
   JSON `:add` would accept, and assert the response JSON deserialises
   to `{:status :succeeded :result <sum>}`."
  (:require
    [cheshire.core :as json]
    [clojure.string]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:dynamic *router* nil)


(def ^:dynamic *storage* nil)


(def ^:private test-auth-token "test-token-abc123")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage)
             ;; Full bootstrap WITH type-check sweep enabled
             ;; (`:skip-type-check? false`). The sweep populates
             ;; `:return-type` rich-types for composed fn-defs.
             ;; Without it, `ref-produces-callable?` returns nil
             ;; for `:_router` and `:router-result`'s HOF wrap
             ;; captures the router-builder closure instead of the
             ;; built router callable — `:assoc-empty` /
             ;; `:update-in` then classcast on the wrapped
             ;; callable. Production never skips the sweep, so
             ;; this test mirrors production.
             _ (sb/bootstrap-with-cached-sweep! storage ["core" "web" "app"])
             ;; Auth seam (§3.0): inject a single-token provider with the
             ;; test token. Auth now reads `(:auth-provider ctx)` (captured
             ;; at construction), so the old `:env`-override trick is gone.
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (try
           (binding [*router* router
                     *storage* storage]
             (t))
           (finally (sp/close storage)))))))


(defn- json-post
  "Dispatch a POST request through the branch router. Body is JSON-
   encoded and passed as a String — same shape `:_branch-routed-
   handler`'s `realize-body` produces from http-kit's InputStream."
  [path body]
  (br/dispatch
    *router*
    {:request-method :post
     :uri path
     :headers {"content-type" "application/json"
               "authorization" (str "Bearer " test-auth-token)}
     :query-string nil
     :body (json/generate-string body)}))


(defn- parse-body
  [resp]
  (let [b (:body resp)]
    (when (string? b)
      (json/parse-string b true))))


(deftest execute-by-fn-name-returns-sum-end-to-end
  ;; The regression we're protecting against: NPE inside the deep
  ;; `:_execute-svc-conflicts-raw` because `:storage-query` was
  ;; bound on a wrap (`:_branch-routed-handler`) that the per-branch
  ;; dispatcher skips. The bug surfaced as 500 on every /api/execute
  ;; call — INCLUDING the trivial `add 1 2 3` shape below — but every
  ;; existing test path injected `:storage-query` manually so nothing
  ;; caught it. Dispatching through `br/dispatch` exercises the SAME
  ;; closure http-kit invokes.
  (testing "POST /api/execute → 200 + JSON {:status :succeeded :result 6}"
    (let [resp (json-post "/api/execute"
                          {:fn-name "add" :args {:nums [1 2 3]}})
          body (parse-body resp)]
      (is (= 200 (:status resp))
          (str "expected 200, got " (:status resp)
               " body: " (:body resp)))
      (is (= "succeeded" (:status body)))
      (is (= 6 (:result body))))))


(deftest execute-with-empty-args-uses-defaults
  (testing "POST /api/execute with `:args {}` still routes through the chain"
    (let [resp (json-post "/api/execute" {:fn-name "add" :args {:nums []}})
          body (parse-body resp)]
      (is (= 200 (:status resp)))
      (is (= "succeeded" (:status body)))
      (is (zero? (:result body))))))


(deftest execute-unknown-fn-rejects-cleanly
  ;; A separate codepath through the same handler chain — verifies
  ;; `_execute-validation` runs without NPE'ing on storage queries.
  ;; A missing `:storage-query` here would surface as 500 (NPE)
  ;; rather than the structured `:rejected` envelope.
  (testing "POST /api/execute with unknown fn-name → structured 404 rejection"
    (let [resp (json-post "/api/execute"
                          {:fn-name "no-such-fn" :args {}})
          body (parse-body resp)]
      ;; Error honesty (audit-8): logical rejections carry REAL HTTP
      ;; statuses — an unresolvable fn is a 404, not a 200 the client
      ;; must body-parse to discover.
      (is (= 404 (:status resp))
          (str "expected 404 structured rejection, got " (:status resp)
               " body: " (:body resp)))
      (is (= "rejected" (:status body)))
      (is (false? (:ok body))))))


;; ============================================================================
;; Stage-2 pre-flight rejections — the `:_execute-validation` graph `:cond`.
;;
;; These migrated here from `graphden.crud.fn-execution-test` when the
;; Clojure `validate-execute` mirror was deleted: validation now lives
;; ONLY in the graph, so the rejection logic can only be exercised
;; against a fully bootstrapped package graph (this fixture) driven
;; through the real POST /api/execute path (`br/dispatch`). Values in
;; the JSON body come back as strings (keyword → name over the wire);
;; the rejection SHAPE + `:reason` match the pre-deletion Clojure
;; version exactly (verified guard-by-guard, no divergence).
;; ============================================================================

(defn- reject-body
  "POST `body-map` to /api/execute and return the parsed rejection map,
   asserting a REAL 4xx status (audit-8 error honesty: rejections are
   no longer 200s the client must body-parse to discover) alongside
   the structured envelope."
  [body-map]
  (let [resp (json-post "/api/execute" body-map)
        body (parse-body resp)]
    (is (<= 400 (:status resp) 499)
        (str "expected 4xx, got " (:status resp) " body: " (:body resp)))
    (is (false? (:ok body)))
    (is (= "rejected" (:status body)))
    body))


(deftest execute-rejects-no-fn
  (testing "request carrying neither :fn-id nor :fn-name → :no-fn"
    (let [body (reject-body {:args {} :timeout-ms 1000})]
      (is (= "no-fn" (get-in body [:error-data :reason]))))))


(deftest execute-rejects-unknown-fn-id
  ;; A well-formed UUID that resolves to no row must reject as
  ;; :fn-not-found (symmetric with the :fn-name path), citing the id
  ;; in the message — not slip through to a bare "Function not found: ".
  (testing "well-formed but absent :fn-id → :fn-not-found citing the id"
    (let [absent "00000000-0000-0000-0000-000000000000"
          body (reject-body {:fn-id absent :args {} :timeout-ms 1000})]
      (is (= "fn-not-found" (get-in body [:error-data :reason])))
      (is (clojure.string/includes? (:error body) absent)
          "error message cites the actual fn-id, not an empty tail"))))


(deftest execute-rejects-timeout-out-of-range
  (testing "negative timeout → :timeout-out-of-range"
    (let [body (reject-body {:fn-name "add" :args {:nums []} :timeout-ms -1})]
      (is (= "timeout-out-of-range" (get-in body [:error-data :reason])))))
  (testing "timeout above the 60s cap → :timeout-out-of-range"
    (let [body (reject-body {:fn-name "add" :args {:nums []} :timeout-ms 999999})]
      (is (= "timeout-out-of-range" (get-in body [:error-data :reason]))))))


(deftest execute-rejects-args-too-large
  ;; > 256 KB serialised :args. The size check fires before the
  ;; unknown-arg check, so the arg shape is irrelevant.
  (testing "oversized :args payload → :args-too-large with byte count"
    (let [big (vec (repeat (* 30 1024) "padding-string-here-zzz"))
          body (reject-body {:fn-name "add" :args {:nums big} :timeout-ms 1000})]
      (is (= "args-too-large" (get-in body [:error-data :reason])))
      (is (pos? (get-in body [:error-data :bytes]))
          "bytes count reported in error-data"))))


(deftest execute-rejects-unknown-arg
  (testing "arg name not matching any free slot → :unknown-arg"
    (let [body (reject-body {:fn-name "add"
                             :args {:not-a-real-slot 42}
                             :timeout-ms 1000})]
      (is (= "unknown-arg" (get-in body [:error-data :reason])))
      (is (some #{"not-a-real-slot"} (get-in body [:error-data :unknown]))))))


(deftest execute-rejects-malformed-ref
  ;; Regression: an arg shaped {:ref "not-a-uuid"} parsed to nil and
  ;; slipped through, so `add` summed an empty list to 0. Reject early
  ;; with a clean :malformed-ref instead.
  (testing "non-UUID inside :ref → :malformed-ref naming the arg"
    (let [body (reject-body {:fn-name "add"
                             :args {:nums {:ref "not-a-uuid"}}
                             :timeout-ms 1000})]
      (is (= "malformed-ref" (get-in body [:error-data :reason])))
      (is (= "nums" (get-in body [:error-data :malformed 0 :arg])))
      (is (= "not-a-uuid" (get-in body [:error-data :malformed 0 :raw-ref])))))
  (testing "blank string inside :ref → :malformed-ref"
    (let [body (reject-body {:fn-name "add"
                             :args {:nums {:ref ""}}
                             :timeout-ms 1000})]
      (is (= "malformed-ref" (get-in body [:error-data :reason]))))))


(deftest execute-rejects-already-running-as-service
  ;; A fn owned by an enabled :service row can't be ad-hoc Run — the
  ;; reconciler owns it. The guard only reads the DB row; the
  ;; reconciler need not have actually started a future.
  (let [add-id (-> (sp/query-entities *storage* :fn {:name "add"}) first :id)
        svc (sp/create-entity *storage* :service
                              {:fn-id add-id
                               :enabled? true
                               :restart-policy :always})]
    (try
      (testing "enabled :service for the fn → :already-running-as-service"
        (let [body (reject-body {:fn-name "add"
                                 :args {:nums [1 2]}
                                 :timeout-ms 5000})]
          (is (= "already-running-as-service" (get-in body [:error-data :reason])))
          (is (= "service" (get-in body [:error-data :source])))
          (is (some? (get-in body [:error-data :service-id]))
              ":service-id surfaced so the UI can link to it")))
      (testing "disabling the service unblocks Run"
        (sp/update-entity *storage* :service (:id svc) {:enabled? false})
        (let [resp (json-post "/api/execute"
                              {:fn-name "add" :args {:nums [1 2]} :timeout-ms 5000})
              body (parse-body resp)]
          (is (= "succeeded" (:status body))
              "validation passes once :enabled? is false")
          (is (= 3 (:result body)))))
      (finally
        ;; Never leave an enabled :service for `add` behind — the
        ;; happy-path tests in this ns run `add` too.
        (sp/delete-entity *storage* :service (:id svc))))))
