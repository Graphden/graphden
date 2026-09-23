(ns graphden.system.api-url-drift-test
  "Pure-helper unit tests for the api-url drift validator. The full
   `check-router!` end-to-end is exercised by the boot path in
   prod/dev; this NS pins the small, pure transforms that the
   validator builds on (literal-prefix derivation, JS regex
   extraction, drift comparison, error messaging)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.system.api-url-drift :as drift]
    [reitit.ring :as ring]))


;; =============================================================================
;; literal-prefix — internal helper exposed via @#'
;; =============================================================================

(deftest literal-prefix-test
  (let [literal-prefix @#'drift/literal-prefix]
    (testing "no params → path returned verbatim"
      (is (= "/api/branches" (literal-prefix "/api/branches")))
      (is (= "/health" (literal-prefix "/health")))
      (is (= "/" (literal-prefix "/"))))

    (testing "params → everything before the first :seg, with trailing slash"
      (is (= "/api/branches/" (literal-prefix "/api/branches/:ref")))
      (is (= "/api/branches/" (literal-prefix "/api/branches/:ref/diff")))
      (is (= "/api/fns/" (literal-prefix "/api/fns/:id/versions"))))

    (testing "param in first position → just the root prefix"
      (is (= "/" (literal-prefix "/:tenant/api/fns"))))))


;; =============================================================================
;; allowed-literal-set — produced from router paths
;; =============================================================================

(deftest allowed-literal-set-test
  (testing "only /api/* paths contribute — /health, /favicon, /assets/* are filtered"
    (let [allowed (drift/allowed-literal-set
                    ["/health"
                     "/favicon.ico"
                     "/assets/editor.js"
                     "/api/branches"
                     "/api/branches/:ref"])]
      (is (= #{"/api/branches" "/api/branches/"} allowed))))

  (testing "patterns sharing a prefix collapse to that prefix once"
    (let [allowed (drift/allowed-literal-set
                    ["/api/fns/:id"
                     "/api/fns/:id/versions"
                     "/api/fns/:id/refs"])]
      (is (= #{"/api/fns/"} allowed))))

  (testing "static + parametric variants on same root both contribute"
    (let [allowed (drift/allowed-literal-set
                    ["/api/secrets"
                     "/api/secrets/:fn-id"])]
      (is (= #{"/api/secrets" "/api/secrets/"} allowed)))))


;; =============================================================================
;; extract-js-literals — regex over JS source
;; =============================================================================

(deftest extract-js-literals-no-api-references
  (testing "JS with no /api/* literals → empty"
    (is (empty?
          (drift/extract-js-literals
            "file.js"
            "function foo() { return bar(); }")))))


(deftest extract-js-literals-single-quoted
  (testing "single-quoted string → captured with file + line"
    (let [out (drift/extract-js-literals
                "file.js"
                "const url = '/api/branches';\nfetch(url);")]
      (is (= [{:file "file.js" :line 1 :literal "/api/branches"}]
             out)))))


(deftest extract-js-literals-double-quoted
  (testing "double-quoted string → captured"
    (let [out (drift/extract-js-literals
                "file.js"
                "const url = \"/api/branches\";")]
      (is (= [{:file "file.js" :line 1 :literal "/api/branches"}]
             out)))))


(deftest extract-js-literals-prefix-only
  (testing "literal that ends in `/` → captured as the prefix"
    (let [out (drift/extract-js-literals
                "file.js"
                "fetch('/api/branches/' + encodeURIComponent(ref));")]
      (is (= [{:file "file.js" :line 1 :literal "/api/branches/"}]
             out)))))


(deftest extract-js-literals-multiple-on-one-line
  (testing "more than one literal on the same line → each gets its own entry"
    (let [out (drift/extract-js-literals
                "file.js"
                "a('/api/x'); b('/api/y/');")]
      (is (= [{:file "file.js" :line 1 :literal "/api/x"}
              {:file "file.js" :line 1 :literal "/api/y/"}]
             out)))))


(deftest extract-js-literals-tracks-line-numbers
  (testing "literals on different lines get correct :line numbers"
    (let [src (str "// line 1 comment\n"
                   "const a = '/api/a';\n"
                   "// line 3 comment\n"
                   "const b = '/api/b';")
          out (drift/extract-js-literals "file.js" src)]
      (is (= [2 4] (map :line out))))))


(deftest extract-js-literals-ignores-non-api-paths
  (testing "/health, /favicon, http://… and the like → ignored"
    (is (empty?
          (drift/extract-js-literals
            "file.js"
            "fetch('/health'); fetch('http://example.com/api/x');")))))


(deftest extract-js-literals-honors-opt-out-marker
  (testing "lines carrying `// api-url-drift-allow:` are skipped wholesale"
    (let [src (str "// regular comment\n"
                   "const a = '/api/real-call';\n"
                   "if (url.startsWith('/api/')) {} // api-url-drift-allow: discriminator\n"
                   "const b = '/api/another';")
          out (drift/extract-js-literals "file.js" src)]
      (is (= 2 (count out))
          "the line with the opt-out marker is skipped; the other two literals remain")
      (is (= #{"/api/real-call" "/api/another"}
             (set (map :literal out)))
          "captured literals exclude the discriminator line"))))


;; =============================================================================
;; /partials/* — the second root the guard owns
;; =============================================================================

(deftest allowed-literal-set-includes-partials
  (testing "/partials/* routes contribute exactly like /api/* ones"
    (is (= #{"/partials/stats" "/partials/marketplace/item" "/api/x"}
           (drift/allowed-literal-set
             ["/partials/stats" "/partials/marketplace/item" "/api/x" "/health"])))))


(deftest extract-js-literals-captures-partials
  (testing "fetch literal, hx-get attribute built in a JS string, template literal"
    (let [src (str "authFetch('/partials/provenance?fn-id=' + id);\n"
                   "  + '<div hx-get=\"/partials/stats\" hx-trigger=\"load\">'\n"
                   "const u = `/partials/fn-versions?fn-id=${id}`;")]
      (is (= [{:file "f.js" :line 1 :literal "/partials/provenance"}
              {:file "f.js" :line 2 :literal "/partials/stats"}
              {:file "f.js" :line 3 :literal "/partials/fn-versions"}]
             (drift/extract-js-literals "f.js" src))))))


(deftest extract-js-literals-skips-comment-lines
  (testing "prose naming a route in a comment is not a request"
    (is (empty? (drift/extract-js-literals
                  "f.js"
                  (str "// Body comes from `/partials/execute-result?id=…`\n"
                       " * and '/api/old' was renamed\n"
                       "/* `/partials/gone` */"))))))


(deftest removed-partial-route-is-drift
  (testing "a JS hx-get naming a partial the router no longer serves fails the check"
    (let [allowed (drift/allowed-literal-set ["/partials/stats" "/api/failures"])
          literals (drift/extract-js-literals
                     "editor-feedback.js"
                     "const r = await authFetch('/partials/error-log');")
          e (try (drift/assert-no-drift! allowed literals)
                 (catch Exception e e))]
      (is (= :web/api-url-drift (:type (ex-data e))))
      (is (str/includes? (ex-message e) "/partials/error-log")))))


(deftest optional-router-literals-checked-only-when-loaded
  (testing "`api-url-drift-optional: <router>` tags the literal with its router"
    (let [literals (drift/extract-js-literals
                     "f.js"
                     (str "fetch('/partials/stats');\n"
                          "fetch('/partials/marketplace'); // api-url-drift-optional: registry-router"))]
      (is (= [nil "registry-router"] (map :optional-router literals)))
      (testing "router absent → the tagged literal is not checked (the package is off)"
        (is (= ["/partials/stats"]
               (map :literal (drift/checkable-literals literals #{})))))
      (testing "router loaded → checked against it like any other"
        (is (= 2 (count (drift/checkable-literals literals #{"registry-router"}))))
        (let [core (ring/router [["/partials/stats" {:get (constantly nil)}]])
              registry (ring/router [["/partials/marketplace" {:get (constantly nil)}]])
              allowed (drift/allowed-literal-set
                        (mapcat drift/router-paths [core registry]))]
          (is (= :ok (drift/assert-no-drift!
                       allowed (drift/checkable-literals literals #{"registry-router"}))))
          (is (thrown? clojure.lang.ExceptionInfo
                (drift/assert-no-drift!
                  (drift/allowed-literal-set (drift/router-paths core))
                  (drift/checkable-literals literals #{"registry-router"})))
              "a loaded optional router that lost the route is drift"))))))


;; =============================================================================
;; find-drift / assert-no-drift!
;; =============================================================================

(deftest find-drift-detects-unknown-literal
  (testing "literal not in allowed set → returned as drift"
    (let [drift (drift/find-drift
                  #{"/api/branches" "/api/branches/"}
                  [{:file "f.js" :line 5 :literal "/api/typo"}])]
      (is (= 1 (count drift)))
      (is (= "/api/typo" (-> drift first :literal))))))


(deftest find-drift-accepts-exact-match
  (testing "literal equal to a known path → not drift"
    (is (empty?
          (drift/find-drift
            #{"/api/branches"}
            [{:file "f.js" :line 1 :literal "/api/branches"}])))))


(deftest find-drift-accepts-prefix-form
  (testing "literal `/api/branches/` matches a known prefix in the allowed set"
    (is (empty?
          (drift/find-drift
            #{"/api/branches/"}
            [{:file "f.js" :line 1 :literal "/api/branches/"}])))))


(deftest find-drift-accepts-implicit-trailing-slash
  (testing "literal without trailing slash matches a prefix WITH trailing slash"
    ;; e.g. JS says fetch('/api/secrets') and the allowed set has
    ;; both '/api/secrets' and '/api/secrets/' — either acceptance
    ;; path keeps the literal valid.
    (is (empty?
          (drift/find-drift
            #{"/api/secrets/"}
            [{:file "f.js" :line 1 :literal "/api/secrets"}])))))


(deftest find-drift-accepts-literal-extending-allowed-prefix
  (testing "JS literal `/api/entities/fn/` extends allowed prefix `/api/entities/`"
    ;; The router has `/api/entities/:entity-type`, which contributes
    ;; the prefix `/api/entities/`. JS does
    ;; `'/api/entities/fn/' + encodeURIComponent(name)` — the literal
    ;; the regex captures is `/api/entities/fn/`. Should be valid:
    ;; the `fn` is the runtime value of `:entity-type`.
    (is (empty?
          (drift/find-drift
            #{"/api/entities/"}
            [{:file "f.js" :line 1 :literal "/api/entities/fn/"}])))
    (is (empty?
          (drift/find-drift
            #{"/api/entities/"}
            [{:file "f.js" :line 1 :literal "/api/entities/binding"}])))))


(deftest find-drift-rejects-prefix-that-doesnt-end-in-slash
  (testing "literal must extend a `/`-terminated prefix; `/api/foo` allowed doesn't accept `/api/foozzy`"
    (let [drift (drift/find-drift
                  #{"/api/foo"}
                  [{:file "f.js" :line 1 :literal "/api/foozzy"}])]
      (is (= 1 (count drift))
          "extends-without-slash boundary must fail — sibling-route-name typos would otherwise pass"))))


(deftest assert-no-drift-passes-when-empty
  (is (= :ok (drift/assert-no-drift! #{"/api/x"} []))))


(deftest assert-no-drift-throws-on-mismatch
  (testing "drift triggers an ex-info with :type :web/api-url-drift"
    (let [e (try (drift/assert-no-drift!
                   #{"/api/branches"}
                   [{:file "x.js" :line 3 :literal "/api/typo"}])
                 (catch Exception e e))]
      (is (some? e))
      (is (= :web/api-url-drift (:type (ex-data e))))
      (is (= 1 (count (:drift (ex-data e)))))
      (is (str/includes? (ex-message e) "x.js:3"))
      (is (str/includes? (ex-message e) "/api/typo")))))


;; =============================================================================
;; router-paths — roundtrip from a compiled reitit router
;; =============================================================================

(deftest router-paths-roundtrip-with-ring-handler
  (testing "ring-handler input → full path list (param syntax preserved)"
    (let [handler (ring/ring-handler
                    (ring/router
                      [["/api/branches" {:get (constantly {:status 200})}]
                       ["/api/branches/:ref" {:get (constantly {:status 200})}]
                       ["/api/branches/:ref/diff" {:get (constantly {:status 200})}]]))
          paths (drift/router-paths handler)]
      (is (= ["/api/branches" "/api/branches/:ref" "/api/branches/:ref/diff"]
             paths)))))


(deftest router-paths-roundtrip-with-bare-router
  (testing "bare reitit.core/Router input also accepted"
    (let [router (ring/router
                   [["/api/x" {:get (constantly {:status 200})}]])]
      (is (= ["/api/x"] (drift/router-paths router))))))


(deftest router-paths-tolerates-a-plain-fn-router
  ;; A route-collection router may be ANY `(fn [req] resp-or-nil)` — the
  ;; accounts /auth/* router is exactly that. It has no reitit route table, so
  ;; it contributes no window.API paths and must NOT throw the `r/routes`
  ;; protocol error (which killed rebuild-window-api! at boot).
  (is (= [] (drift/router-paths (fn [_req] nil)))))
