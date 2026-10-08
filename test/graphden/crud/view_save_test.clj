(ns ^:serial graphden.crud.view-save-test
  "Save view against real versioned PostgreSQL: identity round trips and
   rollback of partial graph writes, including their version rows. Serial
   because failure injection temporarily replaces the publication function."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.entities.views :as read-views]
    [graphden.crud.views :as views]
    [graphden.executor.context :as context]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture) exec/with-isolated-rich-types)


(defn- install-view-base!
  [storage]
  (let [base (setup/create-base-fn! storage "explorer-view")
        const-row (setup/create-base-fn! storage "const")
        value-slot (setup/create-slot! storage "value" :any)
        _ (setup/attach-slot! storage (:id const-row) (:id value-slot) 0)
        slots (into {} (map-indexed
                         (fn [position [arg type]]
                           (let [slot (setup/create-slot! storage (name arg) type)]
                             (setup/attach-slot! storage (:id base) (:id slot) position)
                             [arg (:id slot)]))
                         [[:name :text] [:uses :fn-ref] [:also :fn-ref]
                          [:uses-all :sequence] [:also-all :sequence]
                          [:effects :sequence] [:kinds :sequence] [:problems :sequence]
                          [:namespaces :sequence] [:exclude :sequence] [:unused :bool]]))]
    {:id (:id base) :slots slots}))


(defn- with-storage
  [f]
  (let [storage (setup/create-versioned-test-storage 6)]
    (try
      (f storage (context/create-context {:storage storage}) (install-view-base! storage))
      (finally (sp/close storage)))))


(defn- save!
  [ctx body]
  (views/save! ctx (views/parse-command body)))


(defn- counts
  [storage]
  (into {} (map (fn [entity]
                  [entity (count (sp/query-entities (vs/unwrap storage) entity {}))]))
        [:fn :fn-version :binding :binding-version
         :binding-list-item :binding-list-item-version]))


(defn- adapters
  [storage]
  (into #{} (comp (filter #(str/starts-with? (or (:name %) "") "_view-")) (map :id))
        (sp/query-entities storage :fn {})))


(deftest create-id-is-create-only-and-never-revives-an-existing-identity
  (with-storage
    (fn [storage ctx _]
      (let [proposed (random-uuid)
            body {:create-id (str proposed) :name "proposed-view" :filters {:name "match"}}
            result (save! ctx body)]
        (is (true? (:committed result)))
        (is (= proposed (get-in result [:view :id])))
        (doseq [invalid [(assoc body :id proposed)
                         (assoc body :id nil)
                         (assoc body :create-id "invalid")
                         (assoc body :create-id nil)]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(never both|must be a UUID)"
                (views/parse-command invalid))))
        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity storage :fn proposed))
        (let [before (counts storage)
              collision (save! ctx (assoc body :name "must-not-revive"))]
          (is (false? (:committed collision)))
          (is (= 409 (:http-status collision)))
          (is (= before (counts storage)))
          (is (nil? (sp/read-entity storage :fn proposed))))))))


