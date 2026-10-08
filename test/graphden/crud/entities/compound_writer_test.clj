(ns ^:integration graphden.crud.entities.compound-writer-test
  "Compound writes against real PostgreSQL: rollback and reads after a
   competing writer commits. No timing assumption substitutes for lock waits."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities.record-type :as record-type]
    [graphden.crud.entities.seq :as sequence]
    [graphden.crud.entities.tighten :as tighten]
    [graphden.executor.context :as context]
    [graphden.executor.interface :as executor]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture) executor/with-isolated-rich-types)


(defn- with-storage
  [f]
  (let [storage (setup/create-versioned-test-storage 6)]
    (try (f storage (context/create-context {:storage storage :base-fns {}}))
         (finally (sp/close storage)))))


(defn- await-waiter
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
        (if (or (pos? waiting) (> (System/currentTimeMillis) deadline))
          waiting
          (do (Thread/sleep 10) (recur)))))))


(defn- after-competing-write
  "Hold an uncommitted semantic change while apply starts. It must wait
   before reading the state its decision depends on."
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
          (is (pos? (await-waiter storage)))
          (deliver release true)
          (deref applying 15000 :timeout)
          (finally
            (deliver release true)
            (future-cancel applying))))
      (finally
        (deliver release true)
        (deref holding 15000 :timeout)
        (future-cancel holding)))))


(defn- sequence-fixture
  [storage]
  (let [parent (setup/create-base-fn! storage "sequence-parent")
        slot (setup/create-slot! storage "items" :any)
        _ (setup/attach-slot! storage (:id parent) (:id slot) 0)
        owner (setup/create-composed-fn! storage "sequence-owner" (:id parent))
        binding (sp/create-entity storage :binding
                                  {:fn-id (:id owner) :slot-id (:id slot) :list-append true})
        items (mapv #(sp/create-entity storage :binding-list-item
                                       {:binding-id (:id binding) :position % :value %})
                    [0 1])]
    {:owner owner :binding binding :items items :slot slot}))


