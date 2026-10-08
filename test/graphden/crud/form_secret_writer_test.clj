(ns ^:integration graphden.crud.form-secret-writer-test
  "Real PostgreSQL checks for form preflight races and the SQL/Vault boundary."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.vault :as vault]
    [graphden.crud.entities :as entities]
    [graphden.crud.secrets :as secrets]
    [graphden.executor.context :as context]
    [graphden.executor.interface :as executor]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.core :as versioned]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture) executor/with-isolated-rich-types)


(defn- with-storage
  [f]
  (let [storage (setup/create-versioned-test-storage 6)]
    (try (f storage (assoc (context/create-context {:storage storage :base-fns {}})
                           :vault {:address "http://unused-vault" :token "test"}))
         (finally (sp/close storage)))))


(defn- slot-fixture
  [storage prefix]
  (let [parent (setup/create-base-fn! storage (str prefix "-parent"))
        slot (setup/create-slot! storage "input" :any)
        _ (setup/attach-slot! storage (:id parent) (:id slot) 0)
        child (setup/create-composed-fn! storage (str prefix "-child") (:id parent))]
    {:parent parent :slot slot :child child}))


(defn- wait-for-lock
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


(defn- after-competing-write
  [storage change! apply!]
  (let [entered (promise)
        release (promise)
        holding (future
                  (writer/call-with-write
                    storage :graph
                    (fn [bound]
                      (change! bound)
                      (deliver entered true)
                      (deref release 15000 :timeout))))]
    (try
      (is (true? (deref entered 10000 :timeout)))
      (let [applying (future (apply!))]
        (try
          (is (wait-for-lock storage))
          (deliver release true)
          (deref applying 15000 :timeout)
          (finally
            (deliver release true)
            (future-cancel applying))))
      (finally
        (deliver release true)
        (deref holding 15000 :timeout)
        (future-cancel holding)))))


(deftest form-create-rechecks-inherited-final-value-after-lock
  (with-storage
    (fn [storage ctx]
      (let [{:keys [parent slot child]} (slot-fixture storage "create")
            result (after-competing-write
                     storage
                     #(setup/bind-value! % (:id parent) (:id slot) "final")
                     #(entities/apply-create-core
                        {:entity-type :binding :type-str "binding" :form-data {}
                         :entity-data {:fn-id (:id child) :slot-id (:id slot) :value "override"}}
                        ctx))]
        (is (= 400 (:http-status result)))
        (is (string? (:error result)))
        (is (empty? (sp/query-entities storage :binding {:fn-id (:id child)})))))))


(deftest partial-put-rechecks-current-owner-under-lock
  (doseq [form? [true false]]
    (testing (if form? "form partial PUT" "generic partial PUT")
      (with-storage
        (fn [storage ctx]
          (let [{:keys [parent slot child]} (slot-fixture storage "update")
                binding (setup/bind-value! storage (:id child) (:id slot) "before")
                result (after-competing-write
                         storage
                         #(setup/bind-value! % (:id parent) (:id slot) "final")
                         #(if form?
                            (entities/apply-update-core
                              {:entity-type :binding :type-str "binding" :form-data {}
                               :id-uuid (:id binding) :entity-data {:value "after"}}
                              ctx)
                            (try (entities/update-entity :binding (:id binding) {:value "after"} ctx)
                                 (catch clojure.lang.ExceptionInfo e (ex-data e)))))]
            (if form?
              (is (= 400 (:http-status result)))
              (is (= :constraint-violation/value-override (:type result))))
            (is (= "before" (:value (sp/read-entity storage :binding (:id binding)))))))))))


