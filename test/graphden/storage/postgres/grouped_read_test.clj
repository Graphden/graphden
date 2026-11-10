(ns ^:integration graphden.storage.postgres.grouped-read-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [graphden.storage.tx :as tx]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      SQLException)
    (java.time
      Instant)))


(use-fixtures :once (setup/container-fixture))


(defn- sample-storage
  []
  (let [storage (setup/create-test-storage)
        fields (into {} (map (fn [[field type]] [field {:uuid (random-uuid) :type type :nullable? true}]))
                     {:owner-id :uuid :branch-id :uuid :name :text :value :jsonb
                      :created-at :timestamptz :amount :numeric :flag :bool})]
    (sp/initialize storage (th/make-schema :entity-name :sample :fields fields))
    storage))


(defn- seed!
  [storage]
  (let [owner (random-uuid) branch (random-uuid)
        row {:owner-id owner :branch-id branch :name "older" :value {:a [nil false 12]}
             :created-at (Instant/parse "2026-01-01T00:00:00Z") :amount 12.25M :flag false}
        old (sp/create-entity storage :sample row)
        newer (sp/create-entity storage :sample (assoc row :name "newer" :value nil
                                                       :created-at (Instant/parse "2026-01-02T00:00:00Z")))]
    {:old old :newer newer :owner owner :branch branch}))


