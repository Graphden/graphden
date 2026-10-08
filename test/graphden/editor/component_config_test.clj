(ns graphden.editor.component-config-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-config :as config]
    [graphden.packages.records.ids :as ids]))


(def ^:private slot-names
  [:menu-initial :menu-update :menu-view :picker-view])


(defn- fixture
  []
  (let [fn-id (random-uuid)
        refs (vec (repeatedly 4 random-uuid))
        slots (mapv #(hash-map :id (ids/slot-id config/configuration-id %) :name (name %)
                               :type-fn-id ids/fn-ref-type-id) slot-names)]
    {:id fn-id
     :refs refs
     :graph {:fns [{:id fn-id :name "personal-ui" :parent-ids [config/configuration-id]}
                   {:id config/configuration-id :name "ui-components" :parent-ids []
                    :return-type-fn-id (random-uuid)}]
             :slots slots
             :fn-slots (mapv (fn [position slot]
                               {:id (random-uuid) :fn-id config/configuration-id
                                :slot-id (:id slot) :position position}) (range) slots)
             :bindings (mapv (fn [slot ref]
                               {:id (random-uuid) :fn-id fn-id :slot-id (:id slot)
                                :ref-fn-id ref}) slots refs)
             :list-items []}}))


(defn- refusal
  [graph id]
  (try (config/entries graph id)
       (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))


(deftest ordinary-configuration-reads-identity-bindings-without-executing-entries
  (let [{:keys [id refs graph]} (fixture)]
    (is (= {:account-menu {:initial (nth refs 0) :update (nth refs 1) :view (nth refs 2)}
            :fn-picker {:view (nth refs 3)}}
           (config/entries graph id))
        "entry functions need not be executable or even loaded to read an identity binding")))


(deftest incomplete-or-computed-configuration-does-not-become-a-privileged-plan
  (let [{:keys [id graph]} (fixture)]
    (is (= :invalid-configuration
           (refusal (update graph :bindings pop) id)))
    (is (= :invalid-configuration
           (refusal (update-in graph [:bindings 0]
                               #(-> % (dissoc :ref-fn-id) (assoc :value (str (random-uuid))))) id)))
    (is (= :invalid-configuration
           (refusal (update-in graph [:bindings 0]
                               #(-> % (dissoc :ref-fn-id)
                                    (assoc :value "vault/input" :resolver-fn-id (random-uuid)))) id)))
    (is (= :invalid-configuration
           (refusal (assoc-in graph [:fns 0 :parent-ids] []) id)))))
