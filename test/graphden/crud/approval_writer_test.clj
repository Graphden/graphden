(ns ^:integration graphden.crud.approval-writer-test
  "Real PostgreSQL approval authorization and content stamps after lock wait."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.context :as context]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.loader :as loader]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.seams :as seams]
    [graphden.versioning.merge.core :as policy]
    [graphden.versioning.storage.core :as versioned]
    [next.jdbc :as jdbc]))


(def ^:dynamic *approve* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  seams/isolated-seams-fixture
  (fn [tests]
    (tc/install-org-cap-fn! nil)
    (let [packages (loader/load-packages ["app"])]
      (binding [*approve* (get-in packages [:base-fn-defs :approve-proposal! :impl])]
        (tests)))))


(defn- with-proposal
  [f]
  (let [storage (setup/create-versioned-test-storage 6)
        source (versioned/create-branch! storage (str "approval-" (random-uuid)))
        ctx (context/create-context {:storage storage :base-fns {}})]
    (try
      (tc/with-org "acme"
                   (binding [tc/*current-principal* {:user-id "alice" :user "Alice"}]
                     (f storage source #(try
                                          (*approve* {:source-branch-id (:id source)} ctx)
                                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (finally (sp/close storage)))))


(defn- wait-for-writer
  [storage]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [n (-> (jdbc/execute-one!
                    (tx/datasource storage)
                    ["SELECT count(*) AS n FROM pg_locks l
                       JOIN pg_database d ON d.oid=l.database
                       WHERE l.locktype='advisory' AND NOT l.granted
                         AND d.datname=current_database()"])
                  vals first long)]
        (cond
          (pos? n) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 10) (recur)))))))


(defn- approve-after-competing-write
  [storage change! approve!]
  (let [entered (promise)
        release (promise)
        holding (future
                  (writer/call-with-write
                    (versioned/unwrap storage) :graph
                    (fn [bound]
                      (change! bound)
                      (deliver entered true)
                      (when-not (true? (deref release 15000 false))
                        (throw (ex-info "Approval test timed out" {}))))))]
    (try
      (is (true? (deref entered 10000 false)))
      (let [approval (future (approve!))]
        (try
          (is (wait-for-writer storage))
          (deliver release true)
          (deref approval 15000 :timeout)
          (finally (deliver release true) (future-cancel approval))))
      (finally (deliver release true) (deref holding 15000 nil) (future-cancel holding)))))


(deftest approval-rechecks-target-policy-after-waiting
  (with-proposal
    (fn [storage source approve!]
      (let [result (approve-after-competing-write
                     storage
                     #(sp/update-entity % :branch (:base-branch-id source)
                                        {:write-policy "owner" :owner-id "bob"})
                     approve!)]
        (is (= :authz/forbidden result))
        (is (empty? (sp/query-entities storage :branch-approval {:source-branch-id (:id source)})))
        (sp/update-entity storage :branch (:base-branch-id source) {:write-policy nil})
        (is (= "alice" (approve!)))
        (is (= 1 (count (sp/query-entities storage :branch-approval {:source-branch-id (:id source)}))))))))


(deftest approval-stamps-the-content-committed-before-its-writer-lock
  (with-proposal
    (fn [storage source approve!]
      (let [before (policy/branch-content-stamp (versioned/unwrap storage) (:id source))
            result (approve-after-competing-write
                     storage
                     #(sp/create-entity (assoc storage :base-storage % :branch-id (:id source))
                                        :fn {:name (str "new-proposal-content-" (random-uuid))})
                     approve!)
            after (policy/branch-content-stamp (versioned/unwrap storage) (:id source))
            row (first (sp/query-entities storage :branch-approval {:source-branch-id (:id source)}))]
        (is (= "alice" result))
        (is (not= before after) "The competing transaction changed actual proposal versions")
        (is (= after (:content-stamp row)))
        (is (= (:base-branch-id source) (:target-branch-id row)))))))
