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
  {:account-menu {:id (ids/fn-id "app.ui-account-menu" :account-menu-view)
                  :roles #{:initial :update :view}}
   :fn-picker {:id (ids/fn-id "app.ui-fn-picker" :picker-view) :roles #{:view}}
   :recents {:id (ids/fn-id "app.ui-recents" :recents-view)
             :roles #{:initial :update :view}}})


(defrecord Identity [id])


(defn- literal
  [value]
  (when-not (or (contains? #{:component-id :entries :role :fn-id :initial :update :view} value)
                (and (vector? value)
                     (every? #{:component-id :entries :role :fn-id :initial :update :view} value)))
    (reject!))
  value)


(defn manifest-entries
  "Project a finite const/list/zipmap composition without invoking graph code.
   UUIDs originate exclusively in compiler-classified const value fn-ref
   bindings. Identity tags survive composition until descriptor validation."
  [graph fn-id]
  (let [index (lookups/build-lookups graph)
        cache (atom {})
        visiting (atom #{})
        budget (atom 0)
        const-id (ids/fn-id "core.logic" :const)
        list-id (ids/fn-id "core.collections" :list)
        zipmap-id (ids/fn-id "core.collections" :zipmap)]
    (letfn [(item [row]
              (cond
                (:ref-fn-id row) (project (:ref-fn-id row))
                (contains? row :value) (literal (:value row))
                :else (reject!)))
            (argument [{:keys [kind value ref-id items slot-id]} root]
              (case kind
                :value (literal value)
                :ref (project ref-id)
                :seq (mapv item items)
                :fn-ref (if (and (= root const-id)
                                 (= slot-id (ids/slot-id const-id :value))
                                 (uuid? ref-id))
                          (->Identity ref-id) (reject!))
                (reject!)))
            (project [id]
              (when (or (> (swap! budget inc) 4096) (contains? @visiting id) (>= (count @visiting) 64)) (reject!))
              (if (contains? @cache id) (get @cache id)
                  (let [root (lookups/root-fn id (:fn-map index) index)
                        root-id (:id root)
                        classified (bindings/collect-bindings id index)
                        chain (lookups/inheritance-chain* id index)]
                    (when (some :resolver-fn-id
                                (mapcat #(get-in index [:bindings-by-fn %]) chain)) (reject!))
                    (when-not (and (contains? #{const-id list-id zipmap-id} root-id)
                                   (empty? (bindings/collect-env-bindings id index))) (reject!))
                    (swap! visiting conj id)
                    (let [args (into {} (map (fn [binding]
                                              [(:base-name binding) (argument binding root-id)])) classified)
                          result (cond
                                   (= root-id const-id) (get args :value)
                                   (= root-id list-id) (get args :items)
                                   :else (let [ks (:keys args) vs (:vals args)]
                                           (when-not (and (vector? ks) (vector? vs)
                                                          (= (count ks) (count vs))
                                                          (= (count ks) (count (set ks)))
                                                          (every? #{:component-id :entries :role :fn-id} ks))
                                             (reject!))
                                           (zipmap ks vs)))]
                      (swap! visiting disj id)
                      (swap! cache assoc id result)
                      result))))]
      (let [descriptors (project fn-id)]
        (when-not (and (vector? descriptors) (<= (count descriptors) 64)) (reject!))
        (reduce (fn [result descriptor]
                  (when-not (map? descriptor) (reject!))
                  (let [{component :component-id entry-list :entries} descriptor]
                    (when-not (and (= #{:component-id :entries} (set (keys descriptor)))
                                   (instance? Identity component)
                                   (not (contains? result (:id component)))
                                   (vector? entry-list) (<= 1 (count entry-list) 3)) (reject!))
                    (assoc result (:id component)
                           (reduce (fn [roles entry]
                                     (when-not (map? entry) (reject!))
                                     (let [{role :role target :fn-id} entry]
                                       (when-not (and (= #{:role :fn-id} (set (keys entry)))
                                                      (contains? #{:initial :update :view} role)
                                                      (instance? Identity target)
                                                      (not (contains? roles role))) (reject!))
                                       (assoc roles role (:id target)))) {} entry-list))))
                {} descriptors)))))


(defn configuration
  "Keep the historical four-slot interface exact; otherwise read the bounded
   ordinary descriptor-list manifest. Missing components are not executable."
  [graph fn-id]
  (if (some #{configuration-id}
            (lookups/inheritance-chain* fn-id (lookups/build-lookups graph)))
    (entries graph fn-id)
    (let [manifest (manifest-entries graph fn-id)]
      (into {} (keep (fn [[component {:keys [id roles]}]]
                       (when-let [entry (get manifest id)]
                         (when-not (= roles (set (keys entry))) (reject!))
                         [component entry]))) component-contracts))))
