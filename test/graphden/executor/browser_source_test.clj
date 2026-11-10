(ns graphden.executor.browser-source-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-contracts :as contracts]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.packages.records.ids :as ids]
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
        (swap! calls conj [:candidates entity where (count rows)])
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


(defn- graph-tables
  [graph]
  (into {} (map (fn [[entity collection]] [entity (keyed (get graph collection))]))
        {:fn :fns :slot :slots :fn-slot :fn-slots :binding :bindings :binding-list-item :list-items}))


(defn- graph-rows
  [graph]
  (update-vals graph set))


(deftest hinted-general-closure-batches-depth-and-shared-dependencies
  (let [ids (vec (repeatedly 20 random-uuid))
        common (random-uuid)
        graph {:fns (conj (mapv (fn [id next-id] {:id id :parent-ids [next-id common]})
                                (butlast ids) (rest ids))
                          {:id (peek ids)} {:id common})
               :bindings [{:id (random-uuid) :fn-id (peek ids) :value "fresh"}]}
        calls (atom [])
        storage (fixture-storage (graph-tables graph) calls)
        ordinary (source/collect-closure storage [(first ids)] (constantly nil))
        ordinary-scans (count (filter #(= :candidates (first %)) @calls))
        _ (reset! calls [])
        hinted (source/collect-closure storage [(first ids)] (constantly nil) graph)
        hinted-queries (count (filter #(= :query (first %)) @calls))]
    (is (= (graph-rows ordinary) (graph-rows hinted)))
    (is (> ordinary-scans 35))
    (is (= 1 hinted-queries) "one nonempty owner query; empty owner groups are cached too")
    (is (= 3 (count (filter #(= :candidates (first %)) @calls)))
        "bounded candidate scans are independent of closure depth")))


(deftest hinted-values-are-never-authoritative-and-new-edges-are-followed
  (let [root (random-uuid) old (random-uuid) fresh (random-uuid) binding (random-uuid)
        hint {:fns [{:id root} {:id old}]
              :bindings [{:id binding :fn-id root :ref-fn-id old :value "stale"}]}
        current {:fns [{:id root} {:id old} {:id fresh}]
                 :bindings [{:id binding :fn-id root :ref-fn-id fresh :value "current"}]}
        calls (atom [])
        storage (fixture-storage (graph-tables current) calls)
        ordinary (source/collect-closure storage [root] (constantly nil))
        hinted (source/collect-closure storage [root] (constantly nil) hint)]
    (is (= (graph-rows ordinary) (graph-rows hinted)))
    (is (= #{root fresh} (set (map :id (:fns hinted)))))
    (is (= "current" (:value (first (:bindings hinted)))))))


(deftest denied-or-missing-stale-hints-fall-back-before-body-reads
  (let [root (random-uuid) extra (random-uuid)
        hint {:fns [{:id root :parent-ids [extra]} {:id extra}]}
        calls (atom [])
        authorize! (fn [row]
                     (when (= extra (:id row))
                       (throw (ex-info "Denied" {:type :authz/forbidden}))))]
    (doseq [extra-row [nil {:id extra}]]
      (reset! calls [])
      (let [storage (fixture-storage
                      {:fn (cond-> {root {:id root}} extra-row (assoc extra extra-row))
                       :binding {(random-uuid) {:id (random-uuid) :fn-id extra :value "private"}}}
                      calls)
            result (source/collect-closure storage [root] authorize! hint)]
        (is (= [{:id root}] (:fns result)))
        (is (not-any? (fn [[operation entity where]]
                        (and (= :query operation) (= :binding entity)
                             (some #{extra} (:fn-id where)))) @calls))))))


(deftest oversized-hint-does-not-expand-the-request-or-its-function-budget
  (let [root (random-uuid)
        extras (vec (repeatedly 2048 random-uuid))
        hint {:fns (cons {:id root :parent-ids extras} (map #(hash-map :id %) extras))}
        calls (atom [])
        storage (fixture-storage {:fn {root {:id root}}} calls)]
    (is (= [{:id root}]
           (:fns (source/collect-closure storage [root] (constantly nil) hint))))
    (is (= [[:read :fn root]] (filterv #(= :read (first %)) @calls)))))


(deftest stale-extra-budget-exhaustion-retries-once-with-ordinary-semantics
  (let [root (random-uuid) extra (random-uuid) fresh (random-uuid)
        hint {:fns [{:id root :parent-ids [extra]} {:id extra}]}
        extra-bindings (mapv (fn [_] {:id (random-uuid) :fn-id extra :value "unused"}) (range 16384))
        current-binding {:id (random-uuid) :fn-id fresh :value "current"}
        current {:fns [{:id root :parent-ids [fresh]} {:id extra} {:id fresh}]
                 :bindings (conj extra-bindings current-binding)}
        calls (atom [])
        storage (fixture-storage (graph-tables current) calls)
        result (source/collect-closure storage [root] (constantly nil) hint)
        candidates (filter #(= :candidates (first %)) @calls)]
    (is (= #{root fresh} (set (map :id (:fns result)))))
    (is (= [current-binding] (:bindings result)))
    (is (= 2 (count (filter #{[:read :fn root]} @calls))) "One speculative pass and one ordinary retry")
    (is (<= (reduce + (map #(nth % 3) candidates)) (+ (* 2 16384) 2))
        "Physical candidate work is bounded, including overflow sentinels")))


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


(deftest manifest-identity-targets-are-authorized-without-body-reads
  (let [root (random-uuid)
        sibling-root (random-uuid)
        parent (random-uuid)
        target (ids/fn-id "app.ui-tutorial-catalog" :catalog-view)
        const-id (ids/fn-id "core.logic" :const)
        slot-id (ids/slot-id const-id :value)
        any-id (ids/primitive-fn-id :any)
        calls (atom [])
        authorized (atom {})
        tables {:fn (keyed [{:id root :parent-ids [parent]}
                            {:id sibling-root :parent-ids [parent]}
                            {:id parent :parent-ids [const-id]}
                            {:id const-id :return-type-fn-id any-id :parent-ids []}
                            {:id any-id :parent-ids []}
                            {:id ids/fn-ref-type-id :parent-ids []}
                            {:id target :parent-ids [(random-uuid)]}])
                :slot {slot-id {:id slot-id :name "value" :type-fn-id any-id}}
                :fn-slot (keyed [{:id (random-uuid) :fn-id const-id :slot-id slot-id :position 0}])
                :binding (keyed [{:id (random-uuid) :fn-id parent :slot-id slot-id
                                  :type-override-fn-id ids/fn-ref-type-id}
                                 {:id (random-uuid) :fn-id root :slot-id slot-id :ref-fn-id target}
                                 {:id (random-uuid) :fn-id target :value-present true :value "private body"}])}
        storage (fixture-storage tables calls)
        graph (source/collect-manifests storage [root sibling-root]
                                        (fn [row & [permission]]
                                          (swap! authorized assoc (:id row) permission)))]
    (is (every? (set (map :id (:fns graph))) [root sibling-root]))
    (is (contains? @authorized target))
    (is (= {:identity-only? true} (get @authorized target)))
    (is (contains? (set (map :id (:fns graph))) target))
    (is (not-any? #(= target (:fn-id %)) (:bindings graph)))
    (is (not-any? (fn [[operation entity where]]
                    (and (= operation :query) (contains? #{:binding :fn-slot} entity)
                         (some #{target} (:fn-id where)))) @calls))
    (reset! calls [])
    (is (= :source-missing
           (try (source/collect-closure storage [root] (constantly nil))
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (some (fn [[operation entity where]]
                (and (= operation :query) (= entity :binding)
                     (some #{target} (:fn-id where)))) @calls))))


(deftest manifest-roots-share-one-function-budget
  (let [roots (vec (repeatedly 2049 random-uuid))
        calls (atom [])
        storage (fixture-storage {:fn (keyed (for [id roots] {:id id}))} calls)]
    (is (= :source-function-limit
           (try (source/collect-manifests storage roots (constantly nil))
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (not-any? #(= :query (first %)) @calls)
        "Combined identity overflow refuses before any body or literal query")))


(deftest shipped-discriminator-permission-is-explicitly-metadata-only
  (is (= "app.ui-tutorial-catalog"
         (get contracts/component-identities (ids/fn-id "app.ui-tutorial-catalog" :catalog-view))))
  (doseq [id (keys contracts/component-identities)]
    (let [calls (atom [])
          permissions (atom [])
          row {:id id :name "shipped-view" :parent-ids [(random-uuid)]}
          storage (fixture-storage {:fn {id row}
                                    :binding {(random-uuid) {:fn-id id :value "never read"}}} calls)]
      (is (= [row] (source/collect-component-identities
                     storage [id] (fn [actual permission]
                                    (swap! permissions conj [(:id actual) permission])))))
      (is (= [[id {:identity-only? true}]] @permissions))
      (is (= [[:read :fn id]] @calls))
      (is (= :invalid-component-identities
             (try (source/collect-component-identities storage [(random-uuid)] (constantly nil))
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
      (is (= [[:read :fn id]] @calls)))))


(deftest explicit-server-call-target-is-terminal-before-body-or-capture-reads
  (let [root (random-uuid)
        target (random-uuid)
        primitive capabilities/server-call-id
        slot capabilities/server-call-slot-id
        fn-type (ids/primitive-fn-id :fn)
        text-type (ids/primitive-fn-id :text)
        calls (atom [])
        authorized (atom {})
        tables {:fn (keyed [{:id root :parent-ids [primitive]}
                            {:id primitive :parent-ids [] :return-type-fn-id text-type}
                            {:id fn-type :parent-ids []}
                            {:id text-type :parent-ids []}
                            {:id target :parent-ids [(random-uuid)]}])
                :slot {slot {:id slot :name "func" :type-fn-id fn-type}}
                :fn-slot (keyed [{:id (random-uuid) :fn-id primitive :slot-id slot :position 0}])
                :binding (keyed [{:id (random-uuid) :fn-id root :slot-id slot :ref-fn-id target}
                                 {:id (random-uuid) :fn-id target :value-present true :value "server capture"}])}
        graph (source/collect-client-closure (fixture-storage tables calls) [root]
                                             (fn [row & [permissions]]
                                               (swap! authorized assoc (:id row) permissions)))]
    (is (= {:execute? true :server-target? true} (get @authorized target)))
    (is (contains? (:identity-target-ids graph) target))
    (is (= [target] (mapv :ref-fn-id (:server-bindings graph))))
    (is (not-any? #(= target (:fn-id %)) (:bindings graph)))
    (is (not-any? (fn [[operation entity where]]
                    (and (= operation :query) (contains? #{:binding :fn-slot} entity)
                         (some #{target} (:fn-id where)))) @calls))
    (let [ordinary-slot (random-uuid)
          junction (random-uuid)
          binding (random-uuid)
          mixed (-> tables
                    (assoc-in [:fn target :parent-ids] [])
                    (assoc-in [:slot ordinary-slot] {:id ordinary-slot :name "ordinary" :type-fn-id text-type})
                    (assoc-in [:fn-slot junction] {:id junction :fn-id root :slot-id ordinary-slot :position 1})
                    (assoc-in [:binding binding] {:id binding :fn-id root :slot-id ordinary-slot :ref-fn-id target}))]
      (is (= :mixed-server-target
             (try (source/collect-client-closure (fixture-storage mixed (atom [])) [root] (constantly nil))
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))


(deftest actual-ancestry-cannot-open-server-target-bodies
  (let [root (random-uuid) target (random-uuid) capture (random-uuid)
        primitive capabilities/server-call-id
        slot capabilities/server-call-slot-id
        fn-type (ids/primitive-fn-id :fn)
        calls (atom [])
        rows [{:id root :parent-ids [primitive]} {:id primitive} {:id fn-type}
              {:id target :parent-ids [capture]} {:id capture}]
        storage (fixture-storage
                  {:fn (keyed rows)
                   :slot {slot {:id slot :name "func" :type-fn-id fn-type}}
                   :fn-slot (keyed [{:id (random-uuid) :fn-id primitive :slot-id slot :position 0}])
                   :binding (keyed [{:id (random-uuid) :fn-id root :slot-id slot :ref-fn-id target}
                                    {:id (random-uuid) :fn-id target :value "private target"}
                                    {:id (random-uuid) :fn-id capture :value "private capture"}])}
                  calls)]
    (let [graph (source/collect-client-closure storage [root] (constantly nil))]
      (is (contains? (:identity-target-ids graph) target))
      (is (not (contains? (set (map :id (:fns graph))) capture)))
      (is (not-any? #{[:read :fn capture]} @calls) "Even capture function metadata remains unread")
      (is (not-any? (fn [[operation entity where]]
                      (and (= operation :candidates) (contains? #{:binding :fn-slot} entity)
                           (some #{target capture} (:fn-id where)))) @calls)))))


(deftest strict-collector-interface-rejection-precedes-ancestry-payload
  (doseq [[authorization reason]
          [[:bare-type :primitive-type-source-mismatch]
           [{:type-contract {:fn {:id :root}}} :public-type-contract-mismatch]]]
    (let [calls (atom [])
          storage (fixture-storage {:fn {:root {:id :root :parent-ids [:private]}
                                         :private {:id :private}}
                                    :binding {:secret {:id :secret :fn-id :private :value "hidden"}}}
                                   calls)]
      (is (= reason
             (try (source/collect-manifest storage :root (constantly authorization))
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
      (is (not-any? (fn [[operation entity where]]
                      (and (= operation :candidates) (contains? #{:binding :fn-slot} entity)
                           (some #{:private} (:fn-id where)))) @calls)))))