(deftest record-update-rollback-restores-junction-and-clears-replay-journal
  (with-storage
    (fn [storage ctx]
      (let [created (record-type/apply-create-record-type-body
                      {:name "record" :fields [{:name "old" :type "int"}]}
                      (atom []) ctx)
            fn-id (parse-uuid (:id created))
            before (sp/query-entities storage :fn-slot {:fn-id fn-id})
            journal (atom [])]
        ;; A DB failure in phase 4, after the old junction was removed.
        ;; Static trigger text, scoped to this test's fresh database.
        (jdbc/execute! (tx/datasource storage)
                       ["CREATE FUNCTION reject_new_field() RETURNS trigger AS $$
                          BEGIN
                            IF EXISTS (SELECT 1 FROM slot WHERE id = NEW.slot_id AND name = 'fail') THEN
                              RAISE EXCEPTION 'injected junction failure';
                            END IF;
                            RETURN NEW;
                          END;
                          $$ LANGUAGE plpgsql"])
        (jdbc/execute! (tx/datasource storage)
                       ["CREATE TRIGGER reject_new_field BEFORE INSERT ON fn_slot
                          FOR EACH ROW EXECUTE FUNCTION reject_new_field()"])
        (try
          (let [error (try
                        (record-type/apply-update-record-type-body
                          {:fn-id fn-id :fields [{:name "fail" :type "int"}]}
                          journal ctx)
                        nil
                        (catch Exception e e))]
            (is (some? error))
            (is (empty? @journal) "the graph on-throw must not undo SQL rollback")
            (record-type/apply-update-record-type-rollback journal error ctx)
            (is (= before (sp/query-entities storage :fn-slot {:fn-id fn-id})))
            (is (empty? (sp/query-entities storage :slot {:name "fail"}))))
          (finally
            (jdbc/execute! (tx/datasource storage)
                           ["DROP TRIGGER reject_new_field ON fn_slot"])
            (jdbc/execute! (tx/datasource storage)
                           ["DROP FUNCTION reject_new_field()"])))))))


(deftest record-update-reads-complete-competing-field-change
  (with-storage
    (fn [storage ctx]
      (let [created (record-type/apply-create-record-type-body
                      {:name "record" :fields [{:name "old" :type "int"}]}
                      (atom []) ctx)
            fn-id (parse-uuid (:id created))
            old (first (sp/query-entities storage :fn-slot {:fn-id fn-id}))
            replacement (setup/create-slot! storage "concurrent" :int)
            result (after-competing-write
                     storage
                     (fn [bound]
                       (sp/delete-entity bound :fn-slot (:id old))
                       (setup/attach-slot! bound fn-id (:id replacement) 0))
                     #(record-type/apply-update-record-type-body
                        {:fn-id fn-id :fields [{:name "requested" :type "int"}]}
                        (atom []) ctx))
            fields (sp/query-entities storage :fn-slot {:fn-id fn-id})]
        (is (:ok result))
        (is (= 1 (count fields)))
        (is (= "requested" (:name (sp/read-entity storage :slot (:slot-id (first fields))))))))))


(deftest sequence-rechecks-deleted-item-and-detached-slot
  (with-storage
    (fn [storage ctx]
      (let [{:keys [owner binding items]} (sequence-fixture storage)
            stale (first items)
            update-result (after-competing-write
                            storage
                            #(sp/delete-entity % :binding-list-item (:id stale))
                            #(sequence/apply-seq-update-core
                               {:item-id (:id stale) :body {:value 9}} stale ctx))]
        (is (= 404 (:http-status update-result)))
        (is (nil? (sp/read-entity storage :binding-list-item (:id stale))))
        (let [append-result (after-competing-write
                              storage
                              #(sp/update-entity % :fn (:id owner) {:parent-ids []})
                              #(sequence/apply-seq-append-core
                                 {:fn-id (:id owner) :body {:value 9}} binding ctx))]
          (is (= 409 (:http-status append-result)))
          (is (= 1 (count (sp/query-entities storage :binding-list-item
                                             {:binding-id (:id binding)})))))))))


(deftest sequence-move-rolls-back-the-first-position-write
  (with-storage
    (fn [storage ctx]
      (let [{:keys [binding items]} (sequence-fixture storage)
            [a b] items
            before (sp/query-entities storage :binding-list-item {:binding-id (:id binding)})]
        ;; Only b's move to 0 fails; a already moved to the temporary 2.
        ;; UUID is generated by storage, never supplied by a request.
        (jdbc/execute! (tx/datasource storage)
                       [(str "ALTER TABLE binding_list_item_version ADD CONSTRAINT fail_second_swap "
                             "CHECK (item_id <> '" (:id b) "'::uuid OR position <> 0)")])
        (try
          (is (thrown? Exception
                (sequence/apply-seq-move-core
                  {:item-id (:id a) :body {:direction "down"}} a ctx)))
          (is (= (set before)
                 (set (sp/query-entities storage :binding-list-item {:binding-id (:id binding)}))))
          (finally
            (jdbc/execute! (tx/datasource storage)
                           ["ALTER TABLE binding_list_item_version DROP CONSTRAINT fail_second_swap"])))))))


(deftest tightening-rechecks-the-current-effective-type
  (with-storage
    (fn [storage _ctx]
      (let [broad (sp/create-entity storage :fn
                                    {:name "broad" :constraint [:fn {} :int #{:io :db}]})
            narrow (sp/create-entity storage :fn
                                     {:name "narrow" :constraint [:fn {} :int #{}]})
            parent (setup/create-base-fn! storage "tighten-parent")
            slot (sp/create-entity storage :slot {:name "callback" :type-fn-id (:id broad)})
            _ (setup/attach-slot! storage (:id parent) (:id slot) 0)
            owner (setup/create-composed-fn! storage "tighten-owner" (:id parent))
            binding (sp/create-entity storage :binding {:fn-id (:id owner) :slot-id (:id slot)})
            result (after-competing-write
                     storage
                     #(sp/update-entity % :binding (:id binding) {:type-override-fn-id (:id narrow)})
                     #(tighten/tighten-fn-type-impl! storage (:id binding) {:effects ["io"]}))]
        (is (= 400 (:status result)))
        (is (= (:id narrow) (:type-override-fn-id (sp/read-entity storage :binding (:id binding)))))
        (is (empty? (filter :anonymous-hash (sp/query-entities storage :fn {}))))))))


(deftest tightening-rollback-removes-only-its-own-anonymous-type
  (doseq [shared? [false true]]
    (with-storage
      (fn [storage _ctx]
        (let [constraint [:fn {} :int #{:io}]
              hash-hex (records/digest-hex "SHA-1" (pr-str constraint))
              type-id (records/anonymous-fn-id hash-hex)
              broad (sp/create-entity storage :fn
                                      {:name "broad" :constraint [:fn {} :int #{:io :db}]})
              parent (setup/create-base-fn! storage "parent")
              slot (sp/create-entity storage :slot {:name "callback" :type-fn-id (:id broad)})
              _ (setup/attach-slot! storage (:id parent) (:id slot) 0)
              owner (setup/create-composed-fn! storage "owner" (:id parent))
              binding (sp/create-entity storage :binding {:fn-id (:id owner) :slot-id (:id slot)})]
          (when shared?
            (sp/create-entity storage :fn
                              {:id type-id :anonymous-hash hash-hex :constraint constraint}))
          ;; Reject the binding write after a missing anonymous type was created.
          (jdbc/execute! (tx/datasource storage)
                         ["ALTER TABLE binding_version ADD CONSTRAINT fail_tighten
                            CHECK (type_override_fn_id IS NULL)"])
          (try
            (is (thrown? Exception
                  (tighten/tighten-fn-type-impl! storage (:id binding) {:effects ["io"]})))
            (is (nil? (:type-override-fn-id (sp/read-entity storage :binding (:id binding)))))
            (is (= shared? (some? (sp/read-entity storage :fn type-id)))
                "rollback preserves a pre-existing deduplicated identity")
            (finally
              (jdbc/execute! (tx/datasource storage)
                             ["ALTER TABLE binding_version DROP CONSTRAINT fail_tighten"]))))))))
