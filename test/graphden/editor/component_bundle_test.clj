(ns graphden.editor.component-bundle-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-templates :as templates]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.packages.records.ids :as ids]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.core :as types]))


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
