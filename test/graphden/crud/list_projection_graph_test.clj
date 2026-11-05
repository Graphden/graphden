(ns ^:integration ^:serial graphden.crud.list-projection-graph-test
  "Graph policies over one concealed snapshot: parity, secrecy and acquisition budgets."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities.list :as listing]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.test-setup :as setup]
    [graphden.executor.context :as exec-ctx]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.graph-harness :as gh]
    [graphden.types.diagnostics :as diag]))


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(defn- storage-for
  ([namespaces reads] (storage-for namespaces reads []))
  ([namespaces reads services]
   (reify sp/StorageCRUD
    (query-entities [_ entity where]
      (swap! reads conj [entity where])
      (case entity :ns namespaces :service services
            (throw (ex-info "Unexpected projection read" {:entity entity}))))
    (query-entities [_ _ _ _] (throw (ex-info "Unexpected paged read" {})))
    (create-entity [_ _ _] (throw (ex-info "Unexpected write" {})))
    (read-entity [_ _ _] (throw (ex-info "Unexpected per-row read" {})))
    (update-entity [_ _ _ _] (throw (ex-info "Unexpected write" {})))
    (delete-entity [_ _ _] (throw (ex-info "Unexpected write" {})))
    (query-latest-per-group [_ _ _ _] (throw (ex-info "Unexpected grouped read" {}))))))


(defn- graph-of
  [rows]
  {:fns rows :slots [] :fn-slots [] :bindings [] :list-items []})


