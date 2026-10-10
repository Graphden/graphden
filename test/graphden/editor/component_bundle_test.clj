(ns graphden.editor.component-bundle-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-config :as config]
    [graphden.editor.component-templates :as templates]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.core :as types]))


(deftest selected-copies-contain-only-the-required-owned-closure
  (doseq [[group child] templates/groups]
    (let [root (str "personal.selected-" (name group))
          {:keys [definitions records]} (bundle/parse root group nil nil {})
          paths (set (map :namespace definitions))
          names (set (map :name definitions))]
      (is (= (cond-> #{root (str root "." child)}
               (= group :account-menu) (conj (str root ".theme"))) paths))
      (is (contains? names :ui))
      (is (not (contains? names :theme)) "a selected component never copies the theme entry")
      (is (= (count definitions) (count (filter #(= :fn (:kind %)) records)))))))


(defn- manifest-operation-records
  []
  (concat (ids/boot-primitive-records)
          (mapcat #(parse/parse-fn-def % {})
                  (filter #(contains? #{[:const "core.logic"] [:list "core.collections"]
                                        [:zipmap "core.collections"] [:concat "core.collections"]}
                                      [(:name %) (:namespace %)])
                          (templates/interface-definitions)))))


(deftest immutable-extension-keeps-the-authored-manifest-reference
  (let [old-root "personal.retained-picker"
        new-root "personal.added-recents"
        old (bundle/parse old-root :fn-picker nil nil {})
        old-id (ids/fn-id old-root :ui)
        retained-ref (keyword new-root (str "_retained-target-" old-id))
        added (bundle/parse new-root :recents retained-ref nil {retained-ref old-id})
        records (concat (manifest-operation-records)
                        (map #(cond-> % (= old-id (:id %)) (assoc :name nil)) (:records old)) (:records added))
        grouped (group-by :kind records)
        graph {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
               :bindings (:binding grouped) :list-items (:binding-list-item grouped)}]
    (is (= {:fn-picker {:view (ids/fn-id (str old-root ".picker") :picker-view)}
            :recents {:initial (ids/fn-id (str new-root ".recents") :recents-initial)
                      :update (ids/fn-id (str new-root ".recents") :recents-update)
                      :view (ids/fn-id (str new-root ".recents") :recents-view)}}
           (config/configuration graph (ids/fn-id new-root :ui))))
    (is (some #(= old-id (:ref-fn-id %)) (:records added)))
    (is (not-any? #(= old-id (:id %)) (:records added)) "retained identities are never part of the create-only receipt")))


(deftest legacy-adapter-keeps-original-entry-identities-through-fixed-descriptors
  (let [root "personal.legacy-addition"
        retained {:account-menu {:initial (random-uuid) :update (random-uuid) :view (random-uuid)}
                  :fn-picker {:view (random-uuid)}}
        reference #(keyword root (str "_retained-target-" %))
        legacy (update-vals retained #(update-vals % reference))
        names (into {} (mapcat #(map (fn [id] [(reference id) id]) (vals %))) (vals retained))
        added (bundle/parse root :recents nil legacy names)
        graph (bundle/graph (concat (manifest-operation-records) (:records added)))
        actual (config/configuration graph (ids/fn-id root :ui))]
    (is (= retained (select-keys actual [:account-menu :fn-picker])))
    (is (= (ids/fn-id (str root ".recents") :recents-view) (get-in actual [:recents :view])))
    (doseq [[_ {:keys [id reference]}] config/component-contracts]
      (is (= id (ids/fn-id (namespace reference) (keyword (name reference))))))))


(deftest template-identity-targets-never-hide-an-ordinary-reference
  (let [target (random-uuid)
        identity-binding {:kind :binding :id (random-uuid) :fn-id (random-uuid)
                          :slot-id (ids/slot-id (ids/fn-id "core.logic" :const) :value)
                          :type-override-fn-id ids/fn-ref-type-id :ref-fn-id target :value-present false}]
    (is (= #{target} (bundle/identity-targets [identity-binding])))
    (is (= #{} (bundle/identity-targets [identity-binding {:kind :binding-list-item :ref-fn-id target}])))
    (is (= #{} (bundle/identity-targets [(assoc identity-binding :value-present true :value "mixed")])))))


(deftest real-parser-keeps-every-copy-named-and-color-form-compatible
  (binding [tenancy/*current-org* "acme"]
    (let [root "users.alice.ui-test"
          {:keys [records]} (bundle/parse root)
          fns (filter #(= :fn (:kind %)) records)
          expected (templates/definitions root)
          color-id (ids/fn-id "core.refinements" :color)
          wrapper-id (ids/fn-id (str root ".theme") :color-const)
          const-slot (ids/slot-id (ids/fn-id "core.logic" :const) :value)
          wrapper-binding (first (filter #(and (= :binding (:kind %))
                                               (= wrapper-id (:fn-id %))
                                               (= const-slot (:slot-id %))) records))]
      (is (= (count expected) (count fns)))
      (is (every? :name fns))
      (let [style-id (ids/fn-id (str root ".menu") :_account-menu-style)]
        (doseq [rule [:_account-menu-hover-rule :_account-menu-focus-rule]]
          (is (= style-id (:return-type-fn-id
                            (first (filter #(= (ids/fn-id (str root ".menu") rule) (:id %)) fns)))))))
      (is (some? wrapper-binding) "the wrapper narrows the inherited const value slot")
      (is (= color-id (:type-override-fn-id wrapper-binding)))
      (is (contains? (bundle/external-identities records) color-id))
      (is (not (contains? (bundle/external-identities records)
                          (ids/fn-id "core.refinements" :color-const))))
      (is (not (contains? (bundle/external-identities records) wrapper-id))))))


(deftest independent-personal-copies-do-not-shadow-qualified-state-contracts
  (binding [tenancy/*current-org* "acme"
            types/*type-aliases-override* (atom {})
            runtime/*per-org-aliases-override* (atom {})]
    (let [roots ["users.alice.ui-one" "users.bob.ui-two"]
          records (into (ids/boot-primitive-records) (mapcat #(:records (bundle/parse %))) roots)
          paths (into {} (map #(vector % (random-uuid))) (keep :namespace-id records))
          state-id (ids/fn-id "users.alice.ui-one.menu" :_account-menu-state)
          changed-slot (ids/slot-id state-id :active)
          records (mapv (fn [row]
                          (cond-> row
                            (:namespace-id row) (update :namespace-id paths)
                            (= :fn (:kind row)) (assoc :org-id "acme")
                            (= changed-slot (:id row)) (assoc :type-fn-id (ids/primitive-fn-id :text)))) records)
          grouped (group-by :kind records)
          graph {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
                 :bindings (:binding grouped) :list-items (:binding-list-item grouped)}]
      (runtime/register-type-aliases-from-db! graph ::two-copies
                                              (into {} (map (fn [[path id]] [id path])) paths))
      (is (= :text (:active (types/resolve-alias :users.alice.ui-one.menu/_account-menu-state))))
      (is (= :int (:active (types/resolve-alias :users.bob.ui-two.menu/_account-menu-state))))
      (doseq [root roots]
        (let [phase-id (ids/fn-id (str root ".menu") :account-menu-phase)
              expected-state (ids/fn-id (str root ".menu") :_account-menu-state)
              state-slot (first (filter #(and (= :slot (:kind %))
                                              (= (:id %) (ids/slot-id phase-id :state))) records))]
          (is (= expected-state (:type-fn-id state-slot))))))))
