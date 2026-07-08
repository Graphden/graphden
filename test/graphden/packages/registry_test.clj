(ns graphden.packages.registry-test
  "Tests for the package registry — the `:package-version` entity that
   stores immutable published bundles."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (t))))


(defn- storage
  []
  (:storage *bootstrap*))


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


(deftest materialize-package-version-into-versioned-ns
  (testing "materialize syncs a published bundle under <ns-root>@<sanitized-version>"
    (let [{:keys [ctx storage all-name->id]} *bootstrap*
          export-id (get all-name->id :export-namespace)
          publish-id (get all-name->id :publish-package)
          materialize-id (get all-name->id :materialize-package-version)
          bundle (exec/execute-with-named-args ctx export-id {:root "app.contact-demo"})]
      (exec/execute-with-named-args ctx publish-id
                                    {:pkg-name "app.contact-demo" :pkg-version "2.0.0" :bundle bundle})
      (testing "materialize succeeds under the version-qualified ns (resolves :extras/:label on external :submit-button)"
        (let [r (exec/execute-with-named-args ctx materialize-id
                                              {:pkg-name "app.contact-demo" :pkg-version "2.0.0"})]
          (is (true? (:ok r)))
          (is (= "app.contact-demo@2-0-0" (:namespace r)))
          (is (pos? (:materialized r)))))
      (testing "the bundle's fns exist under the versioned namespace"
        (let [vns (first (sp/query-entities storage :ns {:name "contact-demo@2-0-0"}))]
          (is (some? vns) "leaf versioned ns created")
          (is (seq (sp/query-entities storage :fn {:namespace-id (:id vns)}))
              "bundle fns materialized under it")))
      (testing "materialize is idempotent (re-run stays ok)"
        (let [r (exec/execute-with-named-args ctx materialize-id
                                              {:pkg-name "app.contact-demo" :pkg-version "2.0.0"})]
          (is (true? (:ok r)))))
      (testing "an unknown version rejects"
        (let [r (exec/execute-with-named-args ctx materialize-id
                                              {:pkg-name "app.contact-demo" :pkg-version "9.9.9"})]
          (is (false? (:ok r)))
          (is (= "not-found" (:reason r))))))))


(deftest package-install-entity-roundtrips
  (testing "a :package-install pin stores + restores its fields"
    (let [branch-id (random-uuid)
          row (sp/create-entity (storage) :package-install
                                {:branch-id branch-id
                                 :package-name "acme.demo"
                                 :version "1.2.0"
                                 :org-id "public"})
          back (sp/read-entity (storage) :package-install (:id row))]
      (is (= branch-id (:branch-id back)))
      (is (= "acme.demo" (:package-name back)))
      (is (= "1.2.0" (:version back)))
      (is (= "public" (:org-id back)))))
  (testing "query-entities finds pins by branch + package"
    (let [branch-id (random-uuid)]
      (sp/create-entity (storage) :package-install
                        {:branch-id branch-id :package-name "acme.p" :version "0.1.0"})
      (let [rows (sp/query-entities (storage) :package-install
                                    {:branch-id branch-id :package-name "acme.p"})]
        (is (= ["0.1.0"] (map :version rows)))))))


(deftest package-pin-lifecycle-through-executor
  (testing "set → list → update → remove pins drive through the base-fns"
    (let [{:keys [ctx all-name->id]} *bootstrap*
          set-id    (get all-name->id :set-package-pin)
          list-id   (get all-name->id :list-installed-packages)
          remove-id (get all-name->id :remove-package-pin)
          installed #(->> (exec/execute-with-named-args ctx list-id {})
                          (filter (fn [p] (= "acme.pinned" (:package-name p)))))]
      (testing "set creates a pin visible in the installed list"
        (let [r (exec/execute-with-named-args ctx set-id
                                              {:pkg-name "acme.pinned" :pkg-version "1.0.0"})]
          (is (true? (:ok r)))
          (is (= "1.0.0" (:version r))))
        (is (= ["1.0.0"] (map :version (installed)))))
      (testing "set again UPDATES in place — one pin per (branch, package)"
        (exec/execute-with-named-args ctx set-id
                                      {:pkg-name "acme.pinned" :pkg-version "1.1.0"})
        (is (= ["1.1.0"] (map :version (installed)))
            "still a single pin, at the new version"))
      (testing "remove drops the pin (idempotent)"
        (let [r (exec/execute-with-named-args ctx remove-id {:pkg-name "acme.pinned"})]
          (is (true? (:ok r)))
          (is (true? (:removed r))))
        (is (empty? (installed)))
        (let [r2 (exec/execute-with-named-args ctx remove-id {:pkg-name "acme.pinned"})]
          (is (false? (:removed r2)) "second remove is a no-op"))))))


