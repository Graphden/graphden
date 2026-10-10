(ns ^:integration ^:serial graphden.crud.delete-batch-graph-test
  "Exact-receipt batch deletion through the shipped graph and real PostgreSQL."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [clojure.tools.logging.test :refer [logged? with-log]]
    [graphden.crud.entities.delete-batch :as batch]
    [graphden.crud.entities.invalidation :as invalidation]
    [graphden.crud.test-autorun :as autorun]
    [graphden.executor.context :as context]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.crud :as pg-crud]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]
    [graphden.versioning.storage.core :as versioned]))


(use-fixtures :once (ga/fixture (ns-name *ns*))
  (fn [f] (binding [autorun/*auto-run?* false] (f))))


(defn- fresh-fn
  ([] (fresh-fn {}))
  ([attrs]
   (let [storage (:storage ga/*bootstrap*)
         ns-row (sp/create-entity storage :ns {:name (str "batch-" (random-uuid))})]
     (sp/create-entity storage :fn
                       (merge {:name "owned" :namespace-id (:id ns-row)
                               :parent-ids [(ga/fn-id :const)]} attrs)))))


(defn- receipt
  [row]
  (select-keys row [:id :name :namespace-id]))


(defn- delete!
  [rows]
  (batch/delete-receipts! (:ctx ga/*bootstrap*) (mapv receipt rows)))


(defn- refusal
  [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(defn- bind-ref!
  [owner attrs]
  (let [storage (:storage ga/*bootstrap*)
        slot-id (:slot-id (first (sp/query-entities storage :fn-slot {:fn-id (ga/fn-id :const)})))]
    (sp/create-entity storage :binding
                      (merge {:fn-id (:id owner) :slot-id slot-id} attrs))))


(defrecord DeniedWriteStorage
  [base denied-id]

  writer/GraphWriteAuthorization

  (authorize-graph-write!
    [_ entity row id]
    (when (= id denied-id)
      (throw (ex-info "Namespace write denied" {:type :authz/forbidden})))
    (writer/assert-write-authorized! base entity row id))


  sp/StorageBatchCRUD

  (create-entities [_ entity rows] (sp/create-entities base entity rows))


  (read-entities [_ entity ids] (sp/read-entities base entity ids))


  (update-entities [_ entity rows] (sp/update-entities base entity rows))


  (upsert-entities [_ entity rows] (sp/upsert-entities base entity rows))


  (delete-entities [_ entity ids] (sp/delete-entities base entity ids))


  (query-ref-many-owners [_ entity field id] (sp/query-ref-many-owners base entity field id))


  sp/StorageCRUD

  (create-entity [_ entity row] (sp/create-entity base entity row))


  (read-entity [_ entity id] (sp/read-entity base entity id))


  (update-entity [_ entity id row] (sp/update-entity base entity id row))


  (delete-entity [_ entity id] (sp/delete-entity base entity id))


  (query-entities [_ entity where] (sp/query-entities base entity where))


  (query-entities [_ entity where opts] (sp/query-entities base entity where opts))


  (query-latest-per-group [_ entity where cols] (sp/query-latest-per-group base entity where cols)))


(deftest decorated-authorization-denial-precedes-all-writes-test
  (let [storage (:storage ga/*bootstrap*) a (fresh-fn) b (fresh-fn)
        scoped (->DeniedWriteStorage storage (:id b))]
    (is (= :authz/forbidden
           (refusal #(batch/delete-receipts! {:storage scoped} (mapv receipt [a b])))))
    (is (= #{(:id a) (:id b)} (set (keys (sp/read-entities storage :fn [(:id a) (:id b)])))))))


(deftest graph-handler-deletes-internal-dependencies-and-retries-test
  (let [storage (:storage ga/*bootstrap*)
        target (fresh-fn)
        caller (fresh-fn {:parent-ids [(:id target)]})
        _ (bind-ref! caller {:ref-fn-id (:id target)})
        request {:request-method :post :uri "/api/entities/fn/delete-batch"
                 :headers {"content-type" "application/json"}
                 :body (json/generate-string {:functions (mapv receipt [target caller])})}
        invalidations (atom [])
        original invalidation/invalidate!
        response (with-redefs [invalidation/invalidate!
                               (fn [ctx s entity data]
                                 (swap! invalidations conj data)
                                 (original ctx s entity data))]
                   (ga/exec-handler :delete-fn-batch-handler request))]
    (is (= 200 (:status response)))
    (is (= {:deleted (mapv (comp str :id) [target caller]) :already-absent []}
           (json/parse-string (:body response) true)))
    (is (= [{:ids #{(:id target) (:id caller)}}] @invalidations))
    (is (= {} (sp/read-entities storage :fn [(:id target) (:id caller)])))
    (is (= {:deleted [] :already-absent [(:id target) (:id caller)]}
           (delete! [target caller])))))


(deftest stale-receipt-refuses-entire-set-test
  (let [storage (:storage ga/*bootstrap*) a (fresh-fn) b (fresh-fn)]
    (sp/update-entity storage :fn (:id b) {:name "renamed"})
    (is (= :graph-write/stale-delete-receipt (refusal #(delete! [a b]))))
    (is (= #{(:id a) (:id b)} (set (keys (sp/read-entities storage :fn [(:id a) (:id b)])))))))


(deftest external-live-references-refuse-each-supported-edge-test
  (let [storage (:storage ga/*bootstrap*)]
    (doseq [edge [:parent :ref-fn-id :resolver-fn-id :list-item]]
      (let [target (fresh-fn) companion (fresh-fn)
            owner (fresh-fn (if (= edge :parent) {:parent-ids [(:id target)]} {}))]
        (case edge
          :parent nil
          :list-item (let [binding (bind-ref! owner {})]
                       (sp/create-entity storage :binding-list-item
                                         {:binding-id (:id binding) :position 0 :ref-fn-id (:id target)}))
          (bind-ref! owner {edge (:id target)}))
        (is (= :graph-write/functions-in-use (refusal #(delete! [companion target]))) (name edge))
        (is (= #{(:id companion) (:id target)}
               (set (keys (sp/read-entities storage :fn [(:id companion) (:id target)])))))
        (delete! [owner])
        (is (= #{(:id companion) (:id target)} (set (:deleted (delete! [companion target])))))))))


(deftest decorated-write-failure-rolls-back-and-does-not-publish-test
  (let [storage (:storage ga/*bootstrap*) a (fresh-fn) b (fresh-fn)
        original pg-crud/create-entity calls (atom 0) published (atom [])]
    (binding [pg-crud/*create-entity-override*
              (fn [ds entity data fields]
                (when (and (= entity :fn-version) (:deleted-at data) (= 2 (swap! calls inc)))
                  (throw (ex-info "Denied physical write" {:type :authz/forbidden})))
                (binding [pg-crud/*create-entity-override* nil]
                  (original ds entity data fields)))]
      (with-redefs [registry/unregister-rich-type! (fn [& args] (swap! published conj args))]
        (is (= :authz/forbidden (refusal #(delete! [a b]))))))
    (is (= 2 @calls))
    (is (= [] @published))
    (is (= #{(:id a) (:id b)} (set (keys (sp/read-entities storage :fn [(:id a) (:id b)])))))))


(deftest package-and-secret-identities-refuse-test
  (let [storage (:storage ga/*bootstrap*)
        shipped (sp/read-entity storage :fn (ga/fn-id :const))
        companion (fresh-fn)
        leaf-name (first (registry/fn-names-with-tag :secret-shape))
        secret (fresh-fn {:parent-ids [(ga/fn-id leaf-name)]})]
    (is (= :authz/forbidden (refusal #(delete! [companion shipped]))))
    (is (= :graph-write/secret-delete (refusal #(delete! [companion secret]))))
    (is (= (:id companion) (:id (sp/read-entity storage :fn (:id companion)))))))


(deftest branch-ref-change-and-merge-protection-test
  (let [storage (:storage ga/*bootstrap*)
        target (fresh-fn) other (fresh-fn) owner (fresh-fn)
        ref-binding (bind-ref! owner {:ref-fn-id (:id other)})
        branch (versioned/create-branch! storage (str "cleanup-" (random-uuid)))
        child (versioned/switch-branch storage (:id branch))
        child-ctx (context/create-context {:storage child})]
    (sp/update-entity child :binding (:id ref-binding) {:ref-fn-id (:id target)})
    (is (= :graph-write/functions-in-use
           (refusal #(batch/delete-receipts! child-ctx [(receipt target)]))))
    (is (= #{(:id target) (:id owner)}
           (set (:deleted (batch/delete-receipts! child-ctx (mapv receipt [target owner]))))))
    (is (= {} (sp/read-entities child :fn [(:id target) (:id owner)])))
    (is (= #{(:id target) (:id owner)}
           (set (keys (sp/read-entities storage :fn [(:id target) (:id owner)])))))
    (sp/update-entity storage :branch (:id branch) {:require-merge? true})
    (is (true? (:require-merge? (sp/read-entity storage :branch (:id branch)))))
    (binding [versioned/*enforce-require-merge?* true]
      (is (true? versioned/*enforce-require-merge?*))
      (is (= :branch/merge-required
             (refusal #(batch/delete-receipts! child-ctx [(receipt other)])))))
    (is (= (:id other) (:id (sp/read-entity child :fn (:id other)))))))


(deftest committed-delete-remains-success-when-publication-needs-repair-test
  (let [storage (:storage ga/*bootstrap*) a (fresh-fn)
        events (atom []) ctx (assoc (:ctx ga/*bootstrap*) :notify-emitter #(swap! events conj %))]
    (with-log
      (let [result (with-redefs [invalidation/invalidate!
                                 (fn [& _] (throw (ex-info "PRIVATE-binding-sentinel" {:value "PRIVATE-binding-sentinel"})))]
                     (batch/delete-receipts! ctx [(receipt a)]))]
        (is (= {:deleted [(:id a)] :already-absent []} result))
        (is (logged? 'graphden.crud.entities.delete-batch :warn nil #"needs publication repair"))
        (is (not (logged? 'graphden.crud.entities.delete-batch :warn #"PRIVATE-binding-sentinel")))))
    (is (nil? (sp/read-entity storage :fn (:id a))))
    (is (= [{:id (str (:id a)) :invalidate-origin? true}]
           (mapv #(select-keys % [:id :invalidate-origin?]) @events)))))
