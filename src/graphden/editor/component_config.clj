(ns graphden.editor.component-config
  "Read the fixed identity interface of an ordinary personal UI configuration.
   No graph is invoked and no computed UUID is accepted as an entry point."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.structural-projection :as projection]
    [graphden.packages.records.ids :as ids]))


(def configuration-id
  (ids/fn-id "app.ui-components" :ui-components))


(def ^:private entry-slots
  {:menu-initial [:account-menu :initial]
   :menu-update [:account-menu :update]
   :menu-view [:account-menu :view]
   :picker-view [:fn-picker :view]})


(def ^:private record-contracts
  {configuration-id
   {:fields (into {} (map (fn [[field _]]
                            [(ids/slot-id configuration-id field)
                             {:name field :type-fn-id ids/fn-ref-type-id}])) entry-slots)}})


(def ^:private literal-fields
  (into #{:component-id :entries :role :fn-id :initial :update :view} (keys entry-slots)))


(defn- reject!
  []
  (throw (ex-info "Select a valid component manifest or legacy UI configuration"
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


(def component-contracts
  "Shipped view identities are the stable component discriminators. No names
   supplied by a client participate in dispatch."
  {:explorer {:id (ids/fn-id "app.ui-explorer" :explorer-toolbar-view)
              :reference :app.ui-explorer/explorer-toolbar-view :roles #{:initial :update :view}}
   :account-menu {:id (ids/fn-id "app.ui-account-menu" :account-menu-view)
                  :reference :app.ui-account-menu/account-menu-view
                  :roles #{:initial :update :view}}
   :fn-picker {:id (ids/fn-id "app.ui-fn-picker" :picker-view)
               :reference :app.ui-fn-picker/picker-view :roles #{:view}}
   :inspector {:id (ids/fn-id "app.ui-inspector" :inspector-view)
               :reference :app.ui-inspector/inspector-view :roles #{:initial :update :view}}
   :recents {:id (ids/fn-id "app.ui-recents" :recents-view)
             :reference :app.ui-recents/recents-view
             :roles #{:initial :update :view}}})


(defn- projected-manifest
  [graph fn-id]
  (projection/project graph fn-id
                      (fn [value]
                        (or (nil? value) (= {} value)
                            (contains? literal-fields value)
                            (and (vector? value)
                                 (every? literal-fields value))))
                      record-contracts))


(defn- checked-manifest-entries
  [value]
  (let [descriptors (cond
                      (vector? value) value
                      (map? value) (mapv (fn [[component entry-list]] {:component-id component :entries entry-list}) value)
                      :else (reject!))]
    (when-not (<= (count descriptors) 64) (reject!))
    (reduce (fn [result descriptor]
              (when-not (map? descriptor) (reject!))
              (let [{component :component-id entry-list :entries} descriptor]
                (when-not (and (= #{:component-id :entries} (set (keys descriptor)))
                               (projection/identity-value? component)
                               (not (contains? result (:id component)))
                               (vector? entry-list) (<= 1 (count entry-list) 3)) (reject!))
                (assoc result (:id component)
                       (reduce (fn [roles entry]
                                 (when-not (map? entry) (reject!))
                                 (let [{role :role target :fn-id} entry]
                                   (when-not (and (= #{:role :fn-id} (set (keys entry)))
                                                  (contains? #{:initial :update :view} role)
                                                  (projection/identity-value? target)
                                                  (not (contains? roles role))) (reject!))
                                   (assoc roles role (:id target)))) {} entry-list))))
            {} descriptors)))


(defn manifest-entries
  "Project finite structural composition without invoking graph code.
   UUIDs originate exclusively in compiler-classified const value fn-ref
   bindings. A descriptor list or explicit component map carries the same
   entries; identity tags survive get/map/into/merge until validation."
  [graph fn-id]
  (checked-manifest-entries (projected-manifest graph fn-id)))


(defn configuration-info
  "Validate one source configuration and report its ordinary record/list/map
   shape for composition adapters. This is source evidence, not client input."
  [graph fn-id]
  (if (some #{configuration-id}
            (lookups/inheritance-chain* fn-id (lookups/build-lookups graph)))
    {:format :record :configuration (entries graph fn-id)}
    (let [value (projected-manifest graph fn-id)
          manifest (checked-manifest-entries value)]
      {:format (if (map? value) :map :list)
       :configuration (into {} (keep (fn [[component {:keys [id roles]}]]
                                       (when-let [entry (get manifest id)]
                                         (when-not (= roles (set (keys entry))) (reject!))
                                         [component entry]))) component-contracts)})))


(defn configuration
  "Keep the historical four-slot interface exact; otherwise read the bounded
   ordinary descriptor-list or component-map manifest."
  [graph fn-id]
  (:configuration (configuration-info graph fn-id)))
