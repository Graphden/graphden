(ns ^:integration graphden.packages.app.services-list-graph-test
  "Service metadata loads only referenced functions while retaining the
   existing current-branch resolution and service-row visibility boundary."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.graph-harness :as gh :refer [*graph*]]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(deftest services-bound-identity-and-version-loads-on-each-branch
  (let [{:keys [storage ctx all-name->id]} *graph*
        call (fn [s name]
               (setup/exec-with-storage (assoc ctx :storage s) s
                                        (get all-name->id name) {}))
        list-services (fn [s]
                        (let [response (call s :list-services-handler)]
                          (is (= 200 (:status response)))
                          (:services (json/parse-string (:body response) true))))]
    ;; This namespace owns its golden clone; remove any seed service so the
    ;; empty predicate is exercised independently of the bootstrap contents.
    (doseq [service (sp/query-entities storage :service {})]
      (sp/delete-entity storage :service (:id service)))
    (testing "empty service IDs match nothing, never the whole function table"
      (is (empty? (call storage :_list-services-fn-identities)))
      (is (empty? (call storage :_list-services-fn-versions)))
      (is (empty? (list-services storage))))
    (let [namespace-id (:namespace-id (sp/read-entity storage :fn (get all-name->id :add)))
          target (sp/create-entity storage :fn {:name (gh/uniq "service-root")
                                                :namespace-id namespace-id})
          _unrelated (sp/create-entity storage :fn {:name (gh/uniq "not-a-service")
                                                    :namespace-id namespace-id})
          branch (vs/create-branch! storage (gh/uniq "service-branch"))
          child (vs/switch-branch storage (:id branch))
          child-name (gh/uniq "service-child")
          child-only (sp/create-entity child :fn {:name (gh/uniq "child-only")
                                                  :namespace-id namespace-id})
          service-data {:enabled? false :restart-policy :never :cardinality :singleton}
          root-service (sp/create-entity storage :service
                                         (assoc service-data :fn-id (:id target)
                                                :branch-id (:branch-id storage)))
          child-service (sp/create-entity storage :service
                                          (assoc service-data :fn-id (:id target)
                                                 :branch-id (:id branch)))
          invisible-service (sp/create-entity storage :service
                                              (assoc service-data :fn-id (:id child-only)
                                                     :branch-id (:id branch)))
          expected-ids #{(:id target) (:id child-only)}]
      (sp/update-entity child :fn (:id target) {:name child-name})
      (testing "duplicate service functions share one bounded identity set"
        (is (= expected-ids (set (call storage :_list-services-fn-ids))))
        (is (= 2 (count (call storage :_list-services-fn-ids))))
        (is (= expected-ids (set (map :id (call storage :_list-services-fn-identities))))))
      (testing "version selection excludes unrelated functions and sibling branches"
        (let [versions (call storage :_list-services-fn-versions)]
          (is (seq versions))
          (is (= #{(:id target)} (set (map :fn-id versions))))
          (is (= #{(:branch-id storage)} (set (map :branch-id versions)))))
        (let [versions (call child :_list-services-fn-versions)]
          (is (= expected-ids (set (map :fn-id versions))))
          (is (= #{(:branch-id storage) (:id branch)} (set (map :branch-id versions))))))
      (testing "current-branch metadata preserves inherited names and branch overrides"
        (doseq [[s expected-name] [[storage (:name target)] [child child-name]]]
          (let [rows (into {} (map (juxt :id identity)) (list-services s))]
            (doseq [service [root-service child-service]]
              (let [row (get rows (str (:id service)))]
                (is (= expected-name (:fn-name row)))
                (is (= (str namespace-id) (:namespace-id row)))
                (is (= (str (:branch-id service)) (:branch-id row)))))
            (is (= (when (= s child) (:name child-only))
                   (:fn-name (get rows (str (:id invisible-service))))))))))))
