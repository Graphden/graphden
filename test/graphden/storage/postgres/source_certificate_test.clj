(ns ^:integration graphden.storage.postgres.source-certificate-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.storage.postgres.source-certificate :as certificate]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      SQLException
      Timestamp)))


(use-fixtures :once (setup/container-fixture))

(def ^:dynamic *connection* nil)
(def ^:dynamic *pool* nil)
(def ^:private locked #{:certificate_row :certificate_edge})
(def ^:private all-rows {:select [:*] :from [:certificate_row]})


(defn- fixture
  [f]
  (let [storage (setup/create-test-storage) pool (:pool storage)]
    (try
      (jdbc/execute! pool ["CREATE TABLE certificate_row(id bigint PRIMARY KEY, owner_id bigint, value text, stamp timestamp)"])
      (jdbc/execute! pool ["CREATE TABLE certificate_edge(id bigint PRIMARY KEY, owner_id bigint)"])
      (jdbc/execute! pool ["INSERT INTO certificate_row VALUES(1,10,'original','2026-01-01'),(2,20,'unrelated','2026-01-02')"])
      (with-open [connection (jdbc/get-connection pool)]
        (binding [*pool* pool *connection* connection] (f)))
      (finally (sp/close storage)))))


(use-fixtures :each fixture)


(defn- snapshot
  ([f] (snapshot *connection* (constantly nil) f))
  ([connection check! f]
   (jdbc/with-transaction [tx connection {:isolation :repeatable-read :read-only true}]
                          (certificate/call-with-guard tx locked check! #(f tx %)))))


(defn- prepare-and-read
  [connection guard ast]
  (let [query (sql/format ast {:quoted true})
        operation (certificate/prepare-operation guard ast query)]
    (pg/exec! connection query)
    operation))


(defn- capture
  [asts]
  (snapshot (fn [connection guard]
              (certificate/capture guard (mapv #(prepare-and-read connection guard %) asts)))))


(defn- unchanged?
  [captured]
  (snapshot (fn [_ guard] (certificate/unchanged? guard captured))))


(defn- observed
  [f]
  (let [queries (atom [])
        result (binding [pg/*jdbc-override*
                         {:execute! (fn [connection query opts]
                                      (swap! queries conj query)
                                      (jdbc/execute! connection query opts))}]
                 (f))]
    {:result result :queries @queries}))


(defn- miss-reason
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error
         (when-not (= ::certificate/miss (:type (ex-data error))) (throw error))
         (:reason (ex-data error)))))


(deftest fresh-witnesses-preserve-complete-predicates-and-empty-reads-test
  (let [captured (capture [(assoc all-rows :where [:= :id 1])
                           (assoc all-rows :where [:= :owner_id 99])])
        first-check (observed #(unchanged? captured))
        second-check (observed #(unchanged? captured))]
    (is (:result first-check))
    (is (= (:queries first-check) (:queries second-check)))
    (is (= 1 (count (filter #(str/includes? (first %) "UNION ALL") (:queries first-check)))))
    (is (= {:operations 2 :rows 1 :packets 1 :metadata-selects 2}
           (select-keys (certificate/stats captured) [:operations :rows :packets :metadata-selects])))
    (jdbc/execute! *pool* ["UPDATE certificate_row SET value='still unrelated' WHERE id=2"])
    (is (unchanged? captured) "Unmatched tuple updates do not invalidate a complete predicate")
    (jdbc/execute! *pool* ["INSERT INTO certificate_row(id,owner_id,value) VALUES(3,99,'new member')"])
    (is (false? (unchanged? captured)) "Previously empty operation remains part of the certificate")))


(deftest updates-deletes-and-join-membership-invalidate-test
  (let [one (assoc all-rows :where [:= :id 1])]
    (doseq [mutation ["UPDATE certificate_row SET value='repaired' WHERE id=1"
                      "DELETE FROM certificate_row WHERE id=1"]]
      (let [captured (capture [one])]
        (jdbc/execute! *pool* [mutation])
        (is (false? (unchanged? captured)))))
    (jdbc/execute! *pool* ["INSERT INTO certificate_edge VALUES(1,20),(2,20)"])
    (let [joined {:select [[:r.id :id]] :from [[:certificate_row :r]]
                  :join [[:certificate_edge :e] [:= :r.owner_id :e.owner_id]]}
          captured (capture [joined])]
      (is (= 4 (:rows (certificate/stats captured))) "Both participants and duplicate join membership count")
      (is (unchanged? captured))
      (jdbc/execute! *pool* ["DELETE FROM certificate_edge WHERE id=2"])
      (is (false? (unchanged? captured))))))


(deftest guard-is-fresh-scoped-and-connection-bound-test
  (let [escaped (atom nil) operation (atom nil)
        captured (snapshot (fn [connection guard]
                             (reset! escaped guard)
                             (reset! operation (prepare-and-read connection guard all-rows))
                             (certificate/capture guard [@operation])))]
    (is (= :guard-inactive (miss-reason #(certificate/unchanged? @escaped captured))))
    (snapshot (fn [_ guard]
                (is (= :operation-scope (miss-reason #(certificate/capture guard [@operation]))))))
    (with-open [other (jdbc/get-connection *pool*)]
      (is (false? (snapshot other (constantly nil) (fn [_ guard] (certificate/unchanged? guard captured))))))
    (jdbc/with-transaction [tx *connection* {:isolation :repeatable-read :read-only true}]
                           (jdbc/execute! tx ["SELECT 1"])
                           (let [answer (observed #(miss-reason
                                                     (fn [] (certificate/call-with-guard tx locked (constantly nil) identity))))]
                             (is (= :not-fresh-read-transaction (:result answer)))
                             (is (empty? (:queries answer)) "No SET/LOCK after an earlier snapshot")))))


(deftest schema-and-session-changes-refuse-reuse-test
  (let [captured (capture [all-rows])]
    (jdbc/execute! *pool* ["ALTER TABLE certificate_row ADD COLUMN legacy_note text DEFAULT 'retained'"])
    (is (false? (unchanged? captured))))
  (let [captured (capture [all-rows])]
    (jdbc/execute! *connection* ["SET TIME ZONE 'Pacific/Auckland'"])
    (try (is (false? (unchanged? captured)))
         (finally (jdbc/execute! *connection* ["RESET TIME ZONE"])))))


(deftest exact-query-admission-rejects-volatile-and-unbounded-plans-test
  (snapshot
    (fn [_ guard]
      (doseq [ast [(assoc all-rows :order-by [[[:random]]])
                   (assoc all-rows :limit [:raw "random()"])
                   (assoc all-rows :select [[[:raw "random()"] :value]])
                   (assoc all-rows :where [:= :value (apply str (repeat 262145 \x))])]]
        (let [answer (observed #(miss-reason
                                  (fn [] (certificate/prepare-operation guard ast (sql/format ast {:quoted true})))))]
          (is (keyword? (:result answer)))
          (is (empty? (:queries answer)))))
      (is (= :plan-query-mismatch
             (miss-reason #(certificate/prepare-operation guard all-rows (sql/format all-rows)))))
      (let [ast (assoc all-rows :where [:= :id 1])]
        (is (= :plan-query-mismatch
               (miss-reason #(certificate/prepare-operation guard ast
                                                            [(first (sql/format ast {:quoted true})) 1N]))))))))


(deftest timestamp-parameters-are-frozen-before-source-execution-test
  (let [stamp (Timestamp/valueOf "2026-01-01 00:00:00")
        ast (assoc all-rows :where [:= :stamp stamp])
        captured (snapshot
                   (fn [connection guard]
                     (let [prepared (prepare-and-read connection guard ast)]
                       (.setTime stamp 0)
                       (.setNanos stamp 123456789)
                       (certificate/capture guard [prepared]))))]
    (is (= 1 (:rows (certificate/stats captured))))
    (is (unchanged? captured))
    (jdbc/execute! *pool* ["UPDATE certificate_row SET stamp='2026-01-03' WHERE id=1"])
    (is (false? (unchanged? captured)))))


(deftest cancellation-and-sql-errors-are-not-cache-misses-test
  (let [cancelled? (atom false)
        check! #(when @cancelled? (throw (ex-info "Cancelled" {:type ::cancelled})))
        answer (observed
                 #(try
                    (snapshot *connection* check!
                              (fn [_ guard]
                                (reset! cancelled? true)
                                (certificate/unchanged? guard nil)))
                    nil
                    (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))]
    (is (= ::cancelled (:result answer)))
    (is (= 4 (count (:queries answer))) "Cancellation before validation does no witness SQL"))
  (is (= "42P01"
         (try
           (jdbc/with-transaction [tx *connection* {:isolation :repeatable-read :read-only true}]
                                  (certificate/call-with-guard tx #{:missing_certificate_table} (constantly nil) identity))
           nil
           (catch SQLException error (.getSQLState error))))))


(deftest lock-happens-before-snapshot-and-remains-held-test
  (let [queries (:queries (observed #(snapshot (fn [_ _] true))))]
    (is (= "SET LOCAL statement_timeout = '1000ms'" (ffirst queries)))
    (is (str/starts-with? (first (second queries)) "LOCK TABLE "))
    (is (str/includes? (first (nth queries 2)) "pg_current_snapshot")))
  (snapshot
    (fn [_ _]
      (with-open [other (jdbc/get-connection *pool*)]
        (jdbc/execute! other ["SET lock_timeout = '100ms'"])
        (is (= "55P03"
               (try (jdbc/execute! other ["ALTER TABLE certificate_row ADD COLUMN blocked text"])
                    nil
                    (catch SQLException error (.getSQLState error)))))))))


(deftest schema-byte-budget-prevents-large-default-transfer-test
  (jdbc/execute! *pool* ["ALTER TABLE certificate_row ADD COLUMN huge_default text"])
  (jdbc/execute! *pool* [(str "ALTER TABLE certificate_row ALTER COLUMN huge_default SET DEFAULT '"
                              (apply str (repeat 1100000 \x)) "'")])
  (let [answer (observed #(miss-reason (fn [] (snapshot (fn [_ _] true)))))]
    (is (= :unsupported-schema-or-session (:result answer)))
    (is (= 4 (count (:queries answer))))))


(deftest ordered-selection-requires-complete-nonambiguous-membership-test
  (jdbc/execute! *pool* ["INSERT INTO certificate_row(id,owner_id,value,stamp) VALUES(3,10,'newer','2026-01-03')"])
  (let [latest {:select-distinct-on [[:owner_id] :*] :from [:certificate_row]
                :order-by [:owner_id [:stamp :desc]]}
        captured (capture [latest])]
    (is (= 3 (:rows (certificate/stats captured))) "Older historical members remain witnessed")
    (is (unchanged? captured))
    (jdbc/execute! *pool* ["INSERT INTO certificate_row(id,owner_id,value,stamp) VALUES(4,10,'older','2025-01-01')"])
    (is (false? (unchanged? captured)))
    (jdbc/execute! *pool* ["INSERT INTO certificate_row(id,owner_id,value,stamp) VALUES(5,10,'tie','2026-01-03')"])
    (is (= :ambiguous-order (miss-reason #(capture [latest])))))
  (is (= :clipped-result (miss-reason #(capture [(assoc all-rows :limit 1)]))))
  (is (unchanged? (capture [(assoc all-rows :limit 10)])))
  (snapshot
    (fn [_ guard]
      (let [ast (assoc all-rows :offset 1)]
        (is (= :unknown-read-plan
               (miss-reason #(certificate/prepare-operation guard ast (sql/format ast {:quoted true})))))))))


(deftest operation-budget-and-empty-predicate-test
  (let [captured (capture [(assoc all-rows :where [:= [:inline 1] [:inline 0]])])]
    (is (= {:operations 1 :rows 0 :packets 1 :metadata-selects 1}
           (select-keys (certificate/stats captured) [:operations :rows :packets :metadata-selects])))
    (is (unchanged? captured)))
  (snapshot
    (fn [connection guard]
      (let [operation (prepare-and-read connection guard all-rows)
            answer (observed #(miss-reason (fn [] (certificate/capture guard (vec (repeat 2049 operation))))))]
        (is (= :operation-scope (:result answer)))
        (is (empty? (:queries answer)))))))


(defn- policy-options
  []
  {:policy-contract
   {:tables {:certificate_row
             {:enabled? true :forced? true
              :policies #{{:name "certificate_read" :command "r" :permissive? true :roles [0]
                           :using "(value = current_setting('graphden.current_org'::text, true))" :check nil}}}
             :certificate_edge {:enabled? false :forced? false :policies #{}}}
    :required-gucs {"graphden.current_org" "original"}}})


(defn- policy-snapshot
  [options f]
  (jdbc/with-transaction [connection *connection* {:isolation :repeatable-read :read-only true}]
                         (certificate/call-with-guard connection locked options (constantly nil) #(f connection %))))


(deftest force-rls-requires-exact-trusted-policy-and-current-context-test
  (let [role (str "certificate_reader_" (str/replace (str (random-uuid)) "-" ""))]
    (jdbc/execute! *pool* [(str "CREATE ROLE " role " NOLOGIN NOSUPERUSER NOBYPASSRLS")])
    (try
      (jdbc/execute! *pool* ["ALTER TABLE certificate_row ENABLE ROW LEVEL SECURITY"])
      (jdbc/execute! *pool* ["ALTER TABLE certificate_row FORCE ROW LEVEL SECURITY"])
      (jdbc/execute! *pool* ["CREATE POLICY certificate_read ON certificate_row FOR SELECT USING(value=current_setting('graphden.current_org',true))"])
      (jdbc/execute! *pool* [(str "GRANT SELECT ON certificate_row,certificate_edge TO " role)])
      (jdbc/execute! *connection* [(str "SET ROLE " role)])
      (jdbc/execute! *connection* ["SELECT set_config('graphden.current_org','original',false)"])
      (is (= :rls-contract-required (miss-reason #(snapshot (fn [_ _] true)))))
      (let [captured (policy-snapshot (policy-options)
                                      (fn [connection guard]
                                        (certificate/capture guard [(prepare-and-read connection guard all-rows)])))]
        (is (= 1 (:rows (certificate/stats captured))))
        (is (policy-snapshot (policy-options) (fn [_ guard] (certificate/unchanged? guard captured))))
        (jdbc/execute! *connection* ["SELECT set_config('graphden.current_org','unrelated',false)"])
        (is (= :policy-context-mismatch
               (miss-reason #(policy-snapshot (policy-options) (fn [_ _] true)))))
        (is (false? (policy-snapshot
                      (assoc-in (policy-options) [:policy-contract :required-gucs "graphden.current_org"] "unrelated")
                      (fn [_ guard] (certificate/unchanged? guard captured)))))
        (jdbc/execute! *connection* ["SELECT set_config('graphden.current_org','original',false)"])
        (jdbc/execute! *pool* ["CREATE POLICY unexpected_read ON certificate_row FOR SELECT USING(true)"])
        (is (= :policy-mismatch (miss-reason #(policy-snapshot (policy-options) (fn [_ _] true)))))
        (jdbc/execute! *pool* ["DROP POLICY unexpected_read ON certificate_row"])
        (jdbc/execute! *pool* ["CREATE FUNCTION certificate_rule(text) RETURNS boolean LANGUAGE sql VOLATILE AS 'SELECT true'"])
        (jdbc/execute! *pool* ["ALTER POLICY certificate_read ON certificate_row USING(certificate_rule(value))"])
        (is (= :unsupported-policy (miss-reason #(policy-snapshot (policy-options) (fn [_ _] true))))))
      (finally
        (jdbc/execute! *connection* ["RESET ROLE"])
        (jdbc/execute! *pool* ["DROP FUNCTION IF EXISTS certificate_rule(text) CASCADE"])
        (jdbc/execute! *pool* [(str "DROP OWNED BY " role)])
        (jdbc/execute! *pool* [(str "DROP ROLE " role)])))))


(deftest capture-reservation-bounds-retained-state-before-witness-sql-test
  (snapshot
    (fn [connection guard]
      (let [operation (prepare-and-read connection guard all-rows)
            refused (observed #(miss-reason
                                 (fn [] (certificate/capture guard [operation] {:max-bytes (* 1024 1024)}))))
            accepted (certificate/capture guard [operation] {:max-bytes (* 2 1024 1024)})]
        (is (= :capture-budget (:result refused)))
        (is (empty? (:queries refused)))
        (is (<= (* 1024 1024) (:charge-bytes (certificate/stats accepted)) (* 2 1024 1024)))
        (is (= 2 (:rows (certificate/stats accepted))))))))


(deftest lock-timeout-propagates-and-requires-new-transaction-test
  (jdbc/with-transaction [writer *pool*]
                         (jdbc/execute! writer ["LOCK TABLE certificate_row IN ACCESS EXCLUSIVE MODE"])
                         (is (= "57014"
                                (try (snapshot (fn [_ _] true))
                                     nil
                                     (catch SQLException error (.getSQLState error))))))
  (is (unchanged? (capture [all-rows])) "Ordinary recovery begins in a new transaction"))


(defn- prepared-key
  [value]
  (snapshot
    (fn [_ guard]
      (let [ast (assoc all-rows :where [:= :stamp value])]
        (certificate/operation-key (certificate/prepare-operation guard ast (sql/format ast {:quoted true})))))))


(deftest operation-keys-preserve-typed-parameters-without-scope-or-mutable-state-test
  (let [value (Timestamp/valueOf "2026-01-01 00:00:00.123456789")
        before (prepared-key value)
        same (prepared-key (Timestamp/valueOf "2026-01-01 00:00:00.123456789"))]
    (.setNanos value 987654321)
    (is (= before same))
    (is (= (hash before) (hash same)))
    (is (= :found (get {before :found} same)))
    (is (not= before (prepared-key value)))
    (is (= "#<source-operation-key>" (str before))))
  (is (not= (prepared-key 1) (prepared-key 1N)) "Clojure-equal numbers retain their JDBC parameter type"))


(deftest ordered-projection-alias-cannot-change-the-witness-sort-key-test
  (jdbc/execute! *pool* ["INSERT INTO certificate_row(id,owner_id,value) VALUES(3,10,'tied alias')"])
  (snapshot
    (fn [connection guard]
      (let [ast {:select-distinct-on [[:id] [:owner_id :id] :value]
                 :from [:certificate_row] :order-by [:id]}
            query (sql/format ast {:quoted true})
            rows (pg/exec! connection query)
            answer (observed #(miss-reason (fn [] (certificate/prepare-operation guard ast query))))]
        (is (= 2 (count rows)) "Source DISTINCT ON id resolves projected owner_id alias")
        (is (= :unknown-read-plan (:result answer)))
        (is (empty? (:queries answer)) "Cannot attest input id uniqueness for output owner_id ordering")))))
