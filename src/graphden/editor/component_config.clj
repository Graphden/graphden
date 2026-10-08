(ns graphden.editor.component-config
  "Read the fixed identity interface of an ordinary personal UI configuration.
   No graph is invoked and no computed UUID is accepted as an entry point."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.packages.records.ids :as ids]))


(def configuration-id
  (ids/fn-id "app.ui-components" :ui-components))


(def ^:private entry-slots
  {:menu-initial [:account-menu :initial]
   :menu-update [:account-menu :update]
   :menu-view [:account-menu :view]
   :picker-view [:fn-picker :view]})


(defn- reject!
  []
  (throw (ex-info "Select a UI configuration with all four component identity bindings"
                  {:type :browser-plan/unsupported :reason :invalid-configuration})))


(defn entries
  "Extract the fixed four :fn-ref bindings with the executor's inheritance and
   binding rules. Extra slots, renames, resolvers, literals and missing entries
   refuse instead of evaluating a graph to discover privileged UUIDs. `graph`
   must already have passed snapshot source authorization."
  [graph fn-id]
  (let [index (lookups/build-lookups graph)
        chain (lookups/inheritance-chain* fn-id index)
        classified (bindings/collect-bindings fn-id index)
        expected-ids (into {} (map (fn [[name path]]
                                     [(ids/slot-id configuration-id name) [name path]]))
                           entry-slots)]
    (when-not (and (some #{configuration-id} chain)
                   (= (set (keys expected-ids)) (set (map :slot-id classified)))
                   (= (count entry-slots) (count classified)))
      (reject!))
    (reduce (fn [result {:keys [kind slot-id base-name ext-name ref-id]}]
              (let [[name path] (get expected-ids slot-id)]
                (when-not (and (= :fn-ref kind) (= name base-name ext-name) (uuid? ref-id))
                  (reject!))
                (assoc-in result path ref-id)))
            {} classified)))