(deftest usages-graph-preserves-concealment-cap-and-root-summaries
  (let [target (random-uuid)
        service-only (random-uuid)
        slot-id (random-uuid)
        namespace-id (random-uuid)
        denied-namespace-id (random-uuid)
        hidden (random-uuid)
        foreign (random-uuid)
        users (mapv (fn [n]
                      {:id (random-uuid) :name (str "user-" n)
                       :namespace-id namespace-id :org-id "projection-owner"
                       :parent-ids [target]})
                    (range 405))
        anonymous (assoc (first users) :name nil :anonymous-hash "fixture")
        users (assoc users
                     0 anonymous
                     1 (assoc (second users) :name "_anon-fixture")
                     2 (assoc (nth users 2) :parent-ids [target foreign]))
        graph (assoc (graph-of
                (into [{:id service-only :name "service-only" :org-id "projection-owner" :parent-ids []}
                       {:id target :name "target" :org-id "projection-owner" :parent-ids []}
                       {:id hidden :name "hidden" :org-id "projection-owner" :parent-ids [target]}
                       {:id foreign :name "foreign" :org-id "other" :parent-ids [target]}
                       {:id (random-uuid) :name "constraint" :org-id "projection-owner"
                        :constraint [:union :target :text]}]
                      users))
                     :slots [{:id slot-id :name "callback" :org-id "projection-owner"}]
                     :bindings [{:id (random-uuid) :fn-id target :slot-id slot-id
                                 :ref-fn-id service-only :org-id "projection-owner"}])
        reads (atom [])
        ctx {:graph-cache (atom graph)
             :storage (storage-for [{:id namespace-id :name "visible"}
                                    {:id denied-namespace-id :name "denied"}]
                                   reads [{:fn-id target :name "enabled service"}
                                          {:fn-id service-only :name "service-only root"}
                                          {:fn-id foreign :name "invisible service"}])}
        conceal #(listing/strip-impl-of % #{hidden})
        facts-fn types-api/types-usages-facts
        acquisitions (atom 0)]
    (with-redefs [types-api/types-usages-facts
                  (fn [parsed _ _]
                    (swap! acquisitions inc)
                    (facts-fn parsed ctx conceal))]
      (tenancy/with-org "projection-owner"
        (let [facts (facts-fn {:target-id target} ctx conceal)]
          (is (not (contains? (:fns facts) foreign)))
          (is (every? #(every? #{:id :name :role :namespace-id :anonymous-hash} (keys %))
                      (vals (:fns facts))))
          (is (not (contains? (:namespace-paths facts) denied-namespace-id)))
          (is (every? #(contains? (:fns facts) (:target-id %)) (:links facts))))
        (doseq [target-id [target service-only foreign (random-uuid)]]
          (let [parsed {:target-id target-id :root-id (:id anonymous)}]
            (reset! reads [])
            (reset! acquisitions 0)
            (let [actual (gh/exec-name :_types-usages-apply {:parsed parsed})]
              (is (= (str target-id) (:type-fn-id actual)))
              (is (true? (:ok actual)))
              (when (#{foreign} target-id)
                (is (= [] (:usages actual)))
                (is (zero? (:count actual)))
                (is (nil? (:type-name actual))))
              (is (= 1 @acquisitions) "summaries, caps and paths share the same acquisition")
              (is (= [[:ns {}] [:service {:enabled? true}]] @reads))
              (is (not-any? #{(str hidden) (str foreign)} (map :fn-id (:usages actual))))
              (when (= service-only target-id)
                (is (= [:ref-of :service-root] (mapv :kind (:usages actual))))
                (is (= ["callback" "service-only root"] (mapv :slot-name (:usages actual)))))
              (when (= target target-id)
                (is (= 407 (:count actual)))
                (is (= 400 (count (:usages actual))))
                (is (true? (:truncated? actual)))
                (is (= :reachable (get-in actual [:reachability :state])))
                (is (= :service (get-in actual [:reachability :origin])))
                (is (true? (:anonymous (first (:usages actual)))))
                (is (true? (:anonymous (second (:usages actual)))))))))))))


(deftest tree-graph-conceals-facts-and-keeps-anonymous-diagnostics
  (let [named (random-uuid)
        anonymous (random-uuid)
        foreign (random-uuid)
        parent (random-uuid)
        namespace-id (random-uuid)
        types-ns (random-uuid)
        mixed-ns (random-uuid)
        graph (assoc (graph-of [{:id named :name "own" :org-id "projection-owner"
                                :parent-ids [parent]}
                               {:id anonymous :name nil :org-id "projection-owner"
                                :namespace-id namespace-id :parent-ids []}
                               {:id foreign :name "foreign" :org-id "other" :parent-ids []}
                               {:id (random-uuid) :name "type-a" :namespace-id types-ns :parent-ids []}
                               {:id (random-uuid) :name "type-b" :namespace-id types-ns :parent-ids []}
                               {:id (random-uuid) :name "type-c" :namespace-id mixed-ns :parent-ids []}
                               {:id (random-uuid) :name "composed" :namespace-id mixed-ns :parent-ids [parent]}])
                     :extension {:flag false})
        reads (atom [])
        ctx {:graph-cache (atom graph)
             :storage (storage-for [{:id namespace-id :name "anonymous-only"}
                                    {:id types-ns :name "types"}
                                    {:id mixed-ns :name "mixed"}] reads)}
        tree-fn listing/graph-tree-facts
        acquisitions (atom 0)]
    (with-redefs-fn
      {#'listing/view-impl-filter (atom #(listing/strip-impl-of % #{named}))
       #'listing/graph-tree-facts (fn [_] (swap! acquisitions inc) (tree-fn ctx))
       (ns-resolve 'graphden.crud.entities.list 'reverse-ref-index)
       (fn [_] (throw (ex-info "Tree must not compute reverse refs" {})))}
      (fn []
        (binding [diag/*diagnostics-override* (atom {})]
          (diag/record! nil foreign [{:message "foreign diagnostic"}])
          (diag/record! nil anonymous [{:message "anonymous diagnostic"}])
          (tenancy/with-org "projection-owner"
            (let [expected {:namespaces [{:id namespace-id :name "anonymous-only"}
                                          {:id types-ns :name "types"} {:id mixed-ns :name "mixed"}]
                            :counts [{:namespace-id nil :count 1 :fn-count 1}
                                     {:namespace-id types-ns :count 2 :type-count 2}
                                     {:namespace-id mixed-ns :count 2 :type-count 1 :fn-count 1}
                                     {:namespace-id namespace-id :count 0 :type-error-count 1}]}]
              (reset! acquisitions 0)
              (reset! reads [])
              (let [actual (gh/exec-name :graph-tree {})]
                (is (= expected actual))
                (is (= {:namespace-id namespace-id :count 0 :type-error-count 1}
                       (first (filter #(= namespace-id (:namespace-id %)) (:counts actual)))))
                (is (= 1 (reduce + 0 (keep :type-error-count (:counts actual)))))
                (is (= 5 (reduce + 0 (map :count (:counts actual)))))
                (is (= 1 @acquisitions))
                (is (= [[:ns {}]] @reads)))))
          (is (= graph @(:graph-cache ctx)) "facts never mutate the shared graph cache"))))))


(deftest tree-facts-hide-unreadable-namespace-aggregates-before-the-graph
  (let [visible (random-uuid)
        hidden (random-uuid)
        named (random-uuid)
        anonymous (random-uuid)
        reads (atom [])
        ctx {:graph-cache (atom (graph-of [{:id named :name "visible" :namespace-id visible}
                                          {:id anonymous :name nil :namespace-id hidden}]))
             :storage (storage-for [{:id visible :name "visible"} {:id hidden :name "hidden"}] reads)}
        calls (atom 0)]
    (with-redefs [listing/view-impl-filter
                  (atom (fn [payload]
                          (swap! calls inc)
                          (is (map? (:counts payload)))
                          (-> payload
                              (update :namespaces #(filterv (fn [row] (= visible (:id row))) %))
                              (update :counts dissoc hidden))))]
      (binding [diag/*diagnostics-override* (atom {})]
        (diag/record! nil anonymous [{:message "hidden anonymous diagnostic"}])
        (let [facts (listing/graph-tree-facts ctx)]
          (is (= [{:id visible :name "visible"}] (:namespaces facts)))
          (is (= #{visible} (set (keys (:kinds facts)))))
          (is (= {} (:errors facts)))
          (is (= 1 @calls))
          (is (= [[:ns {}]] @reads)))))))


(defn- usages
  "Run the real graph policy against the supplied isolated acquisition context."
  ([parsed ctx] (usages parsed ctx identity))
  ([parsed ctx conceal]
   (let [acquire types-api/types-usages-facts]
     (with-redefs [types-api/types-usages-facts (fn [input _ _] (acquire input ctx conceal))]
       (gh/exec-name :_types-usages-apply {:parsed parsed})))))


(deftest types-usages-test
  (let [storage (setup/create-test-storage)
        c (exec-ctx/create-context {:storage storage :base-fns {}})]
    (try
      (testing "a slot typed against the target type-row is reported as a usage"
        (let [int-id  (get setup/primitive-fn-ids :int)
              type-row (sp/create-entity storage :fn
                                         {:name "tu-type" :parent-ids []
                                          :base-fn-id int-id :constraint [:> 0]})
              host    (setup/create-base-fn! storage "tu-host")
              other   (setup/create-base-fn! storage "tu-other")
              slot    (setup/create-slot! storage "field" (:id type-row))
              _       (setup/attach-slot! storage (:id host) (:id slot) 0)
              _       (setup/attach-slot! storage (:id other) (:id slot) 0)
              res     (usages {:target-id (:id type-row)} c)]
          (is (true? (:ok res)))
          (is (pos? (:count res)))
          (is (= ["tu-host" "tu-other"]
                 (sort (map :fn-name (filter #(= :slot-of (:kind %))
                                             (:usages res)))))
              "a shared slot must report every owner")))

      (finally (sp/close storage))))

  ;; Fresh storage — the cached graph from the slot-usage call above
  ;; would otherwise hide these later writes.
  (let [storage (setup/create-test-storage)
        c (exec-ctx/create-context {:storage storage :base-fns {}})]
    (try
      (testing "composition plane: children, arg refs, list refs, resolvers"
        (let [target  (setup/create-base-fn! storage "fu-target")
              _child  (setup/create-composed-fn! storage "fu-child" (:id target))
              host    (setup/create-base-fn! storage "fu-host")
              callee  (setup/create-slot! storage "callee" :any)
              items   (setup/create-slot! storage "items" :any)
              resolvd (setup/create-slot! storage "resolved" :any)
              _       (setup/attach-slot! storage (:id host) (:id callee) 0)
              _       (setup/attach-slot! storage (:id host) (:id items) 1)
              _       (setup/attach-slot! storage (:id host) (:id resolvd) 2)
              caller  (setup/create-composed-fn! storage "fu-caller" (:id host))
              _       (sp/create-entity storage :binding
                                        {:fn-id (:id caller) :slot-id (:id callee)
                                         :ref-fn-id (:id target)})
              list-b  (sp/create-entity storage :binding
                                        {:fn-id (:id caller) :slot-id (:id items)
                                         :list-append true})
              _       (sp/create-entity storage :binding-list-item
                                        {:binding-id (:id list-b) :position 0
                                         :ref-fn-id (:id target)})
              _       (sp/create-entity storage :binding-list-item
                                        {:binding-id (:id list-b) :position 1
                                         :ref-fn-id (:id target)})
              _       (sp/create-entity storage :binding
                                        {:fn-id (:id caller) :slot-id (:id resolvd)
                                         :resolver-fn-id (:id target)})
              res     (usages {:target-id (:id target)} c)
              by-kind (group-by :kind (:usages res))
              concealed (usages
                          {:target-id (:id target)} c
                          (fn [graph]
                            (update graph :bindings
                                    #(filterv (fn [b] (not= (:id caller) (:fn-id b))) %))))]
          (is (true? (:ok res)))
          (is (= ["fu-child"] (map :fn-name (:parent-of by-kind)))
              "a child extending the target is a parent-of usage")
          (is (= [["fu-caller" "callee"] ["fu-caller" "items"]]
                 (sort (map (juxt :fn-name :slot-name) (:ref-of by-kind))))
              "one ref-of per (fn, slot) — the two list items collapse to one")
          (is (= [["fu-caller" "resolved"]]
                 (map (juxt :fn-name :slot-name) (:resolver-of by-kind)))
              "a resolver-fn-id reference is a resolver-of usage")
          (is (not-any? #(and (= "fu-caller" (:fn-name %))
                              (#{:ref-of :resolver-of} (:kind %)))
                        (:usages concealed))
              "a concealed owner's binding and list-item references stay hidden")))
      (finally (sp/close storage))))

  (let [storage (setup/create-test-storage)
        c (exec-ctx/create-context {:storage storage :base-fns {}})]
    (try
      (testing "a union branch + a binding type-override referencing the target"
        (let [int-id   (get setup/primitive-fn-ids :int)
              type-row (sp/create-entity storage :fn
                                         {:name "tu-target" :parent-ids []
                                          :base-fn-id int-id :constraint [:> 0]})
              ;; a union type-row whose constraint names the target
              _        (sp/create-entity storage :fn
                                         {:name "tu-union" :parent-ids []
                                          :constraint [:union :tu-target :text]})
              ;; a binding whose :type-override-fn-id is the target
              host     (setup/create-base-fn! storage "tu-ovr-host")
              slot     (setup/create-slot! storage "n" :int)
              _        (setup/attach-slot! storage (:id host) (:id slot) 0)
              comp-fn     (setup/create-composed-fn! storage "tu-ovr-comp-fn" (:id host))
              _        (sp/create-entity storage :binding
                                         {:fn-id (:id comp-fn) :slot-id (:id slot)
                                          :type-override-fn-id (:id type-row)})
              res      (usages {:target-id (:id type-row)} c)
              kinds    (set (map :kind (:usages res)))]
          (is (true? (:ok res)))
          (is (contains? kinds :union-branch))
          (is (contains? kinds :binding-of))))
      (finally (sp/close storage)))))


(deftest usages-reachability-respects-visible-service-route-and-editor-roots
  (let [storage (setup/create-test-storage)
        c (exec-ctx/create-context {:storage storage :base-fns {}})]
    (try
      (let [target (setup/create-base-fn! storage "reach-target")
            middle (setup/create-composed-fn! storage "reach-middle" (:id target))
            service-root (setup/create-composed-fn! storage "reach-service" (:id middle))
            route-root (setup/create-composed-fn! storage "reach-route" (:id middle))
            unrelated (setup/create-base-fn! storage "reach-unrelated")
            _ (sp/create-entity storage :service
                                {:fn-id (:id service-root) :enabled? true
                                 :restart-policy :never :cardinality :singleton})
            routes (fn [_] [{:handler-fn-id (:id route-root)}])]
        (with-redefs [tenancy/tenant-app-routes routes]
          (let [reachable (usages {:target-id (:id target)} c)
                from-editor (usages
                              {:target-id (:id target) :root-id (:id middle)} c)
                service-usage (usages {:target-id (:id service-root)} c)
                route-usage (usages {:target-id (:id route-root)} c)
                disconnected (usages {:target-id (:id unrelated)} c)
                concealed (usages
                            {:target-id (:id target)} c
                            (fn [graph]
                              (update graph :fns
                                      (fn [rows]
                                        (mapv #(if (= (:id %) (:id middle))
                                                 (assoc % :parent-ids []) %)
                                              rows)))))]
            (is (= :reachable (get-in reachable [:reachability :state])))
            (let [path-ids (mapv (comp parse-uuid :fn-id)
                                 (get-in reachable [:reachability :path]))]
              (is (contains? #{(:id service-root) (:id route-root)} (first path-ids)))
              (is (= [(:id middle) (:id target)] (subvec path-ids 1))))
            (is (= #{:service :route}
                   (set (map :root-kind (get-in reachable [:reachability :roots])))))
            (is (= #{:service :route :editor}
                   (set (map :root-kind (get-in from-editor [:reachability :roots])))))
            (is (some #(= :service-root (:kind %)) (:usages service-usage)))
            (is (some #(= :route-root (:kind %)) (:usages route-usage)))
            (is (= :unreachable (get-in disconnected [:reachability :state])))
            (is (= :unreachable (get-in concealed [:reachability :state])))
            (is (empty? (get-in concealed [:reachability :path]))
                "a concealed parent cannot appear in a path"))))
      (finally (sp/close storage)))))


