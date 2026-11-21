(ns graphden.input.source-scope
  "Read-only cross-reference origin verification against a frozen effective-input
   analysis. Derives no routes and changes neither source nor storage."
  (:require
    [graphden.input.context :as context]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.input.source-rename :as rename]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


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
  [owner-id arguments bindings path]
  (let [arg (only (filter #(= path (:source-path %)) arguments) :missing-binding-origin)
        id (get-in arg [:origin :binding-id])
        binding (only (filter #(= id (:id %)) bindings) :missing-binding-origin)
        [operator payload] (:expression arg)
        expected-expression (case operator
                              :value (and (:value-present binding) (migration/same-edn? payload (:value binding)))
                              :ref (and (qualified-keyword? payload)
                                        (= (:ref-fn-id binding) (ids/fn-id (namespace payload) (keyword (name payload)))))
                              false)]
    (when-not (and expected-expression (= owner-id (:fn-id binding) (get-in arg [:origin :fn-id]))
                   (= (:slot-id binding) (get-in arg [:origin :slot-id]))
                   (= id (ids/binding-id owner-id (:slot-id binding))))
      (reject! :source-snapshot-mismatch))
    [arg binding]))


(defn- legacy-omissions
  [{owner-id :identity :keys [legacy blockers kind status]} paths snapshot]
  (when-not (and (= :composed kind) (= :blocked status) (uuid? owner-id)
                 (seq paths) (= (count paths) (count (set paths)) (count blockers))
                 (= (set (for [path paths] [path :ref-scope-required]))
                    (set (map (juxt :source-path :code) blockers)))
                 (every? #(and (= :binding (:kind %)) (empty? (:policy %))) (:arguments legacy)))
    (reject! :unsupported-scope-source))
  (let [records (:records legacy)
        bindings (filter #(= :binding (:kind %)) records)
        owner (only (filter #(and (= :fn (:kind %)) (= owner-id (:id %))) records)
                    :missing-owner-declaration)
        omitted (mapv #(legacy-origin owner-id (:arguments legacy) bindings %) paths)
        omitted-ids (into #{} (map (comp :id second)) omitted)
        current (filter #(= owner-id (:fn-id %)) (vals (:bindings snapshot)))]
    (when-not (and (= (count omitted) (count omitted-ids))
                   (= (:parent-ids owner) (get-in snapshot [:fns owner-id :parent-ids]))
                   (not-any? (:bindings snapshot) omitted-ids)
                   (migration/same-edn? (into {} (map (juxt :id meaning)) (remove #(contains? omitted-ids (:id %)) bindings))
                                        (into {} (map (fn [row] [(:id row) (meaning (rename/legacy-binding (vals (:fields snapshot)) row))]))
                                              current)))
      (reject! :source-snapshot-mismatch))
    omitted))


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
        expected (context/call-with-types snapshot #(types/resolve-alias (get-in arg [:target :type])))
        declaring (only (filter #(= slot (ids/slot-id % field)) (get-in graph [:scopes use-path :declaration-order]))
                        :reference-field-origin-mismatch)]
    (when-not (and (seq edges) (= field (peek (:source-path arg))) (= names (conj (mapv :name edges) field))
                   (= :input-field (:kind route)) (:whole-bindable? route) (= slot (:origin-field-id route))
                   (migration/same-edn? expected (:completion-type route)))
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
  [prior current additions]
  (let [facts [:binding-id :origin-fn-id :expression]
        addresses (set (map :address additions))
        reference-children
        (into #{}
              (keep (fn [{[[owner use-path]] :address :keys [row]}]
                      (when (:ref-fn-id row) [owner (conj use-path (:id row))])))
              additions)]
    (and (every? (fn [[key old]]
                   (migration/same-edn? (select-keys old facts) (select-keys (get current key) facts))) prior)
         (every? (fn [[[owner path] _ :as address]]
                   (or (contains? prior address) (contains? addresses address)
                       (some (fn [[root child]]
                               (and (= owner root) (= child (take (count child) path))))
                             reference-children)))
                 (keys current)))))


(defn- binding-certificate
  [snapshot before [arg binding]]
  (let [owner (:fn-id binding)
        [public-path address edges declaring] (public-route before snapshot arg binding)]
    {:snapshot-id (:snapshot-id snapshot) :source-path (:source-path arg) :public-path public-path
     :address address :edges edges :field-owner declaring
     :owner-parents (get-in snapshot [:fns owner :parent-ids])
     :row (scoped-row binding address)}))


(defn- verified-addition?
  [after binding {:keys [address row]}]
  (let [added (get-in after [:public-input :bindings address])
        expression (if (:value-present binding)
                     {:kind :value :value (:value binding)}
                     {:kind :ref :fn-id (:ref-fn-id binding)})]
    (and (= address (occurrence after (:slot-id binding)))
         (= (:id binding) (:binding-id added)) (= (:fn-id binding) (:origin-fn-id added))
         (migration/same-edn? expression (:expression added))
         (migration/same-edn? (meaning binding) (meaning (migration/legacy-binding (assoc row :scope-path [])))))))


(defn verify-bindings
  "Check a group of legacy bindings whose complete omitted set is known.
   Resolve all addresses in one frozen baseline. Replay all rows together so
   interdependent type constraints and occupancy use the completed graph."
  [{owner :identity :as plan-entry} paths snapshot analyze]
  (let [omitted (legacy-omissions plan-entry paths snapshot)
        before (checked-graph analyze snapshot owner)
        certificates (mapv (partial binding-certificate snapshot before) omitted)
        additions (into {} (map (juxt :id identity)) (map :row certificates))]
    (when-not (= (count certificates) (count additions) (count (set (map :address certificates))))
      (reject! :scope-replay-mismatch))
    (let [updated (-> snapshot (assoc :snapshot-id (random-uuid)) (update :bindings merge additions))
          after (checked-graph analyze updated owner)
          prior (get-in before [:public-input :bindings])
          current (get-in after [:public-input :bindings])]
      (when-not (and (every? true? (map #(verified-addition? after (second %1) %2) omitted certificates))
                     (preserves-occupancy? prior current certificates))
        (reject! :scope-replay-mismatch))
      certificates)))


(defn verify-binding
  "Check a single omitted binding through a unique ordered reference path."
  [plan-entry source-path snapshot analyze]
  (first (verify-bindings plan-entry [source-path] snapshot analyze)))


(defn- free-route!
  [graph address type]
  (let [[names route] (only (filter #(= address (:address (val %))) (get-in graph [:public-input :routes]))
                            :unavailable-forwarded-input)]
    (when-not (and (= :input-field (:kind route)) (:whole-bindable? route)
                   (migration/same-edn? type (:completion-type route)))
      (reject! :unproved-forwarded-input))
    names))


(defn verify-forwarded-projection
  "Replay a previously located direct legacy field link in a checked snapshot.
   Prove both free input addresses before adding the projection. Confirm the
   result retains the existing occupancy and reads the original local field."
  [snapshot {:keys [owner-id source-address target-address projection-id
                    field-id source-type target-type target-fn-id]} analyze]
  (let [[[root [use-id :as uses]] [target-id :as target-path]] target-address]
    (when-not (and (= owner-id root) (= source-address [[owner-id []] [field-id]])
                   (= [use-id] uses) (= [target-id] target-path)
                   (every? uuid? [owner-id field-id use-id target-id target-fn-id])
                   (= projection-id (migration/derived-id :forwarded-field owner-id use-id target-id))
                   (not (contains? (:bindings snapshot) projection-id)))
      (reject! :unproved-forwarded-identity))
    (let [before (checked-graph analyze snapshot owner-id)
          source-names (free-route! before source-address source-type)
          target-names (free-route! before target-address target-type)
          edge (checked-edge before snapshot owner-id uses)
          field-owner (only (filter #(= target-id (ids/slot-id % (peek target-names)))
                                    (get-in before [:scopes uses :declaration-order]))
                            :reference-field-origin-mismatch)]
      (when-not (and (= target-address (occurrence before target-id))
                     (= target-fn-id (:target-fn-id edge))
                     (= 1 (count source-names))
                     (= [(:name edge) (first source-names)] target-names))
        (reject! :unproved-forwarded-use))
      (let [row (rows/normalize {:kind :input-binding :id projection-id :fn-id owner-id
                                 :org-id (get-in snapshot [:fns owner-id :org-id])
                                 :scope-path uses :target-path target-path
                                 :expression-kind "projection" :value-present false
                                 :projection-scope-path [] :projection-path [field-id]})
            updated (-> snapshot (assoc :snapshot-id (random-uuid)) (assoc-in [:bindings projection-id] row))
            after (checked-graph analyze updated owner-id)
            prior (get-in before [:public-input :bindings])
            current (get-in after [:public-input :bindings])
            added (get current target-address)]
        (when-not (and (= projection-id (:binding-id added)) (= owner-id (:origin-fn-id added))
                       (= {:kind :projection :scope [owner-id []] :path [field-id]} (:expression added))
                       (preserves-occupancy? prior current [{:address target-address :row row}])
                       (not (contains? (get-in after [:public-input :routes]) target-names))
                       (= source-names (free-route! after source-address source-type)))
          (reject! :forwarded-replay-mismatch))
        {:snapshot-id (:snapshot-id snapshot) :row row :edges [edge] :field-owner field-owner
         :owner-parents (get-in snapshot [:fns owner-id :parent-ids])
         :address target-address :public-path target-names :source-names source-names}))))
