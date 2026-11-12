(ns ^:serial graphden.executor.browser-server-site-call-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.editor.components :as components]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-server-source :as server-source]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.storage.binding-read :as binding-read]
    [graphden.storage.remote.core :as remote]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]))


(defn- fixture-graph
  []
  (let [root (random-uuid) owner (random-uuid) target (random-uuid) sibling (random-uuid)
        type (random-uuid) helper (random-uuid)
        ref-slot (random-uuid) sibling-slot (random-uuid) site-slot capabilities/server-call-slot-id]
    {:root root :owner owner :target target :sibling sibling :site-slot site-slot :type type
     :graph {:fns (into (mapv #(hash-map :id %) [root owner target sibling])
                        [{:id type :parent-ids [helper]} {:id helper :constraint {:kind :original}}])
             :slots [{:id ref-slot :name "selected"} {:id sibling-slot :name "other"}
                     {:id site-slot :name "func" :type-fn-id type}]
             :fn-slots [{:id (random-uuid) :fn-id root :slot-id ref-slot :position 0}
                        {:id (random-uuid) :fn-id root :slot-id sibling-slot :position 1}
                        {:id (random-uuid) :fn-id owner :slot-id site-slot :position 0}]
             :bindings [{:id (random-uuid) :fn-id root :slot-id ref-slot :ref-fn-id owner}
                        {:id (random-uuid) :fn-id root :slot-id sibling-slot :ref-fn-id sibling}
                        {:id (random-uuid) :fn-id owner :slot-id site-slot :ref-fn-id target}]
             :list-items [] :identity-target-ids #{target}}}))


(defn- storage-for
  [graph branch]
  (assoc (remote/from-bundle {:fn (:fns graph) :slot (:slots graph) :fn-slot (:fn-slots graph)
                              :binding (:bindings graph) :binding-list-item (:list-items graph)})
         :branch-id branch))


(defn- with-site-call
  [consume & [customize]]
  (let [{:keys [root owner target site-slot] :as fixture} ((or customize identity) (fixture-graph))
        graph (atom (:graph fixture)) branch (random-uuid)
        anchor (atom {:id (random-uuid) :fn-id (random-uuid) :slot-id (random-uuid) :ref-fn-id root})
        denied (atom #{}) target-digest (atom "target")
        after (atom (constantly nil)) reads (atom [])
        authorize! (fn [row & _]
                     (swap! reads conj (:id row))
                     (when (contains? @denied (:id row)) (throw (ex-info "Denied" {:type :authz/forbidden}))))
        storage (storage-for @graph branch)
        ctx {:storage storage :invalidation-count (atom 0) :base-fns {}
             :request-scope (fn [_ _ thunk] (thunk))}
        request {:headers {"authorization" "Bearer selected-site-fixture"}}]
    (binding [tenancy/*current-org* tenancy/public-org tenancy/*current-principal* nil]
      (with-redefs [binding-read/owner-bindings (fn [& _] [@anchor])
                    source/with-snapshot (fn [_ thunk] (thunk (storage-for @graph branch) authorize!))
                    source/collect-client-closure (fn [& _] @graph)
                    source/collect-namespaces (constantly [])
                    server-source/capture (fn [_ _ guard _ id]
                                            (guard {:id id} {:execute? true :server-target? true})
                                            {:graph {:fns [{:id target}]} :fingerprint @target-digest
                                             :input-type {:query :text} :output-type :text})
                    snapshot/export-snapshot (fn [captured _ _ _] (get-in captured [:server-plan-options :server-descriptors]))
                    snapshot/capture-context-policy (constantly {})
                    components/assert-current-policy! (constantly nil)
                    recheck/call-with-ctx-slices (fn [_ thunk] (thunk))
                    eager/compile-all (constantly {})
                    persist/acquire-execution-slot! (fn [& _] (constantly nil))
                    persist/redact-outcome (fn [_ result] result)
                    runtime/execute (fn [& _] (@after) "ok")]
        (let [descriptors (server/export-client-plan ctx storage authorize! {} {:root root} request @anchor)
              descriptor (get descriptors [owner site-slot])
              invoke #(server/invoke-current ctx request {:descriptor descriptor :input (wire/encode-value {:query ""})})]
          (reset! reads [])
          (consume (assoc fixture :invoke invoke :graph graph :denied denied :target-digest target-digest
                          :after after :reads reads :anchor anchor
                          :invalidate! #(swap! (:invalidation-count ctx) inc))))))))


(deftest selected-call-does-not-borrow-sibling-authority-or-read-its-body
  (with-site-call
    (fn [{:keys [invoke sibling denied reads graph]}]
      (swap! denied conj sibling)
      (swap! graph update :bindings conj {:id (random-uuid) :fn-id sibling :value "hidden sibling payload"})
      (is (true? (:ok (invoke))))
      (is (not-any? #{sibling} @reads)))))


(deftest root-path-and-target-revocation-refuse-before-or-after-execution
  (doseq [which [:root :owner :target :type] stage [:before :after]]
    (with-site-call
      (fn [{:keys [invoke denied after] :as fixture}]
        (let [revoke #(swap! denied conj (which fixture))]
          (if (= :before stage) (revoke) (reset! after revoke))
          (is (= "forbidden" (:failure-type (invoke)))))))))


(deftest source-anchor-and-target-changes-never-release-a-result
  (doseq [change [:binding :parent :slot-type :type-body :type-dependency-body :anchor :target] stage [:before :after]]
    (with-site-call
      (fn [{:keys [invoke graph after anchor target-digest]}]
        (let [mutate #(case change
                        :binding (swap! graph assoc-in [:bindings 2 :ref-fn-id] (random-uuid))
                        :parent (swap! graph assoc-in [:fns 1 :parent-ids] [(random-uuid)])
                        :slot-type (swap! graph assoc-in [:slots 2 :type-fn-id] (random-uuid))
                        :type-body (swap! graph assoc-in [:fns 4 :constraint] {:kind :changed})
                        :type-dependency-body (swap! graph assoc-in [:fns 5 :constraint] {:kind :changed})
                        :anchor (swap! anchor assoc :description "changed")
                        :target (reset! target-digest "changed"))]
          (if (= :before stage) (mutate) (reset! after mutate))
          (is (false? (:ok (invoke)))))))))


(deftest unrelated-epoch-stays-stale-but-never-disguises-selected-revocation
  (with-site-call
    (fn [{:keys [invoke invalidate!]}]
      (invalidate!)
      (is (true? (:server-stale? (invoke))))))
  (with-site-call
    (fn [{:keys [invoke invalidate! denied target]}]
      (invalidate!)
      (swap! denied conj target)
      (let [result (invoke)]
        (is (= "forbidden" (:failure-type result)))
        (is (false? (:server-stale? result)))))))


(defn- change-row
  [rows id changes]
  (mapv #(if (= id (:id %)) (merge % changes) %) rows))


(deftest inherited-site-bindings-and-multiple-parent-order-remain-pinned
  (doseq [change [:binding :parent-order :rename] stage [:before :after]]
    (let [parent (random-uuid) other-parent (random-uuid)]
      (with-site-call
        (fn [{:keys [invoke graph after owner]}]
          (is (true? (:ok (invoke))) "The inherited site is callable before modification")
          (let [binding-id (:id (first (filter #(= parent (:fn-id %)) (:bindings @graph))))
                mutate #(case change
                          :binding (swap! graph update :bindings change-row binding-id {:ref-fn-id (random-uuid)})
                          :parent-order (swap! graph update :fns change-row owner {:parent-ids [other-parent parent]})
                          :rename (swap! graph update :slots change-row capabilities/server-call-slot-id
                                         {:source-slot-id (random-uuid) :name "renamed"}))]
            (if (= :before stage) (mutate) (reset! after mutate))
            (is (false? (:ok (invoke))))))
        (fn [{:keys [graph owner site-slot] :as fixture}]
          (assoc fixture :graph
                 (-> graph
                     (update :fns #(into (change-row % owner {:parent-ids [parent other-parent]})
                                         [{:id parent} {:id other-parent}]))
                     (update :fn-slots #(mapv (fn [row]
                                                (if (= owner (:fn-id row)) (assoc row :fn-id parent) row)) %))
                     (update :bindings #(mapv (fn [row]
                                                (if (= site-slot (:slot-id row)) (assoc row :fn-id parent) row)) %)))))))))
