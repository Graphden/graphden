(ns ^{:cost :heavy} graphden.packages.registry-install-test
  "Package registry — the INSTALL side: materialising a published version
   into its `<ns>@<version>` namespace, install / pin / uninstall (direct and
   through the panel), dependency + version-constraint resolution, update
   rewrites, and fork. The publish side is `registry-publish-test`; shared
   fixture in `registry-fixture`."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.registry-fixture :as rf :refer [*bootstrap* publish-req storage]]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.seams :as ts]
    [graphden.versioning.storage.core :as vcore]))


(use-fixtures :once
  ts/isolated-seams-fixture
  (rf/bootstrap-fixture "registry-install-test"))


(deftest materialize-package-version-into-versioned-ns
  (testing "materialize syncs a published bundle under <ns-root>@<sanitized-version>"
    (let [{:keys [ctx all-name->id] st :storage} *bootstrap*
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
        (let [vns (first (sp/query-entities st :ns {:name "contact-demo@2-0-0"}))]
          (is (some? vns) "leaf versioned ns created")
          (is (seq (sp/query-entities st :fn {:namespace-id (:id vns)}))
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


(deftest rematerialize-after-deleting-the-materialised-fns
  ;; Uninstalling leaves the materialised `<ns>@<version>` copy behind by
  ;; design, and a user can delete those rows like any others. Re-installing
  ;; then re-materialises over ids that are DETERMINISTIC per (namespace,
  ;; name) — the identity rows come back, the versions don't — and the batch
  ;; upsert used to classify that as an update, so `update-entities` threw
  ;; "Entities not found" and every later install of that package 404'd.
  (let [{:keys [ctx all-name->id] st :storage} *bootstrap*
        export-id (get all-name->id :export-namespace)
        publish-id (get all-name->id :publish-package)
        materialize-id (get all-name->id :materialize-package-version)
        bundle (exec/execute-with-named-args ctx export-id {:root "app.contact-demo"})]
    (exec/execute-with-named-args ctx publish-id
                                  {:pkg-name "revive-demo" :pkg-version "1.0.0"
                                   :bundle bundle})
    (exec/execute-with-named-args ctx materialize-id
                                  {:pkg-name "revive-demo" :pkg-version "1.0.0"})
    (let [vns (first (sp/query-entities st :ns {:name "contact-demo@1-0-0"}))
          fns (sp/query-entities st :fn {:namespace-id (:id vns)})]
      (is (seq fns) "precondition: the copy is there")
      ;; The USER-facing delete (what /api/entities/fn does) tombstones the
      ;; version and keeps the identity row — that asymmetry is the whole
      ;; bug: a hard delete would drop the identity and the re-sync would
      ;; simply create it again.
      (binding [vcore/*tombstone-delete?* true]
        (doseq [f fns] (sp/delete-entity st :fn (:id f))))
      (is (empty? (sp/query-entities st :fn {:namespace-id (:id vns)}))
          "precondition: the copy is gone")
      (testing "re-materialising the same version succeeds instead of 404-ing"
        (let [r (exec/execute-with-named-args ctx materialize-id
                                              {:pkg-name "revive-demo" :pkg-version "1.0.0"})]
          (is (true? (:ok r)))
          (is (pos? (:materialized r)))))
      (testing "and the fns are visible again"
        (is (seq (sp/query-entities st :fn {:namespace-id (:id vns)})))))))


(deftest version-qualified-ns-respects-dot-boundaries
  ;; Guards against a sibling ns sharing a non-dotted prefix being mangled:
  ;; a bare `starts-with?` turned `app.foobar` under root `app.foo` into
  ;; `app.foo@1-0-0bar`. The rewrite must fire only for the root ns itself or
  ;; a TRUE descendant (`<root>.`). `version-qualified-ns` is a private helper,
  ;; reached through the registry impls ns the golden bootstrap loaded.
  (let [vqns @(ns-resolve 'graphden.packages.app.registry.impls 'version-qualified-ns)]
    (testing "the root ns and true descendants are rewritten"
      (is (= "app.foo@1-0-0" (vqns "app.foo" "1.0.0" "app.foo")))
      (is (= "app.foo@1-0-0.bar" (vqns "app.foo" "1.0.0" "app.foo.bar"))))
    (testing "a sibling sharing a non-dotted prefix is NOT mangled"
      (is (= "app.foobar.x" (vqns "app.foo" "1.0.0" "app.foobar.x")))
      (is (= "app.foobar" (vqns "app.foo" "1.0.0" "app.foobar"))))
    (testing "an unrelated ns is left as-is; a nil ns passes through"
      (is (= "other.ns" (vqns "app.foo" "1.0.0" "other.ns")))
      (is (nil? (vqns "app.foo" "1.0.0" nil))))))


(deftest already-materialized?-checks-completeness-not-just-identity
  ;; A prior materialize that died mid-way leaves an orphaned :fn identity with
  ;; no body (write-records! commits the :fn batch before the :binding batch,
  ;; non-transactionally). Probing only the identity would report TRUE and make
  ;; install SKIP the (idempotent) re-materialize, freezing the half-written
  ;; version. The guard must verify bodies too. Both helpers are private —
  ;; reached through the registry impls ns the golden bootstrap loaded.
  (let [st (storage)
        impls-ns 'graphden.packages.app.registry.impls
        already-materialized? @(ns-resolve impls-ns 'already-materialized?)
        materialize-fns! @(ns-resolve impls-ns 'materialize-fns!)
        version-qualified-ns @(ns-resolve impls-ns 'version-qualified-ns)
        ns-root "amat.demo"
        version "3.1.0"
        ;; :const carries a :value binding — the body row a mid-way write drops.
        bundle [{:namespace ns-root :name :amat-leaf :parent :const :args {:value 42}}]
        fid (ids/fn-id (version-qualified-ns ns-root version ns-root) :amat-leaf)]
    (testing "before any write → not materialized"
      (is (false? (already-materialized? st ns-root version bundle))))
    (materialize-fns! st ns-root version bundle)
    (testing "after a complete materialize → materialized"
      (is (true? (already-materialized? st ns-root version bundle)))
      (is (seq (sp/query-entities st :binding {:fn-id fid})) "binding body present"))
    (testing "identity kept but bindings dropped (mid-way write) → NOT materialized"
      (doseq [b (sp/query-entities st :binding {:fn-id fid})]
        (sp/delete-entity st :binding (:id b)))
      (is (seq (sp/query-entities st :fn {:id fid})) "identity row still present")
      (is (empty? (sp/query-entities st :binding {:fn-id fid})) "bindings gone")
      (is (false? (already-materialized? st ns-root version bundle))
          "missing body → not complete, so install re-materializes"))))


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
        (is (re-find #"No add-on packages installed" (:body resp))
            "the sole pin is gone → refreshed panel shows the empty-state")
        (is (not (re-find #"acme\.uninstall" (:body resp)))
            "the uninstalled package no longer appears in the table"))
      (is (empty? (installed)) "pin removed from the branch"))))


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


(deftest recursive-install-pulls-package-dependencies
  ;; The whole Approach-A loop: publish records which PACKAGE a bundle's
  ;; cross-package refs come from, and install pulls those packages first.
  (let [{:keys [ctx all-name->id]} *bootstrap*
        install-id (get all-name->id :install-package)
        remove-id  (get all-name->id :remove-package-pin)
        list-id    (get all-name->id :list-installed-packages)
        install! (fn [n v] (exec/execute-with-named-args ctx install-id {:pkg-name n :pkg-version v}))
        pinned?  (fn [n]
                   (boolean (some #(= n (:package-name %))
                                  (exec/execute-with-named-args ctx list-id {}))))]
    ;; --- B: publish + install → materialises `bdep-greeting` at bdeppkg@1-0-0
    (sp/create-entity (storage) :package-version
                      {:name "bdep.pkg" :version "1.0.0" :ns-root "bdeppkg"
                       :fns [{:name :bdep-greeting :namespace "bdeppkg"
                              :parent :const :args {:value "hi from B"}}]
                       :dependencies [:const] :package-dependencies []
                       :content-hash "bdh"})
    (is (true? (:ok (install! "bdep.pkg" "1.0.0"))) "B installs")
    (is (seq (sp/query-entities (storage) :fn {:name "bdep-greeting"})) "B materialised")

    ;; --- A: a fn parented to B's MATERIALISED fn, then publish A's namespace
    (setup/sync-and-invalidate! ctx (storage)
                                [{:name :adep-uses-b :namespace "adeppkg" :parent :bdep-greeting}])
    (let [resp (setup/via-graph *bootstrap* :publish-package-handler
                                (publish-req {:name "adep.pkg" :version "1.0.0"
                                              :ns-root "adeppkg"}))
          body (json/parse-string (:body resp) true)]
      (is (true? (:ok body)) "A publishes")
      (testing "A's :package-dependencies were recorded at publish, pointing at B"
        (let [pdeps (:package-dependencies
                      (first (sp/query-entities (storage) :package-version {:name "adep.pkg"})))]
          (is (= 1 (count pdeps)) "exactly one package dependency")
          (is (= "bdep.pkg" (:name (first pdeps))) "→ package B")
          (is (= "1.0.0" (:version (first pdeps))) "→ B's version"))))

    ;; --- uninstall B (pin gone; materialised fns stay), then install A ---
    (exec/execute-with-named-args ctx remove-id {:pkg-name "bdep.pkg"})
    (is (not (pinned? "bdep.pkg")) "B unpinned before installing A")
    (is (true? (:ok (install! "adep.pkg" "1.0.0"))) "A installs")
    (is (pinned? "adep.pkg") "A is pinned")
    (is (pinned? "bdep.pkg")
        "B was recursively pulled + re-pinned as a side effect of installing A")))


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
    (let [{:keys [ctx all-name->id] st :storage} *bootstrap*
          install-id (get all-name->id :install-package)
          update-id  (get all-name->id :update-package-version)
          fns [{:name :ubase :namespace "updemo" :parent :const :args {:value "b"}}
               {:name :uwrap :namespace "updemo" :parent :map
                :args {:func :ubase :coll {:value []}}}]]
      (doseq [v ["1.0.0" "2.0.0"]]
        (sp/create-entity st :package-version
                          {:name "updemo" :version v :ns-root "updemo"
                           :fns fns :dependencies [:const :map] :content-hash (str "uh-" v)}))
      (exec/execute-with-named-args ctx install-id {:pkg-name "updemo" :pkg-version "1.0.0"})
      (let [old-ubase (ids/fn-id "updemo@1-0-0" :ubase)
            new-ubase (ids/fn-id "updemo@2-0-0" :ubase)
            ;; the package-INTERNAL ref uwrap@1 → ubase@1, created by materialize
            internal (first (sp/query-entities st :binding {:ref-fn-id old-ubase}))
            ;; a USER fn (owner OUTSIDE the package) referencing ubase@1
            user-ns (sp/create-entity st :ns {:name "userland"})
            consumer (sp/create-entity st :fn {:name "up-consumer" :namespace-id (:id user-ns)})
            user-binding (sp/create-entity st :binding
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
          (is (= new-ubase (:ref-fn-id (sp/read-entity st :binding (:id user-binding))))))
        (testing "the package-internal ref still points at v1 (versions never mixed)"
          (is (= old-ubase (:ref-fn-id (sp/read-entity st :binding (:id internal))))))
        (testing "the pin now records v2"
          (is (= "2.0.0" (:version (first (sp/query-entities st :package-install
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


(deftest fork-refuses-a-package-owned-namespace
  ;; Fork syncs at the ORIGINAL namespace on the deterministic (ns, name)
  ;; ids. A namespace the loader synced from disk on this instance owns
  ;; those ids: the fork would land on platform rows, the "copies" would
  ;; stay read-only behind crud.package-guard, and the next boot's sync
  ;; would overwrite them (the written lesson 32 used to promise editable
  ;; copies here). The graph guard refuses the whole fork with the owned
  ;; names — the same predicate the MCP upsert guard consults.
  (let [{:keys [ctx all-name->id]} *bootstrap*
        bundle (exec/execute-with-named-args ctx (get all-name->id :export-namespace)
                                             {:root "app.contact-demo"})
        owned-before (->> (:fns bundle)
                          (filter #(owned/owned-fn-id? (ids/fn-id (:namespace %) (:name %))))
                          (map #(name (:name %)))
                          set)]
    (exec/execute-with-named-args ctx (get all-name->id :publish-package)
                                  {:pkg-name "cd-owned" :pkg-version "1.0.0" :bundle bundle})
    (is (seq owned-before) "precondition: the exported platform namespace IS package-owned")
    (testing "POST /api/packages/fork answers a package-owned envelope and writes nothing"
      (let [resp (setup/via-graph *bootstrap* :fork-package-handler
                                  (publish-req {:name "cd-owned" :version "1.0.0"}))
            body (json/parse-string (:body resp) true)]
        (is (= 200 (:status resp)))
        (is (false? (:ok body)))
        (is (= "package-owned" (:reason body)))
        (is (= "cd-owned" (:name body)))
        (is (= owned-before (set (:owned body)))
            "every package-synced name in the bundle is reported, nothing else")
        (is (nil? (:forked body)) "no fns were synced")))
    (testing "the panel's fork notice names the owned fns and where the fix belongs"
      (let [resp (setup/via-graph *bootstrap* :_pkg-fork-panel-handler
                                  {:request-method :post
                                   :query-params {"name" "cd-owned" "version" "1.0.0"}})]
        (is (= 200 (:status resp)))
        (is (re-find #"packages-fork-err" (:body resp)) "error notice class")
        (is (re-find #"package-owned" (:body resp)) "the reason code")
        (is (re-find #"synced from a package on this instance" (:body resp)))
        (is (re-find #"fns.edn instead" (:body resp)) "where the fix belongs")
        (is (re-find (re-pattern (first owned-before)) (:body resp))
            "an owned fn name is listed")))))


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


(deftest install-package-with-inline-anonymous-fns-executes
  ;; Regression: the sync writes an inline anonymous fn-def (`{:parent …}` bound
  ;; to a slot or listed in a sequence) as its own synthetic `_anon-*` row, but
  ;; `sync-bundle!` used to report only the DECLARED defs' ids, so install's
  ;; delta invalidation never told the registry about the anonymous rows — the
  ;; parent could not compile (`fn-not-found` on execute) and its free args did
  ;; not surface until an unrelated full rebuild. Caught by the starter
  ;; catalogue's `word-count` / `mean` (docs/MARKETPLACE.md § 11).
  (testing "a bundle whose defs bind inline anon fns installs into a working registry"
    (let [{:keys [ctx all-name->id] st :storage} *bootstrap*
          install-id (get all-name->id :install-package)]
      (sp/create-entity st :package-version
                        {:name "anon.demo" :version "1.0.0" :ns-root "anondemo"
                         :fns [{:name :shout :namespace "anondemo"
                                :parent :if
                                :args {:test {:parent :non-blank? :args {:string {:as :text}}}
                                       :then {:parent :str-upper :args {:string {:as :text}}}
                                       :else "nothing"}}
                               {:name :avg :namespace "anondemo"
                                :parent :div
                                :args {:nums [{:parent :add :args {:nums {:as :values}}}
                                              {:parent :count :args {:coll {:as :values}}}]}}]
                         :dependencies [:if :non-blank? :str-upper :div :add :count]
                         :content-hash "anon-h"})
      (is (true? (:ok (exec/execute-with-named-args ctx install-id {:pkg-name "anon.demo" :pkg-version "1.0.0"}))))
      (testing "the slot-bound anon's free arg surfaces and the fn runs"
        (is (= "HI" (exec/execute-with-named-args ctx (ids/fn-id "anondemo@1-0-0" :shout) {:text "hi"})))
        (is (= "nothing" (exec/execute-with-named-args ctx (ids/fn-id "anondemo@1-0-0" :shout) {:text " "}))))
      (testing "the sequence-item anons' free arg surfaces and the fn runs"
        (is (== 5 (exec/execute-with-named-args ctx (ids/fn-id "anondemo@1-0-0" :avg) {:values [2 4 9]})))))))
