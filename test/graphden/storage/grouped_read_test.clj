(ns graphden.storage.grouped-read-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.lang.reflect
      InvocationHandler
      Proxy)
    (java.sql
      Connection)))


(defn- scalar-storage
  [calls]
  (reify sp/StorageCRUD
    (create-entity [_ _ _] (throw (UnsupportedOperationException. "read-only fixture")))

    (update-entity [_ _ _ _] (throw (UnsupportedOperationException. "read-only fixture")))

    (delete-entity [_ _ _] (throw (UnsupportedOperationException. "read-only fixture")))

    (read-entity
      [_ entity id]
      (swap! calls conj [:read entity id])
      (when (odd? id) {:id id :visible true}))

    (query-entities
      [_ entity where]
      (swap! calls conj [:query entity where])
      [{:id 1}])

    (query-entities
      [_ entity where opts]
      (swap! calls conj [:query entity where opts])
      [{:id 1}])

    (query-latest-per-group
      [_ entity where cols]
      (swap! calls conj [:latest entity where cols])
      [{:id 1}])


    sp/StorageBoundedQuery

    (query-bounded-entities [_ _ _ _] (throw (UnsupportedOperationException. "unexpected bounded read")))

    (query-identity-candidates
      [_ _ _ _ limit]
      (swap! calls conj :candidate)
      (if (zero? limit)
        (throw (ex-info "overflow" {:type :storage-error/candidate-limit}))
        (with-meta [{:id 1}] {::sp/candidate-count 3})))))


(deftest unknown-wrapper-preserves-its-scalar-visibility-and-result-shapes
  (let [calls (atom [])
        storage (scalar-storage calls)
        results (grouped/read-operations
                  storage [{:op :read-ids :entity :fn :ids [1 2 3]}
                           {:op :query :entity :fn :where {:id [1]} :opts {:limit 1}}
                           {:op :latest :entity :fn-version :where {:fn-id [1]}
                            :group-cols [:fn-id]}])]
    (is (= [{1 {:id 1 :visible true} 3 {:id 3 :visible true}}
            [{:id 1}] [{:id 1}]] results))
    (is (= [[:read :fn 1] [:read :fn 2] [:read :fn 3]
            [:query :fn {:id [1]} {:limit 1}]
            [:latest :fn-version {:fn-id [1]} [:fn-id]]] @calls))))


(deftest grouping-is-finite-and-candidates-remain-sequential-barriers
  (let [calls (atom [])
        scalar (scalar-storage calls)
        storage (reify sp/StorageGroupedRead
                  (read-operations
                    [_ requests]
                    (swap! calls conj [:group (count requests)])
                    (mapv :where requests))


                  sp/StorageBoundedQuery

                  (query-bounded-entities [_ e w n] (sp/query-bounded-entities scalar e w n))

                  (query-identity-candidates
                    [_ e w v n]
                    (sp/query-identity-candidates scalar e w v n)))
        queries (mapv #(hash-map :op :query :entity :fn :where {:id [%]}) (range 17))
        candidate {:op :candidates :entity :binding :where {:fn-id [1]} :max-candidates 3}]
    (is (= (mapv :where queries) (grouped/read-operations storage queries)))
    (is (= [[:group 8] [:group 8] [:group 1]] @calls))
    (reset! calls [])
    (let [result (grouped/read-operations storage [candidate (first queries)])]
      (is (= 3 (::sp/candidate-count (meta (first result)))))
      (is (= [:candidate [:group 1]] @calls)))
    (reset! calls [])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow"
          (grouped/read-operations storage
                                   [(assoc candidate :max-candidates 0)
                                    (first queries)])))
    (is (= [:candidate] @calls) "No later payload read after candidate overflow")))


(deftest malformed-group-result-is-not-silently-zipped
  (let [storage (reify sp/StorageGroupedRead
                  (read-operations [_ _] []))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"count does not match"
          (grouped/read-operations storage [{:op :read-ids :entity :fn :ids [1]}])))))


(defn- connection
  [state calls]
  (Proxy/newProxyInstance
    (.getClassLoader Connection) (into-array Class [Connection])
    (reify InvocationHandler
      (invoke
        [_ _ method _]
        (let [method-name (.getName method)]
          (swap! calls conj method-name)
          (case method-name
            "getAutoCommit" (:auto-commit @state)
            "isReadOnly" (:read-only @state)
            "isClosed" (:closed @state)
            "getTransactionIsolation" (int (:isolation @state))
            (throw (UnsupportedOperationException. method-name))))))))


(deftest schema-cache-is-connection-and-transaction-lifetime-local
  (let [state (atom {:auto-commit false :read-only true :closed false
                     :isolation Connection/TRANSACTION_REPEATABLE_READ})
        calls (atom [])
        conn (connection state calls)
        other (connection state (atom []))
        retained (atom nil)
        delayed (atom nil)]
    (grouped/call-with-snapshot-cache
      conn
      #(let [cache (grouped/snapshot-cache conn)]
         (reset! retained cache)
         (reset! delayed (bound-fn [] (grouped/snapshot-cache conn)))
         (is (identical? cache (grouped/snapshot-cache conn)))
         (is (nil? (grouped/snapshot-cache other)))
         (is (= {} @(:shapes cache)))))
    (is (= 1 (count (filter #{"getTransactionIsolation"} @calls)))
        "PgJDBC isolation inspection is a SQL exchange, not a per-group getter")
    (is (false? @(:active? @retained)))
    (is (nil? (@delayed)) "A conveyed binding cannot retain an expired transaction")
    (testing "Unknown or weaker transaction context stays scalar"
      (swap! state assoc :isolation Connection/TRANSACTION_READ_COMMITTED)
      (is (nil? (grouped/call-with-snapshot-cache conn #(grouped/snapshot-cache conn))))
      (is (nil? (grouped/call-with-snapshot-cache :not-a-connection
                                                  #(grouped/snapshot-cache conn)))))))
