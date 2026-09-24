(ns ^{:cost :heavy} graphden.packages.registry-publish-test
  "Package registry — the PUBLISH side: the `:package-version` row, the
   export bundles a publish ships, publish / withdraw / list, the panel's
   publish + update, and remote pulls (incl. which origin gets the bearer).
   The install side is `registry-install-test`; shared fixture in
   `registry-fixture`."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.wire :as wire]
    [graphden.packages.registry-fixture :as rf :refer [*bootstrap* publish-req storage run-named]]
    [graphden.packages.registry-shared :as shared]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.seams :as ts]
    [org.httpkit.server :as http-kit]))


(use-fixtures :once
  ts/isolated-seams-fixture
  (rf/bootstrap-fixture "registry-publish-test"))


(deftest package-version-entity-roundtrips
  (testing "a :package-version row stores + restores its fields, incl. jsonb"
    (let [bundle [{:name :foo :parent :bar :args {:x {:value 1}}}
                  {:name :baz :namespace "acme.demo" :type {:a :int}}]
          row (sp/create-entity (storage) :package-version
                                {:name "acme.demo"
                                 :version "1.0.0"
                                 :ns-root "acme.demo"
                                 :fns bundle
                                 :dependencies [:html-page-handler :hiccup]
                                 :content-hash "deadbeef"})
          back (sp/read-entity (storage) :package-version (:id row))]
      (is (= "acme.demo" (:name back)))
      (is (= "1.0.0" (:version back)))
      (is (= "acme.demo" (:ns-root back)))
      (is (= "deadbeef" (:content-hash back)))
      (testing "jsonb fn-def bundle round-trips with keywords intact"
        (is (= bundle (:fns back))))
      (testing "jsonb dependency list round-trips"
        (is (= [:html-page-handler :hiccup] (:dependencies back))))))
  (testing "query-entities finds published versions by name"
    (sp/create-entity (storage) :package-version
                      {:name "acme.q" :version "0.1.0" :ns-root "acme.q"
                       :fns [] :dependencies [] :content-hash "h1"})
    (sp/create-entity (storage) :package-version
                      {:name "acme.q" :version "0.2.0" :ns-root "acme.q"
                       :fns [] :dependencies [] :content-hash "h2"})
    (let [rows (sp/query-entities (storage) :package-version {:name "acme.q"})]
      (is (= #{"0.1.0" "0.2.0"} (set (map :version rows)))))))


(deftest export-namespace-base-fn-executes
  (testing ":export-namespace runs through the executor against the live graph"
    (let [{:keys [ctx all-name->id]} *bootstrap*
          fn-id (get all-name->id :export-namespace)
          bundle (exec/execute-with-named-args ctx fn-id {:root "app.contact-demo"})]
      (is (= "app.contact-demo" (:namespace bundle)))
      (is (seq (:fns bundle)))
      (is (every? #(= "app.contact-demo" (:namespace %)) (:fns bundle)))
      (is (some #{:html-page-handler} (:dependencies bundle))
          "the bundle declares its external dependency"))))


(deftest export-graph-base-fn-and-handler
  (testing ":export-graph runs through the executor against the whole live graph"
    (let [{:keys [ctx all-name->id]} *bootstrap*
          bundle (exec/execute-with-named-args ctx (get all-name->id :export-graph) {})]
      (is (= #{:fns :namespaces :secrets :secret-paths-included?}
             (set (keys bundle))))
      (is (> (count (:fns bundle)) 2000) "whole graph = thousands of fn-defs")
      (is (contains? (set (:namespaces bundle)) "app.page"))
      (is (false? (:secret-paths-included? bundle))
          "no query param bound → default strip mode")))
  (testing "GET /api/export/graph returns the bundle as an application/edn body"
    (let [resp (setup/via-graph *bootstrap* :_export-graph-handler
                                {:request-method :get})
          ;; Bundle consumers read with `wire/wire-readers` — the live
          ;; corpus HAS duplicated names in unspellable namespaces, so
          ;; the body legitimately carries `#graphden/ref` literals.
          bundle (edn/read-string {:readers wire/wire-readers} (:body resp))]
      (is (= 200 (:status resp)))
      ;; The header map keys come back keyword-ised from the fns.edn literal
      ;; (JSONB roundtrip); the production http adapter stringifies them before
      ;; the wire, but via-graph returns the raw handler output — so assert on
      ;; the value, key-form-independent.
      (is (contains? (set (vals (:headers resp))) "application/edn"))
      (is (= #{:fns :namespaces :secrets :secret-paths-included?}
             (set (keys bundle)))
          "the EDN body round-trips to the same bundle shape (keywords preserved)")
      (is (some #(= :html-page-handler (:name %)) (:fns bundle))
          "a known fn-def survives the EDN round-trip with keyword keys/values"))))


(deftest publish-carries-secrets-manifest-install-reports-needs-definition
  ;; The share-safety contract end-to-end: a bundle whose export stripped
  ;; a vault path carries the :secrets manifest → publish persists +
  ;; returns it (the publisher's warning) → install surfaces it as
  ;; :needs-definition (the installer's todo). The secret-shaped def
  ;; (:parent :secret-leaf, no :in binding) materializes fine — its :in
  ;; slot is simply a free [:secret :text] arg until the installer binds
  ;; a vault path of their own.
  (let [{:keys [ctx all-name->id]} *bootstrap*
        bundle {:namespace "sectest"
                :namespaces ["sectest"]
                :fns [{:name :sec-pass :namespace "sectest" :parent :secret-leaf}]
                :dependencies [:secret-leaf]
                :package-dependencies []
                :secrets [{:fn :sec-pass :arg :in}]
                :secret-paths-included? false}
        ;; :publish-package (not the -apply core): the core returns the
        ;; row-or-nil since the envelope moved into the graph — the
        ;; envelope contract is asserted at the graph fn-def.
        pub (exec/execute-with-named-args
              ctx (get all-name->id :publish-package)
              {:pkg-name "sec.pkg" :pkg-version "1.0.0" :bundle bundle})]
    (testing "publish returns + persists the secrets manifest"
      (is (true? (:ok pub)))
      (is (= [{:fn :sec-pass :arg :in}] (:secrets pub))
          "the publisher is told what was stripped — never silent")
      (let [row (first (sp/query-entities (storage) :package-version
                                          {:name "sec.pkg"}))]
        (is (= [{:fn :sec-pass :arg :in}] (:secrets row)))))
    (testing "install surfaces :needs-definition from the stored manifest"
      (let [res (exec/execute-with-named-args
                  ctx (get all-name->id :install-package)
                  {:pkg-name "sec.pkg" :pkg-version "1.0.0"})]
        (is (true? (:ok res)))
        (is (= [{:fn :sec-pass :arg :in}] (:needs-definition res))
            "the installer is told which secrets to define")))))


(deftest publish-refuses-a-breaking-change-inside-the-caret-range
  ;; Semver is verified: the bundle is diffed against the newest published
  ;; version below the candidate (`graphden.packages.compat`), and a
  ;; consumer-visible break is refused unless the version leaves that
  ;; version's caret range.
  (let [{:keys [ctx all-name->id]} *bootstrap*
        bundle (fn [fns]
                 {:namespace "compat" :namespaces ["compat"] :fns fns
                  :dependencies [] :package-dependencies []
                  :secrets [] :secret-paths-included? false})
        publish (fn [version fns]
                  (exec/execute-with-named-args
                    ctx (get all-name->id :publish-package)
                    {:pkg-name "compat.pkg" :pkg-version version :bundle (bundle fns)}))
        v1 [{:name :greet :namespace "compat" :parent :str :args {:parts {:value ["hi"]}}}
            {:name :shape :namespace "compat" :type {:id :uuid :nick :text}}]]
    (testing "the first version has nothing to break"
      (is (true? (:ok (publish "1.0.0" v1)))))
    (testing "a compatible successor publishes"
      (is (true? (:ok (publish "1.1.0" (conj v1 {:name :wave :namespace "compat" :parent :str
                                                 :args {:parts {:value ["o/"]}}}))))))
    (testing "a break inside ^1.1.0 is refused with the change list"
      (let [res (publish "1.2.0" [{:name :greet :namespace "compat" :parent :str
                                   :args {:parts {:value ["hi"]}}}
                                  {:name :shape :namespace "compat" :type {:id :uuid}}])]
        (is (false? (:ok res)))
        (is (= "breaking-change" (:reason res)))
        (is (= "1.1.0" (:previous res)) "diffed against the newest version below the candidate")
        (is (= [{:kind :arg-removed :fn :shape :arg :nick :old :text}
                {:kind :fn-removed :fn :wave}]
               (:changes res)))
        (is (empty? (sp/query-entities (storage) :package-version
                                       {:name "compat.pkg" :version "1.2.0"}))
            "nothing written")))
    (testing "the same bundle as a major bump publishes"
      (is (true? (:ok (publish "2.0.0" [{:name :greet :namespace "compat" :parent :str
                                         :args {:parts {:value ["hi"]}}}
                                        {:name :shape :namespace "compat" :type {:id :uuid}}])))))))


(deftest publish-public-flag-normalisation
  ;; Spec §5: `:public?` is normalised AT WRITE time — a platform-tier
  ;; publish (single-tenant / operator) is always platform-visible; a
  ;; tenant publish is private unless the explicit opt-in is set. Readers
  ;; (browse badge, RLS select arm) key on the flag alone, never on org-id.
  (let [{:keys [ctx all-name->id]} *bootstrap*
        ;; :publish-package (not the -apply core) — the :ok/:public
        ;; envelope is graph composition now; the row-level :public?
        ;; assertions below still pin the write-time normalisation.
        apply-id (get all-name->id :publish-package)
        bundle {:namespace "pubflag"
                :namespaces ["pubflag"]
                :fns [{:name :pf-x :namespace "pubflag" :parent :const
                       :args {:value {:value 1}}}]
                :dependencies [:const]
                :package-dependencies []
                :secrets []
                :secret-paths-included? false}]
    (testing "platform-tier publish normalises :public? true without the opt-in"
      (let [res (exec/execute-with-named-args
                  ctx apply-id
                  {:pkg-name "pubflag.plat" :pkg-version "1.0.0" :bundle bundle})]
        (is (true? (:ok res)))
        (is (true? (:public res)))
        (is (true? (:public? (first (sp/query-entities (storage) :package-version
                                                       {:name "pubflag.plat"})))))))
    (testing "org-bound publish defaults to private; the opt-in makes it public"
      ;; Simulate a tenant: bind a real org + grant the publish capability
      ;; through the org-cap seam (default-deny would 403 first).
      (try
        (tc/install-org-cap-fn! (fn [cap] (= cap :publish-packages)))
        (binding [tc/*current-org* "org-priv-test"]
          (let [private-res (exec/execute-with-named-args
                              ctx apply-id
                              {:pkg-name "pubflag.priv" :pkg-version "1.0.0" :bundle bundle})
                public-res (exec/execute-with-named-args
                             ctx apply-id
                             {:pkg-name "pubflag.pub" :pkg-version "1.0.0" :bundle bundle
                              :pkg-public true})]
            (is (true? (:ok private-res)))
            (is (false? (:public private-res)))
            (is (false? (:public? (first (sp/query-entities (storage) :package-version
                                                            {:name "pubflag.priv"})))))
            (is (true? (:ok public-res)))
            (is (true? (:public public-res)))
            (is (true? (:public? (first (sp/query-entities (storage) :package-version
                                                           {:name "pubflag.pub"})))))))
        (finally
          (tc/install-org-cap-fn! nil))))))


(deftest withdraw-requires-publish-capability
  ;; Withdraw is the DESTRUCTIVE counterpart of publish and must be gated on the
  ;; same `:publish-packages` capability at the core — otherwise an org member
  ;; explicitly DENIED publish rights could still permanently erase the org's
  ;; published versions (a within-org missing-authorization escalation).
  (let [{:keys [ctx all-name->id]} *bootstrap*
        apply-id (get all-name->id :withdraw-package-apply)
        mk! (fn [nm]
              (:id (sp/create-entity (storage) :package-version
                                     {:name nm :version "1.0.0" :ns-root nm
                                      :fns [] :dependencies [] :content-hash "h"
                                      :org-id "org-wd-test"})))]
    (testing "a tenant WITHOUT :publish-packages is refused (:authz/forbidden), row survives"
      (try
        (tc/install-org-cap-fn! (constantly false))
        (binding [tc/*current-org* "org-wd-test"]
          (let [pid (mk! "wd.denied")
                ex (try (exec/execute-with-named-args ctx apply-id {:id pid})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) "withdraw threw")
            (is (= :authz/forbidden (:type (ex-data ex))))
            (is (seq (sp/query-entities (storage) :package-version {:name "wd.denied"}))
                "the version survives the refused withdraw")))
        (finally (tc/install-org-cap-fn! nil))))
    (testing "with :publish-packages the withdraw succeeds"
      (try
        (tc/install-org-cap-fn! (fn [cap] (= cap :publish-packages)))
        (binding [tc/*current-org* "org-wd-test"]
          (let [pid (mk! "wd.allowed")]
            (exec/execute-with-named-args ctx apply-id {:id pid})
            (is (empty? (sp/query-entities (storage) :package-version {:name "wd.allowed"}))
                "the version is gone after an authorized withdraw")))
        (finally (tc/install-org-cap-fn! nil))))))


(deftest publish-handler-creates-and-rejects-duplicate
  (testing "POST /api/packages/publish exports + stores a package version"
    (let [resp (setup/via-graph *bootstrap* :publish-package-handler
                                (publish-req {:name "demo.pkg" :version "1.0.0"
                                              :ns-root "app.contact-demo"}))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (= "demo.pkg" (:name body)))
      (is (pos? (:fn-count body)))
      ;; the row-derived fields — nil until the envelope re-ran
      ;; the effectful apply for each field it read
      (is (re-matches #"[0-9a-f-]{36}" (str (:id body))) "the created row's id")
      (is (true? (:public body)) "a platform-tier publish is public")
      (is (= "approved" (:status body)))
      (is (string? (:content-hash body)))
      (is (some #{"html-page-handler"} (:dependencies body))
          "external dep surfaced (keyword → string over JSON)")
      (testing "the row persisted with the exported subtree"
        (let [rows (sp/query-entities (storage) :package-version {:name "demo.pkg"})]
          (is (= 1 (count rows)))
          (is (= "app.contact-demo" (:ns-root (first rows))))
          (is (seq (:fns (first rows))))))))
  (testing "another org cannot publish under a name this org lists publicly — names are first come, first served"
    (tc/install-org-cap-fn! (fn [cap] (= cap :publish-packages)))
    (try
      (let [resp (binding [tc/*current-org* "rival-org"]
                   (setup/via-graph *bootstrap* :publish-package-handler
                                    (publish-req {:name "demo.pkg" :version "2.0.0"
                                                  :ns-root "app.contact-demo"})))
            body (json/parse-string (:body resp) true)]
        (is (false? (:ok body)))
        (is (= "name-taken" (:reason body)))
        (is (= "public" (:holder body)) "the holder is named")
        (is (= 1 (count (sp/query-entities (storage) :package-version {:name "demo.pkg"}))) "nothing written"))
      (finally (tc/install-org-cap-fn! nil))))
  (testing "re-publishing the same (name, version) is rejected — immutability"
    (let [resp (setup/via-graph *bootstrap* :publish-package-handler
                                (publish-req {:name "demo.pkg" :version "1.0.0"
                                              :ns-root "app.contact-demo"}))
          body (json/parse-string (:body resp) true)]
      (is (false? (:ok body)))
      (is (= "version-exists" (:reason body)))
      (is (= 1 (count (sp/query-entities (storage) :package-version {:name "demo.pkg"})))
          "no duplicate row written")))
  (testing "publishing a non-existent namespace exports 0 fns → rejected, no garbage row"
    (let [resp (setup/via-graph *bootstrap* :publish-package-handler
                                (publish-req {:name "empty.pkg" :version "1.0.0"
                                              :ns-root "no.such.namespace.xyz"}))
          body (json/parse-string (:body resp) true)]
      (is (false? (:ok body)))
      (is (= "empty-bundle" (:reason body)))
      (is (empty? (sp/query-entities (storage) :package-version {:name "empty.pkg"}))
          "no 0-fn garbage row written to the registry"))))


(deftest list-and-fetch-package-versions
  (sp/create-entity (storage) :package-version
                    {:name "lf.pkg" :version "2.0.0" :ns-root "lf.pkg"
                     :fns [{:name :x}] :dependencies [:dep-a] :content-hash "hh"})
  (testing "GET /api/packages returns the index (metadata, no :fns blob)"
    (let [resp (setup/via-graph *bootstrap* :list-packages-handler
                                {:request-method :get :headers {}})
          body (json/parse-string (:body resp) true)
          entry (first (filter #(= "lf.pkg" (:name %)) body))]
      (is (= 200 (:status resp)))
      (is (some? entry))
      (is (= "2.0.0" (:version entry)))
      (is (= 1 (:fn-count entry)))
      (is (not (contains? entry :fns)) "index omits the bundle")))
  (testing "GET /api/packages/:name/:version returns the full bundle"
    (let [resp (setup/via-graph *bootstrap* :fetch-package-handler
                                {:request-method :get
                                 :path-params {:name "lf.pkg" :version "2.0.0"}
                                 :headers {}})
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= "lf.pkg" (:name body)))
      (is (= [{:name "x"}] (:fns body)) "the full :fns bundle is present")))
  (testing "fetching an unknown version returns null"
    (let [resp (setup/via-graph *bootstrap* :fetch-package-handler
                                {:request-method :get
                                 :path-params {:name "lf.pkg" :version "9.9.9"}
                                 :headers {}})]
      (is (= "null" (:body resp))))))


(deftest panel-install-pulls-from-a-remote-registry
  ;; The remote-install form: form-encoded {source, name, version} → the SAME
  ;; panel-install route mirrors the version from the remote registry
  ;; (mirror-remote-package! inside the install worklist), then materializes
  ;; + pins it locally and returns the refreshed panel.
  (let [remote-row {:name "remote.pkg" :version "1.0.0" :ns-root "remotepkg.demo"
                    :fns [{:name :remote-greeting :namespace "remotepkg.demo"
                           :parent :const :args {:value "hi from remote"}}]
                    :dependencies [:const] :package-dependencies []
                    :content-hash "rh"}
        handler (fn [req]
                  (condp = (:uri req)
                    "/api/packages"
                    {:status 200 :headers {"Content-Type" "application/json"}
                     :body (json/generate-string
                             {:packages [{:name "remote.pkg" :version "1.0.0"}]})}
                    "/api/packages/remote.pkg/1.0.0"
                    {:status 200 :headers {"Content-Type" "application/edn"}
                     :body (pr-str remote-row)}
                    ;; the origin's marketplace card — the social signals a
                    ;; mirror snapshots read-only (docs/MARKETPLACE.md § 7)
                    "/api/marketplace"
                    {:status 200 :headers {"Content-Type" "application/json"}
                     :body (json/generate-string
                             [{:name "other.pkg" :rating {:count 1 :avg 1.0} :installs 1}
                              {:name "remote.pkg" :rating {:count 3 :avg 4.7} :installs 12
                               :version-count 2 :latest "1.0.0"}])}
                    {:status 404 :body "no"}))
        stop (http-kit/run-server handler {:port 0})
        port (:local-port (meta stop))
        base (java.net.URLEncoder/encode (str "http://127.0.0.1:" port) "UTF-8")]
    (try
      (let [resp (setup/via-graph *bootstrap* :_pkg-install-panel-handler
                                  {:request-method :post
                                   :query-params {}
                                   :headers {"content-type" "application/x-www-form-urlencoded"}
                                   :body (str "source=" base
                                              "&name=remote.pkg&version=1.0.0")})]
        (is (= 200 (:status resp)))
        (is (re-find #"remote\.pkg" (:body resp))
            "the refreshed panel lists the mirrored package")
        (is (seq (sp/query-entities (storage) :package-version {:name "remote.pkg"}))
            "the version row was mirrored into the local registry")
        (is (seq (sp/query-entities (storage) :fn {:name "remote-greeting"}))
            "the fn materialized under the versioned ns")
        (is (seq (sp/query-entities (storage) :package-install {:package-name "remote.pkg"}))
            "a :package-install pin was written")
        (let [origin (:origin (first (sp/query-entities (storage) :package-version {:name "remote.pkg"})))]
          (is (= (str "http://127.0.0.1:" port) (:url origin)) "the mirror remembers its origin")
          (is (= {:count 3 :avg 4.7} (:rating origin)) "…and the origin's rating, exact-name matched")
          (is (= 12 (:installs origin)))
          (is (string? (:as-of origin)) "stamped with when the snapshot was taken")))
      (finally (stop)))))


(deftest remote-bearer-goes-only-to-the-configured-origin
  ;; `source` in POST /api/packages/install is caller-chosen. The registry
  ;; token used to ride EVERY dial to it — a caller naming their own host
  ;; received this instance's GRAPHDEN_REGISTRY_TOKEN. It now goes only to
  ;; the origin of GRAPHDEN_REGISTRY_URL (the hub token likewise only to
  ;; GRAPHDEN_HUB_URL's).
  (let [seen (atom [])
        row {:name "bearer.pkg" :version "1.0.0" :ns-root "bearerpkg"
             :fns [{:name :bearer-hello :namespace "bearerpkg" :parent :const :args {:value "hi"}}]
             :dependencies [:const] :package-dependencies [] :content-hash "bh"}
        stop (http-kit/run-server
               (fn [req]
                 (swap! seen conj (get-in req [:headers "authorization"]))
                 {:status 200 :headers {"Content-Type" "application/edn"} :body (pr-str row)})
               {:port 0})
        stub (str "http://127.0.0.1:" (:local-port (meta stop)))
        mirror! #(run-named "mirror-remote-package!"
                            {:source stub :pkg-name "bearer.pkg" :version "1.0.0"})]
    (try
      (binding [shared/*secret-env-override* {"GRAPHDEN_REGISTRY_TOKEN" "reg-secret"
                                              "GRAPHDEN_HUB_TOKEN" "hub-secret"}]
        (testing "the auth value itself: same origin only"
          (deploy-config/install! {:registry-url "https://registry.example:443/"
                                   :hub-url "http://hub.example"})
          (is (= "Bearer reg-secret"
                 (run-named "remote-auth-value" {:url "https://REGISTRY.example/api/packages" :endpoint "registry"}))
              "scheme / host case / default port normalise to one origin")
          (is (nil? (run-named "remote-auth-value" {:url "https://evil.example/api/packages" :endpoint "registry"})))
          (is (nil? (run-named "remote-auth-value" {:url "http://registry.example/api/packages" :endpoint "registry"}))
              "another scheme is another origin")
          (is (nil? (run-named "remote-auth-value" {:url "https://registry.example:8443/x" :endpoint "registry"})))
          (is (= "Bearer hub-secret" (run-named "remote-auth-value" {:url "http://hub.example:80/api/export/graph" :endpoint "hub"})))
          (is (nil? (run-named "remote-auth-value" {:url "https://registry.example/x" :endpoint "hub"}))
              "the hub token never goes to the registry, nor the other way round")
          (is (nil? (run-named "remote-auth-value" {:url "https://registry.example/x" :endpoint "other"}))))
        (testing "a caller-chosen source is dialed WITHOUT the registry token"
          (deploy-config/install! {:registry-url "https://registry.example"})
          (is (= "bearer.pkg" (:mirrored (mirror!))))
          (is (= [nil] @seen) "no Authorization header reached the caller's host"))
        (testing "no registry configured → no token anywhere"
          (reset! seen [])
          (deploy-config/install! {})
          (mirror!)
          (is (= [nil] @seen)))
        (testing "the configured registry gets it"
          (reset! seen [])
          (deploy-config/install! {:registry-url stub})
          (mirror!)
          (is (= ["Bearer reg-secret"] @seen))))
      (finally
        (deploy-config/clear!)
        (stop)))))


(deftest panel-update-handler-updates-and-refreshes-panel
  (testing "POST /api/packages/panel-update form-encoded {name, version} repins + refreshes the panel"
    (doseq [v ["1.0.0" "2.0.0"]]
      (sp/create-entity (storage) :package-version
                        {:name "panel.upd" :version v :ns-root "panelupd.demo"
                         :fns [{:name (keyword (str "panelupd-" (subs v 0 1)))
                                :namespace "panelupd.demo" :parent :const :args {:value v}}]
                         :dependencies [:const] :content-hash (str "puh-" v)}))
    ;; install 1.0.0 first — update rejects a package that isn't installed
    (exec/execute-with-named-args (:ctx *bootstrap*)
                                  (get (:all-name->id *bootstrap*) :set-package-pin)
                                  {:pkg-name "panel.upd" :pkg-version "1.0.0"})
    ;; Other tests leave their own pins on the branch; assert on THIS
    ;; package's row.
    (let [row-of (fn [body] (re-find #"(?s)<tr>(?:(?!</tr>).)*panel\.upd(?:(?!</tr>).)*</tr>" body))]
      (testing "a pin behind the registry's highest version carries the update badge, prefilled into the ↑ input"
        (let [row (row-of (:body (setup/via-graph *bootstrap* :_partial-packages-panel-handler
                                                  {:request-method :get})))]
          (is (re-find #"packages-update-available" row))
          (is (re-find #"2\.0\.0 available" row))
          (is (re-find #"name=\"version\"[^>]*value=\"2\.0\.0\"" row)
              "the version input is prefilled with the update target")))
      (let [resp (setup/via-graph *bootstrap* :_pkg-update-panel-handler
                                  {:request-method :post
                                   :body "name=panel.upd&version=2.0.0"
                                   :headers {"content-type" "application/x-www-form-urlencoded"}})]
        (is (= 200 (:status resp)))
        (is (re-find #"data-packages-panel" (:body resp)))
        (is (re-find #"panel\.upd" (:body resp)) "installed table still lists the package")
        (is (re-find #"2\.0\.0" (:body resp)) "at the updated version")
        (is (= "2.0.0" (:version (first (sp/query-entities (storage) :package-install
                                                           {:package-name "panel.upd"}))))
            "the pin was repointed to the target version")
        (is (not (re-find #"packages-update-available" (row-of (:body resp))))
            "at the highest version there is nothing to update to"))))
  (testing "rollback — the same handler accepts an OLDER version symmetrically"
    (let [resp (setup/via-graph *bootstrap* :_pkg-update-panel-handler
                                {:request-method :post
                                 :body "name=panel.upd&version=1.0.0"
                                 :headers {"content-type" "application/x-www-form-urlencoded"}})]
      (is (= 200 (:status resp)))
      (is (= "1.0.0" (:version (first (sp/query-entities (storage) :package-install
                                                         {:package-name "panel.upd"}))))
          "rolled back to the older version"))))


(deftest withdraw-blocks-only-the-pinned-version
  ;; A pin resolves through ITS version row, so the still-installed gate is
  ;; (name, version): a branch that moved on to 1.0.1 must not keep 1.0.0
  ;; unwithdrawable forever (the gate matched by name alone, so an old
  ;; version could never be retired while anyone pinned any version).
  (doseq [v ["1.0.0" "1.0.1"]]
    (sp/create-entity (storage) :package-version
                      {:name "wd.gate" :version v :ns-root "wdgate.demo"
                       :fns [{:name :wd-gate-fn :namespace "wdgate.demo"
                              :parent :const :args {:value v}}]
                       :dependencies [:const] :content-hash (str "wdg-" v)}))
  (exec/execute-with-named-args (:ctx *bootstrap*)
                                (get (:all-name->id *bootstrap*) :set-package-pin)
                                {:pkg-name "wd.gate" :pkg-version "1.0.1"})
  (let [withdraw! (fn [v]
                    (let [resp (setup/via-graph *bootstrap* :withdraw-package-handler
                                                {:request-method :delete
                                                 :query-params {"name" "wd.gate" "version" v}})]
                      [(:status resp) (json/parse-string (:body resp) true)]))]
    (testing "the version nobody pins any more withdraws"
      (let [[status body] (withdraw! "1.0.0")]
        (is (= 200 status))
        (is (true? (:ok body)))
        (is (empty? (sp/query-entities (storage) :package-version {:name "wd.gate" :version "1.0.0"}))
            "the 1.0.0 row is gone")))
    (testing "the pinned version answers 409 still-installed"
      (let [[status body] (withdraw! "1.0.1")]
        (is (= 409 status))
        (is (= "still-installed" (:reason body)))
        (is (seq (sp/query-entities (storage) :package-version {:name "wd.gate" :version "1.0.1"}))
            "the pinned row survives")))))


(deftest panel-publish-handler-exports-and-publishes
  (testing "POST /api/packages/panel-publish form {name, version, ns-root} publishes + refreshes the panel"
    (let [resp (setup/via-graph *bootstrap* :_pkg-publish-panel-handler
                                {:request-method :post
                                 :body "name=paneltest.pub&version=1.0.0&ns-root=app.contact-demo"
                                 :headers {"content-type" "application/x-www-form-urlencoded"}})]
      (is (= 200 (:status resp)))
      (is (re-find #"data-packages-panel" (:body resp)) "wrapped in the panel root for the swap")
      (is (re-find #"packages-fork-ok" (:body resp)))
      (is (re-find #"Published paneltest\.pub@1\.0\.0 — \d+ fn" (:body resp))
          "the notice reads the publish OUTCOME, not the form")
      (let [rows (sp/query-entities (storage) :package-version {:name "paneltest.pub"})]
        (is (= 1 (count rows)) "exactly one :package-version row written")
        ;; The non-empty :fns is the real assertion: export-namespace's
        ;; full-graph read must run in the handler ctx (via :do), NOT lazily
        ;; inside the hiccup render — the latter exports 0 fns.
        (is (seq (:fns (first rows)))
            "the published bundle carries the exported fns (export ran in the :do step, not empty)"))))
  (testing "a refusal is shown as one — the same version again"
    (let [resp (setup/via-graph *bootstrap* :_pkg-publish-panel-handler
                                {:request-method :post
                                 :body "name=paneltest.pub&version=1.0.0&ns-root=app.contact-demo"
                                 :headers {"content-type" "application/x-www-form-urlencoded"}})]
      (is (= 200 (:status resp)))
      (is (re-find #"packages-fork-err" (:body resp)))
      (is (re-find #"paneltest\.pub@1\.0\.0 already exists" (:body resp)))
      (is (= 1 (count (sp/query-entities (storage) :package-version {:name "paneltest.pub"})))
          "still one row — the notice did not re-run the publish")))
  (testing "a breaking change is spelled out"
    ;; 1.1.0 of the same package from a namespace that has none of 1.0.0's
    ;; fns — every public fn-def of 1.0.0 is gone.
    (sp/create-entity (storage) :package-version
                      {:name "paneltest.brk" :version "1.0.0" :ns-root "brk"
                       :fns [{:name :brk-greeting :namespace "brk" :parent :const :args {:value "hi"}}]
                       :dependencies [] :package-dependencies [] :content-hash "brk1"})
    (let [resp (setup/via-graph *bootstrap* :_pkg-publish-panel-handler
                                {:request-method :post
                                 :body "name=paneltest.brk&version=1.1.0&ns-root=app.contact-demo"
                                 :headers {"content-type" "application/x-www-form-urlencoded"}})]
      (is (re-find #"packages-fork-err" (:body resp)))
      (is (re-find #"paneltest\.brk@1\.1\.0 breaks 1\.0\.0: fn-removed brk-greeting" (:body resp)))
      (is (empty? (sp/query-entities (storage) :package-version {:name "paneltest.brk" :version "1.1.0"}))))))
