(ns ^:integration graphden.packages.storage.queue-test
  "The Postgres queue primitives against a real database: a claim is
   exclusive (SKIP LOCKED — two takers never share a row), a visibility
   timeout releases a claim, ack deletes, nack retries with a delay and
   then dead-letters, and an empty take blocks on the NOTIFY bus until a
   publish wakes it."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.impls :as impls])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)))


(use-fixtures :once
  (setup/create-container-fixture)
  (impls/impls-fixture "storage" "queue"))


(defn- ctx-for
  [storage]
  {:storage storage})


(defn- publish!
  [ctx queue payload]
  ((impls/impl-of :queue-publish) {:queue queue :payload payload :delay-ms 0} ctx))


(defn- take!
  ([ctx queue n] (take! ctx queue n 30000 0))
  ([ctx queue n visibility-ms wait-ms]
   ((impls/impl-of :queue-take)
    {:queue queue :batch n :visibility-ms visibility-ms :wait-ms wait-ms} ctx)))


(deftest claim-ack-and-visibility-test
  (let [storage (setup/create-branch-versioned-test-storage)
        ctx (ctx-for storage)]
    (try
      (let [id (publish! ctx "q-basic" {:n 1})]
        (testing "publish returns the id; take claims it with attempts = 1"
          (let [[m :as batch] (take! ctx "q-basic" 10)]
            (is (= 1 (count batch)))
            (is (= id (:id m)))
            (is (= {:n 1} (:payload m)))
            (is (= 1 (:attempts m)))))
        (testing "while claimed, a second take sees nothing"
          (is (empty? (take! ctx "q-basic" 10))))
        (testing "after the visibility timeout the message is takeable again, attempts = 2"
          (sp/update-entity storage :queue-message id
                            {:locked-until (java.time.Instant/.minusSeconds (java.time.Instant/now) 1)})
          (let [[m] (take! ctx "q-basic" 10)]
            (is (= id (:id m)))
            (is (= 2 (:attempts m)))))
        (testing "ack deletes the row; a second ack is false"
          (is (true? ((impls/impl-of :queue-ack) {:message-id id} ctx)))
          (is (nil? (sp/read-entity storage :queue-message id)))
          (is (false? ((impls/impl-of :queue-ack) {:message-id id} ctx)))))
      (finally (sp/close storage)))))


(deftest nack-retries-then-dead-letters-test
  (let [storage (setup/create-branch-versioned-test-storage)
        ctx (ctx-for storage)
        nack! (fn [id error max-attempts]
                ((impls/impl-of :queue-nack)
                 {:message-id id :error error :retry-ms 0 :max-attempts max-attempts} ctx))]
    (try
      (let [id (publish! ctx "q-nack" {:n 2})]
        (take! ctx "q-nack" 1)
        (testing "under the attempt cap: released for retry, error kept"
          (is (= :retry (nack! id "boom" 3)))
          (let [row (sp/read-entity storage :queue-message id)]
            (is (= "pending" (:state row)))
            (is (nil? (:locked-until row)))
            (is (= "boom" (:error row))))
          (is (= [id] (mapv :id (take! ctx "q-nack" 1))) "retry-ms 0 → takeable at once"))
        (testing "at the cap: dead-lettered, never taken again"
          (is (= :dead (nack! id "boom again" 2)))
          (let [row (sp/read-entity storage :queue-message id)]
            (is (= "dead" (:state row)))
            (is (= "boom again" (:error row))))
          (is (empty? (take! ctx "q-nack" 10))))
        (testing "a delayed retry is not due yet"
          (let [id2 (publish! ctx "q-nack" {:n 3})]
            (take! ctx "q-nack" 1)
            ((impls/impl-of :queue-nack)
             {:message-id id2 :error nil :retry-ms 60000 :max-attempts 3} ctx)
            (is (empty? (take! ctx "q-nack" 10))))))
      (finally (sp/close storage)))))


(deftest concurrent-takers-never-share-a-message-test
  (let [storage (setup/create-branch-versioned-test-storage)
        ctx (ctx-for storage)]
    (try
      (dotimes [i 20] (publish! ctx "q-race" {:i i}))
      (let [claims (->> (repeatedly 4 #(future (mapv :id (take! ctx "q-race" 5))))
                        doall
                        (mapv deref))
            all (apply concat claims)]
        (is (= 20 (count all)))
        (is (= 20 (count (set all))) "every message claimed exactly once")
        (is (empty? (apply set/intersection (map set claims)))))
      (finally (sp/close storage)))))


(defn- pg-opts
  [storage]
  (let [^HikariDataSource pool (:pool storage)]
    {:jdbc-url (HikariDataSource/.getJdbcUrl pool)
     :username (HikariDataSource/.getUsername pool)
     :password (HikariDataSource/.getPassword pool)}))


(deftest empty-take-blocks-until-a-publish-wakes-it-test
  (let [storage (setup/create-branch-versioned-test-storage)
        base (loop [s storage] (if (:pool s) s (recur (or (:base-storage s) (:base s)))))
        listener (pg-notify/create-listener (pg-opts base) {:poll-timeout-ms 250})
        ctx {:storage storage
             :notify-listener listener
             :notify-emitter (pg-notify/make-emitter (:pool base))}]
    (try
      (testing "no publish → the wait times out and returns nothing"
        (let [t0 (System/currentTimeMillis)]
          (is (empty? (take! ctx "q-wait" 10 30000 400)))
          (is (>= (- (System/currentTimeMillis) t0) 350))))
      (testing "a publish during the wait wakes the taker well before the timeout"
        (let [t0 (System/currentTimeMillis)
              f (future (take! ctx "q-wait" 10 30000 8000))]
          (Thread/sleep 300)
          (publish! ctx "q-wait" {:woken true})
          (let [batch (deref f 6000 ::timeout)]
            (is (= [{:woken true}] (mapv :payload batch)))
            (is (< (- (System/currentTimeMillis) t0) 5000)))))
      (testing "a publish on ANOTHER queue does not wake it"
        (let [f (future (take! ctx "q-wait" 10 30000 1500))]
          (Thread/sleep 200)
          (publish! ctx "q-other" {:noise true})
          (is (empty? (deref f 4000 ::timeout)))))
      (finally
        (pg-notify/close-listener! listener)
        (sp/close storage)))))
