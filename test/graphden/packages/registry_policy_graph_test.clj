(ns graphden.packages.registry-policy-graph-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.packages.registry-fixture :as rf :refer [run-named]]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.seams :as seams]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once seams/isolated-seams-fixture
  (rf/bootstrap-fixture "registry-policy-graph-test"))


(deftest external-dependency-sort-key-is-comparable
  (let [sort-keys (mapv #(run-named "_external-dep-package-key" {:row %})
                        [{:name "z.pkg" :version "2.0.0"}
                         {:name "a.pkg" :version "1.0.0"}])]
    (is (every? vector? sort-keys))
    (is (= [["a.pkg" "1.0.0"] ["z.pkg" "2.0.0"]]
           (vec (sort sort-keys))))))


(deftest secret-metadata-graph-preserves-order-and-exhaustion-policy
  (let [definitions [{:name :one :description "Keep metadata"
                      :args {:a {:secret-path "private/a"}
                             :b {:secret-path nil :required true}
                             :c {:value false} :d 17 :e {} :f nil}}
                     {:name :two :args {:token {:secret-path "private/b"}}}
                     {:name :empty :args {}}
                     {:name :without}
                     {:name :null-args :args nil}]]
    (is (= [{:fn :one :arg :a} {:fn :one :arg :b} {:fn :two :arg :token}]
           (vec (run-named "secret-path-args" {:fn-defs definitions}))))
    (is (= [{:name :one :description "Keep metadata"
             :args {:b {:required true} :c {:value false} :d 17 :e {} :f nil}}
            {:name :two} {:name :empty} {:name :without} {:name :null-args :args nil}]
           (vec (run-named "strip-secret-paths" {:fn-defs definitions})))))
  (is (= [] (vec (run-named "secret-path-args" {:fn-defs []}))))
  (is (= [] (run-named "strip-secret-paths" {:fn-defs []})))
  (is (= {:name :one}
         (run-named "get" {:coll (run-named "strip-secret-paths" {:fn-defs [{:name :one :args {}}]})
                           :key 0 :default nil}))))


(deftest public-label-fallback-only-sees-sanitized-candidates
  (doseq [[principal expected] [[nil "anonymous"]
                                [{:display-name " Alice "} "Alice"]
                                [{:display-name "full@example.com" :email "local@example.com"} "local"]
                                [{:user {:display-name "Nested" :primary-email "private@example.com"}
                                  :display-name "Ignored"} "Nested"]
                                [{:email "@domain" :org "org"} ""]
                                [{:email "" :org "org"} "org"]
                                [{:display-name " " :org "org"} "org"]]]
    (binding [tenancy/*current-principal* principal]
      (is (= expected (run-named "current-user-label" {}) (tenancy/current-user-label)))
      (is (not (str/includes? (pr-str (run-named "_request-public-actor-facts" {})) "@"))))))


(deftest owned-branch-default-never-takes-an-owner-from-caller-data
  (doseq [[principal owner policy] [[nil nil nil] [{} nil nil]
                                    [{:user-id "alice" :authenticated? false} "alice" "owner"]
                                    [{:user-id "bob" :authenticated? true} "bob" "owner"]]]
    (binding [tenancy/*current-principal* principal]
      (let [row (run-named "create-owned-branch!" {:branch-name (str "owned-policy-" (random-uuid))})]
        (try
          (is (= owner (:owner-id row)))
          (is (= policy (:write-policy row)))
          (finally (vs/delete-branch! (rf/storage) (:id row))))))))
