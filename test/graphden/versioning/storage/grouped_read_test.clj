(ns ^:integration graphden.versioning.storage.grouped-read-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.browser-source :as source]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture))


(defn- queries
  [fn-slot binding-row item]
  [{:op :query :entity :fn-slot :where {:id [(:id fn-slot)]}}
   {:op :query :entity :binding :where {:id [(:id binding-row)] :value-present true}}
   {:op :query :entity :binding-list-item :where {:id [(:id item)]}}
   {:op :query :entity :binding :where {:id []}}])


(defn- compare-reads
  [storage requests]
  (let [scalar (mapv #(set (grouped/scalar-operation storage %)) requests)
        together (mapv set (grouped/read-operations storage requests))]
    (is (= scalar together))
    together))


(deftest grouped-version-reads-preserve-branch-merge-tombstone-and-owner-predicates
  (let [storage (setup/create-versioned-test-storage)
        owner (sp/create-entity storage :fn {:name "grouped-owner"})
        slot (sp/create-entity storage :slot {:name "value" :type-fn-id (:id owner)})
        fn-slot (sp/create-entity storage :fn-slot {:fn-id (:id owner) :slot-id (:id slot) :position 0})
        binding-row (sp/create-entity storage :binding {:fn-id (:id owner) :slot-id (:id slot) :value false})
        item (sp/create-entity storage :binding-list-item {:binding-id (:id binding-row) :position 0 :value "old"})
        feature (vs/switch-branch storage (:id (vs/create-branch! storage "grouped-source")))
        target (vs/switch-branch storage (:id (vs/create-branch! storage "grouped-target")))
        requests (queries fn-slot binding-row item)]
    (sp/update-entity feature :binding (:id binding-row) {:value nil})
    (sp/update-entity feature :fn-slot (:id fn-slot) {:position 2})
    (binding [vs/*tombstone-delete?* true]
      (sp/delete-entity feature :binding-list-item (:id item)))
    (let [[slots bindings items empty-rows] (compare-reads storage requests)]
      (is (= [0 false "old" #{}]
             [(:position (first slots)) (:value (first bindings))
              (:value (first items)) empty-rows])))
    (compare-reads feature requests)
    (vs/merge-branch! target (:branch-id feature))
    (let [[slots bindings items] (compare-reads target requests)]
      (is (= 2 (:position (first slots))))
      (is (nil? (:value (first bindings))))
      (is (true? (:value-present (first bindings))))
      (is (empty? items)))
    (is (= [#{} #{}]
           (compare-reads target
                          [{:op :query :entity :binding
                            :where {:id [(:id binding-row)] :fn-id (random-uuid)}}
                           {:op :query :entity :fn-slot
                            :where {:id [(random-uuid)]}}])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pagination"
          (grouped/read-operations target
                                   [(assoc (first requests) :opts {:limit 1})])))
    (let [identity (sp/create-entity (vs/unwrap target) :fn {:name "grouped-no-version"})]
      (is (= [{} []]
             (grouped/read-operations target
                                      [{:op :read-ids :entity :fn :ids []}
                                       {:op :query :entity :fn :where {:id [(:id identity)]}}])))
      (is (= (:id identity)
             (get-in (grouped/read-operations target
                                              [{:op :read-ids :entity :fn :ids [(:id identity)]}])
                     [0 (:id identity) :id]))))))


(defrecord ScalarReads
  [base-storage]

  sp/StorageCRUD

  (read-entity [_ e id] (sp/read-entity base-storage e id))


  (query-entities [_ e where] (sp/query-entities base-storage e where))


  (query-entities [_ e where opts] (sp/query-entities base-storage e where opts))


  (query-latest-per-group [_ e where cols] (sp/query-latest-per-group base-storage e where cols))


  sp/StorageBatchCRUD

  (read-entities [_ e ids] (sp/read-entities base-storage e ids))


  sp/StorageBoundedQuery

  (query-identity-candidates
    [_ e where v n]
    (sp/query-identity-candidates base-storage e where v n))


  (query-bounded-entities
    [_ e where n]
    (sp/query-bounded-entities base-storage e where n)))


(defn- observed-closure
  [storage root]
  (let [calls (atom [])
        observe (fn [execute]
                  (fn [ds sql opts]
                    (swap! calls conj (first sql))
                    (execute ds sql opts)))
        graph (binding [util/*jdbc-override* {:execute! (observe jdbc/execute!)
                                              :execute-one! (observe jdbc/execute-one!)}]
                (source/with-snapshot storage
                                      (fn [snapshot _]
                                        (source/collect-closure snapshot [root] (constantly nil)))))]
    {:graph (update-vals graph set) :sql @calls}))


(deftest grouped-source-reduces-real-exchanges-with-depth-and-shared-dependencies
  (let [storage (setup/create-versioned-test-storage)
        type (sp/create-entity storage :fn {:name "grouped-shared-type"})
        slot (sp/create-entity storage :slot {:name "value" :type-fn-id (:id type)})
        owners (reduce (fn [rows n]
                         (conj rows
                               (sp/create-entity storage :fn
                                                 {:name (str "grouped-depth-" n)
                                                  :parent-ids (cond-> [(:id type)]
                                                                (seq rows) (conj (:id (peek rows))))})))
                       [] (range 8))
        root (:id (peek owners))]
    (doseq [owner owners]
      (sp/create-entity storage :fn-slot {:fn-id (:id owner) :slot-id (:id slot) :position 0})
      (sp/create-entity storage :binding {:fn-id (:id owner) :slot-id (:id slot) :value nil}))
    (let [scalar (observed-closure (->ScalarReads storage) root)
          together (observed-closure storage root)
          unions (filter #(str/includes? % "UNION ALL") (:sql together))]
      (is (= (:graph scalar) (:graph together)))
      (is (seq unions) "Production grouped transport, not a mock dispatch counter")
      (is (<= (count (:sql together)) (- (count (:sql scalar)) 8))
          "Depth amortizes schema discovery; at least one exchange saved per owner")
      (is (every? #(not (str/includes? % "UNION ALL")) (:sql scalar))
          "An unknown outer wrapper retains the scalar path"))))
