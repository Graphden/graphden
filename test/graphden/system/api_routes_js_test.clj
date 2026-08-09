(ns graphden.system.api-routes-js-test
  "Pure-helper + cache-lifecycle unit tests for
   `graphden.system.api-routes-js`. The `:exec/api-routes-js-cache`
   init-key path is exercised end-to-end by the production boot
   (every `bb rebuild` smoke run); this NS pins the small,
   independently-testable pieces — the JS templater + the
   process-global cache atom — so a regression surfaces fast."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.system.api-routes-js :as api-js]
    [reitit.ring :as ring]))


(use-fixtures :each
  (fn [f]
    ;; The cache is a process-global atom; reset before AND after
    ;; so a failing test doesn't leak state into siblings.
    (api-js/clear-cache!)
    (try (f) (finally (api-js/clear-cache!)))))


;; =============================================================================
;; routes->js-bundle — same templater the `:routes->js-bundle`
;; defbase delegates to.
;; =============================================================================

(deftest routes->js-bundle-static
  (testing "static path lands as a string-constant entry — no per-entry function(…)"
    (let [out (api-js/routes->js-bundle ["/api/health"])]
      (is (str/includes? out "api_health: \"/api/health\","))
      ;; Exclude `(function () {` from the IIFE wrapper — only
      ;; per-entry `function(<name>…)` callbacks are forbidden.
      (is (not (str/includes? out "function("))))))


(deftest routes->js-bundle-parametric
  (testing "parametric path lands as a function that encodeURIComponent's the param"
    (let [out (api-js/routes->js-bundle ["/api/fns/:id"])]
      (is (str/includes? out "api_fns_id: function(id) { return"))
      (is (str/includes? out "encodeURIComponent(id)"))
      (is (str/includes? out "\"/api/fns\"")))))


(deftest routes->js-bundle-trailing-static-after-param
  (testing "static after param — `/api/branches/:ref/conflicts` does NOT leak trailing slash"
    (let [out (api-js/routes->js-bundle ["/api/branches/:ref/conflicts"])]
      (is (str/includes? out "\"/conflicts\""))
      (is (not (str/includes? out "\"/conflicts/\""))
          "trailing slash on final literal is a regression introduced by the loop-buffer bug fixed in this ns"))))


(deftest routes->js-bundle-sort-and-dedup
  (testing "entries are sorted + deduped for stable bundle hashes"
    (let [out (api-js/routes->js-bundle ["/api/zebra" "/api/apple" "/api/apple"])
          entries (->> (str/split-lines out)
                       (filter #(str/includes? % "/api/")))]
      (is (= 2 (count entries)) "duplicates collapsed")
      (is (str/starts-with? (str/triml (first entries)) "api_apple:")
          "alphabetical order"))))


(deftest routes->js-bundle-empty
  (testing "no paths → still valid JS (empty window.API)"
    (let [out (api-js/routes->js-bundle [])]
      (is (str/includes? out "window.API = {"))
      (is (str/includes? out "})();")))))


;; =============================================================================
;; router-paths — wraps reitit's `r/routes` + ring/get-router
;; =============================================================================

(deftest router-paths-from-ring-handler
  (let [handler (ring/ring-handler
                  (ring/router
                    [["/api/x" {:get (constantly {:status 200})}]
                     ["/api/y/:id" {:get (constantly {:status 200})}]]))]
    (is (= ["/api/x" "/api/y/:id"] (api-js/router-paths handler)))))


(deftest router-paths-from-bare-router
  (let [router (ring/router
                 [["/api/a" {:get (constantly {:status 200})}]])]
    (is (= ["/api/a"] (api-js/router-paths router)))))


(deftest router-paths-tolerates-a-plain-fn-router
  ;; A route-collection router may be ANY `(fn [req] resp-or-nil)` — the
  ;; accounts /auth/* router is exactly that. It has no reitit route table, so
  ;; it contributes no window.API paths and must NOT throw the `r/routes`
  ;; protocol error (which killed rebuild-window-api! at boot).
  (is (= [] (api-js/router-paths (fn [_req] nil)))))


;; =============================================================================
;; Cache lifecycle — install / read / clear
;; =============================================================================

(deftest cache-starts-empty
  (testing "read before install → empty string (NOT nil — keeps editor bundle valid)"
    (is (= "" (api-js/read-cache))
        "empty default lets test bootstraps render the editor page without crashing on a nil splice")))


(deftest cache-roundtrip-install-then-read
  (testing "install! stashes the string; read-cache returns it verbatim"
    (api-js/install! "// some js")
    (is (= "// some js" (api-js/read-cache)))))


(deftest cache-clear-resets-to-empty-default
  (testing "clear-cache! brings read-cache back to the empty fallback"
    (api-js/install! "// not nothing")
    (is (not= "" (api-js/read-cache)))
    (api-js/clear-cache!)
    (is (= "" (api-js/read-cache)))))


(deftest cache-install-from-router-only-includes-api-paths
  (testing "/health and /assets/* are filtered out — only /api/* contributes"
    (let [router (ring/router
                   [["/health" {:get (constantly {:status 200})}]
                    ["/assets/editor.js" {:get (constantly {:status 200})}]
                    ["/api/branches" {:get (constantly {:status 200})}]])]
      (api-js/install-from-router! router)
      (let [js (api-js/read-cache)]
        (is (str/includes? js "api_branches"))
        (is (not (str/includes? js "health"))
            "non-/api paths excluded from window.API")
        (is (not (str/includes? js "editor_js"))
            "static assets excluded from window.API")))))


(deftest cache-install-from-router-overwrites-previous
  (testing "install-from-router! is idempotent + replaces the previous bundle"
    (api-js/install! "// stale")
    (let [router (ring/router
                   [["/api/foo" {:get (constantly {:status 200})}]])]
      (api-js/install-from-router! router)
      (is (not (str/includes? (api-js/read-cache) "stale")))
      (is (str/includes? (api-js/read-cache) "api_foo")))))


(deftest cache-install-from-routers-unions-api-paths
  (testing "install-from-routers! merges /api/* paths from EVERY router — the
            addon's window.API contribution (route-collection seam, frontend
            half): editor JS addresses tenancy routes by key, no hardcoded path"
    (let [core (ring/router
                 [["/api/branches" {:get (constantly {:status 200})}]
                  ["/health" {:get (constantly {:status 200})}]])
          addon (ring/router
                  [["/api/login" {:post (constantly {:status 200})}]
                   ["/api/grants" {:post (constantly {:status 200})}]])]
      (api-js/install-from-routers! [core addon])
      (let [js (api-js/read-cache)]
        (is (str/includes? js "api_branches") "core route present")
        (is (str/includes? js "api_login") "addon auth route merged in")
        (is (str/includes? js "api_grants") "addon panel route merged in")
        (is (not (str/includes? js "health")) "non-/api paths still filtered")))))