(deftest optional-rename-sql-failure-preserves-binding-and-removes-half-view
  (with-storage
    (fn [storage ctx]
      (let [{:keys [slot child]} (slot-fixture storage "rename")]
        ;; The slot INSERT succeeds first. Reject its junction so the optional
        ;; rename savepoint has real partial state to roll back.
        (jdbc/execute! (tx/datasource storage)
                       ["CREATE FUNCTION reject_rename() RETURNS trigger AS $$
                          BEGIN
                            IF EXISTS (SELECT 1 FROM slot WHERE id = NEW.slot_id AND name = 'renamed') THEN
                              RAISE EXCEPTION 'injected rename junction failure';
                            END IF;
                            RETURN NEW;
                          END;
                          $$ LANGUAGE plpgsql"])
        (jdbc/execute! (tx/datasource storage)
                       ["CREATE TRIGGER reject_rename BEFORE INSERT ON fn_slot
                          FOR EACH ROW EXECUTE FUNCTION reject_rename()"])
        (let [result (entities/apply-create-core
                       {:entity-type :binding :type-str "binding"
                        :form-data {:rename-to "renamed"}
                        :entity-data {:fn-id (:id child) :slot-id (:id slot) :value "kept"}}
                       ctx)]
          (is (uuid? (:created result)))
          (is (= "kept" (:value (sp/read-entity storage :binding (:created result)))))
          (is (empty? (sp/query-entities storage :slot {:name "renamed"})))
          (is (empty? (sp/query-entities storage :fn-slot {:fn-id (:id child)}))))))))


(deftest secret-rejection-restores-version-history
  (with-storage
    (fn [storage ctx]
      (let [parent (setup/create-base-fn! storage "plain-parent" :text)
            slot (setup/create-slot! storage "input" :text)
            _ (setup/attach-slot! storage (:id parent) (:id slot) 0)
            leaf (setup/create-base-fn! storage "hidden-leaf" :text)
            _ (registry/record-rich-types-raw!
                :plain-parent {:return :text :args {:input :text} :effects #{}})
            _ (registry/record-rich-types-raw!
                :hidden-leaf {:return [:secret :text] :args {} :effects #{}})
            child (setup/create-composed-fn! storage "plain-child" (:id parent))
            binding (setup/bind-value! storage (:id child) (:id slot) "before")
            base (versioned/unwrap storage)
            before (set (sp/query-entities base :binding-version {}))
            result (entities/apply-update-core
                     {:entity-type :binding :type-str "binding"
                      :id-uuid (:id binding) :form-data {}
                      :entity-data {:value nil :value-present false :ref-fn-id (:id leaf)}}
                     ctx)]
        (is (string? (:error result)))
        (is (re-find #"(?i)type-check failed" (:error result)))
        (is (= "before" (:value (sp/read-entity storage :binding (:id binding)))))
        (is (= before (set (sp/query-entities base :binding-version {}))))))))


(deftest secret-path-is-reserved-before-vault-and-vault-does-not-hold-writer
  (with-storage
    (fn [storage ctx]
      (setup/create-base-fn! storage "vault-get")
      (let [{:keys [slot child]} (slot-fixture storage "secret")
            other (setup/create-composed-fn! storage "secret-other" (first (:parent-ids child)))
            row-entered (promise)
            row-release (promise)
            vault-entered (promise)
            vault-release (promise)
            calls (atom 0)
            parsed {:fn-id (:id child) :slot-id (:id slot) :path "guarded/path" :value "hidden"}]
        (binding [vault/*impl-override*
                  {:put-secret (fn [_ _ _]
                                 (swap! calls inc)
                                 (deliver vault-entered true)
                                 (deref vault-release 15000 :timeout))}]
          (let [first-write
                (future
                  (binding [entities/*create-entity-override*
                            (fn [et data bound-ctx]
                              (deliver row-entered true)
                              (deref row-release 15000 :timeout)
                              (binding [entities/*create-entity-override* nil]
                                (entities/create-entity et data bound-ctx)))]
                    (secrets/apply-create-inline-binding-body parsed (atom []) ctx)))]
            (try
              (is (true? (deref row-entered 10000 :timeout)))
              (let [second-write (future
                                   (try
                                     (secrets/apply-create-inline-binding-body
                                       (assoc parsed :fn-id (:id other)) (atom []) ctx)
                                     :unexpected-success
                                     (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
                (try
                  ;; The first request has checked the path but has not written
                  ;; its binding yet. The competing claim must wait here.
                  (is (wait-for-lock storage))
                  (deliver row-release true)
                  (is (true? (deref vault-entered 10000 :timeout)))
                  (is (= :secrets/path-in-use (deref second-write 10000 :blocked)))
                  (let [unrelated (future (sp/create-entity storage :ns {:name "during-vault"}))]
                    (try
                      (is (= "during-vault" (:name (deref unrelated 10000 :blocked))))
                      (finally (future-cancel unrelated))))
                  (finally (future-cancel second-write))))
              (is (= 1 @calls))
              (deliver vault-release true)
              (is (true? (:ok (deref first-write 10000 :timeout))))
              (is (empty? (sp/query-entities storage :binding {:fn-id (:id other)})))
              (finally
                (deliver row-release true)
                (deliver vault-release true)
                (future-cancel first-write)))))))))


(deftest failed-secret-sql-phase-clears-compensation-and-leaves-no-history
  (with-storage
    (fn [storage ctx]
      (setup/create-base-fn! storage "vault-get")
      (let [{:keys [parent]} (slot-fixture storage "secret-leaf")
            journal (atom [])
            calls (atom 0)]
        (binding [vault/*impl-override* {:put-secret (fn [& _] (swap! calls inc))}
                  entities/*create-entity-override*
                  (fn [entity-type data bound-ctx]
                    (if (= :binding entity-type)
                      (throw (ex-info "injected binding failure" {:type :test/secret-binding-failure}))
                      (binding [entities/*create-entity-override* nil]
                        (entities/create-entity entity-type data bound-ctx))))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected binding failure"
                (secrets/apply-create-secret-body
                  {:nm "must-roll-back" :path "failed/path" :value "hidden"}
                  (:id parent) journal ctx))))
        (is (empty? @journal))
        (is (zero? @calls))
        (is (empty? (sp/query-entities (versioned/unwrap storage) :fn-version
                                       {:name "must-roll-back"})))))))


(deftest vault-failure-compensates-already-committed-sql-rows
  (with-storage
    (fn [storage ctx]
      (setup/create-base-fn! storage "vault-get")
      (let [{:keys [parent]} (slot-fixture storage "vault-failure")
            journal (atom [])
            error (binding [vault/*impl-override*
                            {:put-secret (fn [& _]
                                           (throw (ex-info "Vault unavailable" {:type :vault/write-failed})))}]
                    (try (secrets/apply-create-secret-body
                           {:nm "committed-before-vault" :path "vault/failure" :value "hidden"}
                           (:id parent) journal ctx)
                         nil
                         (catch clojure.lang.ExceptionInfo e e)))]
        (is (= :vault/write-failed (:type (ex-data error))))
        (is (= [:storage-delete :storage-delete] (mapv first @journal)))
        (let [fn-id (nth (first @journal) 2)]
          (is (= "committed-before-vault" (:name (sp/read-entity storage :fn fn-id))))
          (is (= 1 (count (sp/query-entities storage :binding {:fn-id fn-id}))))
          (is (false? (:ok (secrets/replay-secret-rollback! journal error ctx))))
          (is (nil? (sp/read-entity storage :fn fn-id)))
          (is (empty? (sp/query-entities storage :binding {:fn-id fn-id}))))))))
