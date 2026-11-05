(ns ^:integration ^:serial graphden.crud.search-policy-graph-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities.list :as listing]
    [graphden.executor.test-setup :as setup]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.graph-harness :as gh]))


(use-fixtures :once (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(defn- environment
  [rows namespaces annotated]
  {:base {:fns rows} :namespaces namespaces
   :rev-index (delay {}) :rich-snapshot (delay {}) :diag-counts (delay {})
   :role-of (fn [row] (swap! annotated conj (:id row)) (assoc row :role :composed))})


(defn- execute
  [env name args]
  (with-redefs-fn {(ns-resolve 'graphden.crud.entities.list 'env) (constantly env)}
    #(gh/exec-name name args)))


(deftest search-ranks-before-cap-and-annotates-only-selected-rows
  (let [exact {:id (random-uuid) :name "target"}
        rows (conj (mapv (fn [n] {:id (random-uuid) :name (str "target-helper-" n)})
                         (range 9999)) exact)
        annotated (atom [])
        env (environment rows (delay []) annotated)]
    (binding [listing/*default-search-limit* 1]
      (let [out (execute env :graph-search {:q " TaRgEt " :descriptions? false})]
        (is (= [(:id exact)] (mapv :id (:fns out))))
        (is (true? (:truncated? out)))
        (is (= [(:id exact)] @annotated))))))


(deftest blank-search-and-root-namespace-do-not-read-namespaces
  (let [root {:id (random-uuid) :name "root"}
        annotated (atom [])
        namespaces (delay (throw (ex-info "Unexpected namespace read" {})))
        env (environment (into [root {:id (random-uuid) :name nil}]
                               (repeatedly 9998 #(hash-map :id (random-uuid) :name "nested"
                                                           :namespace-id (random-uuid))))
                         namespaces annotated)]
    (doseq [q [nil "" "  "]]
      (is (= {:fns [] :truncated? false}
             (execute env :graph-search {:q q :descriptions? true}))))
    (is (empty? @annotated))
    (is (= [(:id root)] (mapv :id (:fns (execute env :graph-namespace-fns {:namespace-id nil})))))
    (is (= [(:id root)] @annotated))
    (is (not (realized? namespaces)))))


(deftest search-preserves-four-tiers-slash-and-acl
  (let [nid (random-uuid)
        ids (repeatedly 5 random-uuid)
        [exact bare qualified description denied] ids
        rows [{:id description :name "concept" :description "TARGET concept"}
              {:id qualified :name "other" :namespace-id nid}
              {:id bare :name "target-helper"} {:id exact :name "target"}
              {:id denied :name "target"}]
        annotated (atom [])
        env (environment rows (delay [{:id nid :name "targetspace"}]) annotated)]
    (with-redefs [tenancy/apply-graph-read-filter
                  #(update % :fns (fn [xs] (filterv (fn [x] (not= denied (:id x))) xs)))]
      (is (= [exact bare qualified description]
             (mapv :id (:fns (execute env :graph-search {:q "target" :descriptions? true})))))
      (is (= [exact bare qualified]
             (mapv :id (:fns (execute env :graph-search {:q "target" :descriptions? false})))))
      (is (= [qualified]
             (mapv :id (:fns (execute env :graph-search {:q "TARGETSPACE/other" :descriptions? false})))))
      (is (not-any? #{denied} @annotated)))))
