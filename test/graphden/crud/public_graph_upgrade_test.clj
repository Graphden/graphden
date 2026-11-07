(ns ^:integration graphden.crud.public-graph-upgrade-test
  "Saved user renames must survive a public base function becoming a graph."
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))
                                          ["core" "web" "app" "registry"]))


(def ^:private legacy-semver-rank
  {:namespace "app.marketplace"
   :name :semver-rank
   :args {:version {:type :nullable-text}}
   :return-type :int})


(defn- renamed-rank
  [name]
  {:name name
   :parent :app.marketplace/semver-rank
   :args {:version {:as :input-version}}})


(deftest saved-pure-rename-survives-base-to-graph-upgrade
  (let [storage harness/*storage*
        ctx harness/*context*
        root-id (ids/fn-id "app.marketplace" :semver-rank)
        old-version-id (ids/slot-id root-id :version)
        saved-id (ids/fn-id nil :saved-rank)
        fresh-id (ids/fn-id nil :fresh-rank)
        current (some #(when (= :semver-rank (:name %))
                         (assoc % :namespace "app.marketplace"))
                      (:fns (edn/read-string (slurp "resources/packages/registry/marketplace/fns.edn"))))]
    (harness/sync! [legacy-semver-rank (renamed-rank :saved-rank)])
    (let [saved-binding (first (sp/query-entities storage :binding {:fn-id saved-id}))]
      (testing "the legacy rename is valid before upgrade"
        (is (= old-version-id (:slot-id saved-binding)))
        (is (nil? (:source-slot-id (sp/read-entity storage :slot old-version-id))))
        (is (contains? (cr/free-arg-accepted-names ctx saved-id) :input-version)))
      (harness/sync! [current])
      (harness/sync! [(renamed-rank :fresh-rank)])
      (testing "the persisted user binding keeps its identity"
        (is (= old-version-id
               (:slot-id (sp/read-entity storage :binding (:id saved-binding))))))
      (testing "freshly authored rename works against the graph"
        (is (= 1002003 (exec/execute-with-named-args ctx fresh-id
                                                     {:input-version "1.2.3"}))))
      (testing "previously saved rename still works after upgrade"
        (is (= 1002003 (exec/execute-with-named-args ctx saved-id
                                                     {:input-version "1.2.3"})))))))


(deftest saved-direct-parent-rename-survives-base-to-graph-upgrade
  (let [ctx harness/*context*
        legacy {:namespace "app.branches"
                :name :set-branch-policy!
                :args {:branch-id {:type :uuid}
                       :write-policy {:type :nullable-text}}
                :return-type :nullable-text}
        child (fn [name]
                {:name name :parent :app.branches/set-branch-policy!
                 :args {:branch-id {:as :target-branch}}})
        saved-id (ids/fn-id nil :saved-branch-policy)
        fresh-id (ids/fn-id nil :fresh-branch-policy)
        current (some #(when (= :set-branch-policy! (:name %))
                         (assoc % :namespace "app.branches"))
                      (:fns (edn/read-string (slurp "resources/packages/app/branches/fns.edn"))))]
    (harness/sync! [legacy (child :saved-branch-policy)])
    (testing "old direct-parent input is renameable"
      (is (contains? (cr/free-arg-accepted-names ctx saved-id) :target-branch)))
    (harness/sync! [current])
    (harness/sync! [(child :fresh-branch-policy)])
    (testing "new descendant can rename the inherited writer input"
      (is (contains? (cr/free-arg-accepted-names ctx fresh-id) :target-branch)))
    (testing "saved descendant retains the same accepted name"
      (is (contains? (cr/free-arg-accepted-names ctx saved-id) :target-branch)))))


(deftest saved-direct-parent-rename-delivers-runtime-value
  (let [ctx harness/*context*
        storage harness/*storage*
        base {:namespace "test.contract" :name :public-join
              :args {:coll {:type [:list :any]}} :return-type :text}
        graph (assoc base :parent :str-join
                     :args {:coll {:type [:list :any]}
                            :separator ","})
        child (fn [name]
                {:name name :parent :test.contract/public-join
                 :args {:coll {:as :items}}})
        public-slot-id (ids/slot-id (ids/fn-id "test.contract" :public-join) :coll)
        cached-slot #(some (fn [slot] (when (= public-slot-id (:id slot)) slot))
                           (:slots @(:graph-cache ctx)))
        saved-id (ids/fn-id nil :saved-public-join)
        fresh-id (ids/fn-id nil :fresh-public-join)]
    (harness/sync! [base (child :saved-public-join)])
    (is (contains? (cr/free-arg-accepted-names ctx saved-id) :items))
    (is (nil? (:source-slot-id (cached-slot))))
    (harness/sync! [graph])
    (let [persisted-source (:source-slot-id (sp/read-entity storage :slot public-slot-id))]
      (is (some? persisted-source))
      (is (= persisted-source (:source-slot-id (cached-slot)))
          "Delta invalidation refreshes the existing public slot's source link"))
    (harness/sync! [(child :fresh-public-join)])
    (testing "both saved and fresh renames reach the direct parent reader"
      (is (= "saved,values" (exec/execute-with-named-args ctx saved-id
                                                          {:items ["saved" "values"]})))
      (is (= "fresh,values" (exec/execute-with-named-args ctx fresh-id
                                                          {:items ["fresh" "values"]}))))))
