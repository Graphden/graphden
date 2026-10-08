(ns graphden.executor.browser-source-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-source :as source]
    [graphden.storage.bounded-query :as bounded]
    [graphden.storage.protocol.core :as sp]))


(defn- fixture-storage
  [tables calls]
  (reify sp/StorageCRUD
    (read-entity
      [_ entity id]
      (swap! calls conj [:read entity id])
      (get-in tables [entity id]))

    (query-entities
      [this entity where]
      (sp/query-entities this entity where {}))

    (query-entities
      [_ entity where opts]
      (when (empty? where)
        (throw (ex-info "Unexpected whole-table read" {:entity entity})))
      (swap! calls conj [:query entity where])
      (->> (vals (get tables entity))
           (filter (fn [row]
                     (every? (fn [[field values]]
                               (if (coll? values)
                                 (contains? (set values) (get row field))
                                 (= values (get row field)))) where)))
           (take (:limit opts 20000))
           vec))

    (create-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)


    sp/StorageBoundedQuery

    (query-identity-candidates
      [_ entity where _ max-candidates]
      (let [where (bounded/candidate-where entity where max-candidates)
            rows (filterv (fn [row]
                            (every? (fn [[field values]]
                                      (contains? (set (if (coll? values) values [values]))
                                                 (get row field))) where))
                          (vec (vals (get tables entity))))]
        (swap! calls conj [:candidates entity where])
        (bounded/check-candidate-count! rows max-candidates)
        (with-meta (mapv #(select-keys % [:id :org-id]) rows)
          {::sp/candidate-count (count rows)})))

    (query-bounded-entities
      [this entity where max-candidates]
      (let [candidates (sp/query-identity-candidates this entity where nil max-candidates)]
        (with-meta (if (seq candidates)
                     (sp/query-entities this entity (assoc where :id (mapv :id candidates)))
                     [])
          (meta candidates))))))


(defn- keyed
  [rows]
  (into {} (map (juxt :id identity)) rows))


(deftest inaccessible-dependency-is-refused-before-its-literals-are-read
  (let [root (random-uuid)
        hidden (random-uuid)
        binding (random-uuid)
        calls (atom [])
        storage (fixture-storage
                  {:fn (keyed [{:id root} {:id hidden}])
                   :binding (keyed [{:id binding :fn-id root :ref-fn-id hidden}
                                    {:id (random-uuid) :fn-id hidden :value "private literal"}])}
                  calls)
        authorize! (fn [row]
                     (when (= hidden (:id row))
                       (throw (ex-info "Unavailable" {:type :authz/forbidden}))))]
    (is (= :authz/forbidden
           (try (source/collect-closure storage [root] authorize!)
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
    (is (some #{[:read :fn hidden]} @calls) "identity authorization was actually reached")
    (is (not-any? (fn [[operation entity where]]
                    (and (= :query operation) (= :binding entity)
                         (some #{hidden} (:fn-id where)))) @calls)
        "no inaccessible binding/literal query followed that identity read")))


(deftest closure-follows-item-and-slot-types-without-reading-unrelated-graph
  (let [root (random-uuid)
        child (random-uuid)
        type-id (random-uuid)
        unrelated (random-uuid)
        binding (random-uuid)
        slot (random-uuid)
        calls (atom [])
        storage (fixture-storage
                  {:fn (keyed (for [id [root child type-id unrelated]] {:id id}))
                   :fn-slot (keyed [{:id (random-uuid) :fn-id root :slot-id slot}])
                   :slot (keyed [{:id slot :type-fn-id type-id}])
                   :binding (keyed [{:id binding :fn-id root}])
                   :binding-list-item (keyed [{:id (random-uuid) :binding-id binding
                                               :ref-fn-id child}])}
                  calls)
        graph (source/collect-closure storage [root] (constantly nil))]
    (is (= #{root child type-id} (set (map :id (:fns graph)))))
    (is (= #{slot} (set (map :id (:slots graph)))))
    (is (not-any? #{[:read :fn unrelated]} @calls))))


(deftest oversized-frontier-is-refused-before-reading-the-next-generation
  (let [root (random-uuid)
        parents (vec (repeatedly 2048 random-uuid))
        calls (atom [])
        storage (fixture-storage {:fn {root {:id root :parent-ids parents}}} calls)]
    (is (= :source-function-limit
           (try (source/collect-closure storage [root] (constantly nil))
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (= [[:read :fn root]] (filterv #(= :read (first %)) @calls)))))


(deftest public-bare-type-allowance-does-not-allow-a-hidden-custom-predicate
  (let [root (random-uuid)
        calls (atom [])
        storage (fixture-storage
                  {:fn {root {:id root :constraint {:ref-fn-id (random-uuid)}}}}
                  calls)]
    (is (= :primitive-type-source-mismatch
           (try (source/collect-closure storage [root] (constantly :bare-type))
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))


(deftest candidate-row-overflow-stops-before-reading-list-values
  (let [root (random-uuid)
        binding (random-uuid)
        calls (atom [])
        storage (fixture-storage
                  {:fn {root {:id root}}
                   :binding {binding {:id binding :fn-id root}}
                   :binding-list-item (keyed (for [position (range 16384)]
                                               {:id (random-uuid) :binding-id binding
                                                :position position :value "unread literal"}))}
                  calls)]
    (is (= :source-row-limit
           (try (source/collect-closure storage [root] (constantly nil))
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (not-any? #(= [:query :binding-list-item] (take 2 %)) @calls)
        "Only candidate projections were read before overflow")))
