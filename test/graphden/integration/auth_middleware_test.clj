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
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:dynamic *router* nil)
(def ^:dynamic *ctx* nil)


(def ^:private test-auth-token "auth-mw-test-token-xyz789")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  (fn [t]
    ;; Golden-clone (~100 ms) + the CACHED type-check sweep overlaid,
    ;; instead of a full per-NS `bootstrap-from-packages!` — same
    ;; fixture shape as `test-infra.golden-app/fixture`, kept inline
    ;; because the ctx needs the auth-provider seam wired.
    (let [_ (reset! registry-core/*rich-types-override*
                    (sb/ensure-swept-rich-types! ["core" "web" "app"]))
          ;; Sweep BEFORE bootstrap — its rebuild then compiles under
          ;; the same swept types as every sibling NS and shares one
          ;; compile-all cache entry (see golden-app/fixture).
          {:keys [storage]} (setup/bootstrap-crud-graph-from-golden!*
                              "graphden.integration.auth-middleware-test"
                              ["core" "web" "app"])
          ;; Auth seam (§3.0): inject a single-token provider with the
          ;; test token instead of the old `:env`-override trick — auth
          ;; now reads `(:auth-provider ctx)`, captured at construction.
          ctx (exec/create-context
                {:storage storage
                 :auth-provider (auth/single-token-provider test-auth-token)})
          _ (cr/rebuild! ctx)
          router (br/create-router ctx "_app-ring-response")]
      (try
        (binding [*router* router
                  *ctx* ctx]
          (t))
        (finally (sp/close storage))))))


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


(deftest livez-served-unauthenticated-through-the-real-router-test
  (testing "GET /livez with NO token → 200 alive, through the real branch
            router — proves the liveness probe reaches its static bypass
            before per-route auth AND without the compiled registry (a
            recompiling pod still answers liveness)"
    (let [resp (get-with-headers "/livez" {})]
      (is (= 200 (:status resp)))
      (is (re-find #"\"status\":\"alive\"" (str (:body resp))))
      (is (= "application/json" (get-in resp [:headers "Content-Type"]))))))


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


(deftest auth-form-partial-renders-clean-markup-test
  ;; GET /partials/auth-form is a bare (unauthenticated) route, so its handler
  ;; is invoked with the RAW ring request. It used to declare :lambda-params
  ;; [:children] — the 1-arg shape dropped the whole request map into the
  ;; inputs' free :children slot, rendering <request-method>/<uri>/the reitit
  ;; router object INSIDE the <input> elements (the popover-garbage bug).
  (let [resp (get-with-headers "/partials/auth-form" {})]
    (is (= 200 (:status resp)))
    (let [body (str (:body resp))]
      (is (str/includes? body "auth-password-input") "the password field renders")
      (is (not (str/includes? body "<request-method>"))
          "the ring request must NOT leak into the markup")
      (is (not (str/includes? body "reitit.core"))
          "the router object must NOT leak into the markup")
      ;; SINGLE-TENANT build carries NO tenant markup at all (the tenant
      ;; variant ships with the tenancy addon, which shadows this path) —
      ;; the org/username fields must not exist here even hidden.
      (is (not (str/includes? body "auth-org-input")) "no org field in core")
      (is (not (str/includes? body "New org name")) "no org placeholder in core")
      (is (str/includes? body "data-auth-mode=\"admin\"")
          "the served form declares the admin submit mode"))))


(deftest auth-off-serves-protected-routes-openly-test
  ;; PROVIDER-AWARE MIDDLEWARE (B3): with NO `:auth-provider` on the ctx auth is
  ;; OFF, so an auth-required route serves WITHOUT any token instead of 401 —
  ;; this is what lets a self-hosted instance run with no login at all. Same
  ;; route, same no-token request as `auth-missing-token-rejected-test` (which
  ;; gets 401 WITH a provider) — the ONLY difference is the provider's absence.
  (let [open-router (br/create-router (dissoc *ctx* :auth-provider) "_app-ring-response")
        resp (br/dispatch open-router {:request-method :get
                                       :uri "/partials/branch-popover"
                                       :headers {} :query-string nil :body nil})]
    (is (= 200 (:status resp))
        (str "no provider ⇒ auth off ⇒ auth-required route open without a token; "
             "got status=" (:status resp)))))


(deftest graph-view-gated-when-auth-active-test
  ;; REMOVE THE OPEN VIEW (B3 #3): the full graph dump is auth-required now, so
  ;; an unauthenticated caller can't read the graph when auth is active. The
  ;; security sentinel — a regression that reverts /api/graph/entities to an
  ;; open route (anonymous graph view) fails here. Authenticated still works.
  (testing "no token ⇒ 401, NOT the graph (open view removed)"
    (let [resp (get-with-headers "/api/graph/entities" {})]
      (is (= 401 (:status resp))
          (str "unauth graph dump is 401, not served; got status=" (:status resp)))))
  (testing "valid token ⇒ 200 (authed users still read the graph)"
    (let [resp (get-with-headers "/api/graph/entities"
                                 {"authorization" (str "Bearer " test-auth-token)})]
      (is (= 200 (:status resp))
          (str "authed graph dump returns 200; got status=" (:status resp))))))