(deftest denied-update-never-reads-or-diagnoses-the-existing-composition
  (with-storage
    (fn [storage ctx {:keys [id slots]}]
      (let [literal (setup/create-composed-fn! storage "private-literal-view" id)
            computed (setup/create-composed-fn! storage "private-computed-view" id)
            computation (setup/create-base-fn! storage "private-filter-computation")
            _ (setup/bind-value! storage (:id literal) (:name slots) "private-name")
            _ (setup/bind-ref! storage (:id computed) (:name slots) (:id computation))
            query sp/query-entities
            reads (atom [])
            before (counts storage)
            results (with-redefs [writer/assert-write-authorized!
                                  (fn [_ _ _ _]
                                    (throw (ex-info "The view update is not allowed"
                                                    {:type :authz/forbidden})))
                                  sp/query-entities
                                  (fn [active entity params]
                                    (when (#{:binding :binding-list-item :fn-slot :slot} entity)
                                      (swap! reads conj entity))
                                    (query active entity params))]
                      (mapv #(save! ctx {:id % :name "attempted-update"
                                         :filters {:name "replacement"}})
                            [(:id literal) (:id computed) (random-uuid)]))]
        (is (every? #(= 403 (:http-status %)) results))
        (is (apply = (map #(select-keys % [:error :error-data :http-status]) results)))
        (is (every? #(false? (:committed %)) results))
        (is (empty? @reads) "Authorization precedes bindings, slots and computed-clause diagnostics")
        (is (= before (counts storage)))))))


(deftest saves-all-reference-clauses-and-problems-with-stable-identities
  (with-storage
    (fn [storage ctx {:keys [id slots]}]
      (let [a (setup/create-base-fn! storage "save-target-a")
            b (setup/create-base-fn! storage "save-target-b")
            view-a (setup/create-composed-fn! storage "view-a" id)
            view-b (setup/create-composed-fn! storage "view-b" id)
            _ (setup/bind-value! storage (:id view-a) (:name slots) "target")
            _ (setup/bind-value! storage (:id view-b) (:name slots) "save")
            filters {:uses [(:id a) (:id b)] :views [(:id view-a) (:id view-b)]
                     :problems ["type-errors" "failed"]}
            result (save! ctx {:name "saved-view" :filters filters})
            saved-id (get-in result [:view :id])
            helpers (adapters storage)]
        (is (true? (:committed result)))
        (is (= 4 (count helpers)))
        (is (= (read-views/normalise-filters filters)
               (read-views/normalise-filters (get-in result [:view :filters]))))
        (sp/update-entity storage :fn (:id a) {:name "renamed-target"})
        (let [changed (save! ctx {:id saved-id :name "renamed-view"
                                  :filters (assoc filters :name "save")})]
          (is (true? (:committed changed)))
          (is (= helpers (adapters storage)) "Changing another axis reuses existing identity compositions")
          (is (= #{(:id a) (:id b)} (set (get-in changed [:view :filters :uses])))))
        (is (= [id] (:parent-ids (sp/read-entity storage :fn saved-id))))
        (let [legacy (setup/create-composed-fn! storage "legacy-view" id)
              old-binding (setup/bind-ref! storage (:id legacy) (:uses slots) (:id a))
              upgraded (save! ctx {:id (:id legacy) :name "legacy-view"
                                   :filters {:uses [(:id a) (:id b)]}})]
          (is (true? (:committed upgraded)))
          (is (= #{(:id a) (:id b)} (set (get-in upgraded [:view :filters :uses]))))
          (is (nil? (sp/read-entity storage :binding (:id old-binding))))
          (is (= (:fn-ref setup/primitive-fn-ids)
                 (:type-fn-id (sp/read-entity storage :slot (:uses slots))))))))))


(deftest a-late-list-item-failure-rolls-back-the-whole-view-and-history
  (with-storage
    (fn [storage ctx _]
      (let [a (setup/create-base-fn! storage "rollback-a")
            b (setup/create-base-fn! storage "rollback-b")
            proposed (random-uuid)
            before (counts storage)
            published (atom [])]
        (jdbc/execute! (tx/datasource storage)
                       ["CREATE FUNCTION reject_second_view_item() RETURNS trigger AS $$
                           BEGIN
                             IF NEW.position = 1 THEN RAISE EXCEPTION 'injected late list item failure'; END IF;
                             RETURN NEW;
                           END;
                         $$ LANGUAGE plpgsql"])
        (jdbc/execute! (tx/datasource storage)
                       ["CREATE TRIGGER reject_second_view_item BEFORE INSERT ON binding_list_item_version
                          FOR EACH ROW EXECUTE FUNCTION reject_second_view_item()"])
        (let [outcome (with-redefs [entities/publish-write! (fn [& args] (swap! published conj args))]
                        (try (save! ctx {:create-id proposed :name "rolled-back-view"
                                         :filters {:uses [(:id a) (:id b)]}})
                             (catch Exception _ :sql-failure)))]
          (is (not (true? (:committed outcome))))
          (is (= before (counts storage)))
          (is (empty? @published))
          (is (nil? (sp/read-entity (vs/unwrap storage) :fn proposed)))
          (is (empty? (sp/query-entities storage :fn {:name "rolled-back-view"}))))))))


(defn- wait-for-writer
  [storage]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [waiting (-> (jdbc/execute-one!
                          (tx/datasource storage)
                          ["SELECT count(*) AS n FROM pg_locks l
                             JOIN pg_database d ON d.oid = l.database
                             WHERE l.locktype = 'advisory' AND NOT l.granted
                               AND d.datname = current_database()"])
                        vals first long)]
        (cond
          (pos? waiting) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 10) (recur)))))))


(deftest waiting-create-rechecks-the-proposed-identity-before-any-write
  (with-storage
    (fn [storage ctx _]
      (let [proposed (random-uuid)
            entered (promise)
            release (promise)
            holder (future
                     (writer/call-with-write
                       storage :graph
                       (fn [bound]
                         (sp/create-entity bound :fn {:id proposed :name "winning-identity"})
                         (deliver entered (counts bound))
                         (deref release 15000 :timeout))))]
        (try
          (let [after-create (deref entered 10000 :timeout)
                saving (future (save! ctx {:create-id proposed :name "losing-view"
                                           :filters {:name "match"}}))]
            (try
              (is (map? after-create))
              (is (wait-for-writer storage))
              (deliver release true)
              (let [result (deref saving 15000 :timeout)]
                (is (false? (:committed result)))
                (is (= 409 (:http-status result)))
                (is (= after-create (counts storage)))
                (is (= "winning-identity" (:name (sp/read-entity storage :fn proposed)))))
              (finally (deliver release true) (future-cancel saving))))
          (finally
            (deliver release true)
            (deref holder 15000 :timeout)
            (future-cancel holder)))))))


(deftest waiting-save-rechecks-inherited-final-clauses-before-renaming
  (with-storage
    (fn [storage ctx {:keys [id slots]}]
      (let [parent (setup/create-composed-fn! storage "final-parent" id)
            child (setup/create-composed-fn! storage "final-child" (:id parent))
            entered (promise)
            release (promise)
            holder (future
                     (writer/call-with-write
                       storage :graph
                       (fn [bound]
                         (setup/bind-value! bound (:id parent) (:name slots) "fixed-filter")
                         (deliver entered (counts bound))
                         (deref release 15000 :timeout))))]
        (try
          (let [after-parent (deref entered 10000 :timeout)
                saving (future (save! ctx {:id (:id child) :name "must-not-rename" :filters {}}))]
            (try
              (is (map? after-parent))
              (is (wait-for-writer storage))
              (deliver release true)
              (let [result (deref saving 15000 :timeout)]
                (is (false? (:committed result)))
                (is (= 400 (:http-status result)))
                (is (= after-parent (counts storage)))
                (is (= "final-child" (:name (sp/read-entity storage :fn (:id child))))))
              (finally (deliver release true) (future-cancel saving))))
          (finally
            (deliver release true)
            (deref holder 15000 :timeout)
            (future-cancel holder)))))))


(deftest postcommit-publication-failure-does-not-report-a-rollback
  (with-storage
    (fn [storage ctx _]
      (let [result (with-redefs [entities/publish-write! (fn [& _] (throw (ex-info "publication failed" {})))]
                     (save! ctx {:name "committed-view" :filters {:name "member"}}))]
        (is (true? (:ok result)))
        (is (true? (:committed result)))
        (is (= 1 (count (:publication-warnings result))))
        (is (= "committed-view" (:name (sp/read-entity storage :fn (get-in result [:view :id])))))))))


(deftest protected-branch-rejects-save-without-any-graph-write
  (with-storage
    (fn [storage ctx _]
      (let [before (counts storage)]
        (sp/update-entity (vs/unwrap storage) :branch (vs/current-branch-id storage) {:require-merge? true})
        (binding [vs/*enforce-require-merge?* true]
          (let [result (save! ctx {:name "protected-view" :filters {:name "member"}})]
            (is (false? (:committed result)))
            (is (= 409 (:http-status result)))
            (is (= before (counts storage)))))))))


(deftest http-save-and-run-use-the-same-complete-filter-set
  (let [{:keys [storage ctx all-name->id] :as graph} (setup/bootstrap-crud-graph-from-golden!)
        post! (fn [body]
                (let [response (setup/via-graph
                                 graph :api-view-save-handler
                                 {:uri "/api/views/save" :request-method :post
                                  :headers {"content-type" "application/json"}
                                  :body (json/generate-string body)})]
                  (is (= 200 (:status response)))
                  (json/parse-string (:body response) true)))]
    (try
      (let [const-id (get all-name->id :const)
            get-id (get all-name->id :get)
            value-slot (:slot-id (first (sp/query-entities storage :fn-slot {:fn-id const-id})))
            candidate (entities/create-entity :fn {:name "match-http-view" :parent-ids [const-id]} ctx)
            _ (entities/create-entity :binding
                                      {:fn-id (:id candidate) :slot-id value-slot :ref-fn-id get-id
                                       :type-override-fn-id (:fn-ref setup/primitive-fn-ids)} ctx)
            first-view (post! {:name "http-first-view" :filters {:name "match"}})
            second-view (post! {:name "http-second-view" :filters {:name "http"}})
            result (post! {:name "http-combined-view"
                           :filters {:uses [const-id get-id]
                                     :views [(get-in first-view [:view :id]) (get-in second-view [:view :id])]
                                     :name "match-http-view"}})
            saved-id (some-> (get-in result [:view :id]) parse-uuid)]
        (is (true? (:committed result)))
        (is (= 2 (count (get-in result [:view :filters :uses]))))
        (is (= 2 (count (get-in result [:view :filters :also]))))
        (let [stored (entities/view-members ctx (get-in result [:view :filters]))
              direct (entities/view-members ctx {:uses [const-id get-id] :name "match-http-view"})
              run (setup/exec-with-storage ctx storage saved-id {})]
          (is (= #{(:id candidate)} (into #{} (map :id) (:fns direct))) (pr-str direct))
          (is (= #{(:id candidate)} (into #{} (map :id) (:fns stored))) (pr-str stored))
          (is (= #{(:id candidate)} (into #{} (map :id) (:fns run))) (pr-str run))))
      (finally (sp/close storage)))))
