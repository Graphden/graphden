(ns ^:integration graphden.versioning.storage.bounded-query-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.browser-source :as source]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture))


(defn- error-type
  [f]
  (try (f) (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn- jdbc-spy
  [calls]
  {:execute! (fn [ds sql opts]
               (swap! calls conj sql)
               (jdbc/execute! ds sql opts))
   :execute-one! (fn [ds sql opts]
                   (swap! calls conj sql)
                   (jdbc/execute-one! ds sql opts))})


(defn- two-bindings
  [storage]
  (let [a (sp/create-entity storage :fn {:name "bounded-a" :parent-ids []})
        b (sp/create-entity storage :fn {:name "bounded-b" :parent-ids []})
        slot (sp/create-entity storage :slot {:name "items" :type-fn-id (:id a)})]
    (doseq [owner [a b]]
      (sp/create-entity storage :fn-slot {:fn-id (:id owner) :slot-id (:id slot) :position 0}))
    {:a a :b b
     :left (sp/create-entity storage :binding {:fn-id (:id a) :slot-id (:id slot) :list-append true})
     :right (sp/create-entity storage :binding {:fn-id (:id b) :slot-id (:id slot) :list-append true})}))


(deftest bounded-owner-query-resolves-moves-invisible-rows-tombstones-and-merges
  (let [storage (setup/create-versioned-test-storage)
        {:keys [a b left right]} (two-bindings storage)
        item (sp/create-entity storage :binding-list-item
                               {:binding-id (:id left) :position 0 :value 1})
        branch (vs/create-branch! storage "bounded-source")
        feature (vs/switch-branch storage (:id branch))
        target (vs/create-branch! storage "bounded-target")
        target-view (vs/switch-branch storage (:id target))
        hidden (sp/create-entity feature :binding-list-item
                                 {:binding-id (:id left) :position 1 :value 8})
        deleted (sp/create-entity feature :binding-list-item
                                  {:binding-id (:id left) :position 2 :value 9})]
    (sp/update-entity feature :binding-list-item (:id item) {:binding-id (:id right) :value 2})
    (binding [vs/*tombstone-delete?* true]
      (sp/delete-entity feature :binding-list-item (:id deleted)))
    (is (= (:id left) (:binding-id (sp/read-entity (vs/unwrap storage) :binding-list-item (:id item))))
        "The identity's create-time owner has not moved")
    (is (= 3 (::sp/candidate-count
               (meta (sp/query-bounded-entities storage :binding-list-item
                                                {:binding-id (:id left)} 3)))))
    (is (= [{:id (:id item) :value 1}]
           (mapv #(select-keys % [:id :value])
                 (sp/query-bounded-entities storage :binding-list-item {:binding-id (:id left)} 3))))
    (is (empty? (sp/query-bounded-entities storage :binding-list-item {:binding-id (:id right)} 1))
        "A matching version on another branch is only a candidate")
    (is (= #{(:id item)}
           (set (map :id (sp/query-bounded-entities feature :binding-list-item
                                                    {:binding-id (:id right) :value 2} 1)))))
    (is (empty? (sp/query-bounded-entities feature :binding-list-item
                                           {:binding-id (:id right) :value 1} 1)))
    (vs/merge-branch! target-view (:id branch))
    (is (= #{(:id item)}
           (set (map :id (sp/query-bounded-entities target-view :binding-list-item
                                                    {:binding-id (:id right)} 1)))))
    (is (= #{(:id hidden)}
           (set (map :id (sp/query-bounded-entities target-view :binding-list-item
                                                    {:binding-id (:id left)} 3)))))
    (let [graph (source/collect-closure target-view [(:id a) (:id b)] (constantly nil))]
      (is (= #{(:id item) (:id hidden)} (set (map :id (:list-items graph))))))
    (is (= :storage-error/unsupported-opts
           (error-type #(sp/query-entities storage :binding-list-item {:binding-id (:id left)} {:limit 1}))))))


(deftest candidate-overflow-refuses-before-loading-version-or-identity-payloads
  (let [storage (setup/create-versioned-test-storage)
        {:keys [left]} (two-bindings storage)]
    (doseq [position (range 12)]
      (sp/create-entity storage :binding-list-item
                        {:binding-id (:id left) :position position :value (str "payload-" position)}))
    (let [queries (atom [])]
      (binding [util/*jdbc-override* (jdbc-spy queries)]
        (is (= :storage-error/candidate-limit
               (error-type #(sp/query-bounded-entities storage :binding-list-item
                                                       {:binding-id (:id left)} 4)))))
      (is (= 1 (count @queries)) "One bounded candidate query; no version/payload loading")
      (let [sql (ffirst @queries)
            projection (first (str/split (or sql "") #"(?i) FROM "))]
        (is (and sql (str/includes? sql "LIMIT ?")))
        (is (str/includes? projection "DISTINCT"))
        (is (not (str/includes? projection "value")))))))


(deftest owner-batch-query-count-does-not-grow-with-the-number-of-functions
  (let [storage (setup/create-versioned-test-storage)
        type (sp/create-entity storage :fn {:name "bounded-type" :parent-ids []})
        slot (sp/create-entity storage :slot {:name "value" :type-fn-id (:id type)})
        owners (sp/create-entities storage :fn
                                   (mapv #(hash-map :name (str "batch-owner-" %) :parent-ids []) (range 64)))
        rows (sp/create-entities storage :binding
                                 (mapv #(hash-map :fn-id (:id %) :slot-id (:id slot) :value 1) owners))
        query (fn [ids]
                (let [calls (atom [])
                      result (binding [util/*jdbc-override* (jdbc-spy calls)]
                               (sp/query-bounded-entities storage :binding {:fn-id ids} 64))]
                  [(count result) (count @calls)]))]
    ;; Prime the immutable branch-chain cache before comparing query families.
    (sp/query-bounded-entities storage :binding {:fn-id [(:id (first owners))]} 64)
    (let [[one one-queries] (query [(:id (first owners))])
          [all all-queries] (query (mapv :id owners))]
      (is (= 1 one))
      (is (= (count rows) all))
      (is (pos? one-queries) "The JDBC spy observed actual SQL")
      (is (= one-queries all-queries) "A 64-owner batch uses the same SQL round trips"))))
