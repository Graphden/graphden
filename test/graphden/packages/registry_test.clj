(ns graphden.packages.registry-test
  "Tests for the package registry — the `:package-version` entity that
   stores immutable published bundles."
  (:require
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