(defn- requests
  [{:keys [old newer owner branch]}]
  [{:op :read-ids :entity :sample :ids [(:id old) (:id newer)]}
   {:op :query :entity :sample :where {:id #{(:id old) (:id newer)} :flag false}}
   {:op :latest :entity :sample :where {:owner-id owner :branch-id branch}
    :group-cols [:owner-id :branch-id]}])


(defn- snapshot
  [storage f]
  (jdbc/with-transaction [connection (:pool storage) {:read-only true :isolation :repeatable-read}]
                         (grouped/call-with-snapshot-cache connection #(f (tx/with-connection storage connection) connection))))


(defn- observed
  [f]
  (let [calls (atom [])
        result (binding [util/*jdbc-override*
                         {:execute! (fn [connection params opts]
                                      (swap! calls conj params)
                                      (jdbc/execute! connection params opts))
                          :execute-one! (fn [connection params opts]
                                          (swap! calls conj params)
                                          (jdbc/execute-one! connection params opts))}]
                 (f))]
    {:result result :calls @calls}))


(defn- bag
  [result]
  (if (map? result) result (frequencies result)))


(deftest grouped-reads-preserve-raw-shape-latest-selection-and-legacy-columns
  (let [storage (sample-storage)]
    (try
      (let [rows (seed! storage) ops (requests rows)]
        (jdbc/execute! (:pool storage) ["ALTER TABLE sample ADD COLUMN legacy_note text DEFAULT 'retained'"])
        (snapshot storage
                  (fn [scoped _]
                    (let [scalar (mapv #(grouped/scalar-operation scoped %) ops)
                          cold (observed #(grouped/read-operations scoped ops))
                          warm (observed #(grouped/read-operations scoped ops))]
                      (is (= (mapv bag scalar) (mapv bag (:result cold)) (mapv bag (:result warm))))
                      (is (= "retained" (get-in warm [:result 0 (get-in rows [:old :id]) :legacy-note])))
                      (is (= [(:id (:newer rows))] (mapv :id (get-in warm [:result 2]))))
                      (is (= 2 (count (:calls cold))) "One physical shape query then one native carrier")
                      (is (= 1 (count (:calls warm))) "Warm snapshot shape needs one execute for three reads")
                      (is (str/includes? (ffirst (:calls warm)) "UNION ALL"))
                      (is (= 3 (count (:result warm))))))))
      (finally (sp/close storage)))))


(deftest latest-groups-retain-exact-scalar-vector-order
  (let [storage (sample-storage)]
    (try
      (let [first-set (seed! storage) second-set (seed! storage)
            where {:owner-id [(:owner first-set) (:owner second-set)]
                   :branch-id [(:branch first-set) (:branch second-set)]}
            latest {:op :latest :entity :sample :where where :group-cols [:owner-id :branch-id]}
            reads {:op :read-ids :entity :sample :ids [(get-in first-set [:old :id])]}
            ops [latest reads latest]]
        (snapshot storage
                  (fn [scoped _]
                    (let [expected (grouped/scalar-operation scoped latest)
                          actual (grouped/read-operations scoped ops)]
                      (is (= 2 (count expected)))
                      (is (= expected (first actual) (last actual)))
                      (is (not-any? #(contains? % :__gd_row_order) (first actual)))))))
      (finally (sp/close storage)))))


(deftest grouped-identities-restore-junction-order-without-field-double-decode
  (let [storage (setup/create-test-storage)]
    (try
      (sp/initialize storage (setup/make-graph-schema))
      (setup/seed-primitives! storage)
      (let [a (setup/create-base-fn! storage "first-parent")
            b (setup/create-base-fn! storage "second-parent")
            child (setup/create-composed-fn! storage "child" [(:id b) (:id a)])
            slot (setup/create-slot! storage "nullable" :any :required false)
            binding (setup/create-binding! storage (:id child) (:id slot) :value {:nested [nil false]} :value-present true)
            ops [{:op :read-ids :entity :fn :ids [(:id child)]}
                 {:op :read-ids :entity :binding :ids [(:id binding)]}
                 {:op :query :entity :slot :where {:id (:id slot)}}]]
        (snapshot storage
                  (fn [scoped _]
                    (let [scalar (mapv #(grouped/scalar-operation scoped %) ops)
                          actual (grouped/read-operations scoped ops)]
                      (is (= (mapv bag scalar) (mapv bag actual)))
                      (is (= [(:id b) (:id a)] (get-in actual [0 (:id child) :parent-ids])))
                      (is (= {:nested [nil false]} (get-in actual [1 (:id binding) :value])))))))
      (finally (sp/close storage)))))


(defn- query-count
  [f]
  (count (:calls (observed f))))


(defn- assert-shape-lock!
  [storage]
  (with-open [other (jdbc/get-connection (:pool storage))]
    (jdbc/execute! other ["SET lock_timeout = '100ms'"])
    (is (= "55P03"
           (try (jdbc/execute! other ["ALTER TABLE sample ADD COLUMN after_snapshot text"])
                nil
                (catch SQLException error (SQLException/.getSQLState error))))
        "Physical shape inspection holds ACCESS SHARE until transaction end")))


(deftest shape-cache-is-transaction-scoped-and-locks-real-relations
  (let [storage (sample-storage)]
    (try
      (let [ops (vec (take 2 (requests (seed! storage))))]
        (jdbc/with-transaction [connection (:pool storage) {:read-only true :isolation :repeatable-read}]
                               (let [scoped (tx/with-connection storage connection)
                                     retained (atom nil)]
                                 (grouped/call-with-snapshot-cache
                                   connection
                                   (fn []
                                     (grouped/read-operations scoped ops)
                                     (reset! retained (bound-fn [] (query-count #(grouped/read-operations scoped ops))))
                                     (assert-shape-lock! storage)
                                     (jdbc/with-transaction [other (:pool storage) {:read-only true :isolation :repeatable-read}]
                                                            (is (= 2 (query-count #(grouped/read-operations (tx/with-connection storage other) ops)))
                                                                "Another connection cannot reuse the shape cache"))))
                                 (is (= 2 (@retained)) "A retained callback cannot reactivate an expired cache")))
        (jdbc/execute! (:pool storage) ["ALTER TABLE sample ADD COLUMN after_snapshot text DEFAULT 'fresh'"])
        (snapshot storage
                  (fn [scoped _]
                    (let [answer (grouped/read-operations scoped ops)]
                      (is (every? #(= "fresh" (:after-snapshot %)) (vals (first answer))))))))
      (finally (sp/close storage)))))


(deftest first-shape-read-observes-ddl-committed-after-the-data-snapshot
  (let [storage (sample-storage)]
    (try
      (let [data (seed! storage)
            ops (vec (take 2 (requests data)))]
        (snapshot storage
                  (fn [scoped connection]
                    ;; Establish the RR snapshot without locking sample. A
                    ;; later SELECT * still uses its current tuple descriptor.
                    (jdbc/execute! connection ["SELECT count(*) FROM pg_catalog.pg_namespace"])
                    (jdbc/execute! (:pool storage)
                                   ["ALTER TABLE sample ADD COLUMN committed_after_snapshot text DEFAULT 'retained'"])
                    (let [expected (mapv #(grouped/scalar-operation scoped %) ops)
                          actual (observed #(grouped/read-operations scoped ops))]
                      (is (= "retained"
                             (get-in expected [0 (get-in data [:old :id]) :committed-after-snapshot])))
                      (is (= (mapv bag expected) (mapv bag (:result actual))))
                      (is (= 2 (count (:calls actual))))
                      (is (some #(str/includes? (first %) "UNION ALL") (:calls actual)))))))
      (finally (sp/close storage)))))


(deftest native-shapes-preserve-domain-values-without-catalog-type-lookups
  (let [storage (sample-storage)]
    (try
      (let [data (seed! storage) ops (vec (take 2 (requests data)))]
        (jdbc/execute! (:pool storage) ["CREATE DOMAIN sample_text AS text CHECK (VALUE <> '')"])
        (jdbc/execute! (:pool storage) ["CREATE DOMAIN sample_numeric AS numeric(30,10)"])
        (jdbc/execute! (:pool storage) ["ALTER TABLE sample ADD COLUMN domain_text sample_text DEFAULT 'retained', ADD COLUMN domain_numeric sample_numeric DEFAULT 12.2500000000"])
        (snapshot storage
                  (fn [scoped _]
                    (let [expected (mapv #(grouped/scalar-operation scoped %) ops)
                          actual (observed #(grouped/read-operations scoped ops))]
                      (is (= (mapv bag expected) (mapv bag (:result actual))))
                      (is (= "retained" (get-in actual [:result 0 (get-in data [:old :id]) :domain-text])))
                      (is (= 12.2500000000M (get-in actual [:result 0 (get-in data [:old :id]) :domain-numeric])))
                      (is (= 2 (count (:calls actual))))
                      (is (some #(str/includes? (first %) "UNION ALL") (:calls actual)))))))
      (finally (sp/close storage)))))


(deftest unsupported-native-column-labels-retain-scalar-columns
  (let [storage (sample-storage)]
    (try
      (let [data (seed! storage) ops (vec (take 2 (requests data)))]
        (jdbc/execute! (:pool storage) ["ALTER TABLE sample ADD COLUMN __gd_shape_0 text DEFAULT 'retained'"])
        (snapshot storage
                  (fn [scoped _]
                    (let [expected (mapv #(grouped/scalar-operation scoped %) ops)
                          cold (observed #(grouped/read-operations scoped ops))
                          warm (observed #(grouped/read-operations scoped ops))]
                      (is (= (mapv bag expected) (mapv bag (:result cold)) (mapv bag (:result warm))))
                      (is (= 3 (count (:calls cold))))
                      (is (= 2 (count (:calls warm))))
                      (is (not-any? #(str/includes? (first %) "UNION ALL") (:calls cold)))))))
      (finally (sp/close storage)))))


(deftest wide-tables-decline-grouping-without-a-combined-shape-column-limit
  (let [storage (setup/create-test-storage)]
    (try
      (sp/initialize storage
                     (ds/build (reduce (fn [schema entity]
                                         (ds/add-entity schema entity (random-uuid)
                                                        {:name {:uuid (random-uuid) :type :text}}))
                                       (mds/create-builder) [:wide-a :wide-b])))
      (let [a (sp/create-entity storage :wide-a {:name "a"})
            b (sp/create-entity storage :wide-b {:name "b"})
            ops [{:op :read-ids :entity :wide-a :ids [(:id a)]}
                 {:op :read-ids :entity :wide-b :ids [(:id b)]}]
            ;; Test-only DDL: fixed local identifiers, no external input. Two
            ;; legal tables together exceed PG's 1664 SELECT-column ceiling.
            additions (str/join ", " (map #(str "ADD COLUMN c" % " text") (range 850)))]
        (doseq [table ["wide_a" "wide_b"]]
          (jdbc/execute! (:pool storage) [(str "ALTER TABLE " table " " additions)]))
        (snapshot storage
                  (fn [scoped _]
                    (let [expected (mapv #(grouped/scalar-operation scoped %) ops)
                          cold (observed #(grouped/read-operations scoped ops))
                          warm (observed #(grouped/read-operations scoped ops))]
                      (is (= expected (:result cold) (:result warm)))
                      (is (contains? (get-in cold [:result 0 (:id a)]) :c849))
                      (is (= 4 (count (:calls cold))) "Two table shapes plus two scalar payload reads")
                      (is (= 2 (count (:calls warm))))
                      (is (not-any? #(str/includes? (first %) "UNION ALL") (:calls cold)))))))
      (finally (sp/close storage)))))


(defn- assert-parameter-fallback!
  [scoped ops id]
  (binding [util/*max-statement-params* 6]
    (let [ids (vec (repeat 4 id))
          constrained (mapv #(assoc % :ids ids) [(first ops) (first ops)])
          answer (observed #(grouped/read-operations scoped constrained))]
      (is (= 2 (count (:calls answer))))
      (is (= (mapv #(grouped/scalar-operation scoped %) constrained) (:result answer)))))
  (let [ids (vec (repeat 20000 id))
        large (mapv #(assoc % :ids ids) [(first ops) (first ops)])
        answer (observed #(grouped/read-operations scoped large))]
    (is (not-any? #(str/includes? (first %) "UNION ALL") (:calls answer)))
    (is (= (mapv #(grouped/scalar-operation scoped %) large) (:result answer)))))


(deftest unsupported-groups-and-resource-guards-retain-scalar-behavior
  (let [storage (sample-storage)]
    (try
      (let [data (seed! storage) ops (vec (take 2 (requests data)))]
        (is (= 2 (query-count #(grouped/read-operations storage ops)))
            "No explicit snapshot cache means no transport optimization")
        (snapshot storage
                  (fn [scoped _]
                    (let [ordered (assoc-in ops [1 :opts] {:order-by [[:name :desc]]})
                          empty-request (assoc-in ops [0 :ids] [])
                          many (vec (repeat 9 (first ops)))]
                      (is (= (mapv #(grouped/scalar-operation scoped %) ordered)
                             (:result (observed #(grouped/read-operations scoped ordered)))))
                      (is (= 2 (query-count #(grouped/read-operations scoped ordered))))
                      (let [answer (observed #(grouped/read-operations scoped empty-request))]
                        (is (= {} (first (:result answer))))
                        (is (= 1 (count (:calls answer)))))
                      (is (= 9 (query-count #(sp/read-operations scoped many)))
                          "Oversized direct SPI calls stay scalar")
                      (assert-parameter-fallback! scoped ops (get-in data [:old :id]))))))
      (finally (sp/close storage)))))


(deftest candidate-overflow-remains-a-single-query-before-any-payload
  (let [storage (setup/create-test-storage)]
    (try
      (sp/initialize storage (setup/make-graph-schema))
      (setup/seed-primitives! storage)
      (let [owner (setup/create-base-fn! storage "bounded-owner")
            slot (setup/create-slot! storage "bounded-value" :any)
            binding (setup/create-binding! storage (:id owner) (:id slot) :value "payload")
            ops [{:op :candidates :entity :binding :where {:fn-id (:id owner)} :max-candidates 0}
                 {:op :read-ids :entity :binding :ids [(:id binding)]}]]
        (snapshot storage
                  (fn [scoped _]
                    (doseq [invoke [#(grouped/read-operations scoped ops) #(sp/read-operations scoped ops)]]
                      (let [attempt (observed #(try (invoke)
                                                    (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))]
                        (is (= :storage-error/candidate-limit (:result attempt)))
                        (is (= 1 (count (:calls attempt))))
                        (is (not-any? (fn [params] (some #{(:id binding)} (rest params))) (:calls attempt))))))))
      (finally (sp/close storage)))))