(deftest uninstall-handler-drops-pin-and-refreshes-panel
  (testing "DELETE /api/packages/uninstall?name=… unpins + returns the refreshed panel HTML"
    (let [{:keys [ctx all-name->id]} *bootstrap*
          set-id (get all-name->id :set-package-pin)
          list-id (get all-name->id :list-installed-packages)
          remove-id (get all-name->id :remove-package-pin)
          installed #(->> (exec/execute-with-named-args ctx list-id {})
                          (filter (fn [p] (= "acme.uninstall" (:package-name p)))))]
      ;; Clean ALL pins first — this suite shares one `:once` DB, so sibling
      ;; tests (panel-install, install-package, …) leave pins that would keep the
      ;; panel non-empty. Removing them makes the empty-state assertion below
      ;; deterministic regardless of test order.
      (doseq [p (exec/execute-with-named-args ctx list-id {})]
        (exec/execute-with-named-args ctx remove-id {:pkg-name (:package-name p)}))
      (exec/execute-with-named-args ctx set-id
                                    {:pkg-name "acme.uninstall" :pkg-version "1.0.0"})
      (is (seq (installed)) "pin present before uninstall")
      (let [resp (setup/via-graph *bootstrap* :_uninstall-handler
                                  {:request-method :delete
                                   :query-params {"name" "acme.uninstall"}
                                   :headers {}})]
        (is (= 200 (:status resp)))
        (is (re-find #"data-packages-panel" (:body resp))
            "response is the panel root, ready for the HTMX outerHTML swap")
        (is (re-find #"No packages installed" (:body resp))
            "the sole pin is gone → refreshed panel shows the empty-state")
        (is (not (re-find #"acme\.uninstall" (:body resp)))
            "the uninstalled package no longer appears in the table"))
      (is (empty? (installed)) "pin removed from the branch"))))


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


(defn- publish-req
  [body]
  {:request-method :post
   :body (json/generate-string body)
   :headers {"content-type" "application/json"}})


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
      (is (some #{"html-page-handler"} (:dependencies body))
          "external dep surfaced (keyword → string over JSON)")
      (testing "the row persisted with the exported subtree"
        (let [rows (sp/query-entities (storage) :package-version {:name "demo.pkg"})]
          (is (= 1 (count rows)))
          (is (= "app.contact-demo" (:ns-root (first rows))))
          (is (seq (:fns (first rows))))))))
  (testing "re-publishing the same (name, version) is rejected — immutability"
    (let [resp (setup/via-graph *bootstrap* :publish-package-handler
                                (publish-req {:name "demo.pkg" :version "1.0.0"
                                              :ns-root "app.contact-demo"}))
          body (json/parse-string (:body resp) true)]
      (is (false? (:ok body)))
      (is (= "version-exists" (:reason body)))
      (is (= 1 (count (sp/query-entities (storage) :package-version {:name "demo.pkg"})))
          "no duplicate row written"))))


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


(deftest install-package-syncs-or-rejects
  (testing "installing a version materializes it under a versioned ns + writes a pin (reference model)"
    (sp/create-entity (storage) :package-version
                      {:name "inst.demo" :version "1.0.0" :ns-root "installed.demo"
                       :fns [{:name :installed-greeting :namespace "installed.demo"
                              :parent :const :args {:value "hello from install"}}]
                       :dependencies [:const] :content-hash "ih"})
    (let [resp (setup/via-graph *bootstrap* :install-package-handler
                                (publish-req {:name "inst.demo" :version "1.0.0"}))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (= "installed.demo@1-0-0" (:namespace body))
          "materialized under the version-qualified namespace")
      (is (seq (sp/query-entities (storage) :fn {:name "installed-greeting"}))
          "the fn is materialized (found by name under the versioned ns)")
      (is (seq (sp/query-entities (storage) :package-install {:package-name "inst.demo"}))
          "a :package-install pin was written — the fn rows are referenced, not copied")))
  (testing "install rejects when a declared dependency is absent"
    (sp/create-entity (storage) :package-version
                      {:name "inst.bad" :version "1.0.0" :ns-root "installed.bad"
                       :fns [] :dependencies [:no-such-fn-xyz] :content-hash "bh"})
    (let [resp (setup/via-graph *bootstrap* :install-package-handler
                                (publish-req {:name "inst.bad" :version "1.0.0"}))
          body (json/parse-string (:body resp) true)]
      (is (false? (:ok body)))
      (is (= "missing-dependencies" (:reason body)))
      (is (= ["no-such-fn-xyz"] (:missing body)))))
  (testing "installing an unknown version is not-found"
    (let [resp (setup/via-graph *bootstrap* :install-package-handler
                                (publish-req {:name "inst.demo" :version "9.9.9"}))
          body (json/parse-string (:body resp) true)]
      (is (false? (:ok body)))
      (is (= "not-found" (:reason body))))))


(deftest panel-install-handler-installs-and-refreshes-panel
  (testing "POST /api/packages/panel-install?name=&version= installs + returns the refreshed panel HTML"
    (sp/create-entity (storage) :package-version
                      {:name "panel.inst" :version "2.0.0" :ns-root "panelinst.demo"
                       :fns [{:name :panelinst-greeting :namespace "panelinst.demo"
                              :parent :const :args {:value "hi from panel install"}}]
                       :dependencies [:const] :content-hash "pih"})
    (let [resp (setup/via-graph *bootstrap* :_pkg-install-panel-handler
                                {:request-method :post
                                 :query-params {"name" "panel.inst" "version" "2.0.0"}
                                 :headers {}})]
      (is (= 200 (:status resp)))
      (is (re-find #"data-packages-panel" (:body resp))
          "response is the panel root for the HTMX outerHTML swap")
      (is (re-find #"panel\.inst" (:body resp))
          "the installed table now lists the just-installed package")
      (is (re-find #"packages-uninstall" (:body resp))
          "installed row carries the × uninstall control")
      (is (seq (sp/query-entities (storage) :package-install {:package-name "panel.inst"}))
          "a :package-install pin was written on the branch"))))


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
          "the pin was repointed to the target version")))
  (testing "rollback — the same handler accepts an OLDER version symmetrically"
    (let [resp (setup/via-graph *bootstrap* :_pkg-update-panel-handler
                                {:request-method :post
                                 :body "name=panel.upd&version=1.0.0"
                                 :headers {"content-type" "application/x-www-form-urlencoded"}})]
      (is (= 200 (:status resp)))
      (is (= "1.0.0" (:version (first (sp/query-entities (storage) :package-install
                                                         {:package-name "panel.upd"}))))
          "rolled back to the older version"))))


