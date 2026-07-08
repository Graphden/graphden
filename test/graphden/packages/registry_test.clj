(ns graphden.packages.registry-test
  "Tests for the package registry — the `:package-version` entity that
   stores immutable published bundles."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
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
  (testing "installing a version writes its fns + namespace into the graph"
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
      (is (= 1 (:installed body)))
      (is (seq (sp/query-entities (storage) :fn {:name "installed-greeting"}))
          "the novel fn is now in the graph")
      (is (seq (sp/query-entities (storage) :ns {:name "installed"}))
          "the installed namespace subtree was created")))
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
