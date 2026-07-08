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
