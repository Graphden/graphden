(ns graphden.editor.component-config-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.type-check :as type-check]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-config :as config]
    [graphden.editor.component-templates :as templates]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.storage.remote.core :as remote]
    [graphden.types.check.provenance :as provenance]))


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


(defn- parsed-configuration
  []
  (let [root "users.alice.manifest-test"
        interfaces (filter #(contains? #{[:const "core.logic"] [:list "core.collections"]
                                         [:zipmap "core.collections"]} [(:name %) (:namespace %)])
                           (templates/interface-definitions))
        records (concat (ids/boot-primitive-records)
                        (mapcat #(parse/parse-fn-def % {}) interfaces)
                        (:records (bundle/parse root)))
        grouped (group-by :kind records)]
    {:id (ids/fn-id root :ui)
     :root root
     :graph {:fns (:fn grouped) :slots (:slot grouped)
             :fn-slots (:fn-slot grouped) :bindings (:binding grouped)
             :list-items (:binding-list-item grouped)}}))


(deftest ordinary-manifest-projects-only-terminal-identity-values
  (let [{:keys [id root graph]} (parsed-configuration)]
    (is (= {:account-menu {:initial (ids/fn-id (str root ".menu") :account-menu-initial)
                           :update (ids/fn-id (str root ".menu") :account-menu-update)
                           :view (ids/fn-id (str root ".menu") :account-menu-view)}
            :fn-picker {:view (ids/fn-id (str root ".picker") :picker-view)}
            :recents {:initial (ids/fn-id (str root ".recents") :recents-initial)
                      :update (ids/fn-id (str root ".recents") :recents-update)
                      :view (ids/fn-id (str root ".recents") :recents-view)}}
           (config/configuration graph id)))))


(deftest uuid-literals-and-ordinary-evaluation-cannot-select-component-identities
  (let [{:keys [id root graph]} (parsed-configuration)
        identity-id (ids/fn-id root :_menu-view-id)
        change (fn [f]
                 (update graph :bindings
                         #(mapv (fn [row] (if (= identity-id (:fn-id row)) (f row) row)) %)))
        reason (fn [changed]
                 (try (config/configuration changed id)
                      (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))]
    (is (= :invalid-configuration
           (reason (change #(-> % (dissoc :ref-fn-id)
                                (assoc :value-present true :value (random-uuid)))))))
    (is (= :invalid-configuration
           (reason (change #(dissoc % :type-override-fn-id)))))
    (is (= :invalid-configuration
           (reason (change #(assoc % :resolver-fn-id (random-uuid) :value-present true :value "secret")))))))


(deftest identity-override-reconstruction-preserves-checker-provenance
  (let [target (random-uuid)
        binding {:id (random-uuid) :ref-fn-id target
                 :type-override-fn-id ids/fn-ref-type-id}
        storage (remote/from-bundle {:binding-list-item []})
        rows {target {:id target :name "target"}
              ids/fn-ref-type-id {:id ids/fn-ref-type-id :name "fn-ref"}}
        expected {:ref :target :type :fn-ref}
        actual (type-check/binding-shape-for-edn storage rows {} {} binding)
        definition {:name :identity :parent :const :args {:value expected}}
        signature (provenance/stamp {} definition)]
    (is (= expected actual))
    (is (provenance/matches? signature (assoc-in definition [:args :value] actual)))))


(deftest manifest-projection-bounds-depth-and-refuses-duplicate-components
  (let [{:keys [id root graph]} (parsed-configuration)
        const-id (ids/fn-id "core.logic" :const)
        value-slot (ids/slot-id const-id :value)
        wrappers (vec (repeatedly 65 random-uuid))
        deep (-> graph
                 (update :fns into (mapv #(hash-map :id % :parent-ids [const-id]) wrappers))
                 (update :bindings into (mapv (fn [fid ref]
                                                {:id (random-uuid) :fn-id fid :slot-id value-slot
                                                 :ref-fn-id ref}) wrappers (cons id wrappers))))
        first-item (first (filter #(= (ids/fn-id root :_menu-descriptor) (:ref-fn-id %))
                                  (:list-items graph)))
        duplicate (update graph :list-items conj (assoc first-item :id (random-uuid) :position 4))
        reason (fn [g entry]
                 (try (config/configuration g entry)
                      (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))]
    (is (= :invalid-configuration (reason deep (last wrappers))))
    (is (= :invalid-configuration (reason duplicate id)))))
