(ns ^:integration graphden.integration.security-headers-test
  "The branch/tenancy dispatch layer (`graphden.system.branch-router/dispatch`)
   emits responses — unknown-branch 400, control-plane admin HTML, tenant
   app-router — that never flow through the main `:_router`, so
   `:router-ring-response`'s security-header merge misses them (latent
   clickjacking on admin panels). `:_app-secured` (`:secure-headers-wrap`)
   wraps the dispatch layer in the production handler chain to stamp those
   too.

   This drives an unknown-branch request through `:_app-secured` — the same
   node http-kit mounts — and asserts the dispatch-layer 400 carries the
   headers. Runs in PARALLEL: it installs a branch-router, but the parallel
     plugin isolates `*active-router-override*` per thread, so a sibling never
     sees this NS's router
   singleton that `:_branch-routed-handler` reads."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.system.branch-router :as br]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (let [bootstrap (setup/bootstrap-crud-graph-from-golden!)
          router (br/create-router (:ctx bootstrap) "_app-ring-response")]
      (br/set-active-router! router)
      (binding [*bootstrap* bootstrap]
        (try
          (t)
          (finally
            (br/clear-active-router!)))))))


(deftest secure-headers-wrap-stamps-dispatch-layer-responses
  (testing "an unknown branch ref → dispatch 400 that bypasses :router-ring-response"
    (let [resp (setup/via-graph *bootstrap* :_app-secured
                                {:request-method :get
                                 :uri "/"
                                 :headers {"x-graphden-branch" "no-such-branch"}
                                 :query-string nil})]
      (is (= 400 (:status resp))
          "unknown branch surfaces a 400 from the dispatch layer")
      (testing ":_app-secured still stamps the security headers onto it"
        (is (= "DENY" (get-in resp [:headers :X-Frame-Options])))
        (is (= "nosniff" (get-in resp [:headers :X-Content-Type-Options])))
        (is (= "strict-origin-when-cross-origin"
               (get-in resp [:headers :Referrer-Policy])))
        ;; CSP + HSTS added 2026-08-17 (pre-release hardening). CSP is
        ;; script-source-agnostic on purpose (inline editor scripts) —
        ;; assert the clickjacking/embedding directives are present.
        (is (= "frame-ancestors 'none'; object-src 'none'; base-uri 'self'"
               (get-in resp [:headers :Content-Security-Policy])))
        (is (= "max-age=31536000; includeSubDomains"
               (get-in resp [:headers :Strict-Transport-Security])))
        (is (= "0" (get-in resp [:headers :X-XSS-Protection]))
            "legacy XSS auditor disabled (modern-browser recommendation)")))))
