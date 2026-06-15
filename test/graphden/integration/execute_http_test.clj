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
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as rt]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.core :as sys]))


(def ^:dynamic *router* nil)


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
             _ (sys/bootstrap-from-packages! storage ["core" "web" "app"]
                                             {:skip-type-check? false})
             ;; Override the `:env` base-fn AFTER bootstrap (which
             ;; registers the production env-fn impl into the
             ;; thread-local override atom). The auth-required
             ;; middleware reads `AUTH_TOKEN` off the env — we
             ;; intercept just that one key and pass everything else
             ;; through to the real `System/getenv`. Reflection on
             ;; `java.lang.ProcessEnvironment` doesn't survive
             ;; `System/getenv` caching on JVM 17+, which is why
             ;; this override path is cleaner than a real env set.
             _ (exec/register-base-fn!
                 :env
                 (fn [args _ctx]
                   (let [k (rt/resolve-arg args :name)]
                     (if (= k "AUTH_TOKEN") test-auth-token (System/getenv k)))))
             ctx (exec/create-context {:storage storage})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (try
           (binding [*router* router]
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
  (testing "POST /api/execute with unknown fn-name → structured rejection"
    (let [resp (json-post "/api/execute"
                          {:fn-name "no-such-fn" :args {}})
          body (parse-body resp)]
      (is (= 200 (:status resp))
          (str "expected 200 with structured rejection, got " (:status resp)
               " body: " (:body resp)))
      (is (= "rejected" (:status body)))
      (is (false? (:ok body))))))
