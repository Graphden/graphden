(ns ^:integration graphden.crud.explorer-view-migration-test
  "Upgrade saved identities through the shipped public graph, with real PG."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.entities.views :as read-views]
    [graphden.crud.views :as views]
    [graphden.executor.composition.interface :as composition]
    [graphden.executor.context :as context]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.exec-harness :as harness]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- member-ids
  [result]
  (into #{} (map :id) (:fns result)))


(deftest saved-identities-and-clauses-survive-base-to-graph-upgrade
  (let [storage harness/*storage*
        ctx harness/*context*
        root-id (ids/fn-id "app.views" :explorer-view)
        axis-slot #(ids/slot-id root-id %)
        const-id (harness/fn-id "const")
        root (sp/read-entity storage :fn root-id)
        axis-slots (mapv #(sp/read-entity storage :slot (:slot-id %))
                         (sp/query-entities storage :fn-slot {:fn-id root-id}))
        shipped (->> (edn/read-string (slurp "resources/packages/app/views/fns.edn"))
                     :fns
                     (filter #(or (= :explorer-view (:name %))
                                  (str/starts-with? (name (:name %)) "_explorer-")))
                     (mapv #(assoc % :namespace "app.views")))]
    (testing "the new root is genuinely composed and still has optional axes"
      (is (seq (:parent-ids root)))
      (is (= #{} (member-ids (exec/execute ctx root-id {})))))
    ;; Reproduce the previous installed public identity and slot rows. Saved
    ;; descendants below are created BEFORE syncing the new body, and keep
    ;; their existing UUIDs, parent edges and binding slot IDs throughout.
    (sp/update-entity storage :fn root-id {:parent-ids []})
    (doseq [slot axis-slots]
      (sp/update-entity storage :slot (:id slot) {:source-slot-id nil}))
    (doseq [binding (sp/query-entities storage :binding {:fn-id root-id})]
      (sp/delete-entity storage :binding (:id binding)))
    (let [member (setup/create-composed-fn! storage "migration-member" const-id)
          other (setup/create-composed-fn! storage "migration-other" const-id)
          first-view (setup/create-composed-fn! storage "migration-first" root-id)
          nested (setup/create-composed-fn! storage "migration-nested" root-id)
          name-binding (setup/bind-value! storage (:id first-view) (axis-slot :name) "migration-member")
          use-binding (setup/bind-ref! storage (:id first-view) (axis-slot :uses) const-id)
          also-binding (setup/bind-ref! storage (:id nested) (axis-slot :also) (:id first-view))
          branch (vs/switch-branch storage (:id (vs/create-branch! storage "before-view-upgrade")))
          branch-ctx (context/create-context {:storage branch})]
      (sp/update-entity branch :binding (:id name-binding) {:value "migration-other"})
      (is (= #{(:id member)}
             (member-ids (entities/view-members (context/create-context {:storage storage})
                                                {:views [(:id nested)]}))))
      ;; Persist through the production record sync. The checked source
      ;; closure has a separate small contract test; do not seed signatures
      ;; from raw inline EDN in this migration fixture.
      (let [changed (composition/sync-fns-to-storage!
                      storage shipped (loader/sync-namespaces! storage #{"app.views"}))]
        (context/invalidate-graph-cache!
          ctx (into [(:id member) (:id other) (:id first-view) (:id nested)] (vals changed))))
      (testing "sync preserves legacy slot and binding identities"
        (doseq [binding [name-binding use-binding also-binding]]
          (is (= (:slot-id binding) (:slot-id (sp/read-entity storage :binding (:id binding))))))
        (is (= [root-id] (:parent-ids (sp/read-entity storage :fn (:id first-view))))))
      (testing "discovery, nested intersection and Run share the upgraded identity"
        (let [listed (into {} (map (juxt :id identity)) (entities/list-explorer-views ctx))]
          (is (= {:name "migration-member" :uses [const-id]}
                 (:filters (get listed (:id first-view)))))
          (is (= {:also [(:id first-view)]} (:filters (get listed (:id nested))))))
        (is (= #{(:id member)} (member-ids (exec/execute ctx (:id first-view) {}))))
        (is (= #{(:id member)} (member-ids (exec/execute ctx (:id nested) {}))))
        (is (= #{(:id member)} (member-ids (entities/view-members ctx {:views [(:id nested)]})))))
      (testing "a pre-existing branch keeps its own saved-view bindings"
        (is (= #{(:id other)}
               (member-ids (entities/view-members branch-ctx {:views [(:id nested)]}))))
        (is (= #{(:id other)} (member-ids (exec/execute branch-ctx (:id nested) {})))))
      (testing "a same-named user root cannot redirect the installed view identity"
        (let [impostor (setup/create-base-fn! storage "explorer-view")
              child (setup/create-composed-fn! storage "impostor-view" (:id impostor))]
          (is (not (contains? (into #{} (map :id)
                                    (entities/list-explorer-views (context/create-context {:storage storage})))
                              (:id child))))))
      (testing "Save updates the old identity and creates a fresh graph view"
        (let [save! #(views/save! ctx (update % :filters read-views/normalise-filters))
              updated (save! {:id (:id first-view) :name "migration-renamed"
                              :filters {:uses [const-id] :name "migration-member"}})
              fresh (save! {:name "migration-fresh"
                            :filters {:uses [const-id] :views [(:id nested)]}})]
          (is (true? (:committed updated)) (pr-str updated))
          (is (= (:id first-view) (get-in updated [:view :id])))
          (is (true? (:committed fresh)) (pr-str fresh))
          (is (= #{(:id member)}
                 (member-ids (exec/execute ctx (get-in fresh [:view :id]) {})))))))))
