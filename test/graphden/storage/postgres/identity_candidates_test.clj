(ns ^:integration graphden.storage.postgres.identity-candidates-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.storage.postgres.crud :as crud]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [next.jdbc :as jdbc])
  (:import
    (org.postgresql.util
      PGobject)))


(use-fixtures :once (setup/container-fixture))

(def ^:private fields {:id {:type :uuid} :org-id {:type :string} :fn-id {:type :uuid}})


(def ^:private versions
  {:version-entity :binding-version :version-id-field :binding-id
   :version-data-fields #{:fn-id}})


(defn- query-candidates
  [connection where cap]
  (crud/query-identity-candidates connection :binding where fields versions cap))


(defn- explain-nodes
  [connection query]
  (let [row (first (jdbc/execute! connection
                                  (into [(str "EXPLAIN (ANALYZE, FORMAT JSON) " (first query))] (rest query))
                                  (pg/query-opts)))
        value (first (vals row))
        plan (get (first (json/parse-string (PGobject/.getValue value))) "Plan")]
    (tree-seq #(seq (get % "Plans")) #(get % "Plans") plan)))


(defn- populate
  [pool]
  (jdbc/execute! pool ["CREATE TABLE binding(id uuid PRIMARY KEY, org_id text)"])
  (jdbc/execute! pool ["CREATE TABLE binding_version(id uuid PRIMARY KEY, binding_id uuid REFERENCES binding(id), fn_id uuid)"])
  (jdbc/execute! pool ["CREATE INDEX binding_owner_candidates ON binding_version(fn_id)"])
  (jdbc/execute! pool ["INSERT INTO binding SELECT md5(('identity'||i)::text)::uuid,'visible' FROM generate_series(1,30000) i"])
  (jdbc/execute! pool ["INSERT INTO binding_version SELECT md5(('version'||i)::text)::uuid,md5(('identity'||i)::text)::uuid,md5((i%10000)::text)::uuid FROM generate_series(1,30000) i"])
  (jdbc/execute! pool ["INSERT INTO binding_version SELECT md5(('history'||i)::text)::uuid,md5('identity1')::uuid,md5('1')::uuid FROM generate_series(1,12) i"])
  (jdbc/execute! pool ["UPDATE binding SET org_id='foreign' WHERE id=md5('identity2')::uuid"])
  (jdbc/execute! pool ["ALTER TABLE binding ENABLE ROW LEVEL SECURITY"])
  (jdbc/execute! pool ["ALTER TABLE binding FORCE ROW LEVEL SECURITY"])
  (jdbc/execute! pool ["CREATE POLICY candidate_visibility ON binding USING (org_id=current_setting('graphden.test_org',true))"])
  (jdbc/execute! pool ["ANALYZE binding"])
  (jdbc/execute! pool ["ANALYZE binding_version"]))


(deftest sparse-version-candidates-keep-rls-and-avoid-unrelated-identity-scans
  (let [storage (setup/create-test-storage) pool (:pool storage)
        role (str "candidate_" (subs (str (random-uuid)) 0 8))]
    (try
      (populate pool)
      (jdbc/execute! pool [(str "CREATE ROLE " role " NOLOGIN NOSUPERUSER NOBYPASSRLS")])
      (try
        (jdbc/execute! pool [(str "GRANT SELECT ON binding,binding_version TO " role)])
        (jdbc/with-transaction [connection pool {:read-only true :isolation :repeatable-read}]
                               (jdbc/execute! connection [(str "SET LOCAL ROLE " role)])
                               (jdbc/execute! connection ["SELECT set_config('graphden.test_org','visible',true)"])
                               (let [owners (mapv :id (jdbc/execute! connection ["SELECT md5(i::text)::uuid id FROM generate_series(0,63) i"] (pg/query-opts)))
                                     queries (atom [])
                                     rows (binding [pg/*jdbc-override* {:execute! (fn [ds query opts]
                                                                                    (swap! queries conj query)
                                                                                    (jdbc/execute! ds query opts))}]
                                            (query-candidates connection {:fn-id owners} 192))
                                     flat (pg/call-with-flat-read-plans #(query-candidates connection {:fn-id owners} 192))
                                     scans (filter #(= "binding" (get % "Relation Name"))
                                                   (explain-nodes connection (first @queries)))]
                                 (is (= (set flat) (set rows)) "ANY matching version and RLS have unchanged membership")
                                 (is (= 191 (count rows)))
                                 (is (= 191 (::sp/candidate-count (meta rows))))
                                 (is (= #{#{:id :org-id}} (set (map (comp set keys) rows))) "No payload columns")
                                 (is (= 1 (count @queries)))
                                 (is (= 1 (count scans)))
                                 (is (= "Index Scan" (get (first scans) "Node Type")))
                                 (is (= 192 (get (first scans) "Actual Loops")) "Only distinct version candidates reach identity/RLS")
                                 (is (empty? (query-candidates connection {:fn-id []} 0)))
                                 (is (empty? (query-candidates connection {:fn-id owners :id nil} 0)))
                                 (is (= [(first rows)] (query-candidates connection {:fn-id owners :id (:id (first rows))} 1)))
                                 (is (= :storage-error/candidate-limit
                                        (try (query-candidates connection {:fn-id owners} 190) nil
                                             (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))
        (finally
          (jdbc/execute! pool [(str "DROP OWNED BY " role)])
          (jdbc/execute! pool [(str "DROP ROLE " role)])))
      (finally (sp/close storage)))))
