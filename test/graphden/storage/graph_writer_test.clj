(ns ^:integration graphden.storage.graph-writer-test
  "Real PostgreSQL writer-lock and root-transaction lifetime contracts."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as graph-schema]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as schema]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as helpers]
    [graphden.storage.tx :as tx]
    [next.jdbc :as jdbc]))


(def ^:dynamic *container* nil)


(use-fixtures :once (helpers/create-container-fixture #'*container*))
(use-fixtures :each (helpers/create-clean-db-fixture #'*container*))


(defrecord ScopedWriter
  [base org]

  writer/GraphWriterScope

  (writer-scope [_] org))


(defn- with-storage
  [f]
  (let [storage (pg/create-storage
                  (assoc (helpers/get-container-config *container*) :pool-size 6))
        schema (-> (malli/create-builder) graph-schema/extend-builder schema/build)]
    (try
      (sp/initialize storage schema)
      (f storage)
      (finally (sp/close storage)))))


(defn- exception-type
  [f]
  (try (f) :ok
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(defn- waiting-writers
  [storage]
  ;; Actual PG wait state, rather than a sleep guessing whether a future ran.
  (-> (jdbc/execute-one!
        (:pool storage)
        ["SELECT count(*) AS n FROM pg_locks l
           JOIN pg_database d ON d.oid = l.database
          WHERE l.locktype = 'advisory' AND NOT l.granted
            AND d.datname = current_database()"])
      vals first long))


(defn- await-writer
  [storage]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [waiting (waiting-writers storage)]
        (if (or (pos? waiting) (> (System/currentTimeMillis) deadline))
          waiting
          (do (Thread/sleep 10) (recur)))))))


(defn- write-fn!
  [storage name]
  (writer/call-with-write
    storage :fn
    (fn [bound]
      (sp/create-entity (or (:base bound) bound) :fn {:name name}))))


(deftest a-writer-rollback-leaves-no-row-test
  (with-storage
    (fn [storage]
      (let [scoped (->ScopedWriter storage "acme")]
        (is (= :test/rollback
               (exception-type
                 #(writer/call-with-write
                    scoped :graph
                    (fn [bound]
                      (sp/create-entity (:base bound) :fn {:name "rollback-me"})
                      (throw (ex-info "rollback" {:type :test/rollback})))))))
        (is (empty? (sp/query-entities storage :fn {:name "rollback-me"})))))))


(deftest private-writers-serialize-one-org-and-allow-another-org-test
  (with-storage
    (fn [storage]
      (let [entered (promise)
            release (promise)
            acme (->ScopedWriter storage "acme")
            beta (->ScopedWriter storage "beta")
            holding (future
                      (writer/call-with-write
                        acme :graph
                        (fn [bound]
                          (sp/create-entity (:base bound) :fn {:name "acme-first"})
                          (deliver entered true)
                          @release)))]
        (try
          (is (true? (deref entered 10000 :timeout)))
          (let [same-org (future (write-fn! acme "acme-second"))]
            (try
              (is (= 1 (await-writer storage)))
              (let [other-org (future (write-fn! beta "beta-parallel"))
                    result (deref other-org 10000 :timeout)]
                (is (= "beta-parallel" (:name result)))
                (is (= ["beta-parallel"]
                       (mapv :name (sp/query-entities storage :fn {:name "beta-parallel"})))))
              (is (empty? (sp/query-entities storage :fn {:name "acme-second"})))
              (deliver release true)
              (is (= "acme-second" (:name (deref same-org 10000 :timeout))))
              (finally
                (deliver release true)
                (future-cancel same-org))))
          (finally
            (deliver release true)
            (deref holding 10000 :timeout)
            (future-cancel holding)))))))


(defn- assert-exclusion
  [storage held waiting]
  (let [name (str "after-exclusion-" (random-uuid))
        entered (promise)
        release (promise)
        holding (future
                  (writer/call-with-write held :graph
                                          (fn [_] (deliver entered true) @release)))]
    (try
      (is (true? (deref entered 10000 :timeout)))
      (let [blocked (future (write-fn! waiting name))]
        (try
          (is (= 1 (await-writer storage)))
          (is (empty? (sp/query-entities storage :fn {:name name})))
          (deliver release true)
          (is (= name (:name (deref blocked 10000 :timeout))))
          (finally
            (deliver release true)
            (future-cancel blocked))))
      (finally
        (deliver release true)
        (deref holding 10000 :timeout)
        (future-cancel holding)))))


(deftest raw-public-and-private-writers-exclude-each-other-test
  (testing "raw writer waits for the private writer"
    (with-storage
      (fn [storage]
        (assert-exclusion storage (->ScopedWriter storage "acme") storage))))
  (testing "an unknown map cannot acquire private scope from its fields"
    (with-storage
      (fn [storage]
        (assert-exclusion storage {:base storage :org-id "acme"}
                          (->ScopedWriter storage "beta"))))))


(deftest writer-scope-lasts-for-the-outer-transaction-test
  (with-storage
    (fn [storage]
      (tx/in-transaction
        storage
        (fn [bound]
          (write-fn! (->ScopedWriter bound "acme") "first-write")
          (testing "returning from the first guard cannot permit an upgrade"
            (is (= :graph-write/scope-change
                   (exception-type #(write-fn! bound "raw-upgrade")))))
          (testing "a different private org is refused before another lock"
            (is (= :graph-write/scope-change
                   (exception-type #(write-fn! (->ScopedWriter bound "beta") "other-org")))))
          (testing "an unbound pool cannot borrow a second connection under this writer"
            (is (= :graph-write/transaction-mismatch
                   (exception-type #(write-fn! storage "second-connection")))))
          (write-fn! (->ScopedWriter bound "acme") "second-write")))
      (is (= #{"first-write" "second-write"}
             (set (map :name (sp/query-entities storage :fn {}))))))))


(deftest physical-delegation-does-not-transfer-authority-test
  (with-storage
    (fn [storage]
      (let [captured (atom nil)]
        (writer/call-with-write
          (->ScopedWriter storage "acme") :graph
          (fn [bound]
            (let [backend (:base bound)]
              (reset! captured bound)
              (is (identical? tx/*transaction-context* (tx/writer-context bound)))
              (write-fn! backend "physical-delegation")
              (testing "a known decorator must recheck its scope despite the token"
                (is (= :graph-write/scope-change
                       (exception-type #(write-fn! (assoc bound :org "beta") "changed-org"))))
                (is (= :graph-write/scope-change
                       (exception-type #(write-fn! (assoc bound :org nil) "changed-public")))))
              (with-open [other (jdbc/get-connection (:pool storage))]
                (let [forged (assoc backend :pool other)]
                  (is (= :graph-write/invalid-context
                         (exception-type #(write-fn! forged "wrong-connection")))))))))
        (testing "a committed context cannot be reused"
          (is (= :graph-write/invalid-context
                 (exception-type #(write-fn! @captured "closed-context")))))
        (is (= ["physical-delegation"]
               (mapv :name (sp/query-entities storage :fn {}))))))))


(deftest compound-operation-rejects-a-foreign-commit-boundary-test
  (with-storage
    (fn [storage]
      (jdbc/with-transaction [connection (:pool storage)]
                             (let [bound (tx/with-connection storage connection)]
                               (is (= :graph-write/commit-boundary-required
                                      (exception-type
                                        #(do (tx/assert-owns-commit! bound)
                                             (sp/create-entity bound :fn {:name "foreign-commit"})))))))
      (tx/in-transaction storage
                         (fn [bound]
                           (is (= :graph-write/commit-boundary-required
                                  (exception-type #(tx/assert-owns-commit! bound))))))
      (is (empty? (sp/query-entities storage :fn {}))))))


(deftest preview-authorization-is-closed-for-unknown-storage-test
  (is (= :authz/forbidden
         (exception-type #(writer/assert-write-authorized!
                            {:org-id "acme"} :fn {} (random-uuid))))))


(defrecord IdentityWriter
  [base id]

  writer/GraphWriterAdmission

  (writer-scope-for
    [_ _mutation]
    (:org-id (get (sp/read-entities base :fn [id]) id))))


(deftest ownership-is-rechecked-after-waiting-before-any-write-test
  (with-storage
    (fn [storage]
      (let [row (sp/create-entity storage :fn {:name "ownership-before-wait" :org-id "acme"})
            entered (promise)
            release (promise)
            applied? (atom false)
            holding (future
                      (writer/call-with-write
                        storage :graph
                        (fn [bound]
                          (deliver entered true)
                          (when-not (true? (deref release 10000 false))
                            (throw (ex-info "Ownership test timed out" {})))
                          (sp/update-entity bound :fn (:id row) {:org-id nil}))))]
        (try
          (is (true? (deref entered 10000 false)))
          (let [waiting (future
                          (exception-type
                            #(writer/call-with-write
                               (->IdentityWriter storage (:id row)) {:entity :fn :ids [(:id row)]}
                               (fn [_] (reset! applied? true)))))]
            (is (= 1 (await-writer storage)))
            (deliver release true)
            (is (not= :timeout (deref holding 10000 :timeout)))
            (is (= :graph-write/stale-scope (deref waiting 10000 :timeout)))
            (is (false? @applied?) "Stale admission never reaches the write callback"))
          (finally (deliver release true) (deref holding 10000 nil)))))))
