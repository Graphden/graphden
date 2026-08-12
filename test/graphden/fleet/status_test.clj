(ns graphden.fleet.status-test
  "Read-only fleet observability snapshot (`graphden.fleet.status/fleet-status`,
   docs/FLEET_RFC.md §6). Pure read over `:placement` + the controller's cells —
   synthetic storage, no container. `command_test` covers the HTTP routing +
   token gate; this asserts the SHAPE the endpoint returns."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.status :as status]
    [graphden.storage.protocol.core :as sp]))


(def ^:private c1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private c2 #uuid "00000000-0000-0000-0000-000000000002")


(defn- fleet-storage
  "Fake storage: `:app-route` rows (org→handler-fn-id) + `:placement` rows + a
   pending-execution count per org (for cell-weight's load term)."
  [{:keys [app-routes placements pending]}]
  (reify sp/StorageCRUD
    (query-entities
      [_ en where]
      (case en
        :app-route (mapv (fn [[org h]] {:org org :handler-fn-id h}) app-routes)
        :placement placements
        :fn-execution (when (= :pending (:status where))
                        (vec (repeat (get pending (:org-id where) 0) {:status :pending})))
        nil))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(defn- ctx
  [storage]
  {:storage storage :compile-deps (atom {:forward-deps {}})})


(deftest fleet-status-snapshot-test
  (let [storage (fleet-storage
                  {:app-routes {"acme" c1 "beta" c2}
                   :placements [{:org "acme" :entry-fn-id c1 :executor-id "e1" :epoch 1}
                                {:org "beta" :entry-fn-id c2 :executor-id "e2" :epoch 1}]
                   :pending {"acme" 2}})
        snap (status/fleet-status (ctx storage) "e1")]
    (testing "carries this pod's executor-id"
      (is (= "e1" (:executor-id snap))))
    (testing "placements: sorted, entry-fn-id stringified, holder attached"
      (is (= [{:org "acme" :entry-fn-id (str c1) :executor-id "e1"}
              {:org "beta" :entry-fn-id (str c2) :executor-id "e2"}]
             (:placements snap))))
    (testing "loads: per-executor weight (acme = 1 fn + 2 pending = 3; beta = 1)"
      (is (= {"e1" 3.0 "e2" 1.0} (:loads snap))))))


(deftest fleet-status-empty-fleet-test
  (let [snap (status/fleet-status (ctx (fleet-storage {})) "solo")]
    (is (= "solo" (:executor-id snap)))
    (is (= [] (:placements snap)))
    (is (= {} (:loads snap)) "no placements → no per-executor loads")))
