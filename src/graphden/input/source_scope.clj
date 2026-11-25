(ns graphden.input.source-scope
  "Read-only cross-reference origin verification against a frozen effective-input
   analysis. Derives no routes and changes neither source nor storage."
  (:require
    [graphden.input.context :as context]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.input.source-forward-generic :as generic]
    [graphden.input.source-joint :as joint]
    [graphden.input.source-positional-type :as positional-type]
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
                              :ref (let [target (cond
                                                  (qualified-keyword? payload) (ids/fn-id (namespace payload) (keyword (name payload)))
                                                  (map? payload) (:inline-fn-id arg))]
                                     (and (uuid? target) (= target (:ref-fn-id binding))))
                              nil (when-let [row (get-in arg [:scoped-annotation :row])]
                                    (migration/same-edn? (meaning binding) (meaning (migration/legacy-binding row))))
                              false)]
    (when-not (and expected-expression (= owner-id (:fn-id binding) (get-in arg [:origin :fn-id]))
                   (= (:slot-id binding) (get-in arg [:origin :slot-id]))
                   (= id (ids/binding-id owner-id (:slot-id binding))))
      (reject! :source-snapshot-mismatch))
    [arg binding]))


(defn- legacy-omissions
  [{owner-id :identity :keys [legacy blockers kind status]} paths snapshot projections]
  (when-not (and (= :composed kind) (= :blocked status) (uuid? owner-id)
                 (seq paths) (= (count paths) (count (set paths)) (count blockers))
                 (= (set (for [path paths] [path :ref-scope-required]))
                    (set (map (juxt :source-path :code) blockers)))
                 (every? #(and (= :binding (:kind %))
                               (or (empty? (:policy %)) (:annotation %) (:scoped-annotation %)))
                         (:arguments legacy)))
    (reject! :unsupported-scope-source))
  (let [records (:records legacy)
        bindings (filter #(= :binding (:kind %)) records)
        owner (only (filter #(and (= :fn (:kind %)) (= owner-id (:id %))) records)
                    :missing-owner-declaration)
        omitted (mapv #(legacy-origin owner-id (:arguments legacy) bindings %) paths)
        omitted-ids (into #{} (map (comp :id second)) omitted)
        projected-ids (set (map (comp :id :row) projections))
        current (filter #(and (= owner-id (:fn-id %)) (not (projected-ids (:id %)))) (vals (:bindings snapshot)))]
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
    (when-not (and (= :checked (:status graph)) (empty? (:diagnostics graph))
                   (every? #(= [:value-type :field-read] ((juxt :kind :trigger) %)) (:obligations graph))
                   (true? (get-in graph [:coverage :recursive-availability]))
                   (true? (get-in graph [:coverage :type-checker])))
      (reject! :incomplete-reference-analysis))
    graph))


(defn- occurrence
  [graph slot-id & [root-use-id]]
  ;; Look at every declared scope, including occupied/hidden occurrences.
  ;; Picking the sole FREE route would conflate a shared target's uses.
  (let [candidates (for [[scope contract] (get-in graph [:public-input :scope-contracts])
                         :when (and (or (nil? root-use-id) (= root-use-id (first (second scope))))
                                    (some #(= slot-id (:id %)) (:fields contract)))]
                     [scope [slot-id]])]
    (when (empty? candidates) (reject! :missing-reference-input))
    (only candidates :nonunique-reference-input)))


(defn- list-use?
  [graph snapshot [owner use-path] {:keys [owner-id binding-id builder-id scope-path target-path]}]
  (when (and (vector? scope-path) (> (count use-path) (count scope-path)))
    (let [anchor (subvec use-path 0 (- (count use-path) (inc (count scope-path))))
          parent [owner (pop use-path)]
          from [parent target-path]
          link (get-in snapshot [:bindings binding-id])
          bound (get-in graph [:scopes (second parent) :bindings from])]
      (and (= use-path (into anchor (conj scope-path binding-id)))
           (some #{owner-id} (get-in graph [:scopes anchor :declaration-order]))
           (= builder-id (get-in graph [:scopes use-path :fn-id]))
           (= owner-id (:origin-fn-id bound)) (= binding-id (:binding-id bound))
           (= {:kind :ref :fn-id builder-id} (:expression bound))
           (= owner-id (:fn-id link)) (= "ref" (:expression-kind link))
           (= scope-path (:scope-path link)) (= target-path (:target-path link))
           (= builder-id (:ref-fn-id link))
           (some #(= {:from from :to [owner use-path] :binding-id binding-id
                      :target-fn-id builder-id :mode :value} %) (:dependencies graph))))))


(defn- list-reference-origin
  [graph snapshot scope row]
  (let [builder (:fn-id row)
        {:keys [owner-id target-id items] :as fragment} (get-in snapshot [:source-list-fragments builder])
        field (first (:target-path row))
        input (get-in snapshot [:fns builder :input-type-fn-id])
        memberships (filter #(= field (:slot-id %)) (get-in snapshot [:members input]))
        [member & more] memberships
        position (:position member)
        link (get-in snapshot [:bindings (:binding-id fragment)])]
    (and (uuid? owner-id) (uuid? target-id)
         (= (list-builder/origin owner-id target-id)
            (select-keys fragment [:owner-id :target-id :binding-id :builder-id :tail-id]))
         (= builder (:builder-id fragment)) (vector? items) (empty? more)
         (nat-int? position) (= (ids/fn-slot-id input field) (:id member))
         (= field (ids/slot-id builder (str "item-" position)))
         (= (:id row) (:id (get items position)))
         (list-signature/inline-operand? fragment link position (:ref-fn-id row))
         (list-use? graph snapshot scope fragment)
         {:binding-id (:binding-id fragment) :position position :item-id (:id row) :ref-fn-id (:ref-fn-id row)})))


(defn- checked-edge
  [graph snapshot owner prefix]
  (let [to [owner prefix]
        {address :from target :target-fn-id id :binding-id :as edge}
        (only (filter #(= to (:to %)) (:dependencies graph)) :missing-reference-use)
        [parent [field :as path]] address
        bound (get-in graph [:public-input :bindings address])
        row (get-in snapshot [:bindings id])
        contract (get-in graph [:public-input :scope-contracts parent])
        name (:name (only (filter #(= field (:id %)) (:fields contract)) :missing-reference-use))
        operand (list-reference-origin graph snapshot parent row)
        fragment (get-in snapshot [:source-list-fragments target])
        fragment (when (and fragment (list-use? graph snapshot to fragment))
                   (select-keys fragment [:owner-id :target-id :binding-id :builder-id :tail-id :scope-path :target-path]))
        certificate (cond-> {:from address :binding-id id :target-fn-id target :origin-fn-id (:fn-id row) :name name}
                      operand (assoc :source-list-operand operand)
                      fragment (assoc :source-list-fragment fragment))]
    (when-not (and (= :value (:mode edge)) (= [owner (pop prefix)] parent)
                   (= id (peek prefix) (:binding-id bound) (:id row))
                   (= "ref" (:expression-kind row)) (= (:fn-id row) (:origin-fn-id bound))
                   (empty? (:scope-path row)) (= path [field] (:target-path row))
                   (or operand
                       (and (not (contains? (:source-list-fragments snapshot) (:fn-id row)))
                            (= id (ids/binding-id (:fn-id row) field))))
                   (or (nil? fragment) (list-signature/source-link? certificate))
                   (uuid? target) (= target (:ref-fn-id row) (get-in bound [:expression :fn-id])))
      (reject! :unsupported-reference-use))
    certificate))


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
                        :reference-field-origin-mismatch)
        origin (:target-type-origin arg)
        _ (when (and origin (not (and (positional-type/exact? origin (:target arg) snapshot declaring)
                                      (false? (:required route)) (false? (:required-now? route)))))
            (reject! :reference-field-origin-mismatch))
        expected (if origin :any (context/call-with-types snapshot #(types/resolve-alias (get-in arg [:target :type]))))]
    (when-not (and (seq edges) (= field (peek (:source-path arg))) (= names (conj (mapv :name edges) field))
                   (= :input-field (:kind route)) (:whole-bindable? route) (= slot (:origin-field-id route))
                   (migration/same-edn? expected (:completion-type route)))
      (reject! :unsupported-reference-use))
    [names address edges declaring]))


(defn- scoped-row
  [binding [[_ use-path] field-path] annotation]
  (rows/normalize
    (merge (select-keys binding [:id :fn-id :org-id])
           {:kind :input-binding :scope-path use-path :target-path field-path}
           (cond
             (:value-present binding) {:expression-kind "value" :value-present true :value (:value binding)}
             (:ref-fn-id binding) {:expression-kind "ref" :value-present false :ref-fn-id (:ref-fn-id binding)}
             annotation (select-keys (:row annotation) [:type-override-fn-id :description :expression-kind :value-present])
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
        [public-path address edges declaring] (public-route before snapshot arg binding)
        annotation (:scoped-annotation arg)]
    (when (and annotation (not= declaring (:declaring-id annotation)))
      (reject! :reference-field-origin-mismatch))
    (cond-> {:snapshot-id (:snapshot-id snapshot) :source-path (:source-path arg) :public-path public-path
             :address address :edges edges :field-owner declaring
             :owner-parents (get-in snapshot [:fns owner :parent-ids])
             :row (scoped-row binding address annotation)}
      annotation (assoc :configuration (select-keys annotation [:type :description]))
      (:target-type-origin arg) (assoc :target-type-origin (:target-type-origin arg)))))


(defn- verified-addition?
  [before after binding {:keys [address row public-path] :as certificate}]
  (let [added (get-in after [:public-input :bindings address])
        expression (if (:value-present binding)
                     {:kind :value :value (:value binding)}
                     {:kind :ref :fn-id (:ref-fn-id binding)})]
    (and (= address (occurrence after (:slot-id binding)))
         (if (contains? certificate :configuration)
           (let [quantities [:address :completion-type :required :required-now? :whole-bindable?]]
             (and (nil? added)
                  (migration/same-edn? (select-keys (get-in before [:public-input :routes public-path]) quantities)
                                       (select-keys (get-in after [:public-input :routes public-path]) quantities))))
           (and (= (:id binding) (:binding-id added)) (= (:fn-id binding) (:origin-fn-id added))
                (migration/same-edn? expression (:expression added))))
         (migration/same-edn? (meaning binding) (meaning (migration/legacy-binding (assoc row :scope-path [])))))))


(defn- verify-bindings*
  "Check a group of legacy bindings whose complete omitted set is known.
   Resolve all addresses in one frozen baseline. Replay all rows together so
   interdependent type constraints and occupancy use the completed graph.
   Existing field-read obligations must survive exactly; replay never
   discharges, renames, strengthens or introduces a runtime check."
  [{owner :identity :as plan-entry} paths snapshot analyze projections]
  (let [omitted (legacy-omissions plan-entry paths snapshot projections)
        before (checked-graph analyze snapshot owner)
        certificates (mapv (partial binding-certificate snapshot before) omitted)
        additions (into {} (map (juxt :id identity)) (map :row certificates))]
    (when-not (= (count certificates) (count additions) (count (set (map :address certificates))))
      (reject! :scope-replay-mismatch))
    (let [updated (-> snapshot (assoc :snapshot-id (random-uuid)) (update :bindings merge additions))
          after (checked-graph analyze updated owner)
          prior (get-in before [:public-input :bindings])
          current (get-in after [:public-input :bindings])]
      (when-not (and (every? true? (map #(verified-addition? before after (second %1) %2) omitted certificates))
                     (every? (fn [{:keys [address row]}]
                               (= address (occurrence after (first (:target-path row))))) projections)
                     (preserves-occupancy? prior current certificates)
                     (= (set (:obligations before)) (set (:obligations after))))
        (reject! :scope-replay-mismatch))
      (mapv #(assoc % :obligations (:obligations after)) certificates))))


(defn verify-bindings
  "Check exactly the complete omitted binding group, preserving all existing
   occupancy and runtime obligations. No forwarding declarations are admitted."
  [entry paths snapshot analyze]
  (verify-bindings* entry paths snapshot analyze []))


(defn verify-bindings-after-forwarding
  "Check the remaining scoped group after an exact authored forwarding proof.
   Projection rows remain in the analyzed graph and their destinations must
   still have one occurrence after every scoped value/reference is replayed."
  [original projected snapshot analyze]
  (let [{:keys [entry projections]} (joint/scope-view original projected snapshot)
        {:keys [paths]} (joint/groups original)]
    (verify-bindings* entry paths snapshot analyze projections)))


(defn verify-binding
  "Check a single omitted binding through a unique ordered reference path."
  [plan-entry source-path snapshot analyze]
  (first (verify-bindings plan-entry [source-path] snapshot analyze)))


(defn- checked-free-route
  [graph address]
  (let [[_ route :as entry] (only (filter #(= address (:address (val %))) (get-in graph [:public-input :routes]))
                                  :unavailable-forwarded-input)]
    (when-not (and (= :input-field (:kind route)) (:whole-bindable? route))
      (reject! :unproved-forwarded-input))
    entry))


(defn- free-route!
  [graph address type]
  (let [[names route] (checked-free-route graph address)]
    (when-not (migration/same-edn? type (:completion-type route))
      (reject! :unproved-forwarded-input))
    names))


(defn- forwarded-certificate
  [snapshot before {:keys [owner-id source-address target-address projection-id
                           field-id source-type target-type target-fn-id target-field-id root-use-id
                           multiple-readers? generic-reference list-operand]}]
  (let [address (occurrence before target-field-id (when multiple-readers? root-use-id))
        [[root uses] target-path] address
        source-type (context/call-with-types snapshot #(types/resolve-alias source-type))
        target-type (context/call-with-types snapshot #(types/resolve-alias target-type))]
    (when generic-reference (generic/verify! snapshot before owner-id generic-reference))
    (when-not (and (= owner-id root) (= source-address [[owner-id []] [field-id]])
                   (= (or target-address address) address) (= root-use-id (first uses))
                   (every? uuid? [owner-id field-id root-use-id target-field-id target-fn-id])
                   (= projection-id (migration/derived-id :forwarded-field owner-id root-use-id target-field-id))
                   (not (contains? (:bindings snapshot) projection-id)))
      (reject! :unproved-forwarded-identity))
    (let [source-names (free-route! before source-address source-type)
          target-names (if (and list-operand (nil? target-type))
                         (let [[names route] (checked-free-route before address)
                               type (:completion-type route)]
                           (when-not (and (some? type)
                                          (context/call-with-types snapshot #(types/subtype? source-type type)))
                             (reject! :unproved-forwarded-input))
                           names)
                         (free-route! before address target-type))
          edges (mapv #(checked-edge before snapshot owner-id (subvec uses 0 %)) (range 1 (inc (count uses))))
          field-owner (only (filter #(= target-field-id (ids/slot-id % (peek target-names)))
                                    (get-in before [:scopes uses :declaration-order]))
                            :reference-field-origin-mismatch)]
      (when-not (and (= target-fn-id (:target-fn-id (first edges)))
                     (or (nil? list-operand) (= list-operand (:source-list-operand (second edges))))
                     (= 1 (count source-names)) (= (conj (mapv :name edges) (first source-names)) target-names))
        (reject! :unproved-forwarded-use))
      (cond-> {:snapshot-id (:snapshot-id snapshot)
               :row (rows/normalize {:kind :input-binding :id projection-id :fn-id owner-id
                                     :org-id (get-in snapshot [:fns owner-id :org-id])
                                     :scope-path uses :target-path target-path
                                     :expression-kind "projection" :value-present false
                                     :projection-scope-path [] :projection-path [field-id]})
               :edges edges :field-owner field-owner :owner-parents (get-in snapshot [:fns owner-id :parent-ids])
               :address address :public-path target-names :source-names source-names}
        generic-reference (assoc :generic-reference generic-reference)))))


(defn- check-forwarded-readers!
  [graph proofs certificates]
  (doseq [[source readers] (group-by (comp :source-address first)
                                     (filter (comp :multiple-readers? first) (map vector proofs certificates)))]
    (let [name (peek (:source-path (ffirst readers)))
          addresses (set (for [[scope contract] (get-in graph [:public-input :scope-contracts])
                               field (:fields contract)
                               :when (= name (:name field))]
                           [scope [(:id field)]]))]
      (when-not (= addresses (into #{source} (map (comp :address second)) readers))
        (reject! :unproved-forwarded-readers)))))


(defn- preserved-field-checks?
  [before after certificates]
  (let [prior (set (:obligations before))
        current (set (:obligations after))
        sources (into {} (map (fn [{:keys [address row]}]
                                [address [[(:fn-id before) []] (:projection-path row)]]))
                      certificates)]
    (and (every? prior current)
         (every? (fn [{:keys [address] :as obligation}]
                   (let [source (get sources address)]
                     (some #(and (= source (:address %))
                                 (= (select-keys obligation [:kind :trigger :expected-type])
                                    (select-keys % [:kind :trigger :expected-type]))) current)))
                 (remove current prior)))))


(defn verify-forwarded-projections
  "Replay the complete forwarding group in one frozen graph. Every destination
   must have one value-reference path. Existing field-read checks remain, or
   move to an equivalent checked source field; this never discharges them."
  [snapshot proofs analyze]
  (let [owner (only (distinct (map :owner-id proofs)) :unproved-forwarded-identity)
        before (checked-graph analyze snapshot owner)
        certificates (mapv #(forwarded-certificate snapshot before %) proofs)
        additions (into {} (map (juxt (comp :id :row) :row)) certificates)]
    (check-forwarded-readers! before proofs certificates)
    (when-not (= (count proofs) (count additions) (count (set (map :address certificates))))
      (reject! :unproved-forwarded-identity))
    (let [updated (-> snapshot (assoc :snapshot-id (random-uuid)) (update :bindings merge additions))
          after (checked-graph analyze updated owner)
          prior (get-in before [:public-input :bindings])
          current (get-in after [:public-input :bindings])]
      (when-not (and (preserves-occupancy? prior current certificates)
                     (preserved-field-checks? before after certificates)
                     (every? (fn [{:keys [address row public-path source-names]}]
                               (let [added (get current address)
                                     source [[owner []] (:projection-path row)]]
                                 (and (= (:id row) (:binding-id added)) (= owner (:origin-fn-id added))
                                      (= {:kind :projection :scope [owner []] :path (:projection-path row)} (:expression added))
                                      (not (contains? (get-in after [:public-input :routes]) public-path))
                                      (= source-names (free-route! after source
                                                                   (get-in before [:public-input :routes source-names :completion-type]))))))
                             certificates))
        (reject! :forwarded-replay-mismatch))
      (mapv #(assoc % :obligations (:obligations after)) certificates))))


(defn verify-forwarded-projection
  "Single-field convenience form of the atomic forwarding replay."
  [snapshot proof analyze]
  (first (verify-forwarded-projections snapshot [proof] analyze)))
