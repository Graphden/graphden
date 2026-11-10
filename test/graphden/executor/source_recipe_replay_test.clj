(ns ^:integration graphden.executor.source-recipe-replay-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.browser-source :as source]
    [graphden.executor.source-recipe-replay :as replay]
    [graphden.executor.source-recipe-trace :as trace]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.seams :as seams]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection
      SQLException)
    (org.postgresql
      PGConnection)))


(use-fixtures :once (setup/create-container-fixture))
(def ^:dynamic *ctx* nil)
(def ^:dynamic *slot* nil)


(defrecord UnknownStorage
  [base-storage]

  sp/StorageCRUD

  (create-entity [_ entity data] (sp/create-entity base-storage entity data))


  (read-entity [_ entity id] (sp/read-entity base-storage entity id))


  (update-entity [_ entity id data] (sp/update-entity base-storage entity id data))


  (delete-entity [_ entity id] (sp/delete-entity base-storage entity id))


  (query-entities [_ entity where] (sp/query-entities base-storage entity where))


  (query-entities [_ entity where opts] (sp/query-entities base-storage entity where opts))


  (query-latest-per-group [_ entity where cols] (sp/query-latest-per-group base-storage entity where cols)))


(defn- fixture
  [f]
  (let [storage (setup/create-versioned-test-storage 8)
        controller (replay/create-controller
                     (tx/datasource storage)
                     {:pool-mode :direct :max-bytes (* 256 1024 1024)
                      :max-capture-bytes (* 64 1024 1024) :max-entries 32 :max-pending 16
                      :max-families 16 :max-leases 16 :max-ttl-ms 60000})
        slot (sp/create-entity storage :slot {:name "original" :type-fn-id (records/primitive-fn-id :any)})]
    (try
      (tenancy/install-org-cap-fn! nil)
      (tenancy/install-browser-source-policy! nil)
      (binding [*ctx* {:storage storage :source-replay controller} *slot* (:id slot)] (f))
      (finally (replay/retire-controller! controller) (sp/close storage)))))


(use-fixtures :each seams/isolated-seams-fixture fixture)


(defn- capture-slot
  [storage authorize! id]
  (replay/capture storage authorize! {:root id}
                  (fn [fresh-storage fresh-authorize!]
                    (let [row (sp/read-entity fresh-storage :slot id)]
                      (fresh-authorize! {:id (:id row)} {:execute? true})
                      row))))


(defn- request
  ([] (request true))
  ([ok?]
   (replay/call-with-operation *ctx*
                               #(source/with-snapshot (:storage *ctx*)
                                                      (fn [storage authorize!]
                                                        {:ok ok? :value (capture-slot storage authorize! *slot*)})))))


