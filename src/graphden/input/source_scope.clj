(ns graphden.input.source-scope
  "Read-only cross-reference origin verification against a frozen effective-input
   analysis. Derives no routes and changes neither source nor storage."
  (:require
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]))


(defn- reject!
  [code]
  (throw (ex-info "Cannot prove legacy reference input occurrence"
                  {:type :input/source-scope :code code})))


(defn- only
  [matches code]
  (if (= 1 (count matches)) (first matches) (reject! code)))


(defn- meaning
  [row]
  [(:id row) (:fn-id row) (:slot-id row) (:org-id row)
   (cond (:value-present row) [:value (:value row)]
         (:ref-fn-id row) [:ref (:ref-fn-id row)])
   (into {} (remove (comp nil? val))
         (select-keys row [:type-override-fn-id :required :terminal :description :resolver-fn-id
                           :list-append :list-closed :override-kind]))])


(defn- legacy-origin
  [{:keys [identity legacy blockers kind status]} path snapshot]
  (when-not (and (= :composed kind) (= :blocked status) (uuid? identity)
                 (= [[path :ref-scope-required]] (mapv (juxt :source-path :code) blockers))
                 (every? #(and (= :binding (:kind %)) (empty? (:policy %))) (:arguments legacy)))
    (reject! :unsupported-scope-source))
  (let [arg (only (filter #(= path (:source-path %)) (:arguments legacy)) :missing-binding-origin)
        id (get-in arg [:origin :binding-id])
        bindings (filter #(= :binding (:kind %)) (:records legacy))
        binding (only (filter #(= id (:id %)) bindings) :missing-binding-origin)
        [operator payload] (:expression arg)
        expected-expression (case operator
                              :value (and (:value-present binding) (migration/same-edn? payload (:value binding)))
                              :ref (and (qualified-keyword? payload)
                                        (= (:ref-fn-id binding) (ids/fn-id (namespace payload) (keyword (name payload)))))
                              false)
        owner (only (filter #(and (= :fn (:kind %)) (= identity (:id %))) (:records legacy)) :missing-owner-declaration)
        existing (filter #(= identity (:fn-id %)) (vals (:bindings snapshot)))]
    (when-not (and expected-expression (= identity (:fn-id binding) (get-in arg [:origin :fn-id]))
                   (= (:slot-id binding) (get-in arg [:origin :slot-id]))
                   (= (:id binding) (ids/binding-id identity (:slot-id binding)))
                   (= (:parent-ids owner) (get-in snapshot [:fns identity :parent-ids]))
                   (not (contains? (:bindings snapshot) id))
                   (migration/same-edn? (into {} (map (juxt :id meaning)) (remove #(= id (:id %)) bindings))
                                        (into {} (map (fn [row] [(:id row) (meaning (migration/legacy-binding row))]))
                                              existing)))
      (reject! :source-snapshot-mismatch))
    [arg binding bindings]))


(defn- checked-graph
  [analyze snapshot owner]
  (let [graph (analyze snapshot owner)
        surface (:public-input graph)]
    (when-not (and (some? (:snapshot-id snapshot)) (= (:snapshot-id snapshot) (:snapshot-id surface))
                   (= owner (:fn-id graph) (:fn-id surface)))
      (reject! :stale-input-surface))
    (when-not (and (= :checked (:status graph)) (empty? (:diagnostics graph)) (empty? (:obligations graph))
                   (true? (get-in graph [:coverage :recursive-availability]))
                   (true? (get-in graph [:coverage :type-checker])))
      (reject! :incomplete-reference-analysis))
    graph))


(defn- occurrence
  [graph slot-id]
  ;; Look at every declared scope, including occupied/hidden occurrences.
  ;; Picking the sole FREE route would conflate a shared target's uses.
  (let [candidates (for [[scope contract] (get-in graph [:public-input :scope-contracts])
                         :when (some #(= slot-id (:id %)) (:fields contract))]
                     [scope [slot-id]])]
    (when (empty? candidates) (reject! :missing-reference-input))
    (only candidates :nonunique-reference-input)))


(defn- public-route
  [graph arg binding bindings]
  (let [owner (:fn-id binding)
        slot (:slot-id binding)
        [[_ use-path :as scope] _ :as address] (occurrence graph slot)
        edge (only (filter #(= scope (:to %)) (:dependencies graph)) :missing-reference-use)
        [names route] (only (filter #(= address (:address (val %))) (get-in graph [:public-input :routes]))
                            :unavailable-reference-input)
        link (some #(when (= (:id %) (:binding-id edge)) %) bindings)]
    (when-not (and (= 1 (count use-path)) (= :value (:mode edge))
                   (= use-path [(:id link)]) (= owner (:fn-id link)) (uuid? (:ref-fn-id link))
                   (= [[owner []] [(:slot-id link)]] (:from edge))
                   (= (:ref-fn-id link) (:target-fn-id edge))
                   (= slot (ids/slot-id (:target-fn-id edge) (get-in arg [:target :field])))
                   (= :input-field (:kind route)) (:whole-bindable? route) (= slot (:origin-field-id route))
                   (= (last names) (get-in arg [:target :field]))
                   (migration/same-edn? (get-in arg [:target :type]) (:completion-type route)))
      (reject! :unsupported-reference-use))
    [names address]))


(defn- scoped-row
  [binding [[_ use-path] field-path]]
  (rows/normalize
    (merge (select-keys binding [:id :fn-id :org-id])
           {:kind :input-binding :scope-path use-path :target-path field-path}
           (cond
             (:value-present binding) {:expression-kind "value" :value-present true :value (:value binding)}
             (:ref-fn-id binding) {:expression-kind "ref" :value-present false :ref-fn-id (:ref-fn-id binding)}
             :else (reject! :unsupported-scoped-binding)))))


(defn- preserves-occupancy?
  [prior current [[owner use] _ :as address] binding]
  (let [facts [:binding-id :origin-fn-id :expression]
        child (conj use (:id binding))]
    (and (every? (fn [[key old]]
                   (migration/same-edn? (select-keys old facts) (select-keys (get current key) facts))) prior)
         (every? (fn [[[_ path :as scope] _ :as key]]
                   (or (contains? prior key) (= address key)
                       (and (:ref-fn-id binding) (= owner (first scope))
                            (= child (take (count child) path)))))
                 (keys current)))))


(defn verify-binding
  "Verify one previously blocked value/reference binding through a unique,
   direct, value-producing ref owned by the authored function. The frozen
   snapshot contains all its other bindings. analyze takes [snapshot fn-id]
   and returns the stage-4 analyze-effective-input result.

   Replay the overlay against a fresh snapshot, checking its binding identity,
   exact use, expression and the baseline occupancy. The legacy inverse below
   is local to this proved source occurrence. No general flattening is valid.
   This certificate is private and does not change the source plan's status."
  [plan-entry source-path snapshot analyze]
  (let [[arg binding bindings] (legacy-origin plan-entry source-path snapshot)
        owner (:fn-id binding)
        before (checked-graph analyze snapshot owner)
        [public-path address] (public-route before arg binding bindings)
        row (scoped-row binding address)
        updated (-> snapshot (assoc :snapshot-id (random-uuid)) (assoc-in [:bindings (:id row)] row))
        after (checked-graph analyze updated owner)
        added (get-in after [:public-input :bindings address])
        expression (if (:value-present binding)
                     {:kind :value :value (:value binding)}
                     {:kind :ref :fn-id (:ref-fn-id binding)})
        prior (get-in before [:public-input :bindings])
        current (get-in after [:public-input :bindings])]
    (when-not (and (= address (occurrence after (:slot-id binding)))
                   (= (:id binding) (:binding-id added)) (= owner (:origin-fn-id added))
                   (migration/same-edn? expression (:expression added))
                   (preserves-occupancy? prior current address binding)
                   (migration/same-edn? (meaning binding) (meaning (migration/legacy-binding (assoc row :scope-path [])))))
      (reject! :scope-replay-mismatch))
    {:snapshot-id (:snapshot-id snapshot) :source-path source-path :public-path public-path :address address :row row}))
