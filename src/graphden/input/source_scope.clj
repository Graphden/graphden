(ns graphden.input.source-scope
  "Read-only cross-reference origin verification against a frozen effective-input
   analysis. Derives no routes and changes neither source nor storage."
  (:require
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.input.source-rename :as rename]
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
  [{owner-id :identity :keys [legacy blockers kind status]} path snapshot]
  (when-not (and (= :composed kind) (= :blocked status) (uuid? owner-id)
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
        owner (only (filter #(and (= :fn (:kind %)) (= owner-id (:id %))) (:records legacy)) :missing-owner-declaration)
        existing (filter #(= owner-id (:fn-id %)) (vals (:bindings snapshot)))]
    (when-not (and expected-expression (= owner-id (:fn-id binding) (get-in arg [:origin :fn-id]))
                   (= (:slot-id binding) (get-in arg [:origin :slot-id]))
                   (= (:id binding) (ids/binding-id owner-id (:slot-id binding)))
                   (= (:parent-ids owner) (get-in snapshot [:fns owner-id :parent-ids]))
                   (not (contains? (:bindings snapshot) id))
                   (migration/same-edn? (into {} (map (juxt :id meaning)) (remove #(= id (:id %)) bindings))
                                        (into {} (map (fn [row] [(:id row) (meaning (rename/legacy-binding (vals (:fields snapshot)) row))]))
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


(defn- checked-edge
  [graph snapshot owner prefix]
  (let [to [owner prefix]
        {address :from target :target-fn-id id :binding-id :as edge}
        (only (filter #(= to (:to %)) (:dependencies graph)) :missing-reference-use)
        [parent [field :as path]] address
        bound (get-in graph [:public-input :bindings address])
        row (get-in snapshot [:bindings id])
        contract (get-in graph [:public-input :scope-contracts parent])
        name (:name (only (filter #(= field (:id %)) (:fields contract)) :missing-reference-use))]
    (when-not (and (= :value (:mode edge)) (= [owner (pop prefix)] parent)
                   (= id (peek prefix) (:binding-id bound) (:id row))
                   (= "ref" (:expression-kind row)) (= (:fn-id row) (:origin-fn-id bound))
                   (empty? (:scope-path row)) (= path [field] (:target-path row))
                   (= id (ids/binding-id (:fn-id row) field))
                   (uuid? target) (= target (:ref-fn-id row) (get-in bound [:expression :fn-id])))
      (reject! :unsupported-reference-use))
    {:from address :binding-id id :target-fn-id target :origin-fn-id (:fn-id row) :name name}))


(defn- public-route
  [graph snapshot arg binding]
  (let [owner (:fn-id binding)
        slot (:slot-id binding)
        [[_ use-path] _ :as address] (occurrence graph slot)
        [names route] (only (filter #(= address (:address (val %))) (get-in graph [:public-input :routes]))
                            :unavailable-reference-input)
        edges (mapv #(checked-edge graph snapshot owner (subvec use-path 0 %)) (range 1 (inc (count use-path))))
        field (get-in arg [:target :field])
        declaring (only (filter #(= slot (ids/slot-id % field)) (get-in graph [:scopes use-path :declaration-order]))
                        :reference-field-origin-mismatch)]
    (when-not (and (seq edges) (= field (peek (:source-path arg))) (= names (conj (mapv :name edges) field))
                   (= :input-field (:kind route)) (:whole-bindable? route) (= slot (:origin-field-id route))
                   (migration/same-edn? (get-in arg [:target :type]) (:completion-type route)))
      (reject! :unsupported-reference-use))
    [names address edges declaring]))


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
  [prior current [[owner use-path] _ :as address] binding]
  (let [facts [:binding-id :origin-fn-id :expression]
        child (conj use-path (:id binding))]
    (and (every? (fn [[key old]]
                   (migration/same-edn? (select-keys old facts) (select-keys (get current key) facts))) prior)
         (every? (fn [[[_ path :as scope] _ :as key]]
                   (or (contains? prior key) (= address key)
                       (and (:ref-fn-id binding) (= owner (first scope))
                            (= child (take (count child) path)))))
                 (keys current)))))


(defn verify-binding
  "Verify one previously blocked value/reference binding through a unique,
   ordered path of value-producing references. The frozen snapshot contains
   all other owner bindings and inherited uses. analyze takes [snapshot fn-id]
   and returns the stage-4 analyze-effective-input result.

   Replay the overlay against a fresh snapshot, checking its binding identity,
   exact use, expression and the baseline occupancy. The legacy inverse below
   is local to this proved source occurrence. No general flattening is valid.
   This certificate is private and does not change the source plan's status."
  [plan-entry source-path snapshot analyze]
  (let [[arg binding _] (legacy-origin plan-entry source-path snapshot)
        owner (:fn-id binding)
        before (checked-graph analyze snapshot owner)
        [public-path address edges declaring] (public-route before snapshot arg binding)
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
    {:snapshot-id (:snapshot-id snapshot) :source-path source-path :public-path public-path :address address
     :edges edges :field-owner declaring :owner-parents (get-in snapshot [:fns owner :parent-ids]) :row row}))
