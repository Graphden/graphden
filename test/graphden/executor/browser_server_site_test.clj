(ns ^:serial graphden.executor.browser-server-site-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-server-site :as site]
    [graphden.executor.browser-source :as source]))


(defn- graph-fixture
  []
  (let [root (random-uuid) owner (random-uuid) ancestor (random-uuid)
        sibling (random-uuid) slot (random-uuid) binding (random-uuid)]
    {:root root :owner owner :ancestor ancestor :sibling sibling
     :graph {:fns [{:id root} {:id owner :parent-ids [ancestor]} {:id ancestor} {:id sibling}]
             :fn-slots [{:id (random-uuid) :fn-id owner :slot-id slot}]
             :slots [{:id slot :type-fn-id ancestor}]
             :bindings [{:id binding :fn-id root :slot-id slot :ref-fn-id owner}
                        {:id (random-uuid) :fn-id root :slot-id (random-uuid) :ref-fn-id sibling}]
             :list-items []}}))


(deftest issued-witness-includes-effective-parent-and-exact-owned-rows
  (let [{:keys [root owner ancestor graph]} (graph-fixture)
        proof (site/prepare graph {:root root} [owner (random-uuid)] {:fingerprint "target"} {:root root})]
    (is (= #{root owner ancestor} (set (:ids proof))))
    (is (= (:evidence proof) (site/fingerprint graph (:ids proof) {:root root} "target")))
    (doseq [changed [(update-in graph [:fns 1] assoc :concealed true)
                     (update-in graph [:fns 2] assoc :name "new-parent")
                     (update-in graph [:slots 0] assoc :type-fn-id (random-uuid))
                     (update-in graph [:bindings 0] assoc :ref-fn-id (random-uuid))]]
      (is (not= (:evidence proof) (site/fingerprint changed (:ids proof) {:root root} "target"))))
    (is (not= (:evidence proof) (site/fingerprint graph (:ids proof) {:root (random-uuid)} "target")))
    (is (not= (:evidence proof) (site/fingerprint graph (:ids proof) {:root root} "changed-target")))))


(deftest missing-path-or-parent-keeps-full-recapture-fallback
  (let [{:keys [root owner ancestor graph]} (graph-fixture)]
    (is (nil? (site/prepare graph {:root root} [(random-uuid) (random-uuid)] {} {})))
    (is (nil? (site/prepare (update graph :fns #(filterv (fn [row] (not= ancestor (:id row))) %))
                            {:root root} [owner (random-uuid)] {} {})))
    (is (nil? (site/prepare (update-in graph [:fns 1] assoc :parent-ids [(random-uuid)])
                            {:root root} [owner (random-uuid)] {} {})))))


(deftest multiple-entry-authority-keeps-full-recapture
  (let [{:keys [root owner sibling graph]} (graph-fixture)]
    (is (nil? (site/prepare graph {:root root :other sibling} [owner (random-uuid)] {} {})))))


(deftest an-oversized-type-closure-keeps-full-recapture
  (let [{:keys [root owner graph]} (graph-fixture)
        types (vec (repeatedly 130 random-uuid))
        rows (mapv (fn [id parent] (cond-> {:id id} parent (assoc :parent-ids [parent])))
                   types (concat (rest types) [nil]))
        graph (-> graph (update :fns into rows) (assoc-in [:slots 0 :type-fn-id] (first types)))]
    (is (nil? (site/prepare graph {:root root} [owner (random-uuid)] {} {})))))


(deftest an-external-slot-rename-chain-keeps-full-recapture
  (let [{:keys [root owner graph]} (graph-fixture)]
    (is (nil? (site/prepare (assoc-in graph [:slots 0 :source-slot-id] (random-uuid))
                            {:root root} [owner (random-uuid)] {} {})))))


(deftest terminal-identity-cannot-prove-reachability-to-its-parent
  (let [{:keys [root owner ancestor graph]} (graph-fixture)
        graph (assoc graph :identity-target-ids #{owner})]
    (is (nil? (site/prepare graph {:root root} [ancestor (random-uuid)] {} {})))))


(deftest terminal-shortcut-never-replaces-an-ordinary-client-path
  (let [root (random-uuid) a (random-uuid) b (random-uuid)
        terminal (random-uuid) owner (random-uuid)
        binding (fn [from to] {:id (random-uuid) :fn-id from :slot-id (random-uuid) :ref-fn-id to})
        graph {:fns [{:id root} {:id a} {:id b} {:id owner}
                     {:id terminal :parent-ids [owner]}]
               :bindings [(binding root a) (binding a b) (binding b owner) (binding root terminal)]
               :slots [] :fn-slots [] :list-items [] :identity-target-ids #{terminal}}
        proof (site/prepare graph {:root root} [owner (random-uuid)] {} {})]
    (is (= #{root a b owner} (set (:ids proof))))
    (is (nil? (site/prepare (update graph :bindings #(filterv (fn [row] (not= b (:fn-id row))) %))
                            {:root root} [owner (random-uuid)] {} {})))))


(deftest conflicting-duplicate-rows-never-collapse-to-an-order-dependent-proof
  (let [{:keys [root graph]} (graph-fixture)]
    (is (thrown? clojure.lang.ExceptionInfo
          (site/project (update graph :fns conj {:id root :name "conflict"}) [root])))
    (is (= (site/project graph [root])
           (site/project (update graph :fns conj {:id root}) [root])))))


(deftest finite-witness-authorizes-every-owner-before-reading-owned-values
  (let [allowed (random-uuid) denied (random-uuid) payload-reads (atom 0)]
    (with-redefs-fn
      {(ns-resolve 'graphden.executor.browser-source 'read-rows)
       (fn [_ kind ids] (when (= :fn kind) (mapv #(hash-map :id %) ids)))
       (ns-resolve 'graphden.executor.browser-source 'owner-rows)
       (fn [& _] (swap! payload-reads inc) [[] []])}
      (fn []
        (is (thrown? clojure.lang.ExceptionInfo
              (source/capture-nodes nil [allowed denied]
                                    (fn [row]
                                      (when (= denied (:id row))
                                        (throw (ex-info "Denied" {:type :authz/forbidden})))))))
        (is (zero? @payload-reads))))))


(deftest finite-witness-retains-the-bare-type-source-invariant
  (let [id (random-uuid)]
    (with-redefs-fn
      {(ns-resolve 'graphden.executor.browser-source 'read-rows)
       (fn [_ kind ids] (when (= :fn kind) (mapv #(hash-map :id %) ids)))
       (ns-resolve 'graphden.executor.browser-source 'owner-rows)
       (fn [& _] [[] [{:id (random-uuid) :fn-id id :value "invalid bare-type body"}]])
       (ns-resolve 'graphden.executor.browser-source 'limited-query) (constantly [])}
      (fn []
        (is (thrown? clojure.lang.ExceptionInfo (source/capture-nodes nil [id] (constantly :bare-type))))))))
