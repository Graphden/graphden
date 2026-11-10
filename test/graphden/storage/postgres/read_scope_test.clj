(ns graphden.storage.postgres.read-scope-test
  "Structured read plans remain local to their exact synchronous JDBC call."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.postgres.crud :as crud]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.metadata :as metadata]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.sql.pg :as pg]
    [next.jdbc.result-set :as rs]))


(deftest scalar-storage-reads-expose-their-plan
  (let [connection (Object.)
        id (random-uuid)
        fields {:id {:type :uuid} :fn-id {:type :uuid}}
        calls (atom [])
        capture (fn [ds query opts]
                  (swap! calls conj {:plan (util/current-read-plan ds query)
                                     :query query :opts opts}))]
    (binding [util/*jdbc-override*
              {:execute! (fn [ds query opts] (capture ds query opts) [])
               :execute-one! (fn [ds query opts] (capture ds query opts) nil)}]
      (doseq [[label read! table]
              [["one identity" #(crud/read-entity connection :fn id) :fn]
               ["filtered rows" #(crud/query-entities connection :fn {:id id} fields) :fn]
               ["bounded candidates" #(crud/query-identity-candidates connection :fn-slot {:fn-id [id]} fields nil 4)
                [:fn_slot :ident]]
               ["latest versions" #(crud/query-latest-per-group connection :fn-version {:fn-id id} [:fn-id] fields)
                :fn_version]
               ["identity batch" #(crud/read-entities connection :fn [id]) :fn]
               ["junction owners" #(junction/read-junction-owners connection :fn :parent-ids id) :fn_parent_ids]
               ["junction batch" #(junction/read-junction-rows-batch connection :fn :parent-ids [id]) :fn_parent_ids]
               ["schema rows" #(metadata/read-metadata-rows connection) :_schema_metadata]]]
        (testing label
          (reset! calls [])
          (read!)
          (is (= 1 (count @calls)))
          (is (= [table] (get-in @calls [0 :plan :from])))
          (is (= (util/query-opts) (:opts (first @calls)))))))
    (is (nil? (util/current-read-plan connection (:query (first @calls)))))))


(deftest plans-cannot-be-borrowed-by-other-statements
  (let [connection (Object.)
        other-connection (Object.)
        plan {:select [:id] :from [:fn] :where [:= :id 7]}
        error (ex-info "Read failed" {:type ::expected})
        observed (atom nil)]
    (binding [util/*jdbc-override*
              {:execute! (fn [ds query opts]
                           (reset! observed query)
                           (is (= plan (util/current-read-plan ds query)))
                           (is (nil? (util/current-read-plan other-connection query)))
                           (is (nil? (util/current-read-plan ds (assoc query 1 8))))
                           (is (= ["SELECT \"id\" FROM \"fn\" WHERE \"id\" = ?" 7] query))
                           (is (= {:fetch-size 17} opts))
                           (throw error))}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Read failed"
            (util/exec-read! connection plan {:quoted true} {:fetch-size 17}))))
    (is (nil? (util/current-read-plan connection @observed)))))


(deftest application-sql-is-visible-but-unclassified
  (let [connection (Object.)
        ctx {:storage {:pool connection}}
        observed (atom [])
        intercept (fn [ds query opts result]
                    (swap! observed conj {:plan (util/current-read-plan ds query) :opts opts})
                    result)]
    (binding [util/*jdbc-override*
              {:execute! (fn [_ _ _]
                           (binding [util/*jdbc-override*
                                     {:execute! #(intercept %1 %2 %3 [{:answer 7}])
                                      :execute-one! #(intercept %1 %2 %3 {:next.jdbc/update-count 2})}]
                             (is (= [{:answer 7}] (pg/pg-query ctx {:select [[7 :answer]]})))
                             (is (= 2 (pg/pg-execute ctx {:delete-from :fn :where [:= :id 7]}))))
                           [])}]
      (util/exec-read! connection {:select [:id] :from [:fn]}))
    (is (= [nil nil] (mapv :plan @observed)))
    (is (= [{:builder-fn rs/as-unqualified-maps :timeout (util/get-query-timeout-seconds)}
            {:return-keys false :timeout (util/get-query-timeout-seconds)}]
           (mapv :opts @observed)))))
