(ns ^:integration graphden.integration.optional-packages-dispatch-test
  "Guards the OPTIONAL first-party packages (`registry`, `mcp`) being served
   through the branch-router's per-branch optional-handler slot — the fix for
   the route-collection seam, which (being a boot-frozen `execute-by-name`'d
   router) could NOT serve app HTTP routes:

   - `/mcp` : `:_mcp-dispatch` is `:lambda-params [:request :storage-query]`.
     The seam bound neither, so the dispatch baked at boot to a constant
     method-not-found that ignored the request (id not echoed). The
     endpoint-level `mcp-endpoint-test` never caught this because it drives
     `:_mcp-dispatch` DIRECTLY, bypassing the HTTP dispatch. THIS test drives
     it through `br/dispatch` and asserts the request id is echoed.

   - `/api/packages` : the registry index (`:list-package-versions`) is a
     constant-arg `:query-entities` — the seam baked its result at boot, so a
     package published AFTER boot stayed invisible until restart. Served
     per-branch, `ring-callable-for-ctx` re-reads the registry each call, so an
     invalidation makes the fresh row visible. THIS test writes a
     `:package-version` after boot, invalidates, and asserts it appears."
  (:require
    [cheshire.core :as json]
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
(def ^:private test-auth-token "optional-dispatch-test-token")
(def ^:private auth-headers {"authorization" (str "Bearer " test-auth-token)})


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  (fn [t]
    ;; Golden-clone of the CANONICAL optional-packages set — the same
    ;; `[core web app registry mcp]` golden `registry-test` /
    ;; `mcp-endpoint-test` clone (`app-base` arrives transitively via
    ;; `app`'s package.edn deps; naming it explicitly would key a
    ;; separate golden for an identical bundle). Swept rich-types come
    ;; from the per-JVM cache: the `:router-result`-over-`:router-or-nil`
    ;; shape of `:_registry-ring-response` / `:_mcp-ring-response`
    ;; relies on the sweep's `:produces-callable?` detection to
    ;; auto-invoke the produced Ring callable (without it, dispatch
    ;; returns the router fn itself instead of a response).
    (let [pkgs ["core" "web" "app" "registry" "mcp"]
          _ (reset! registry-core/*rich-types-override*
                    (sb/ensure-swept-rich-types! pkgs))
          ;; Sweep BEFORE bootstrap — its rebuild then compiles under
          ;; the same swept types as every sibling NS and shares one
          ;; compile-all cache entry (see golden-app/fixture).
          {:keys [storage]} (setup/bootstrap-crud-graph-from-golden!*
                              "graphden.integration.optional-packages-dispatch-test"
                              pkgs)
          ctx (exec/create-context
                {:storage storage
                 :auth-provider (auth/single-token-provider test-auth-token)})
          _ (cr/rebuild! ctx)
          ;; The production wiring: the branch-router serves the optional
          ;; packages' per-branch handlers alongside the main handler.
          router (br/create-router
                   ctx "_app-ring-response"
                   {:optional-handler-fn-names ["_registry-ring-response"
                                                "_mcp-ring-response"]})]
      (try
        (binding [*router* router *ctx* ctx] (t))
        (finally (sp/close storage))))))


(deftest mcp-route-threads-the-request-through-the-per-branch-handler
  (testing "POST /mcp `initialize` ECHOES the request id + returns a result —
            proving the request reaches `:_mcp-dispatch` (the seam baked this to
            a constant id:null method-not-found)"
    (let [resp (br/dispatch
                 *router*
                 {:request-method :post
                  :uri "/mcp"
                  :headers (assoc auth-headers "content-type" "application/json")
                  :query-string nil
                  :body (json/generate-string
                          {:jsonrpc "2.0" :id "ECHO-guard-1" :method "initialize"
                           :params {}})})
          body (some-> (:body resp) (json/parse-string true))]
      (is (= 200 (:status resp)))
      (is (= "ECHO-guard-1" (:id body)) "the request id is echoed — request was read")
      (is (some? (:result body)) "a real result, not an error")
      (is (nil? (:error body)))))

  (testing "POST /mcp `tools/list` returns the (non-empty) tool catalogue"
    (let [resp (br/dispatch
                 *router*
                 {:request-method :post
                  :uri "/mcp"
                  :headers (assoc auth-headers "content-type" "application/json")
                  :query-string nil
                  :body (json/generate-string
                          {:jsonrpc "2.0" :id 42 :method "tools/list" :params {}})})
          body (json/parse-string (:body resp) true)]
      (is (= 42 (:id body)))
      (is (seq (get-in body [:result :tools])) "tools/list is populated"))))


(deftest mcp-route-fails-closed-without-a-bearer
  ;; /mcp rides :post (auth-required): every AI tool call must carry a real
  ;; graphden token — the whole model of "the AI acts with exactly that
  ;; user's rights" rests on this 401. This was the one auth surface no MCP
  ;; test covered (mcp-endpoint-test drives :_mcp-dispatch directly).
  (let [post! (fn [headers]
                (br/dispatch *router*
                             {:request-method :post
                              :uri "/mcp"
                              :headers (merge {"content-type" "application/json"} headers)
                              :query-string nil
                              :body (json/generate-string
                                      {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}})}))]
    (testing "no Authorization header → 401"
      (is (= 401 (:status (post! {})))))
    (testing "a wrong bearer → 401"
      (is (= 401 (:status (post! {"authorization" "Bearer wrong"})))))
    (testing "the right bearer → 200 (sanity: the same request succeeds)"
      (is (= 200 (:status (post! auth-headers)))))))


(deftest registry-index-is-served-and-fresh-through-the-per-branch-handler
  (testing "GET /api/packages is served (200, JSON array) — the registry router
            reaches the request through the branch-router's optional slot"
    (let [resp (br/dispatch *router* {:request-method :get
                                      :uri "/api/packages"
                                      :headers auth-headers
                                      :query-string nil})]
      (is (= 200 (:status resp)))
      (is (sequential? (json/parse-string (:body resp))) "a JSON array (the registry index)")))

  (testing "a `:package-version` written AFTER boot becomes visible on the next
            read (FRESH) — the seam's boot-baked query would have hidden it"
    (let [before (json/parse-string
                   (:body (br/dispatch *router* {:request-method :get
                                                 :uri "/api/packages"
                                                 :headers auth-headers
                                                 :query-string nil}))
                   true)]
      (sp/create-entity (:storage *ctx*) :package-version
                        {:name "dispatch-guard-pkg" :version "1.0.0"
                         :ns-root "x" :fns [] :dependencies []
                         :content-hash "guardhash"})
      ;; NO cache invalidation on purpose — that IS the production shape:
      ;; `affected-fn-ids` answers #{} for :package-version (a pin row
      ;; cannot move a compiled closure), so the real publish path leaves
      ;; the compiled registry alone, and freshness comes from the
      ;; per-branch handler re-reading the registry each call. (This test
      ;; used to full-clear here "to mirror the publish path" — the
      ;; opposite of what production does — and the post-clear dispatch
      ;; recompiled the whole 5-package bundle: ~130 s idle, ~770 s in a
      ;; contended gate, for an assertion that is STRONGER without it.)
      (let [after (json/parse-string
                    (:body (br/dispatch *router* {:request-method :get
                                                  :uri "/api/packages"
                                                  :headers auth-headers
                                                  :query-string nil}))
                    true)]
        (is (= (inc (count before)) (count after)) "the new version is now listed")
        (is (some #(= "dispatch-guard-pkg" (:name %)) after)
            "the version published after boot is visible — reads are fresh")))))