(defn- observed
  [f]
  (let [source-reads (atom 0) guards (atom 0)
        value (binding [pg/*jdbc-override*
                        {:execute! (fn [connection query opts]
                                     (when (str/starts-with? (first query) "SELECT * FROM \"slot\"")
                                       (swap! source-reads inc))
                                     (when (str/starts-with? (first query) "LOCK TABLE ") (swap! guards inc))
                                     (jdbc/execute! connection query opts))
                         :execute-one! (fn [connection query opts]
                                         (when (str/starts-with? (first query) "SELECT * FROM \"slot\"")
                                           (swap! source-reads inc))
                                         (jdbc/execute-one! connection query opts))}]
                (f))]
    {:value value :source-reads @source-reads :guards @guards}))


(deftest one-recipe-replays-through-eight-distinct-pool-handles
  (let [pool (tx/datasource (:storage *ctx*))
        held (atom (vec (repeatedly 8 #(jdbc/get-connection pool))))
        identities (mapv #(Connection/.unwrap % PGConnection) @held)
        samples (atom [])]
    (try
      (is (= 8 (count (set identities))))
      (dotimes [i 8]
        (Connection/.close (nth @held i))
        (swap! samples conj (observed request))
        (let [replacement (jdbc/get-connection pool)]
          (is (identical? (nth identities i) (Connection/.unwrap replacement PGConnection)))
          (swap! held assoc i replacement)))
      (is (= (repeat 8 "original") (map #(get-in % [:value :value :name]) @samples)))
      (is (= [1 0 0 0 0 0 0 0] (mapv :source-reads @samples)))
      (is (= (repeat 8 1) (map :guards @samples)))
      (is (= {:entries 1 :leases 0 :pending 0}
             (select-keys (replay/stats (:source-replay *ctx*)) [:entries :leases :pending])))
      (finally (doseq [connection @held] (Connection/.close connection))))))


(deftest source-change-fallback-uses-fresh-slot-cache-and-new-transaction
  (let [storage (:storage *ctx*) pool (tx/datasource storage)]
    (is (= "original" (:name (sp/read-entity storage :slot *slot*))))
    (is (= "original" (get-in (request) [:value :name])))
    (jdbc/execute! pool ["UPDATE slot SET name='changed' WHERE id=?" *slot*])
    (is (= "original" (:name (sp/read-entity storage :slot *slot*))) "The shared storage cache is still warm")
    (let [sample (observed request)]
      (is (= "changed" (get-in sample [:value :value :name])))
      (is (= 1 (:source-reads sample)) "A fresh ordinary pass follows the failed certificate")
      (is (= 1 (:guards sample))))
    (is (zero? (:pending (replay/stats (:source-replay *ctx*)))))
    (is (zero? (:entries (replay/stats (:source-replay *ctx*)))))
    (is (= 1 (:source-reads (observed request))) "Next request records the changed source")
    (is (zero? (:source-reads (observed request))) "The replacement is reusable without waiting for TTL")))


(deftest failed-export-never-publishes-and-fresh-policy-still-revokes-replay
  (let [allowed? (atom true) calls (atom 0)]
    (tenancy/install-browser-source-policy!
      (fn [_]
        (fn [_ & _]
          (swap! calls inc)
          (when-not @allowed? (throw (ex-info "Source denied" {:type :authz/forbidden})))))
      {:authority-fields [:id]})
    (is (false? (:ok (request false))))
    (is (= {:entries 0 :pending 0 :charged-bytes 0}
           (select-keys (replay/stats (:source-replay *ctx*)) [:entries :pending :charged-bytes])))
    (is (true? (:ok (request))))
    (reset! calls 0)
    (is (zero? (:source-reads (observed request))))
    (is (= 2 @calls) "Preflight and the live collector each authorize")
    (reset! allowed? false)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Source denied" (request)))
    (is (= {:leases 0 :pending 0}
           (select-keys (replay/stats (:source-replay *ctx*)) [:leases :pending])))))


(deftest ordinary-default-and-unknown-datasource-mode-do-not-enable-replay
  (let [pool (tx/datasource (:storage *ctx*))]
    (is (nil? (replay/create-controller pool {:pool-mode :transaction})))
    (binding [*ctx* (dissoc *ctx* :source-replay)]
      (let [sample (observed request)]
        (is (= "original" (get-in sample [:value :value :name])))
        (is (zero? (:guards sample)))))
    (binding [*ctx* (update *ctx* :storage ->UnknownStorage)]
      (let [sample (observed request)]
        (is (= "original" (get-in sample [:value :value :name])))
        (is (zero? (:guards sample)))))
    (is (= {:entries 0 :pending 0}
           (select-keys (replay/stats (:source-replay *ctx*)) [:entries :pending])))))


(deftest actual-consumer-sql-failure-is-not-retried-or-published
  (let [calls (atom 0)
        state (try
                (replay/call-with-operation *ctx*
                                            #(source/with-snapshot (:storage *ctx*)
                                                                   (fn [storage authorize!]
                                                                     (swap! calls inc)
                                                                     (capture-slot storage authorize! *slot*)
                                                                     (jdbc/execute! (tx/datasource storage) ["SELECT 1/0"])
                                                                     {:ok true})))
                nil
                (catch SQLException error (SQLException/.getSQLState error)))]
    (is (= "22012" state))
    (is (= 1 @calls))
    (is (= {:entries 0 :pending 0 :charged-bytes 0}
           (select-keys (replay/stats (:source-replay *ctx*)) [:entries :pending :charged-bytes])))))


(deftest target-error-keywords-are-not-internal-restart-authority
  (doseq [error-type [::trace/restart ::replay/unavailable]]
    (let [calls (atom 0)
          failure (ex-info "target failure" {:type error-type :reason :source-changed})
          result (try
                   (replay/call-with-operation *ctx*
                                               #(source/with-snapshot (:storage *ctx*)
                                                                      (fn [_ _] (swap! calls inc) (throw failure))))
                   nil
                   (catch clojure.lang.ExceptionInfo error error))]
      (is (identical? failure result))
      (is (= 1 @calls)))))


(deftest guard-sql-failure-restarts-before-entering-consumer
  (let [guards (atom 0)
        result (binding [pg/*jdbc-override*
                         {:execute! (fn [connection query opts]
                                      (if (str/starts-with? (first query) "LOCK TABLE ")
                                        (do (swap! guards inc) (jdbc/execute! connection ["SELECT 1/0"] opts))
                                        (jdbc/execute! connection query opts)))}]
                 (request))]
    (is (= "original" (get-in result [:value :name])) "Ordinary read uses a NEW non-aborted transaction")
    (is (= 1 @guards))
    (is (= {:entries 0 :pending 0 :charged-bytes 0}
           (select-keys (replay/stats (:source-replay *ctx*)) [:entries :pending :charged-bytes])))))


(deftest later-replay-miss-discards-earlier-staged-source-from-the-aborted-snapshot
  (request)
  (let [storage (:storage *ctx*)
        other (sp/create-entity storage :slot {:name "second" :type-fn-id (records/primitive-fn-id :any)})
        attempts (atom 0)]
    (jdbc/execute! (tx/datasource storage) ["UPDATE slot SET name='changed' WHERE id=?" *slot*])
    (let [result (replay/call-with-operation *ctx*
                                             #(source/with-snapshot storage
                                                                    (fn [fresh-storage authorize!]
                                                                      (swap! attempts inc)
                                                                      {:ok true :names [(:name (capture-slot fresh-storage authorize! (:id other)))
                                                                                        (:name (capture-slot fresh-storage authorize! *slot*))]})))]
      (is (= ["second" "changed"] (:names result)))
      (is (= 2 @attempts))
      (is (= {:entries 0 :pending 0 :charged-bytes 0}
             (select-keys (replay/stats (:source-replay *ctx*)) [:entries :pending :charged-bytes]))))))


(deftest fatal-consumer-error-discards-staged-source-without-retry
  (let [failure (AssertionError. "fatal target")
        calls (atom 0)
        result (try
                 (replay/call-with-operation *ctx*
                                             #(source/with-snapshot (:storage *ctx*)
                                                                    (fn [storage authorize!]
                                                                      (swap! calls inc)
                                                                      (capture-slot storage authorize! *slot*)
                                                                      (throw failure))))
                 nil
                 (catch AssertionError error error))]
    (is (identical? failure result))
    (is (= 1 @calls))
    (is (= {:entries 0 :pending 0 :charged-bytes 0}
           (select-keys (replay/stats (:source-replay *ctx*)) [:entries :pending :charged-bytes])))))
