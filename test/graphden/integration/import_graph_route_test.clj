(ns ^:integration graphden.integration.import-graph-route-test
  "End-to-end coverage for `POST /api/import/graph` — the write half of the
   export/import pair (PACKAGE_DISTRIBUTION § runtime bundle import): apply
   an exported EDN bundle to a NAMED branch, with create/prune/skip-owned
   semantics. Driven through `br/dispatch` with the registry served the
   production way (`:optional-handler-fn-names`), like
   `graph_rows_route_test`."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.versioning.storage.core :as vs]
    [org.httpkit.server :as hk]))


(def ^:dynamic *router* nil)
(def ^:dynamic *storage* nil)


;; Registry impls are a PACKAGE ns (path ≠ ns, loaded by the package loader),
;; so reach the private `resolve-remote-version` by load-file + ns-resolve —
;; the house pattern (see effect_trace_test).
(def ^:private registry-resolve-remote-version
  (let [r (io/resource "packages/registry/registry/impls.clj")]
    (when r (load-file (java.io.File/.getPath (io/file r))))
    (ns-resolve (find-ns 'graphden.packages.app.registry.impls)
                'resolve-remote-version)))


;; The `mirror-remote-package!` defbase — the SECOND egress-guard site (the
;; pinned `?format=edn` fetch, reached with a CONCRETE spec that
;; `resolve-remote-version` passes straight through without a list dial, so
;; this guard is the only one on that path).
(def ^:private registry-mirror-remote-package
  (ns-resolve (find-ns 'graphden.packages.app.registry.impls)
              'mirror-remote-package!))


(def ^:private test-auth-token "import-graph-test-token-xyz")
(def ^:private auth-headers {"authorization" (str "Bearer " test-auth-token)})


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  (fn [t]
    (let [pkgs ["core" "web" "app" "registry" "mcp"]
          _ (reset! registry-core/*rich-types-override*
                    (sb/ensure-swept-rich-types! pkgs))
          {:keys [storage]} (setup/bootstrap-crud-graph-from-golden!*
                              "graphden.integration.import-graph-route-test"
                              pkgs)
          ctx (exec/create-context
                {:storage storage
                 :auth-provider (auth/single-token-provider test-auth-token)})
          _ (cr/rebuild! ctx)
          router (br/create-router
                   ctx "_app-ring-response"
                   {:optional-handler-fn-names ["_registry-ring-response"]})]
      (try
        (binding [*router* router
                  *storage* storage]
          (t))
        (finally (sp/close storage))))))


(defn- import!
  "POST an EDN body to /api/import/graph. Returns {:status :json}."
  ([query body] (import! query body auth-headers))
  ([query body headers]
   (let [resp (br/dispatch *router*
                           {:request-method :post
                            :uri "/api/import/graph"
                            :headers (merge {"content-type" "application/edn"} headers)
                            :query-string query
                            :body body})]
     {:status (:status resp)
      :json (try (json/parse-string (str (:body resp)) true)
                 (catch Exception _ nil))})))


(defn- branch-fn-names
  "Named fns visible on `branch-name`'s resolved view."
  [branch-name]
  (let [branch (first (sp/query-entities (:base-storage *storage*)
                                         :branch {:name branch-name}))
        on-branch (vs/switch-branch *storage* (:id branch))]
    (into #{} (keep :name) (sp/query-entities on-branch :fn {}))))


(deftest import-fails-closed
  (testing "no bearer → 401"
    (is (= 401 (:status (import! "target=x" "[]" {})))))
  (testing "no branch param → 400 (imports never touch the request's branch)"
    (let [{:keys [status json]} (import! nil "[]")]
      (is (= 400 status))
      (is (= "branch-param-required" (:reason json)))))
  (testing "unknown branch without ?create=true → 404 with the create hint"
    (let [{:keys [status json]} (import! "target=imp/ghost" "[{:name :a :parent :add}]")]
      (is (= 404 status))
      (is (= "branch-not-found" (:reason json)))))
  (testing "malformed EDN → 400, not a 500"
    (let [{:keys [status json]} (import! "target=imp/ghost&create=true" "{not edn")]
      (is (= 400 status))
      (is (= "body-not-a-bundle" (:reason json)))))
  (testing "a fn-def without :name → 400"
    (let [{:keys [status json]} (import! "target=imp/ghost&create=true" "[{:parent :add}]")]
      (is (= 400 status))
      (is (= "fn-def-without-name" (:reason json))))))


(deftest import-creates-the-branch-and-applies-the-bundle
  (let [branch (str "imp/apply-" (subs (str (random-uuid)) 0 8))
        bundle "[{:name :imp-a :namespace \"imp.demo\" :parent :add :args {:nums [40 2]}}]"
        {:keys [status json]} (import! (str "target=" branch "&create=true") bundle)]
    (testing "the import creates the branch and lands the fn on it"
      (is (= 200 status) (pr-str json))
      (is (true? (:ok json)))
      (is (= branch (:branch json)))
      (is (= 1 (count (:fn-ids json))))
      (is (contains? (branch-fn-names branch) "imp-a")))
    (testing "main never sees it"
      (is (not-any? #(= "imp-a" (:name %))
                    (sp/query-entities *storage* :fn {:name "imp-a"}))))
    (testing "re-importing the same bundle is idempotent (deterministic ids)"
      (let [{again :json} (import! (str "target=" branch) bundle)]
        (is (= (:fn-ids json) (:fn-ids again)))))))


(deftest import-skips-platform-owned-defs
  (let [branch (str "imp/owned-" (subs (str (random-uuid)) 0 8))
        bundle (str "[{:name :add :namespace \"core.arithmetic\" :parent :const :args {:value 1}}"
                    " {:name :imp-own :namespace \"imp.demo\" :parent :add :args {:nums [1 2]}}]")
        {:keys [status json]} (import! (str "target=" branch "&create=true") bundle)]
    (is (= 200 status) (pr-str json))
    (is (= ["add"] (:skipped-owned json))
        "the package-synced def is skipped + reported, not silently rewritten")
    (is (= 1 (count (:fn-ids json))) "the user's own def still lands")))


(deftest import-prune-gives-snapshot-semantics
  (let [branch (str "imp/prune-" (subs (str (random-uuid)) 0 8))
        both (str "[{:name :imp-keep :namespace \"imp.snap\" :parent :add :args {:nums [1]}}"
                  " {:name :imp-drop :namespace \"imp.snap\" :parent :add :args {:nums [2]}}]")
        only-keep "[{:name :imp-keep :namespace \"imp.snap\" :parent :add :args {:nums [1]}}]"]
    (import! (str "target=" branch "&create=true") both)
    (is (= #{"imp-keep" "imp-drop"}
           (into #{} (filter #{"imp-keep" "imp-drop"}) (branch-fn-names branch))))
    (testing "re-importing a smaller snapshot with ?prune=true tombstones the rest"
      (let [{:keys [json]} (import! (str "target=" branch "&prune=true") only-keep)]
        (is (= ["imp-drop"] (get-in json [:pruned :pruned])) (pr-str json))
        (is (not (contains? (branch-fn-names branch) "imp-drop")))
        (is (contains? (branch-fn-names branch) "imp-keep"))))
    (testing "without ?prune the import stays additive"
      (import! (str "target=" branch) both)
      (let [{:keys [json]} (import! (str "target=" branch) only-keep)]
        (is (nil? (:pruned json)))
        (is (contains? (branch-fn-names branch) "imp-drop"))))))


(deftest import-prune-tombstones-a-fn-inherited-from-the-base-branch
  ;; The push/pull case: a deletion made on main must PROPAGATE. A bare
  ;; hard-delete is a no-op for an fn INHERITED from the base branch (no
  ;; branch-local version row to remove), so prune has to TOMBSTONE. Import
  ;; onto main, fork a child, then import a smaller snapshot with prune onto
  ;; the child — the fn that lives only on the parent must vanish on the child.
  (let [_ (import! "target=main"
                   (str "[{:name :inh-keep :namespace \"imp.inh\" :parent :add :args {:nums [1]}}"
                        " {:name :inh-gone :namespace \"imp.inh\" :parent :add :args {:nums [2]}}]"))
        child (str "imp/inh-" (subs (str (random-uuid)) 0 8))]
    (import! (str "target=" child "&create=true") "[]")   ; fork child off main, add nothing
    (is (contains? (branch-fn-names child) "inh-gone")
        "the child inherits both fns from main")
    (testing "prune on the child tombstones the inherited fn absent from the snapshot"
      (let [{:keys [json]} (import! (str "target=" child "&prune=true")
                                    "[{:name :inh-keep :namespace \"imp.inh\" :parent :add :args {:nums [1]}}]")]
        (is (= ["inh-gone"] (get-in json [:pruned :pruned])) (pr-str json))
        (is (not (contains? (branch-fn-names child) "inh-gone"))
            "the inherited fn is hidden on the child (tombstone), not a silent no-op")
        (is (contains? (branch-fn-names child) "inh-keep"))
        (testing "main still has it — the tombstone is branch-scoped"
          (is (some #(= "inh-gone" (:name %))
                    (sp/query-entities *storage* :fn {:name "inh-gone"}))))))))


(deftest import-prune-keeps-referenced-fns-loud
  (let [branch (str "imp/ref-" (subs (str (random-uuid)) 0 8))
        both (str "[{:name :imp-leaf :namespace \"imp.refs\" :parent :add :args {:nums [1]}}"
                  " {:name :imp-user :namespace \"imp.refs\" :parent :add :args {:nums [:imp-leaf]}}]")
        only-user "[{:name :imp-user :namespace \"imp.refs\" :parent :add :args {:nums [:imp-leaf]}}]"]
    (import! (str "target=" branch "&create=true") both)
    (testing "a snapshot that drops a still-referenced fn KEEPS it and reports"
      (let [{:keys [json]} (import! (str "target=" branch "&prune=true") only-user)]
        (is (= ["imp-leaf"] (get-in json [:pruned :kept-referenced])) (pr-str json))
        (is (contains? (branch-fn-names branch) "imp-leaf"))))))


(deftest import-adopts-editor-born-identities
  ;; The pull-after-push dedup: an fn born in the editor carries a RANDOM
  ;; id; the hub's sync of a pushed bundle minted the deterministic
  ;; uuid-v5(ns,name). Importing that bundle back must ADOPT the local
  ;; random-id row onto the deterministic id — not land a same-name twin.
  (let [branch (str "imp/adopt-" (subs (str (random-uuid)) 0 8))
        seed "[{:name :adopt-seed :namespace \"imp.adopt\" :parent :add :args {:nums [1]}}]"
        _ (import! (str "target=" branch "&create=true") seed)
        branch-row (first (sp/query-entities (:base-storage *storage*)
                                             :branch {:name branch}))
        on-branch (vs/switch-branch *storage* (:id branch-row))
        ns-id (:namespace-id (first (sp/query-entities on-branch :fn {:name "adopt-seed"})))
        editor-row (sp/create-entity on-branch :fn {:id (random-uuid)
                                                    :name "adoptee"
                                                    :namespace-id ns-id})
        bundle "[{:name :adoptee :namespace \"imp.adopt\" :parent :add :args {:nums [7]}}]"
        {:keys [json]} (import! (str "target=" branch) bundle)]
    (is (= ["adoptee"] (:adopted json)) (pr-str json))
    (let [rows (sp/query-entities on-branch :fn {:name "adoptee"})]
      (is (= 1 (count rows)) "no same-name twin")
      (is (not= (:id editor-row) (:id (first rows)))
          "the surviving row carries the deterministic id, not the random one"))))


(deftest export-import-round-trips-the-whole-graph
  ;; The migration story: GET /api/export/graph → POST /api/import/graph on
  ;; a branch. Platform defs come back skipped-owned (the boot sync owns
  ;; them); the import itself must succeed without touching them.
  (let [export (br/dispatch *router*
                            {:request-method :get
                             :uri "/api/export/graph"
                             :headers auth-headers
                             :query-string nil
                             :body nil})
        _ (is (= 200 (:status export)))
        branch (str "imp/round-" (subs (str (random-uuid)) 0 8))
        {:keys [status json]} (import! (str "target=" branch "&create=true")
                                       (str (:body export)))]
    (is (= 200 status) (pr-str (select-keys json [:reason :error])))
    (is (true? (:ok json)))
    (is (seq (:skipped-owned json))
        "the whole-graph bundle's platform defs are skipped, not rewritten")))


(deftest remote-install-mirrors-and-installs
  ;; Cross-install package pull (PACKAGE_DISTRIBUTION § 13): POST
  ;; /api/packages/install with a :source mirrors the version from the
  ;; remote registry's EDN face, then the normal install worklist
  ;; materializes + pins it locally.
  (let [row {:name "acme.rp" :version "1.0.0" :ns-root "acme.rp"
             :fns [{:name :rp-hello :namespace "acme.rp"
                    :parent :const :args {:value "hi"}}]
             :dependencies [] :package-dependencies []
             :secrets [] :content-hash "rp-hash"}
        seen (atom [])
        stub (hk/run-server
               (fn [req]
                 (swap! seen conj (:uri req))
                 (if (= "/api/packages/acme.rp/1.0.0" (:uri req))
                   {:status 200 :headers {"Content-Type" "application/edn"}
                    :body (pr-str row)}
                   {:status 404 :body "nope"}))
               {:port 0})
        source (str "http://localhost:" (:local-port (meta stub)))]
    (try
      (let [resp (br/dispatch *router*
                              {:request-method :post
                               :uri "/api/packages/install"
                               :headers (merge auth-headers
                                               {"content-type" "application/json"})
                               :query-string nil
                               :body (json/generate-string
                                       {:name "acme.rp" :version "1.0.0"
                                        :source source})})
            json (json/parse-string (str (:body resp)) true)]
        (is (= 200 (:status resp)) (pr-str json))
        (is (true? (:ok json)) (pr-str json))
        (is (= ["/api/packages/acme.rp/1.0.0"] @seen)
            "exactly one remote fetch (idempotent mirror)")
        (testing "the mirrored row exists locally, never public"
          (let [local (first (sp/query-entities *storage* :package-version
                                                {:name "acme.rp" :version "1.0.0"}))]
            (is (some? local))
            (is (false? (:public? local)))
            (is (= "rp-hash" (:content-hash local)))
            (is (= (:fns row) (:fns local))
                "fn-def keywords survive the EDN wire")))
        (testing "the version is materialized under its version-qualified ns"
          (is (seq (sp/query-entities *storage* :fn {:name "rp-hello"})))))
      (finally (stub)))))


(deftest remote-install-resolves-latest-against-the-remote-list
  ;; A non-concrete spec (latest / range) is resolved against the remote's
  ;; version list first (resolve-remote-version), then the concrete version
  ;; is mirrored + installed.
  (let [mk-row (fn [v]
                 {:name "acme.rp3" :version v :ns-root "acme.rp3"
                  :fns [{:name (keyword (str "rp3-" (str/replace v "." "-")))
                         :namespace "acme.rp3" :parent :const :args {:value v}}]
                  :dependencies [] :package-dependencies []
                  :secrets [] :content-hash (str "h-" v)})
        seen (atom [])
        stub (hk/run-server
               (fn [req]
                 (swap! seen conj (:uri req))
                 (condp = (:uri req)
                   "/api/packages"
                   {:status 200 :headers {"Content-Type" "application/json"}
                    :body (json/generate-string
                            {:packages [{:name "acme.rp3" :version "1.0.0"}
                                        {:name "acme.rp3" :version "2.1.0"}
                                        {:name "other" :version "9.0.0"}]})}
                   "/api/packages/acme.rp3/2.1.0"
                   {:status 200 :headers {"Content-Type" "application/edn"}
                    :body (pr-str (mk-row "2.1.0"))}
                   {:status 404 :body "nope"}))
               {:port 0})
        source (str "http://localhost:" (:local-port (meta stub)))]
    (try
      (let [resp (br/dispatch *router*
                              {:request-method :post
                               :uri "/api/packages/install"
                               :headers (merge auth-headers {"content-type" "application/json"})
                               :query-string nil
                               :body (json/generate-string
                                       {:name "acme.rp3" :version "latest" :source source})})
            json (json/parse-string (str (:body resp)) true)]
        (is (= 200 (:status resp)) (pr-str json))
        (is (true? (:ok json)) (pr-str json))
        (is (some #{"/api/packages"} @seen) "the version list was consulted")
        (is (some #{"/api/packages/acme.rp3/2.1.0"} @seen) "the highest version was fetched")
        (is (some? (first (sp/query-entities *storage* :package-version
                                             {:name "acme.rp3" :version "2.1.0"})))
            "latest resolved to 2.1.0 and was mirrored")
        (is (seq (sp/query-entities *storage* :fn {:name "rp3-2-1-0"}))))
      (finally (stub)))))


(deftest remote-fetch-ssrf-guarded-in-restricted-ctx
  ;; A RESTRICTED (tenant/cloud) execution — `*allowed-effects*` bound —
  ;; must refuse a caller-supplied `source` pointing at an internal /
  ;; link-local target BEFORE dialing (SSRF + registry-token exfiltration).
  ;; The unrestricted platform / self-host ctx is NOT gated, so an offline
  ;; localhost hub still resolves (that's the whole point of push/pull).
  (testing "restricted ctx blocks a link-local source (cloud-metadata probe)"
    (binding [cr/*allowed-effects* #{:network}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"(?i)egress"
            (registry-resolve-remote-version "http://169.254.169.254" "acme.x" "latest"))
          "link-local source → :egress/blocked before any dial")))
  (testing "unrestricted ctx does NOT egress-block (self-host localhost hub)"
    ;; *allowed-effects* nil → no egress check; resolve reaches the dial and
    ;; returns nil (nothing listening) rather than throwing :egress/blocked.
    (is (nil? (registry-resolve-remote-version "http://127.0.0.1:1" "acme.x" "latest"))
        "loopback allowed in the unrestricted path (returns nil, not blocked)"))
  (testing "the CONCRETE-spec mirror path is guarded too (its own check-target!)"
    ;; A concrete version skips resolve-remote-version's list dial, so
    ;; mirror-remote-package!'s own guard is the ONLY one on that path —
    ;; a link-local source must still be blocked before the pinned fetch.
    (binding [cr/*allowed-effects* #{:network :db :env}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"(?i)egress"
            (registry-mirror-remote-package
              {:source "http://169.254.169.254" :pkg-name "acme.x" :spec "1.0.0"}
              {}))
          "concrete-spec mirror → :egress/blocked before the pinned dial")))
  (testing "unrestricted ctx does NOT block the concrete mirror path either"
    (let [r (registry-mirror-remote-package
              {:source "http://127.0.0.1:1" :pkg-name "acme.x" :spec "1.0.0"}
              {})]
      (is (= "remote-unreachable" (:error r))
          "loopback allowed unrestricted → reaches the dial, fails as data not :egress"))))


(deftest import-decodes-graphden-ref-wire-tags
  ;; Regression for the offline-epic fix (`_import-parsed` → `:parse-graph-edn`).
  ;; The exporter emits `#graphden/ref` for keywords no default EDN reader
  ;; accepts (root-ns `:/name`, version-qualified `:ns@ver/name`). With the
  ;; old `:parse-edn` (default readers) such a bundle read as nil → the whole
  ;; import 400'd "body-not-a-bundle". The wire readers must decode the tag.
  (let [branch (str "imp/wire-" (subs (str (random-uuid)) 0 8))
        ;; A root-ns ref (`#graphden/ref "/wt-root"` → :/wt-root) to a root
        ;; fn defined in the SAME bundle — exercises the reader AND resolves.
        bundle (str "[{:name :wt-root :parent :const :args {:value 7}}"
                    " {:name :wt-user :namespace \"wt.demo\" :parent :add"
                    "  :args {:nums [#graphden/ref \"/wt-root\"]}}]")
        {:keys [status json]} (import! (str "target=" branch "&create=true") bundle)]
    (is (not= "body-not-a-bundle" (:reason json))
        "the #graphden/ref bundle is parsed, not rejected as unreadable")
    (is (= 200 status) (pr-str json))
    (is (contains? (branch-fn-names branch) "wt-user")
        "the fn whose arg carries a wire-tag ref landed")
    (is (contains? (branch-fn-names branch) "wt-root"))))
