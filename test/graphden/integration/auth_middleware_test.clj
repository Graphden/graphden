(ns ^:integration graphden.integration.auth-middleware-test
  "End-to-end coverage for the auth-required middleware chain.

   Why this test exists: the auth path is exercised PIECEWISE by
   `smoke_pass_test` (which uses a real token on every probe) and
   by `execute_http_test` (same), but neither asserts the REJECTION
   side — no test today proves that a missing / wrong Bearer token
   actually fails closed. A regression of the middleware chain
   (e.g. accidentally promoting a route from `:get-auth-required`
   to plain `:get-route`, or a wrap-order shuffle that bypasses the
   token check) would slip past unit tests AND existing integrations.

   Test surface: dispatch three flavours of a GET request to
   `/partials/branch-popover` (auth-required) through `br/dispatch`
   — the same closure http-kit invokes:
     - valid `Authorization: Bearer <token>` → 200
     - no Authorization header           → 401 + WWW-Authenticate
     - Bearer <wrong-token>              → 401 + WWW-Authenticate

   The branch popover partial was the cheapest target — it's
   already wired through `:get-auth-required`, doesn't take query
   params, and renders quickly off `:_list-branches-rows`."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.core :as sys]))


(def ^:dynamic *router* nil)


(def ^:private test-auth-token "auth-mw-test-token-xyz789")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage)
             _ (sys/bootstrap-from-packages! storage ["core" "web" "app"]
                                             {:skip-type-check? false})
             ;; Auth seam (§3.0): inject a single-token provider with the
             ;; test token instead of the old `:env`-override trick — auth
             ;; now reads `(:auth-provider ctx)`, captured at construction.
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (try
           (binding [*router* router]
             (t))
           (finally (sp/close storage)))))))


(defn- get-with-headers
  "Dispatch a GET request through the branch router with caller-
   supplied headers. Returns the Ring response. Body is nil — every
   auth-required GET partial we test here is parameter-free."
  [path headers]
  (br/dispatch
    *router*
    {:request-method :get
     :uri path
     :headers headers
     :query-string nil
     :body nil}))


(deftest auth-bearer-token-accepted-test
  (testing "GET /partials/branch-popover with valid Bearer token → 200"
    (let [resp (get-with-headers
                 "/partials/branch-popover"
                 {"authorization" (str "Bearer " test-auth-token)})]
      (is (= 200 (:status resp))
          (str "auth-required route returns 200 for valid token; "
               "got status=" (:status resp) " body=" (:body resp)))
      ;; Body should be the popover HTML — sanity-check it contains
      ;; the `branch-popover-list` class the JS handlers bind to.
      (is (and (string? (:body resp))
               (str/includes? (:body resp) "branch-popover-list"))
          (str "200 response body contains the popover root class; "
               "got body=" (:body resp))))))


(deftest auth-missing-token-rejected-test
  (testing "GET /partials/branch-popover with no Authorization header → 401"
    (let [resp (get-with-headers "/partials/branch-popover" {})]
      (is (= 401 (:status resp))
          (str "missing token returns 401; got status=" (:status resp)))
      ;; Body is the canonical `:default-auth-fail-response` shape —
      ;; literal "Unauthorized" text with text/plain content-type.
      ;; Don't over-specify with WWW-Authenticate (graphden's auth
      ;; model is API-only — no browser-prompt — so the RFC 7235
      ;; challenge header is intentionally omitted; admins can add
      ;; it by re-binding `:default-auth-fail-response`).
      (is (= "Unauthorized" (:body resp))
          (str "401 body is the canonical Unauthorized string; "
               "got body=" (pr-str (:body resp)))))))


(deftest auth-wrong-token-rejected-test
  (testing "GET /partials/branch-popover with wrong Bearer token → 401"
    (let [resp (get-with-headers
                 "/partials/branch-popover"
                 {"authorization" "Bearer totally-wrong-token-value"})]
      (is (= 401 (:status resp))
          (str "wrong token returns 401; got status=" (:status resp)))
      ;; Critical security sentinel: the rejection message must NOT
      ;; echo the supplied token back to the caller. An echo would
      ;; turn the 401 response into an oracle for token enumeration
      ;; via observable side-channels (logs, error tracking).
      (when-let [body (:body resp)]
        (is (not (str/includes? body "totally-wrong-token-value"))
            (str "401 body does not echo the wrong token "
                 "(token-enumeration sentinel); body=" body))))))


(deftest auth-malformed-header-rejected-test
  (testing "GET /partials/branch-popover with malformed Authorization → 401"
    ;; Sentinel that the token-parser doesn't accept arbitrary
    ;; non-Bearer schemes (Basic / Digest / etc.) — only the Bearer
    ;; scheme should unlock the resource. A regression to accept
    ;; any `Authorization` header value as the token would silently
    ;; widen the auth surface.
    (let [resp (get-with-headers
                 "/partials/branch-popover"
                 {"authorization" (str "Basic " test-auth-token)})]
      (is (= 401 (:status resp))
          (str "Basic-scheme rejected (only Bearer accepted); "
               "got status=" (:status resp))))))