(deftest panel-fork-handler-copies-fns-and-notes-result
  ;; Distinct package/fn/ns names from fork-package-copies-into-original-ns —
  ;; both share the one `:once` bootstrap DB, so a reused (name, version) would
  ;; make resolve-version pick the other test's row and fork the wrong ns-root.
  (testing "POST /api/packages/panel-fork?name=&version= copies the fns + returns a success notice"
    (sp/create-entity (storage) :package-version
                      {:name "pfork.demo" :version "1.0.0" :ns-root "pforkdemo.pkg"
                       :fns [{:name :pfork-greeting :namespace "pforkdemo.pkg"
                              :parent :const :args {:value "forked!"}}]
                       :dependencies [:const] :content-hash "pfh"})
    (let [resp (setup/via-graph *bootstrap* :_pkg-fork-panel-handler
                                {:request-method :post
                                 :query-params {"name" "pfork.demo" "version" "1.0.0"}
                                 :headers {}})]
      (is (= 200 (:status resp)))
      (is (re-find #"packages-fork-ok" (:body resp)) "the success notice is rendered")
      (is (re-find #"data-packages-panel" (:body resp)) "wrapped in the panel root for the swap")
      (is (seq (sp/query-entities (storage) :fn {:name "pfork-greeting"}))
          "the fn was COPIED into the graph at its original namespace (no pin)")
      (is (empty? (sp/query-entities (storage) :package-install {:package-name "pfork.demo"}))
          "fork writes no pin — it is a copy, not a reference install")))
  (testing "forking an unknown version renders the error notice"
    (let [resp (setup/via-graph *bootstrap* :_pkg-fork-panel-handler
                                {:request-method :post
                                 :query-params {"name" "pfork.demo" "version" "9.9.9"}
                                 :headers {}})]
      (is (= 200 (:status resp)))
      (is (re-find #"packages-fork-err" (:body resp)) "error notice class")
      (is (re-find #"not-found" (:body resp)) "surfaces the fork failure reason"))))


(deftest install-resolves-version-constraints
  (testing "install picks the highest published version matching a constraint / latest / exact"
    (doseq [[v nm] [["1.0.0" :vg-a] ["1.2.0" :vg-b] ["2.0.0" :vg-c]]]
      (sp/create-entity (storage) :package-version
                        {:name "ver.demo" :version v :ns-root "verdemo"
                         :fns [{:name nm :namespace "verdemo" :parent :const :args {:value v}}]
                         :dependencies [:const] :content-hash (str "h-" v)}))
    (letfn [(install
              [spec]
              (-> (setup/via-graph *bootstrap* :install-package-handler
                                   (publish-req {:name "ver.demo" :version spec}))
                  :body (json/parse-string true)))]
      (testing ">= constraint resolves to the highest match"
        (is (= "2.0.0" (:version (install ">=1.0.0")))))
      (testing "~> pessimistic constraint stays within the minor family"
        (is (= "1.2.0" (:version (install "~>1.0")))))
      (testing "latest resolves to the highest overall"
        (is (= "2.0.0" (:version (install "latest")))))
      (testing "an exact version resolves to itself"
        (is (= "1.0.0" (:version (install "1.0.0")))))
      (testing "an unsatisfiable constraint is not-found"
        (let [b (install ">=9.0.0")]
          (is (false? (:ok b)))
          (is (= "not-found" (:reason b))))))))


(deftest update-package-version-rewrites-project-refs-not-package-internal
  (testing "update repoints the project's OWN refs old→new, leaving package-internal refs alone"
    (let [{:keys [ctx storage all-name->id]} *bootstrap*
          install-id (get all-name->id :install-package)
          update-id  (get all-name->id :update-package-version)
          fns [{:name :ubase :namespace "updemo" :parent :const :args {:value "b"}}
               {:name :uwrap :namespace "updemo" :parent :map
                :args {:func :ubase :coll {:value []}}}]]
      (doseq [v ["1.0.0" "2.0.0"]]
        (sp/create-entity storage :package-version
                          {:name "updemo" :version v :ns-root "updemo"
                           :fns fns :dependencies [:const :map] :content-hash (str "uh-" v)}))
      (exec/execute-with-named-args ctx install-id {:pkg-name "updemo" :pkg-version "1.0.0"})
      (let [old-ubase (ids/fn-id "updemo@1-0-0" :ubase)
            new-ubase (ids/fn-id "updemo@2-0-0" :ubase)
            ;; the package-INTERNAL ref uwrap@1 → ubase@1, created by materialize
            internal (first (sp/query-entities storage :binding {:ref-fn-id old-ubase}))
            ;; a USER fn (owner OUTSIDE the package) referencing ubase@1
            user-ns (sp/create-entity storage :ns {:name "userland"})
            consumer (sp/create-entity storage :fn {:name "up-consumer" :namespace-id (:id user-ns)})
            user-binding (sp/create-entity storage :binding
                                           {:fn-id (:id consumer) :slot-id (:slot-id internal)
                                            :ref-fn-id old-ubase})]
        (is (some? internal) "materialize created the package-internal uwrap→ubase ref")
        (testing "update to v2 rewrites exactly the one user ref"
          (let [r (exec/execute-with-named-args ctx update-id {:pkg-name "updemo" :pkg-version "2.0.0"})]
            (is (true? (:ok r)))
            (is (= "1.0.0" (:from r)))
            (is (= "2.0.0" (:to r)))
            (is (= 1 (:rewritten-refs r)) "the user ref only — NOT the package-internal one")))
        (testing "the user's ref now points at v2"
          (is (= new-ubase (:ref-fn-id (sp/read-entity storage :binding (:id user-binding))))))
        (testing "the package-internal ref still points at v1 (versions never mixed)"
          (is (= old-ubase (:ref-fn-id (sp/read-entity storage :binding (:id internal))))))
        (testing "the pin now records v2"
          (is (= "2.0.0" (:version (first (sp/query-entities storage :package-install
                                                             {:package-name "updemo"}))))))))))


(deftest fork-package-copies-into-original-ns
  (testing "forking a version copies its fns at their ORIGINAL ns and does NOT pin (copy-on-write)"
    (sp/create-entity (storage) :package-version
                      {:name "fork.demo" :version "1.0.0" :ns-root "forked.demo"
                       :fns [{:name :forked-greeting :namespace "forked.demo"
                              :parent :const :args {:value "hello from fork"}}]
                       :dependencies [:const] :content-hash "fh"})
    (let [resp (setup/via-graph *bootstrap* :fork-package-handler
                                (publish-req {:name "fork.demo" :version "1.0.0"}))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (= 1 (:forked body)))
      (is (seq (sp/query-entities (storage) :fn {:name "forked-greeting"}))
          "the fn is copied into the graph")
      (is (seq (sp/query-entities (storage) :ns {:name "forked"}))
          "copied at its ORIGINAL namespace (not a versioned one)")
      (is (empty? (sp/query-entities (storage) :package-install {:package-name "fork.demo"}))
          "fork does NOT write a pin — it is a copy, not a reference install"))))


(deftest install-resolves-ref-based-free-arg-slot-on-external-fn
  ;; Regression for the faithful-reconstruction fix (b32c0be8): a bundle that
  ;; binds :extras — a ref-based free-arg slot owned by an anon fn referenced
  ;; deep inside the EXTERNAL composed :submit-button — used to throw
  ;; :packages/orphan-slot-binding on install, because the incremental sync's
  ;; storage reconstruction dropped composed fns' :args. It now resolves via
  ;; the exporter, the same fn-def path boot uses.
  (testing "install of a bundle binding :extras/:label on external :submit-button"
    (sp/create-entity (storage) :package-version
                      {:name "btn.demo" :version "1.0.0" :ns-root "btndemo"
                       :fns [{:name :my-submit :namespace "btndemo"
                              :parent :submit-button
                              :args {:label {:value "Send"}
                                     :extras {:value {:class "cta"}}}}]
                       :dependencies [:submit-button] :content-hash "bh"})
    (let [resp (setup/via-graph *bootstrap* :install-package-handler
                                (publish-req {:name "btn.demo" :version "1.0.0"}))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (true? (:ok body)))
      (is (seq (sp/query-entities (storage) :fn {:name "my-submit"}))
          "the fn binding the ref-based free-arg slot synced (would throw orphan pre-fix)"))))
