(ns ^:serial graphden.executor.browser-source-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-contracts :as contracts]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.revision-manifest :as manifest]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.bounded-query :as bounded]
    [graphden.storage.grouped-read :as grouped]
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


(deftest server-identity-hints-never-load-stale-owned-source
  (let [root (random-uuid) old (random-uuid) fresh (random-uuid)
        hint {:fns [{:id root :parent-ids [old]} {:id old}]}
        graph {:fns [{:id root :parent-ids [fresh]} {:id old} {:id fresh}]
               :bindings [{:id (random-uuid) :fn-id old :value "stale private body"}
                          {:id (random-uuid) :fn-id fresh :value "fresh body"}]}
        calls (atom [])
        actual (source/collect-identity-hinted-closure
                 (fixture-storage (graph-tables graph) calls) [root] (constantly nil) hint)]
    (is (= #{root fresh} (set (map :id (:fns actual)))))
    (is (= ["fresh body"] (mapv :value (:bindings actual))))
    (is (not-any? (fn [[op entity where]]
                    (and (contains? #{:query :candidates} op)
                         (contains? #{:binding :fn-slot} entity)
                         (some #{old} (:fn-id where)))) @calls))))


(deftest witness-batch-authorizes-all-owners-before-reading-values
  (let [ids (vec (repeatedly 100 random-uuid))
        graph {:fns (mapv #(hash-map :id %) ids)}
        calls (atom [])
        storage (fixture-storage (graph-tables graph) calls)
        denied (last ids)]
    (is (thrown? clojure.lang.ExceptionInfo
          (source/capture-nodes storage ids
                                #(when (= denied (:id %)) (throw (ex-info "denied" {}))))))
    (is (not-any? (fn [[op entity]]
                    (and (contains? #{:query :candidates} op)
                         (contains? #{:binding :fn-slot} entity))) @calls))))


(deftest witness-prepares-namespace-paths-before-individual-authorizations
  (let [ids (vec (repeatedly 3 random-uuid))
        graph {:fns (mapv #(hash-map :id %) ids)}
        events (atom [])
        authorize! (with-meta #(swap! events conj [:authorize (:id %)])
                     {:graphden.tenancy.context/prepare-namespaces
                      #(swap! events conj [:prepare (set (map :id %))])})]
    (source/capture-nodes (fixture-storage (graph-tables graph) (atom [])) ids authorize!)
    (is (= [:prepare (set ids)] (first @events)))
    (is (= (set ids) (set (map second (rest @events)))))))


(deftest client-frontier-prepares-namespace-paths-before-authorization
  (let [root (random-uuid) parent (random-uuid)
        graph {:fns [{:id root :parent-ids [parent]} {:id parent}]}
        events (atom [])
        authorize! (with-meta #(swap! events conj [:authorize (:id %)])
                     {:graphden.tenancy.context/prepare-namespaces
                      #(swap! events conj [:prepare (set (map :id %))])})]
    (source/collect-client-closure (fixture-storage (graph-tables graph) (atom []))
                                   [root] authorize! graph)
    (is (= [[:prepare #{root}] [:authorize root]
            [:prepare #{parent}] [:authorize parent]] @events))))


(deftest client-identity-hints-never-prefetch-unreachable-owned-payload
  (let [root (random-uuid) old (random-uuid) fresh (random-uuid)
        hint {:fns [{:id root :parent-ids [old]} {:id old}]}
        current {:fns [{:id root :parent-ids [fresh]} {:id fresh} {:id old}]
                 :bindings [{:id (random-uuid) :fn-id old :value "hidden old payload"}
                            {:id (random-uuid) :fn-id fresh :value "fresh reachable payload"}]}
        calls (atom [])
        authorize! (fn [row & _]
                     (swap! calls conj [:authorize (:id row)])
                     (when (= old (:id row)) (throw (ex-info "Denied old hint" {:type :authz/forbidden}))))
        result (source/collect-client-closure (fixture-storage (graph-tables current) calls) [root] authorize! hint)]
    (is (= #{root fresh} (set (map :id (:fns result)))))
    (is (= ["fresh reachable payload"] (mapv :value (:bindings result))))
    (is (not-any? #{[:authorize old]} @calls))
    (is (not-any? (fn [[op entity where]]
                    (and (contains? #{:query :candidates} op)
                         (contains? #{:binding :fn-slot} entity)
                         (some #{old} (:fn-id where)))) @calls))))


(deftest unknown-and-oversized-client-hints-retain-ordinary-reads
  (let [root (random-uuid) extras (vec (repeatedly 2048 random-uuid))]
    (doseq [hint [nil {:fns []} {:fns {root 42}}
                  {:fns (cons {:id root :parent-ids extras} (for [id extras] {:id id}))}]]
      (let [calls (atom [])
            storage (fixture-storage {:fn {root {:id root}}} calls)]
        (is (= [{:id root}] (:fns (source/collect-client-closure storage [root] (constantly nil) hint))))
        (is (= [[:read :fn root]] (filterv #(= :read (first %)) @calls)))))))


(deftest identity-hints-never-cross-storage-or-cache-authorization
  (let [root (random-uuid)
        graph {:fns [{:id root :name "outer"}]}
        outer (fixture-storage (graph-tables graph) (atom []))
        inner (fixture-storage {:fn {root {:id root :name "inner"}}} (atom []))
        nested-name (atom nil)
        nested-denied? (atom false)]
    (source/collect-client-closure
      outer [root]
      (fn [_]
        (reset! nested-name (:name (first (:fns (source/collect-client-closure inner [root] (constantly nil))))))
        (try
          (source/collect-client-closure outer [root]
                                         (fn [_] (throw (ex-info "New policy denies" {:type :authz/forbidden}))))
          (catch clojure.lang.ExceptionInfo error
            (reset! nested-denied? (= :authz/forbidden (:type (ex-data error)))))))
      graph)
    (is (= "inner" @nested-name))
    (is (true? @nested-denied?))))


(deftest fresh-metadata-frontier-batches-only-actually-reached-owners
  (let [ids (vec (repeatedly 20 random-uuid))
        graph {:fns (conj (mapv (fn [id parent] {:id id :parent-ids [parent]})
                                (butlast ids) (rest ids)) {:id (peek ids)})
               :bindings [{:id (random-uuid) :fn-id (peek ids) :value "reachable"}]}
        calls (atom []) storage (fixture-storage (graph-tables graph) calls)
        ordinary (source/collect-client-closure storage [(first ids)] (constantly nil))
        owner-candidate? #(and (= :candidates (first %)) (contains? #{:fn-slot :binding} (second %)))
        old-candidates (count (filter owner-candidate? @calls))]
    (reset! calls [])
    (let [result (source/collect-client-closure storage [(first ids)] (constantly nil) graph)]
      (is (= (graph-rows ordinary) (graph-rows result)))
      (is (= 40 old-candidates))
      (is (= 2 (count (filter owner-candidate? @calls))))
      (is (= (set ids) (set (get-in (first (filter #(= [:query :binding] (take 2 %)) @calls)) [2 :fn-id])))))))


(deftest malformed-interface-owner-is-refused-before-traversing-its-parent
  (let [root (random-uuid) hidden (random-uuid) calls (atom [])
        row {:id root :parent-ids []}
        contract {:fn row :slots [] :fn-slots []}
        storage (fixture-storage {:fn {root (assoc row :parent-ids [hidden]) hidden {:id hidden}}
                                  :binding {(random-uuid) {:fn-id hidden :value "private"}}} calls)]
    (is (= :public-type-contract-mismatch
           (try (source/collect-client-closure storage [root] (constantly {:type-contract contract}) {:fns [row]})
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (not-any? #{[:read :fn hidden]} @calls))
    (is (not-any? #(= :query (first %)) @calls))))


(deftest malformed-interface-slot-is-refused-before-traversing-its-type
  (let [root (random-uuid) allowed (random-uuid) hidden (random-uuid)
        slot (random-uuid) junction (random-uuid) calls (atom [])
        row {:id root :parent-ids []}
        declaration {:id slot :type-fn-id allowed}
        edge {:id junction :fn-id root :slot-id slot}
        hint {:fns [row {:id allowed}] :fn-slots [edge] :slots [declaration]}
        contract {:fn row :fn-slots [edge] :slots [declaration]}
        storage (fixture-storage {:fn {root row allowed {:id allowed} hidden {:id hidden}}
                                  :slot {slot (assoc declaration :type-fn-id hidden)}
                                  :fn-slot {junction edge}
                                  :binding {(random-uuid) {:fn-id hidden :value "private"}}} calls)]
    (is (= :public-type-contract-mismatch
           (try (source/collect-client-closure storage [root]
                                               #(when (= root (:id %)) {:type-contract contract}) hint)
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (not-any? #{[:read :fn hidden]} @calls))
    (is (not-any? (fn [[operation entity where]]
                    (and (= [:query :binding] [operation entity])
                         (some #{hidden} (:fn-id where)))) @calls))))


(deftest actual-metadata-overflow-stops-before-any-owned-values
  (let [root (random-uuid) parents (vec (repeatedly 2048 random-uuid)) calls (atom [])
        storage (fixture-storage {:fn {root {:id root :parent-ids parents}}} calls)]
    (is (= :source-function-limit
           (try (source/collect-client-closure storage [root] (constantly nil) {:fns [{:id root}]})
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (not-any? #(contains? #{:query :candidates} (first %)) @calls))))


(deftest fresh-slot-type-must-exist-and-be-authorized-before-its-values
  (let [root (random-uuid) type (random-uuid) slot (random-uuid) junction (random-uuid)]
    (doseq [present? [false true]]
      (let [calls (atom [])
            tables {:fn (cond-> {root {:id root}} present? (assoc type {:id type}))
                    :slot {slot {:id slot :type-fn-id type}}
                    :fn-slot {junction {:id junction :fn-id root :slot-id slot}}
                    :binding {(random-uuid) {:fn-id type :value "not yet read"}}}
            error (try
                    (source/collect-client-closure
                      (fixture-storage tables calls) [root]
                      (fn [row]
                        (when (= type (:id row)) (throw (ex-info "Denied type" {:type :authz/forbidden}))))
                      {:fns [{:id root}]})
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= (if present? :authz/forbidden :browser-plan/unsupported) (:type (ex-data error))))
        (is (not-any? (fn [[operation entity where]]
                        (and (= [:query :binding] [operation entity])
                             (some #{type} (:fn-id where)))) @calls))))))


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
        hint {:fns (cons {:id root :parent-ids extras} (for [id extras] {:id id}))}
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
    (let [ordinary (source/collect-manifest storage root (constantly nil))]
      (reset! calls [])
      (is (= (graph-rows ordinary)
             (graph-rows
               (source/collect-manifest storage root
                                        (fn [row & [permission]]
                                          (when (= target (:id row))
                                            (is (= {:identity-only? true} permission))))
                                        graph))))
      (is (not-any? (fn [[operation entity where]]
                      (and (contains? #{:query :candidates} operation)
                           (contains? #{:binding :fn-slot} entity)
                           (some #{target} (:fn-id where)))) @calls)))
    (reset! calls [])
    (is (= :source-missing
           (try (source/collect-closure storage [root] (constantly nil))
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (some (fn [[operation entity where]]
                (and (= operation :query) (= entity :binding)
                     (some #{target} (:fn-id where)))) @calls))))


(deftest manifest-batches-inheritance-before-owned-source-reads
  (let [chain (vec (repeatedly 6 random-uuid))
        calls (atom [])
        storage (fixture-storage
                  {:fn (keyed (map-indexed (fn [index id]
                                             {:id id :parent-ids (vec (take 1 (drop (inc index) chain)))})
                                           chain))}
                  calls)
        graph (source/collect-manifest storage (first chain) (constantly nil))
        owner-scans (filter #(= [:candidates :fn-slot] (take 2 %)) @calls)]
    (is (= (set chain) (set (map :id (:fns graph)))))
    (is (= [(set chain)] (map #(set (get-in % [2 :fn-id])) owner-scans))
        "One owner scan covers the freshly reached inheritance chain")
    (reset! calls [])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denied"
          (source/collect-manifest storage (first chain)
                                   (fn [row]
                                     (when (= (second chain) (:id row))
                                       (throw (ex-info "denied" {:type :authz/forbidden})))))))
    (is (not-any? #(contains? #{:candidates :query} (first %)) @calls)
        "A denied metadata ancestor stops before any owned source is acquired")))


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
    (reset! calls [])
    (let [hinted (source/collect-client-closure
                   (fixture-storage tables calls) [root]
                   (fn [row & [permissions]]
                     (when (= target (:id row))
                       (is (= {:execute? true :server-target? true} permissions))))
                   graph)]
      (is (= (graph-rows graph) (graph-rows hinted)))
      (is (not-any? (fn [[operation entity where]]
                      (and (contains? #{:query :candidates} operation)
                           (contains? #{:binding :fn-slot} entity)
                           (some #{target} (:fn-id where)))) @calls)))
    (let [denied? (atom false)
          guard (fn [row & [permissions]]
                  (when (= target (:id row))
                    (is (= {:execute? true :server-target? true} permissions))
                    (when @denied?
                      (throw (ex-info "Execute revoked" {:type :authz/forbidden})))))
          ;; Exercise the hit callback independently of the controller tests.
          hit (fn [_ kind _ _ _ validate!]
                (is (= :client kind))
                (let [retained (dissoc graph :server-bindings)]
                  (validate! retained)
                  retained))]
      (with-redefs [manifest/capture hit
                    contracts/component-identities (assoc contracts/component-identities target {})]
        (doseq [hinted? [false true]]
          (reset! calls [])
          (let [storage (fixture-storage tables calls)
                actual (if hinted?
                         (source/collect-client-closure storage [root] guard {})
                         (source/collect-client-closure storage [root] guard))]
            (is (= (graph-rows graph) (graph-rows actual)))
            (is (every? (fn [[operation entity]] (and (= :read operation) (= :fn entity))) @calls))))
        (reset! denied? true)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Execute revoked"
              (source/collect-client-closure (fixture-storage tables calls) [root] guard)))
        (is (= :source-missing
               (try (source/collect-client-closure
                      (fixture-storage (update tables :fn dissoc target) calls) [root] guard)
                    (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))
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
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
      (is (= :mixed-server-target
             (try (source/collect-client-closure (fixture-storage mixed (atom [])) [root] (constantly nil) graph)
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
      (doseq [hinted? [false true]]
        (let [calls (atom [])
              storage (fixture-storage mixed calls)
              guard (fn [row & [permission]]
                      (when (and (= target (:id row)) (not (:server-target? permission)))
                        (throw (ex-info "Private ordinary dependency" {:type :authz/forbidden}))))]
          (is (= :authz/forbidden
                 (try (if hinted? (source/collect-client-closure storage [root] guard graph)
                          (source/collect-client-closure storage [root] guard))
                      (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
          (is (not-any? (fn [[operation entity where]]
                          (and (= operation :query) (= :binding entity)
                               (some #{target} (:fn-id where)))) @calls)))))))


(defn- grouped-fixture
  [base calls candidate-reader]
  (reify sp/StorageCRUD
    (create-entity [_ entity data] (sp/create-entity base entity data))

    (read-entity [_ entity id] (sp/read-entity base entity id))

    (update-entity [_ entity id data] (sp/update-entity base entity id data))

    (delete-entity [_ entity id] (sp/delete-entity base entity id))

    (query-entities [_ entity where] (sp/query-entities base entity where))

    (query-entities [_ entity where opts] (sp/query-entities base entity where opts))

    (query-latest-per-group [_ entity where cols] (sp/query-latest-per-group base entity where cols))


    sp/StorageBoundedQuery

    (query-identity-candidates [_ e w v n] (candidate-reader base e w v n))

    (query-bounded-entities [_ e w n] (sp/query-bounded-entities base e w n))


    sp/StorageGroupedRead

    (read-operations
      [_ requests]
      (swap! calls conj [:group (mapv :entity requests)])
      (mapv #(grouped/scalar-operation base %) requests))))


(deftest grouped-source-preserves-authorization-before-bodies-and-candidate-barriers
  (let [root (random-uuid)
        graph {:fns [{:id root}]
               :bindings [{:id (random-uuid) :fn-id root :value-present true :value nil}]}
        calls (atom [])
        base (fixture-storage (graph-tables graph) calls)
        storage (grouped-fixture base calls sp/query-identity-candidates)
        authorize (fn [row] (swap! calls conj [:authorize (:id row)]))
        ordinary (source/collect-closure base [root] (constantly nil))]
    (reset! calls [])
    (is (= (graph-rows ordinary)
           (graph-rows (source/collect-closure storage [root] authorize))))
    (is (= [[:read :fn root] [:authorize root]
            [:candidates :fn-slot {:fn-id [root]} 0]
            [:candidates :binding {:fn-id [root]} 1]
            [:group [:fn-slot :binding]]]
           (subvec @calls 0 5)))
    (reset! calls [])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denied"
          (source/collect-closure storage [root]
                                  (fn [_] (throw (ex-info "denied" {}))))))
    (is (= [[:read :fn root]] @calls) "Denied owners cause no candidate or payload scan")
    (reset! calls [])
    (let [overflow (grouped-fixture
                     base calls
                     (fn [st e w v n]
                       (if (= e :binding)
                         (throw (ex-info "overflow" {:type :storage-error/candidate-limit}))
                         (sp/query-identity-candidates st e w v n))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source is unavailable"
            (source/collect-closure overflow [root] authorize)))
      (is (not-any? #(contains? #{:query :group} (first %)) @calls)
          "A later candidate overflow prevents even the earlier owner's payload"))))


(deftest retained-server-source-preserves-execute-only-inspection
  (let [target (random-uuid)
        dependency (random-uuid)
        rows {:fns [{:id target :parent-ids [dependency]} {:id dependency}]
              :bindings [{:id (random-uuid) :fn-id dependency :value "private dependency"}]}
        tables (graph-tables rows)
        calls (atom [])
        retained (atom nil)
        revoked? (atom false)
        authorized (atom [])
        guard (fn [row options]
                (swap! authorized conj [(:id row) options])
                (when-not (and (= target (:id row)) (= {:execute? true :server-target? true} options)
                               (not @revoked?))
                  (throw (ex-info "Execute denied" {:type :authz/forbidden}))))
        capture (fn [_ kind _ authorizer run validate!]
                  (is (= :server kind))
                  (is (identical? guard authorizer))
                  (if-let [graph @retained]
                    (do (validate! graph) graph)
                    (let [graph (run)] (reset! retained graph) graph)))]
    (with-redefs [manifest/capture capture]
      (let [cold (source/collect-server-closure (fixture-storage tables calls) target guard nil)]
        (reset! calls [])
        (let [warm (source/collect-server-closure (fixture-storage tables calls) target guard {})]
          (is (= (graph-rows cold) (graph-rows warm)))
          (is (= [[target {:execute? true :server-target? true}]] @authorized))
          (is (every? (fn [[operation entity]] (and (= :read operation) (= :fn entity))) @calls))))
      (reset! revoked? true)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Execute denied"
            (source/collect-server-closure (fixture-storage tables calls) target guard nil)))
      (reset! revoked? false)
      (is (= :source-missing
             (try (source/collect-server-closure
                    (fixture-storage (assoc-in tables [:fn dependency :name] "changed") calls) target guard nil)
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))
