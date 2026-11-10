(ns ^:integration graphden.executor.source-recipe-trace-test
  (:require
    [clojure.datafy :as datafy]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.source-recipe-jdbc-rows :as jdbc-rows]
    [graphden.executor.source-recipe-trace :as trace]
    [graphden.executor.source-recipe-values :as values]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.source-certificate :as certificate]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.sql
      Timestamp)
    (org.postgresql.util
      PGobject)))


(use-fixtures :once (setup/container-fixture))
(def ^:dynamic *connection* nil)
(def ^:dynamic *pool* nil)
(def ^:dynamic *generation* nil)
(def ^:private row-id #uuid "00000000-0000-0000-0000-000000000101")
(def ^:private row-query {:select [:*] :from [:trace_row] :where [:= :id row-id]})


(defn- fixture
  [f]
  (let [storage (setup/create-test-storage) pool (:pool storage)]
    (try
      (jdbc/execute! pool ["CREATE TABLE trace_row(id uuid PRIMARY KEY, org_id text, body jsonb, stamp timestamp)"])
      (jdbc/execute! pool ["INSERT INTO trace_row VALUES(?,'first','{\"value\":1}','2026-01-01 00:00:00.123456')" row-id])
      (jdbc/execute! pool ["CREATE TABLE trace_permission(allowed boolean)"])
      (jdbc/execute! pool ["INSERT INTO trace_permission VALUES(true)"])
      (with-open [connection (jdbc/get-connection pool)]
        (binding [*connection* connection *pool* pool *generation* (Object.)] (f)))
      (finally (sp/close storage)))))


(use-fixtures :each fixture)


(defn- snapshot
  [f]
  (jdbc/with-transaction [connection *connection* {:read-only true :isolation :repeatable-read}]
                         (certificate/call-with-guard
                           connection #{:trace_row} (constantly nil)
                           (fn [guard]
                             (f {:connection connection :guard guard :check! (constantly nil)
                                 :generation *generation* :authority-fields [:id :org-id]
                                 :max-bytes (* 64 1024 1024)})))))


(defn- read-rows
  []
  (pg/exec-read! *connection* row-query {:quoted true}))


(defn- authorize!
  [row & _]
  (when-not (:allowed (pg/exec-one! *connection* ["SELECT allowed FROM trace_permission"]))
    (throw (ex-info "Access revoked" {:type :authz/forbidden})))
  {:authorized-id (:id row)})


(defn- source-pass
  [authorize]
  (let [row (first (read-rows))
        decision (authorize {:id (:id row) :org-id (:org_id row)} {:execute? true})]
    {:row row :decision decision}))


(defn- recorded
  []
  (snapshot #(trace/record % source-pass authorize!)))


(defn- replayed
  [recipe]
  (snapshot #(trace/replay % recipe source-pass authorize!)))


(defn- observed
  [f]
  (let [queries (atom [])
        original {:execute! jdbc/execute! :execute-one! jdbc/execute-one!}
        hooks (into {} (map (fn [[kind execute]]
                              [kind (fn [connection query opts]
                                      (swap! queries conj (first query))
                                      (execute connection query opts))])) original)
        answer (binding [pg/*jdbc-override* hooks] (f))]
    {:value answer :queries @queries}))


(defn- restart-reason
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error
         (when-not (= ::trace/restart (:type (ex-data error))) (throw error))
         (:reason (ex-data error)))))


(deftest replay-skips-source-sql-but-runs-fresh-authorization-and-decoding-test
  (let [{:keys [recipe value charge-bytes]} (recorded)
        replay (observed #(replayed recipe))]
    (is (some? recipe))
    (is (< (* 1024 1024) charge-bytes (* 8 1024 1024)))
    (is (= value (:value replay)))
    (is (= {:authorized-id row-id} (:decision (:value replay))) "Authorizer's live return value is preserved")
    (is (not-any? #(str/starts-with? % "SELECT * FROM \"trace_row\"") (:queries replay)))
    (is (= 2 (count (filter #(= "SELECT allowed FROM trace_permission" %) (:queries replay)))))
    (PGobject/.setValue ^PGobject (get-in replay [:value :row :body]) "{\"changed\":true}")
    (Timestamp/.setNanos ^Timestamp (get-in replay [:value :row :stamp]) 0)
    (let [next-row (:row (replayed recipe))]
      (is (= "{\"value\": 1}" (PGobject/.getValue ^PGobject (:body next-row))))
      (is (= 123456000 (Timestamp/.getNanos ^Timestamp (:stamp next-row)))))))


(deftest source-mutation-and-revocation-prevent-any-cached-payload-test
  (let [recipe (:recipe (recorded)) invoked (atom 0)
        run (fn [auth] (swap! invoked inc) (source-pass auth))]
    (jdbc/execute! *pool* ["UPDATE trace_permission SET allowed=false"])
    (is (= :authorization-changed
           (restart-reason #(snapshot (fn [scope] (trace/replay scope recipe run authorize!))))))
    (is (zero? @invoked))
    (jdbc/execute! *pool* ["UPDATE trace_permission SET allowed=true"])
    (jdbc/execute! *pool* ["UPDATE trace_row SET body='{\"value\":2}' WHERE id=?" row-id])
    (is (= :source-changed
           (restart-reason #(snapshot (fn [scope] (trace/replay scope recipe run authorize!))))))
    (is (zero? @invoked))
    (is (= "{\"value\": 2}" (PGobject/.getValue ^PGobject (get-in (recorded) [:value :row :body]))))))


(deftest changed-read-restarts-the-whole-pass-without-mixed-payloads-test
  (let [recipe (:recipe (recorded))
        run (fn [auth]
              (source-pass auth)
              (pg/exec-read! *connection* (assoc row-query :where [:= :org_id "first"]) {:quoted true}))
        attempt (observed #(restart-reason (fn [] (snapshot (fn [scope] (trace/replay scope recipe run authorize!))))))]
    (is (= :unrecorded-read (:value attempt)))
    (is (not-any? #(str/starts-with? % "SELECT * FROM \"trace_row\"") (:queries attempt)))
    (is (.getAutoCommit ^java.sql.Connection *connection*) "Failed replay RR has unwound before ordinary retry")
    (is (= 1 (count (:value (snapshot #(trace/record % run authorize!))))))))


(deftest unknown-read-and-foreign-connection-poison-the-complete-record-test
  (doseq [extra [(fn [] (pg/exec! *connection* ["SELECT 1 AS unplanned"]))
                 (fn [] (pg/exec-read! *pool* row-query {:quoted true}))
                 (fn [] (pg/exec-read! *connection* row-query {:quoted true} {}))]]
    (let [answer (snapshot #(trace/record % (fn [auth] (source-pass auth) (extra) :ordinary) authorize!))]
      (is (= :ordinary (:value answer)))
      (is (nil? (:recipe answer)))
      (is (contains? #{:unplanned-read :foreign-read-connection :jdbc-options} (:reason answer))))))


(deftest arity-options-and-policy-generation-remain-part-of-replay-admission-test
  (let [recipe (:recipe (recorded))]
    (doseq [run [(fn [_] (pg/exec-read-one! *connection* row-query {:quoted true}))
                 (fn [_] (pg/exec-read! *connection* row-query {:quoted true} (pg/query-opts {:timeout 10})))]]
      (is (= :unrecorded-read (restart-reason #(snapshot (fn [scope] (trace/replay scope recipe run authorize!)))))))
    (let [attempt (snapshot (fn [scope]
                              (observed #(restart-reason
                                           (fn [] (trace/replay (assoc scope :generation (Object.)) recipe source-pass authorize!))))))]
      (is (= :policy-generation-changed (:value attempt)))
      (is (empty? (:queries attempt))))))


(deftest single-and-empty-reads-retain-their-public-result-shapes-test
  (doseq [[ast expected] [[row-query row-id]
                          [(assoc row-query :where [:= :id (random-uuid)]) nil]]]
    (let [run (fn [_] (pg/exec-read-one! *connection* ast {:quoted true}))
          answer (snapshot #(trace/record % run authorize!))
          replay (snapshot #(observed (fn [] (trace/replay % (:recipe answer) run authorize!))))]
      (is (= expected (:id (:value answer))))
      (is (= (:value answer) (:value replay)))
      (is (not-any? #(str/starts-with? % "SELECT * FROM \"trace_row\"") (:queries replay))))))


(deftest single-read-of-multiple-unordered-rows-is-not-replayable-test
  (jdbc/execute! *connection* ["INSERT INTO trace_row(id,org_id) VALUES(?,'second')" (random-uuid)])
  (let [result (snapshot #(trace/record %
                                        (fn [_] (pg/exec-read-one! *connection* (dissoc row-query :where) {:quoted true}))
                                        authorize!))]
    (is (uuid? (get-in result [:value :id])))
    (is (nil? (:recipe result)))
    (is (= :clipped-result (:reason result)))))


(deftest copied-parameters-are-executed-after-preparation-test
  (let [stamp (Timestamp/valueOf "2026-01-01 00:00:00.123456")
        ast (assoc row-query :where [:= :stamp stamp])
        answer (binding [pg/*jdbc-override*
                         {:execute! (fn [connection query opts]
                                      (when (str/starts-with? (first query) "SELECT * FROM \"trace_row\"")
                                        (.setTime stamp 0))
                                      (jdbc/execute! connection query opts))}]
                 (snapshot #(trace/record % (fn [_] (pg/exec-read! *connection* ast {:quoted true})) authorize!)))]
    (is (zero? (.getTime stamp)))
    (is (= [row-id] (mapv :id (:value answer))))
    (is (some? (:recipe answer)))
    (is (= [row-id]
           (mapv :id
                 (snapshot #(trace/replay % (:recipe answer)
                                          (fn [_]
                                            (pg/exec-read! *connection*
                                                           (assoc row-query :where [:= :stamp (Timestamp/valueOf "2026-01-01 00:00:00.123456")])
                                                           {:quoted true})) authorize!)))))))


(deftest budget-and-late-bound-callback-never-publish-partial-records-test
  (let [late (atom nil)
        saved (snapshot #(trace/record %
                                       (fn [auth]
                                         (reset! late {:read (bound-fn [] (read-rows))
                                                       :authorize (fn [] (auth {:id row-id}))})
                                         (source-pass auth)) authorize!))]
    (is (some? (:recipe saved)))
    (is (= :source-scope-expired (restart-reason (:read @late))))
    (is (= :source-scope-expired (restart-reason (:authorize @late)))))
  (jdbc/execute! *pool* ["UPDATE trace_row SET body=jsonb_build_object('huge',repeat('x',400000))"])
  (let [answer (snapshot #(trace/record (assoc % :max-bytes (+ trace/preparation-scratch-bytes 1100000)) source-pass authorize!))]
    (is (= row-id (get-in answer [:value :row :id])))
    (is (nil? (:recipe answer)))
    (is (= :byte-limit (:reason answer)))))


(deftest trace-scoped-grouping-cannot-be-reactivated-by-nested-cache-test
  (snapshot
    (fn [scope]
      (let [answer (trace/record
                     scope
                     (fn [auth]
                       (grouped/call-with-snapshot-cache
                         *connection*
                         #(is (nil? (grouped/snapshot-cache *connection*))))
                       (source-pass auth)) authorize!)]
        (is (some? (:recipe answer)))
        (grouped/call-with-snapshot-cache
          *connection* #(is (some? (grouped/snapshot-cache *connection*))))))))


(deftest jdbc-navigation-is-rebound-to-current-connection-and-options-test
  (with-open [other (jdbc/get-connection *pool*)]
    (doseq [[connection label] [[*connection* "old"] [other "current"]]]
      (jdbc/execute! connection ["CREATE TEMP TABLE parent(id uuid PRIMARY KEY, label text)"])
      (jdbc/execute! connection ["INSERT INTO parent VALUES(?,?)" row-id label]))
    (let [opts {:builder-fn rs/as-unqualified-lower-maps}
          original (first (jdbc/execute! *connection* ["SELECT ?::uuid AS parent_id" row-id] opts))
          frozen (jdbc-rows/freeze [original] false 65536)
          row (first (jdbc-rows/thaw frozen other opts))
          qualified (first (jdbc-rows/thaw frozen other {:builder-fn rs/as-maps}))]
      (is (= "old" (:label (datafy/nav (datafy/datafy original) :parent_id row-id))))
      (is (= "current" (:label (datafy/nav (datafy/datafy row) :parent_id row-id))))
      (is (= "current" (:parent/label (datafy/nav (datafy/datafy qualified) :parent_id row-id))))
      (is (= (set (keys (meta original))) (set (keys (meta row)))))
      (is (not-any? true? (map identical? (vals (meta original)) (vals (meta row))))))))


(deftest unknown-and-nested-metadata-never-become-cached-jdbc-rows-test
  (let [called (atom 0)
        callback (fn [_] (swap! called inc))]
    (doseq [row [(with-meta {:id row-id} {'clojure.core.protocols/datafy callback})
                 {:value (with-meta {:id row-id} {:unknown callback})}]]
      (is (= ::values/ineligible
             (try (jdbc-rows/freeze [row] false 65536) nil
                  (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))))
    (is (zero? @called))))


(deftest scope-cannot-pair-one-guards-witness-with-another-connections-payload-test
  (let [recipe (:recipe (recorded)) reached (atom 0)]
    (with-open [other (jdbc/get-connection *pool*)]
      (snapshot
        (fn [scope]
          (doseq [attempt [(fn [run] (trace/record (assoc scope :connection other) run authorize!))
                           (fn [run] (trace/replay (assoc scope :connection other) recipe run authorize!))]]
            (is (= {:type ::certificate/miss :reason :guard-connection-mismatch}
                   (try (attempt (fn [auth] (swap! reached inc) (source-pass auth))) nil
                        (catch clojure.lang.ExceptionInfo error (ex-data error)))))))))
    (is (zero? @reached))))


(deftest nested-traces-refuse-before-inner-reads-and-poison-outer-record-test
  (let [inner-calls (atom 0)
        inner (fn [_] (swap! inner-calls inc) (pg/exec! *connection* ["SELECT 1"]))
        recipe (:recipe (recorded))]
    (snapshot
      (fn [scope]
        (doseq [nested [(fn [] (trace/record scope inner authorize!))
                        (fn [] (trace/replay scope recipe inner authorize!))]]
          (let [result (trace/record scope
                                     (fn [auth]
                                       (source-pass auth)
                                       (is (= :nested-source-trace (restart-reason nested)))
                                       :ordinary)
                                     authorize!)]
            (is (= :ordinary (:value result)))
            (is (= :nested-source-trace (:reason result)))
            (is (nil? (:recipe result)))))
        (is (= :source-scope-expired
               (restart-reason
                 (fn []
                   (trace/replay scope recipe
                                 (fn [_]
                                   (is (= :nested-source-trace
                                          (restart-reason (fn [] (trace/record scope inner authorize!)))))
                                   :caught-inner-error)
                                 authorize!)))))
        (let [result (trace/record scope source-pass
                                   (fn [& args]
                                     (is (= :nested-source-trace
                                            (restart-reason (fn [] (trace/record scope inner authorize!)))))
                                     (apply authorize! args)))]
          (is (nil? (:recipe result)))
          (is (= :nested-source-trace (:reason result))))))
    (is (zero? @inner-calls))))
