(ns graphden.input.source-scope
  "Read-only cross-reference origin verification against a frozen effective-input
   analysis. Derives no routes and changes neither source nor storage."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.list-records :as list-records]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.literal-lookup :as literal-lookup]
    [graphden.input.metadata :as metadata]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.input.source-collection-checks :as collections]
    [graphden.input.source-external :as external]
    [graphden.input.source-forward-generic :as generic]
    [graphden.input.source-joint :as joint]
    [graphden.input.source-literal-checks :as literal-checks]
    [graphden.input.source-positional-type :as positional-type]
    [graphden.input.source-read-checks :as reads]
    [graphden.input.source-rename :as rename]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


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


(defn- reference-configuration
  [snapshot source {:keys [source-path policy] :as argument} binding]
  (let [{:keys [type] :as raw} (get-in source source-path)
        id (:type-override-fn-id binding)]
    (when (and (map? raw) (= #{:ref :type} (set (keys raw)))
               (uuid? (:ref-fn-id binding)) (uuid? id)
               (= {:type-override-fn-id id} policy)
               (= id (get-in snapshot [:fields (:slot-id binding) :type-fn-id]))
               (migration/same-edn? type (get-in argument [:target :type])))
      {:type type})))


(defn- unchanged-list-binding!
  [snapshot original-bindings records {:keys [origin expression list-builder list-ref-names list-inline-items]}]
  (let [{:keys [binding-id slot-id fn-id]} origin
        fragment (peek (:fragments list-builder))
        builder-id (:builder-id fragment)
        installed (get-in snapshot [:source-list-fragments builder-id])
        actual (get-in snapshot [:bindings binding-id])
        expected (some #(when (= binding-id (:id %)) %) (:bindings list-builder))
        original (get original-bindings binding-id)
        generated (filter #(= :input-binding (:kind %)) (list-records/authored-records list-builder))
        items (sort-by :position (filter #(and (= :binding-list-item (:kind %)) (= binding-id (:binding-id %))) records))]
    (when-not (and (uuid? binding-id) (= binding-id (:binding-id fragment)) (= fn-id (:owner-id fragment))
                   (= slot-id (:target-id fragment))
                   (every? #{:ref :value} (map :kind (:items fragment)))
                   (= (select-keys installed (keys fragment)) fragment)
                   (= [:binding fn-id slot-id] ((juxt :kind :fn-id :slot-id) original))
                   (true? (:list-append original)) (false? (:value-present original)) (nil? (:value original))
                   (= (= :closed (:tail fragment)) (true? (:list-closed original)))
                   (every? nil? (map original [:type-override-fn-id :resolver-fn-id :ref-fn-id :required :terminal :description]))
                   (migration/same-edn? (vec items) (list-builder/compiler-items fragment))
                   (= expected actual) (= "ref" (:expression-kind actual)) (= builder-id (:ref-fn-id actual))
                   (= {:kind :ref :fn-id builder-id}
                      (list-builder/reference-expression list-builder expression list-ref-names list-inline-items))
                   (every? (set (map :id generated)) (into [binding-id] (map :id) (:items fragment)))
                   (every? #(= % (get-in snapshot [:bindings (:id %)])) generated))
      (reject! :source-snapshot-mismatch))
    [binding-id original]))


(defn- legacy-omissions
  [{owner-id :identity :keys [legacy blockers kind status source]} paths snapshot projections]
  (when-not (and (= :composed kind) (= :blocked status) (uuid? owner-id)
                 (seq paths) (= (count paths) (count (set paths)) (count blockers))
                 (= (set (for [path paths] [path :ref-scope-required]))
                    (set (map (juxt :source-path :code) blockers))))
    (reject! :unsupported-scope-source))
  (let [records (:records legacy)
        bindings (filter #(= :binding (:kind %)) records)
        owner (only (filter #(and (= :fn (:kind %)) (= owner-id (:id %))) records)
                    :missing-owner-declaration)
        omitted (mapv (fn [path]
                        (let [[argument binding] (legacy-origin owner-id (:arguments legacy) bindings path)
                              config (reference-configuration snapshot source argument binding)]
                          [(cond-> argument config (assoc :reference-configuration config)) binding]))
                      paths)
        verified-pins (into #{} (comp (map first) (filter :reference-configuration) (map :source-path)) omitted)
        _ (when-not (every? #(and (= :binding (:kind %))
                                  (or (empty? (:policy %)) (:annotation %) (:scoped-annotation %)
                                      (verified-pins (:source-path %))
                                      (and (not-any? #{(:source-path %)} paths)
                                           (or (rename/preserved-pin? snapshot owner-id %)
                                               (and (= :list-builder (first (:expression %)))
                                                    (get-in % [:list-builder :records]))))))
                            (:arguments legacy))
            (reject! :unsupported-scope-source))
        omitted-ids (into #{} (map (comp :id second)) omitted)
        projected-ids (set (map (comp :id :row) projections))
        original-bindings (into {} (map (juxt :id identity)) bindings)
        lists (into {} (comp (filter #(= :list-builder (first (:expression %))))
                             (map (partial unchanged-list-binding! snapshot original-bindings records)))
                    (:arguments legacy))
        current (filter #(and (= owner-id (:fn-id %)) (not (projected-ids (:id %)))) (vals (:bindings snapshot)))]
    (when-not (and (= (count omitted) (count omitted-ids))
                   (= (:parent-ids owner) (get-in snapshot [:fns owner-id :parent-ids]))
                   (not-any? (:bindings snapshot) omitted-ids)
                   (migration/same-edn? (into {} (map (juxt :id meaning)) (remove #(contains? omitted-ids (:id %)) bindings))
                                        (into {}
                                              (map (fn [{:keys [id] :as row}]
                                                     [id (meaning (or (get lists id)
                                                                      (rename/legacy-binding (vals (:fields snapshot)) row)))]))
                                              current)))
      (reject! :source-snapshot-mismatch))
    omitted))


(defn container-check?
  "Corroborate a scheduled container check with its immutable configured descriptor."
  [graph {:keys [id address scope path kind trigger expected-type]}]
  (and (= [:container-type :container-materialization] [kind trigger])
       (vector? scope) (= 2 (count scope)) (= (:fn-id graph) (first scope)) (vector? path)
       (= address [scope path]) (= id [address kind trigger])
       (when-let [contract (get-in graph [:scopes (second scope) :configured-contract])]
         (when-let [node (descriptor/find-node contract path)]
           (and (contains? node :fields) (= expected-type (:type node)))))))


(defn- checked-graph
  [analyze snapshot owner & [scoped-bindings?]]
  (let [graph (analyze snapshot owner)
        surface (:public-input graph)]
    (when-not (and (some? (:snapshot-id snapshot)) (= (:snapshot-id snapshot) (:snapshot-id surface))
                   (= owner (:fn-id graph) (:fn-id surface)))
      (reject! :stale-input-surface))
    (when-not (and (= :checked (:status graph)) (empty? (:diagnostics graph))
                   (every? #(or (= [:value-type :field-read] ((juxt :kind :trigger) %))
                                (and scoped-bindings? (container-check? graph %))) (:obligations graph))
                   (true? (get-in graph [:coverage :recursive-availability]))
                   (true? (get-in graph [:coverage :type-checker])))
      (reject! :incomplete-reference-analysis))
    graph))


(defn- occurrences
  [graph slot-id & [root-use-id]]
  ;; Look at every declared scope, including occupied/hidden occurrences.
  ;; Picking the sole FREE route would conflate a shared target's uses.
  (for [[scope contract] (get-in graph [:public-input :scope-contracts])
        :when (and (or (nil? root-use-id) (= root-use-id (first (second scope))))
                   (some #(= slot-id (:id %)) (:fields contract)))]
    [scope [slot-id]]))


(defn- occurrence
  [graph slot-id & [root-use-id]]
  (let [candidates (occurrences graph slot-id root-use-id)]
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


(defn- source-scope-path
  [snapshot graph [owner path] row]
  (let [local (:scope-path row)
        start (- (count path) (count local))
        origin (:fn-id row)
        prior (get-in snapshot [:source-scoped-references (:id row)])]
    (when (and (seq local) (<= 0 start) (= local (subvec path start))
               (= row (:row prior)) (= [[origin local] (:target-path row)] (:address prior))
               (some #{origin} (get-in graph [:scopes (subvec path 0 start) :declaration-order]))
               (= owner (:fn-id graph)))
      local)))


(defn- checked-edge
  [graph snapshot owner prefix scoped-target]
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
        source-path (source-scope-path snapshot graph parent row)
        legacy-slot (get-in snapshot [:source-legacy-reference-slots id])
        capture? (external/captured-input? graph snapshot edge scoped-target)
        certificate (cond-> {:from address :binding-id id :target-fn-id target :origin-fn-id (:fn-id row) :name name}
                      legacy-slot (assoc :source-legacy-slot legacy-slot)
                      capture? (assoc :captured-input scoped-target)
                      source-path (assoc :source-scope-path source-path)
                      operand (assoc :source-list-operand operand)
                      fragment (assoc :source-list-fragment fragment))]
    (when-not (and (or (= :value (:mode edge)) capture?) (= [owner (pop prefix)] parent)
                   (= id (peek prefix) (:binding-id bound) (:id row))
                   (= "ref" (:expression-kind row)) (= (:fn-id row) (:origin-fn-id bound))
                   (or (empty? (:scope-path row)) source-path) (= path [field] (:target-path row))
                   (or operand
                       (and (not (contains? (:source-list-fragments snapshot) (:fn-id row)))
                            (or (= id (ids/binding-id (:fn-id row) field))
                                (and (uuid? legacy-slot) (= id (ids/binding-id (:fn-id row) legacy-slot))))))
                   (or (nil? fragment) (list-signature/source-link? certificate))
                   (uuid? target) (= target (:ref-fn-id row) (get-in bound [:expression :fn-id])))
      (reject! :unsupported-reference-use))
    certificate))


(defn- callable-alias-route?
  [snapshot arg binding edge route]
  (let [{:keys [reader-address target-id callable-reader?]}
        (get-in snapshot [:source-reference-aliases (:binding-id edge)])]
    (and callable-reader?
         (= :ref (first (:expression arg)))
         (uuid? (:ref-fn-id binding))
         (= [(:slot-id binding)] (second reader-address))
         (= target-id (:target-fn-id edge))
         (types/fn-type? (:completion-type route)))))


(defn- public-route
  [graph snapshot arg binding [[_ use-path] _ :as address]]
  (let [owner (:fn-id binding)
        slot (:slot-id binding)
        [names route] (only (filter #(= address (:address (val %))) (get-in graph [:public-input :routes]))
                            :unavailable-reference-input)
        edges (mapv #(checked-edge graph snapshot owner (subvec use-path 0 %) address) (range 1 (inc (count use-path))))
        field (get-in arg [:target :field])
        declaring (only (filter #(= slot (ids/slot-id % field)) (get-in graph [:scopes use-path :declaration-order]))
                        :reference-field-origin-mismatch)
        origin (:target-type-origin arg)
        _ (when (and origin (not (and (positional-type/exact? origin (:target arg) snapshot declaring)
                                      (false? (:required route)) (false? (:required-now? route)))))
            (reject! :reference-field-origin-mismatch))
        expected (if origin :any (context/call-with-types snapshot #(types/resolve-alias (get-in arg [:target :type]))))
        declared (descriptor/find-node (get-in graph [:scopes use-path :declared-contract]) (second address))
        scoped (when (and (= expected (:type declared)) (types/type-any? types/type-var? expected))
                 (variables/type-in-scope (first address) expected))]
    (when-not (and (seq edges) (= field (peek (:source-path arg))) (= names (conj (mapv :name edges) field))
                   (= :input-field (:kind route)) (:whole-bindable? route) (= slot (:origin-field-id route))
                   (or (migration/same-edn? expected (:completion-type route))
                       (and scoped (migration/same-edn? scoped (:completion-type route)))
                       (and (types/type-var? scoped) (contains? (:substitution graph) scoped)
                            (some :captured-input edges) (:scoped-annotation arg)
                            (nil? (:expression arg)) (empty? (:policy arg))
                            (= (:completion-type route) (types/resolve (:substitution graph) scoped)))
                       (and (= :any expected) (callable-alias-route? snapshot arg binding (peek edges) route))))
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
  [snapshot before [arg binding] address]
  (let [owner (:fn-id binding)
        [public-path address edges declaring] (public-route before snapshot arg binding address)
        annotation (:scoped-annotation arg)
        ref-config (:reference-configuration arg)]
    (when (and annotation (not= declaring (:declaring-id annotation)))
      (reject! :reference-field-origin-mismatch))
    (cond-> {:snapshot-id (:snapshot-id snapshot) :source-path (:source-path arg) :public-path public-path
             :address address :edges edges :field-owner declaring
             :owner-parents (get-in snapshot [:fns owner :parent-ids])
             :row (cond-> (scoped-row binding address annotation)
                    ref-config (assoc :type-override-fn-id (:type-override-fn-id binding)))}
      annotation (assoc :configuration (select-keys annotation [:type :description]))
      ref-config (assoc :configuration ref-config)
      (:target-type-origin arg) (assoc :target-type-origin (:target-type-origin arg)))))


(defn- repeated-binding-certificate
  [primary value-present {:keys [address row] :as reader}]
  (let [{id :id owner :fn-id} (:row primary)
        [[_ use-path] [slot-id]] address
        [[_ source-scope] source-path] (:address primary)
        common (assoc reader :copy-of id :repeated-occurrences (:repeated-occurrences primary))]
    (if value-present
      (assoc-in common [:row :id] (migration/derived-id :scoped-literal-read owner use-path slot-id))
      (assoc common
             :shared-source (select-keys primary [:address :public-path])
             :row (rows/normalize
                    (assoc (dissoc row :ref-fn-id)
                           :id (migration/derived-id :scoped-ref-read owner use-path slot-id)
                           :expression-kind "projection"
                           :projection-scope-path source-scope :projection-path source-path))))))

(defn- binding-certificates
  [snapshot before reader-witness [arg {:keys [slot-id value-present ref-fn-id] :as binding} :as origin]]
  (let [addresses (occurrences before slot-id)
        field (peek (:source-path arg))]
    (if (and (next addresses) reader-witness (or value-present (uuid? ref-fn-id)) (empty? (:policy arg))
             (every? (fn [[_ contract]]
                       (every? #(or (not= field (:name %)) (= slot-id (:id %))) (:fields contract)))
                     (get-in before [:public-input :scope-contracts]))
             (reader-witness field binding))
      (let [addresses (sort-by context/address-sort-key addresses)
            [primary & others :as readers] (map (partial binding-certificate snapshot before origin) addresses)
            primary (assoc primary :repeated-occurrences (set addresses))]
        (when (and (not value-present) (some :captured-input (mapcat :edges readers)))
          (reject! :nonunique-reference-input))
        (into [primary] (map (partial repeated-binding-certificate primary value-present)) others))
      [(binding-certificate snapshot before origin (occurrence before slot-id))])))

(defn- verified-shared-projection?
  [after binding added {:keys [row shared-source]}]
  (let [[source-scope source-path] (:address shared-source)
        source (get-in after [:public-input :bindings (:address shared-source)])]
    (and (= (:id row) (:binding-id added)) (= (:fn-id binding) (:origin-fn-id added))
         (= {:kind :projection :scope source-scope :path source-path} (:expression added))
         (= (:id binding) (:binding-id source))
         (= :value (get-in source [:reference :mode]))
         (= {:kind :ref :fn-id (:ref-fn-id binding)} (:expression source)))))

(defn- verified-addition?
  [before after binding {:keys [address row public-path repeated-occurrences shared-source] :as certificate}]
  (let [added (get-in after [:public-input :bindings address])
        expression (if (:value-present binding)
                     {:kind :value :value (:value binding)}
                     {:kind :ref :fn-id (:ref-fn-id binding)})]
    (and (= (:id binding) (or (:copy-of certificate) (:id row)))
         (if repeated-occurrences
           (= repeated-occurrences (set (occurrences after (:slot-id binding))))
           (= address (occurrence after (:slot-id binding))))
         (if shared-source
           (verified-shared-projection? after binding added certificate)
           (and (if (nil? (:expression-kind row))
                  (let [quantities [:address :completion-type :required :required-now? :whole-bindable?]]
                    (and (nil? added)
                         (migration/same-edn? (select-keys (get-in before [:public-input :routes public-path]) quantities)
                                              (select-keys (get-in after [:public-input :routes public-path]) quantities))))
                  (and (= (:id row) (:binding-id added)) (= (:fn-id binding) (:origin-fn-id added))
                       (migration/same-edn? expression (:expression added))))
                (migration/same-edn? (meaning binding)
                                     (meaning (migration/legacy-binding (assoc row :scope-path [] :id (:id binding))))))))))

(defn- nested-field-check
  [target [root prefix] {:keys [id address scope path kind trigger] :as check}]
  (when-not (and (= target (first scope)) (= address [scope path]) (= id [address kind trigger]))
    (reject! :scope-replay-mismatch))
  (let [nested [root (into prefix (second scope))]
        address [nested path]]
    (assoc check :id [address kind trigger] :scope nested :address address)))


(defn- nested-binders
  [target-graph after [root prefix]]
  ;; Independent checking seeds variables from their declaring scopes. Match
  ;; each original declaration before translating its binder identities.
  (reduce-kv
    (fn [result path node]
      (let [nested-path (into prefix path)
            nested (get-in after [:scopes nested-path])
            contract (:declared-contract node)]
        (if (and nested (= (:fn-id node) (:fn-id nested))
                 (= (:declaration-order node) (:declaration-order nested))
                 (= contract (:declared-contract nested)))
          (merge result (variables/value-renaming [(:fn-id target-graph) path] [root nested-path] (:type contract)))
          result)))
    {} (:scopes target-graph)))


(defn- type-at-use
  [renaming rich]
  (if (seq renaming)
    (types/freshen-with rich (fn [variable-sort variable] (if (= :value variable-sort) (get renaming variable variable) variable)))
    rich))


(defn- nested-field-checks
  [target-graph after use-scope]
  (let [checks (:obligations target-graph)
        renaming (when (some #(types/type-any? types/type-var? (:expected-type %)) checks)
                   (nested-binders target-graph after use-scope))]
    (mapv (fn [check]
            (update (nested-field-check (:fn-id target-graph) use-scope check) :expected-type (partial type-at-use renaming)))
          checks)))


(defn- scoped-unary-callable
  "Validate a unary call site receiving a graph with one explicit positional
   parameter or a graph that ignores the argument. Preserve independently checked
   public inputs of handlers that declare an empty parameter vector."
  [snapshot before after {:keys [address row public-path external-graph]}]
  (let [[[root scope-path]] address
        id (:id row)
        target (:ref-fn-id row)
        use-scope [root (conj scope-path id)]
        edge {:from address :to use-scope :binding-id id :target-fn-id target :mode :graph-callable}
        {:keys [expected-type dispatch-type reference]} (get-in after [:scopes scope-path :bindings address])
        original (get-in before [:public-input :routes public-path :completion-type])
        {:keys [parameters captures external-captures call-sites callable-type]} (get-in after [:closures address])
        target-node (get-in after [:scopes (second use-scope)])
        signature (:signature target-node)]
    (when (and target (= target (:fn-id target-node)) (some #{edge} (:dependencies after))
               (= :graph-callable (:mode reference)) (true? (:capture-resolved? reference))
               (vector? parameters) (<= (count parameters) 1) (map? (:args signature))
               (= parameters (mapv keyword (:lambda-params signature))) (= [] captures)
               (if (seq external-captures)
                 (and (= [] parameters (:lambda-params signature) call-sites)
                      (= target (:fn-id external-graph))
                      (external/same-inputs? external-graph after use-scope public-path external-captures
                                             (partial type-at-use (nested-binders external-graph after use-scope))))
                 (and (= [] external-captures) (= parameters (vec (keys (:args signature))))))
               (types/fn-type? original) (= original dispatch-type)
               (= expected-type (types/resolve (:substitution after) original)) (types/fn-type? expected-type)
               (= 1 (count (types/fn-args expected-type)))
               (= (:actual-type reference) (reference/callable-contract signature))
               (= callable-type (:callable-type reference)
                  (reference/call-site-contract expected-type (:actual-type reference) (assoc signature :lambda-params parameters)))
               (types/fn-type? callable-type)
               (= (vals (types/fn-args expected-type)) (vals (types/fn-args callable-type)))
               (= (types/fn-ret expected-type) (types/fn-ret callable-type))
               (context/call-with-types snapshot #(types/subtype? callable-type expected-type)))
      expected-type)))


(defn- scoped-value-reference
  "Check the instantiated input against the independently inferred target in
   both scopes, preserving whether it was a generic list or an open variable."
  [snapshot analyze before after {:keys [address row public-path] :as certificate}]
  (let [[[root scope-path]] address
        {id :id target :ref-fn-id} row
        use-scope [root (conj scope-path id)]
        edge {:from address :to use-scope :binding-id id :target-fn-id target :mode :value}
        original (get-in before [:public-input :routes public-path :completion-type])
        {:keys [expected-type reference]} (get-in after [:scopes scope-path :bindings address])
        node (get-in after [:scopes (second use-scope)])
        actual (:actual-type reference)
        lookup (when (some #(= address (:captured-input %)) (:edges certificate))
                 (collections/captured-lookup before after address original expected-type actual))
        proof (cond
                lookup :checked-lookup
                (and (types/list-type? original) (types/type-any? types/type-var? original) (types/list-type? expected-type)
                     (not (types/type-any? types/type-var? expected-type))) :checked-list
                (and (types/type-var? original) (= original (types/resolve (:substitution before) original))
                     (= :any expected-type)) :checked-open)]
    (when (and proof target (= target (:fn-id node))
               (= :value (:mode reference)) (some #{edge} (:dependencies after))
               (= (types/resolve (:substitution after) original) expected-type)
               (= actual (get-in node [:signature :return]))
               (or lookup (= expected-type actual)))
      (let [graph (checked-graph analyze snapshot target true)
            returned (get-in graph [:scopes [] :signature :return])]
        (when (if lookup (= actual (type-at-use (nested-binders graph after use-scope) returned))
                  (= expected-type returned))
          [proof (cond-> {:type expected-type :original original :graph graph}
                   lookup (assoc :native-address lookup))])))))


(defn- reference-field-checks
  "Replay the independently checked target on the new edge. Its own field
   may carry the universal :any check or the proved unary callable contract."
  [snapshot analyze before after {:keys [address row public-path repeated-occurrences] :as certificate}]
  (when-let [target (:ref-fn-id row)]
    (let [[[root scope-path] field-path] address
          id (:id row)
          use-scope [root (conj scope-path id)]
          edge {:from address :to use-scope :binding-id id :target-fn-id target :mode :value}
          proof (get-in after [:proofs address])
          node (get-in after [:scopes scope-path])
          binding (get-in node [:bindings address])
          asserted (when (and (:type-override-fn-id row) (= id (:binding-id binding)) (= (:ref-fn-id row) (get-in binding [:expression :fn-id])))
                     (metadata/asserted-read-type node binding (get-in binding [:reference :actual-type])))
          callable-type (:checked-callable certificate)
          checked-value (some certificate [:checked-list :checked-open :checked-lookup])]
      (when (or callable-type (some #{edge} (:dependencies after)))
        (let [checks (nested-field-checks (or (:external-graph certificate) (:graph checked-value)
                                             (checked-graph analyze snapshot target true))
                                         after use-scope)
              checks (if repeated-occurrences
                       (mapv #(update % :expected-type (partial types/resolve (:substitution after))) checks)
                       checks)]
          (cond-> checks
            (and (or asserted callable-type checked-value
                     (= :any (get-in before [:public-input :routes public-path :completion-type])
                        (get-in after [:scopes scope-path :bindings address :expected-type])))
                 (= [:deferred :runtime-type-obligation] ((juxt :status :reason) proof)))
            (conj {:id [address :value-type :field-read] :address address
                   :scope (first address) :path field-path
                   :kind :value-type :trigger :field-read :expected-type (or asserted callable-type (:type checked-value) :any)})))))))


(defn- callable-alias-reference-addresses
  [snapshot after certificates]
  (let [modes (into {} (map (juxt :from :mode)) (:dependencies after))]
    (into #{}
          (keep (fn [{:keys [address edges]}]
                  (let [{:keys [binding-id from]} (peek edges)]
                    (when (and (= :graph-callable (get modes address))
                               (get-in snapshot [:source-reference-aliases binding-id :callable-reader?]))
                      from))))
          certificates)))


(defn- annotation-field-check
  [snapshot before after {:keys [configuration address public-path]}]
  (when-let [authored-type (:type configuration)]
    (let [expected (context/call-with-types snapshot #(types/resolve-alias authored-type))
          [scope path] address]
      ;; Metadata on an already typed, still open field schedules the same
      ;; check at the original read. Ignore neither the type nor the use.
      (when (and (= expected (get-in before [:public-input :routes public-path :completion-type])
                    (get-in after [:public-input :routes public-path :completion-type]))
                 (= address (get-in before [:public-input :routes public-path :address])
                    (get-in after [:public-input :routes public-path :address])))
        {:id [address :value-type :field-read] :address address :scope scope :path path
         :kind :value-type :trigger :field-read :expected-type expected}))))


(defn- reference-certificate
  [snapshot analyze before after {:keys [address row] :as certificate}]
  (if-let [[proof result] (scoped-value-reference snapshot analyze before after certificate)]
    (assoc certificate proof result)
    (let [{:keys [parameters call-sites captures external-captures]} (get-in after [:closures address])
          target (:ref-fn-id row)
          checked (cond-> certificate
                    (and target (= [] parameters call-sites captures) (seq external-captures)) (assoc :external-graph (checked-graph analyze snapshot target true)))]
      (if-let [callable (scoped-unary-callable snapshot before after checked)]
        (assoc checked :checked-callable callable)
        certificate))))


(defn- projected-check
  [snapshot analyze before after by-address current old]
  (when-let [replacement (get current (:id old))]
    (when-let [[target use-scope] (reads/projected-reference before after by-address old replacement)]
      (let [graph (checked-graph analyze snapshot target true)
            returned (get-in graph [:scopes [] :signature :return])
            renaming (when (types/type-any? types/type-var? returned) (nested-binders graph after use-scope))]
        (when (= (:expected-type replacement) (type-at-use renaming returned))
          replacement)))))


(defn- resolved-projected-check?
  [snapshot analyze before after by-address check]
  (when-let [[target required] (reads/resolved-projection-reference before after by-address check)]
    (= required (get-in (checked-graph analyze snapshot target true) [:scopes [] :signature :return]))))


(defn- shared-value-read?
  [after by-address {:keys [address expected-type] :as check}]
  (let [{:keys [row shared-source]} (get by-address address)
        added (reads/binding-at after address)]
    (and shared-source (reads/field-read? check) (= :any expected-type)
         (= (:id row) (:binding-id added))
         (= :projection (get-in added [:reference :mode]))
         (= (:address shared-source) (get-in added [:reference :source])))))


(defn- specialize-generated-read
  [before after {:keys [address expected-type additional-types kind trigger] :as check}]
  (let [[scope [slot :as path]] address
        node (get-in before [:scopes (second scope)])
        next-node (get-in after [:scopes (second scope)])
        {:keys [generated-list argument-paths base-fn-id] :as source} (:signature-source node)
        declaration (descriptor/find-node (:declared-contract node) path)
        declared (:type declaration)
        argument (:name declaration)]
    (if (and (= [:value-type :field-read] [kind trigger]) (empty? additional-types)
             generated-list (= base-fn-id (:fn-id node) (:fn-id next-node))
             (= source (:signature-source next-node)) (simple-keyword? argument)
             (= path [slot] [(ids/slot-id base-fn-id argument)] (get argument-paths argument))
             (= declaration (descriptor/find-node (:declared-contract next-node) path))
             (types/type-any? types/type-var? declared)
             (= expected-type (types/resolve (:substitution before) (variables/type-in-scope scope declared)))
             (= (select-keys (get-in node [:bindings address]) [:binding-id :origin-fn-id :expression])
                (select-keys (get-in next-node [:bindings address]) [:binding-id :origin-fn-id :expression])))
      (assoc check :expected-type (types/resolve (:substitution after) expected-type))
      check)))


(defn- preserves-scoped-checks?
  [snapshot analyze before after certificates]
  (let [current (set (:obligations after))
        original (into #{}
                       (comp (remove #(and (not (current %)) (literal-lookup/proves-read? before after certificates %)))
                             (map #(if (current %) % (specialize-generated-read before after %))))
                       (literal-checks/replay before after certificates))
        certificates (if (= current original)
                       certificates
                       (mapv (partial reference-certificate snapshot analyze before after) certificates))
        alias-references (callable-alias-reference-addresses snapshot after certificates)
        specialized (into {} (keep #(when-let [lookup (:checked-lookup %)] [(:native-address lookup) lookup])) certificates)
        original (if (seq specialized)
                   (into #{} (map (fn [{:keys [address expected-type] :as check}]
                                    (let [lookup (get specialized address)]
                                      (if (and lookup (= (:original lookup) expected-type))
                                        (assoc check :expected-type (:type lookup)) check)))) original)
                   original)
        prior (if (and (empty? alias-references) (not-any? :checked-list certificates)
                       (not-any? :checked-callable certificates))
                original
                (into #{}
                      (comp (remove (fn [{:keys [address kind trigger expected-type] :as check}]
                                      (and (contains? alias-references address)
                                           (= [:value-type :field-read :any] [kind trigger expected-type])
                                           (not (contains? current check)))))
                            (map #(update % :expected-type (partial types/resolve (:substitution after)))))
                      original))
        by-address (into {} (map (juxt :address identity)) certificates)
        by-id (into {} (map (juxt :id identity)) current)
        prior (into #{} (keep (fn [check]
                                (cond
                                  (or (current check) (seq (:additional-types check))
                                      (seq (:additional-types (get by-id (:id check))))) check
                                  (or (reads/discharged? snapshot before after certificates check)
                                      (and (not (contains? by-id (:id check)))
                                           (resolved-projected-check? snapshot analyze before after by-address check))) nil
                                  (collections/refined-read? #(checked-graph analyze snapshot % true) before after certificates check (get by-id (:id check)))
                                  (get by-id (:id check))
                                  :else (or (projected-check snapshot analyze before after by-address by-id check) check)))) prior)]
    (or (= prior current)
        (and (every? current prior)
             (let [ref-checks (into prior (mapcat #(reference-field-checks snapshot analyze before after %)) certificates)
                   annotations (into #{} (keep #(annotation-field-check snapshot before after %)) certificates)]
               (and (every? current ref-checks)
                    (every? (fn [check]
                              (or (ref-checks check) (annotations check)
                                  (and (empty? (:additional-types check))
                                       (or (shared-value-read? after by-address check)
                                           (some #(reads/literal-read? snapshot after % check) certificates))))) current)))))))


(defn- preserves-projection-readers?
  [graph projections]
  (let [by-slot (group-by #(first (get-in % [:row :target-path])) projections)]
    (every? (fn [[slot certificates]]
              (let [actual (set (for [[scope contract] (get-in graph [:public-input :scope-contracts])
                                      :when (some #(= slot (:id %)) (:fields contract))]
                                  [scope [slot]]))]
                (= actual (into #{} (map :address) certificates))))
            by-slot)))


(defn- replacement-inputs?
  [snapshot analyze after {:keys [address public-path row]} capture-path inputs]
  (let [[[owner path]] address
        {:keys [id ref-fn-id]} row
        use-scope [owner (conj path id)]
        bound (reads/binding-at after address)
        edge {:from address :to use-scope :binding-id id :target-fn-id ref-fn-id :mode :value}]
    (when (and (uuid? ref-fn-id) (uuid? id) (= id (:binding-id bound)) (= :value (get-in bound [:reference :mode]))
               (= {:kind :ref :fn-id ref-fn-id} (:expression bound)) (some #{edge} (:dependencies after)))
      (let [original (checked-graph analyze snapshot ref-fn-id true)]
        (external/matching-inputs? original after use-scope public-path inputs
                                   (partial type-at-use (nested-binders original after use-scope))
                                   #(into capture-path %))))))


(defn with-certificate-rows
  "Install exact scoped rows and their authored reference type witnesses."
  [snapshot certificates]
  (-> snapshot
      (assoc :snapshot-id (random-uuid))
      (update :bindings into (map (comp (juxt :id identity) :row) certificates))
      (update :source-scoped-references (fnil into {})
              (keep (fn [{:keys [address row]}]
                      (when (= "ref" (:expression-kind row))
                        [(:id row) {:row row :address address}]))) certificates)
      (update :source-binding-types (fnil into {})
              (keep (fn [{:keys [row configuration]}]
                      (when (and (= "ref" (:expression-kind row)) (:type configuration))
                        [[(:id row) (:type-override-fn-id row)] (:type configuration)])))
              certificates)))


(defn- verify-bindings*
  "Check a group of legacy bindings whose complete omitted set is known.
   Resolve all addresses in one frozen baseline. Replay all rows together so
   interdependent type constraints and occupancy use the completed graph.
   Existing field-read obligations must survive exactly. New value ref uses
   may bring the exact field checks of their independently checked targets."
  [{owner :identity :as plan-entry} paths snapshot analyze projections reader-witness]
  (let [omitted (legacy-omissions plan-entry paths snapshot projections)
        before (checked-graph analyze snapshot owner true)
        grouped (mapv (partial binding-certificates snapshot before reader-witness) omitted)
        certificates (into [] cat grouped)
        additions (into {} (map (juxt :id identity)) (map :row certificates))]
    (when-not (= (count certificates) (count additions) (count (set (map :address certificates))))
      (reject! :scope-replay-mismatch))
    (let [updated (with-certificate-rows snapshot certificates)
          after (checked-graph analyze updated owner true)
          prior (get-in before [:public-input :bindings])
          current (get-in after [:public-input :bindings])]
      (when-not (and (every? true? (map (fn [[_ binding] group]
                                          (every? (partial verified-addition? before after binding) group)) omitted grouped))
                     (preserves-projection-readers? after projections)
                     (preserves-occupancy? prior current certificates)
                     (external/preserves-capture-inputs? before after certificates (partial replacement-inputs? snapshot analyze after))
                     (preserves-scoped-checks? snapshot analyze before after certificates))
        (reject! :scope-replay-mismatch))
      (mapv #(assoc % :obligations (:obligations after)) certificates))))


(defn verify-bindings
  "Check exactly the complete omitted binding group, preserving all existing
   occupancy and runtime obligations. No forwarding declarations are admitted."
  ([entry paths snapshot analyze] (verify-bindings entry paths snapshot analyze nil))
  ([entry paths snapshot analyze reader-witness]
   (verify-bindings* entry paths snapshot analyze [] reader-witness)))


(defn verify-bindings-after-forwarding
  "Check the remaining scoped group after an exact authored forwarding proof.
   Projection rows remain in the analyzed graph and their destinations must
   still have one occurrence after every scoped value/reference is replayed."
  [original projected snapshot analyze]
  (let [{:keys [entry projections]} (joint/scope-view original projected snapshot)
        {:keys [paths]} (joint/groups original)]
    (verify-bindings* entry paths snapshot analyze projections nil)))


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
                           multiple-readers? nested-reader? generic-reference list-operand untyped-rename? named-leaf? inherited-reader-slot]}]
  (let [address (if nested-reader? target-address (occurrence before target-field-id (when multiple-readers? root-use-id)))
        [[root uses] target-path] address
        source-type (context/call-with-types snapshot #(types/resolve-alias source-type))
        target-type (context/call-with-types snapshot #(types/resolve-alias target-type))]
    (when generic-reference (generic/verify! snapshot before owner-id generic-reference))
    (when-not (and (= owner-id root) (= source-address [[owner-id []] [field-id]])
                   (= (or target-address address) address) (= root-use-id (first uses))
                   (every? uuid? [owner-id field-id root-use-id target-field-id target-fn-id])
                   (= projection-id (migration/derived-id :forwarded-field owner-id (if nested-reader? uses root-use-id) target-field-id))
                   (not (contains? (:bindings snapshot) projection-id)))
      (reject! :unproved-forwarded-identity))
    (let [source-names (free-route! before source-address source-type)
          inferred? (and (or list-operand untyped-rename?) (nil? target-type))
          [target-names target-route] (checked-free-route before address)
          expected (:completion-type target-route)
          variable (when (and inferred? (types/type-var? expected)) expected)
          _ (when-not (if inferred?
                        (and (some? expected)
                             (or variable (context/call-with-types snapshot #(types/subtype? source-type expected))))
                        (migration/same-edn? target-type expected))
              (reject! :unproved-forwarded-input))
          edges (mapv #(checked-edge before snapshot owner-id (subvec uses 0 %) address) (range 1 (inc (count uses))))
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
        generic-reference (assoc :generic-reference generic-reference)
        variable (assoc :target-variable variable)
        named-leaf? (assoc :named-leaf? true)
        inherited-reader-slot (assoc :inherited-reader-slot inherited-reader-slot)
        nested-reader? (assoc :nested-reader? true)))))


(defn- check-forwarded-readers!
  [graph proofs certificates]
  (doseq [[source readers] (group-by (comp :source-address first)
                                     (filter (comp :multiple-readers? first) (map vector proofs certificates)))]
    (let [name (peek (:source-path (ffirst readers)))
          occupied (set (for [[{:keys [occupied-reader-ids]} {:keys [edges]}] readers
                              {:keys [from binding-id]} edges
                              :when (and (contains? occupied-reader-ids binding-id)
                                         (= :value (get-in graph [:public-input :bindings from :reference :mode]))
                                         (= binding-id (get-in graph [:public-input :bindings from :binding-id])))]
                          from))
          addresses (set (for [[scope contract] (get-in graph [:public-input :scope-contracts])
                               field (:fields contract)
                               :let [address [scope [(:id field)]]]
                               :when (and (= name (:name field)) (not (occupied address)))]
                           address))]
      (when-not (= addresses (into #{source} (map (comp :address second)) readers))
        (reject! :unproved-forwarded-readers)))))


(defn- projected-variable?
  [before after {:keys [address target-variable source-names]}]
  (let [source-type (get-in before [:public-input :routes source-names :completion-type])]
    (and target-variable source-type
         (not (types/type-any? types/type-var? source-type))
         (= source-type (types/resolve (:substitution after) target-variable)
            (get-in after [:public-input :routes source-names :completion-type]))
         (= :checked (get-in after [:proofs address :status])))))


(defn- discharged-forwarded-variable?
  [before after certificates {:keys [address kind trigger expected-type]}]
  (let [old (get-in before [:public-input :bindings address])
        installed (get-in after [:public-input :bindings address])
        from (get-in old [:reference :source])
        cert (get certificates from)
        concrete (get-in after [:public-input :routes (:source-names cert) :completion-type])]
    (and (= [:value-type :field-read] [kind trigger]) cert
         (= expected-type (:target-variable cert) (:expected-type old) (get-in old [:reference :actual-type]))
         (= :projection (get-in old [:reference :mode]) (get-in installed [:reference :mode]))
         (= from (get-in installed [:reference :source]))
         (= (select-keys old [:binding-id :origin-fn-id :expression])
            (select-keys installed [:binding-id :origin-fn-id :expression]))
         (= concrete (:expected-type installed) (get-in installed [:reference :actual-type]))
         (= :checked (get-in after [:proofs address :status]))
         (projected-variable? before after cert))))


(defn- projected-field-check?
  [before after {:keys [public-path source-names]} {:keys [address kind trigger expected-type additional-types] :as check}]
  (when (and public-path (empty? additional-types))
    (let [unchanged? (= expected-type
                        (get-in before [:public-input :routes public-path :completion-type])
                        (get-in before [:public-input :routes source-names :completion-type])
                        (get-in after [:public-input :routes source-names :completion-type]))]
      (or (and (= [:value-type :field-read] [kind trigger])
               (or (= :any expected-type) (and (types/list-type? expected-type) unchanged?)
                   (and unchanged? (contains? #{[:map :any :any] [:union :null [:map :keyword :any]]} expected-type)
                        (= expected-type (get-in after [:public-input :bindings address :reference :actual-type]))
                        (= :projection (get-in after [:public-input :bindings address :reference :mode])))))
          (and unchanged? (container-check? after check)
               (= expected-type (get-in after [:public-input :bindings address :reference :actual-type])))))))


(defn- preserved-field-checks?
  [before after certificates]
  (let [prior (set (:obligations before))
        current (set (:obligations after))
        certified (into {} (map (juxt :address identity)) certificates)
        sources (into {} (map (fn [{:keys [address row]}]
                                [address [[(:fn-id before) []] (:projection-path row)]]))
                      certificates)]
    (and (every? #(or (prior %) (projected-field-check? before after (certified (:address %)) %)) current)
         (every? (fn [{:keys [address] :as obligation}]
                   (or (when-let [source (get sources address)]
                         (let [check (select-keys obligation [:kind :trigger :expected-type :additional-types])]
                           (some #(and (= source (:address %))
                                       (= check (select-keys % [:kind :trigger :expected-type :additional-types]))) current)))
                       (discharged-forwarded-variable? before after certified obligation)))
                 (remove current prior)))))


(defn verify-forwarded-projections
  "Replay the complete forwarding group in one frozen graph. Every destination
   must have one value-reference path. Existing field-read checks remain, or
   move to an equivalent checked source field. A projected list retains the
   exact supplied list contract at its reader."
  [snapshot proofs analyze]
  (let [owner (only (distinct (map :owner-id proofs)) :unproved-forwarded-identity)
        before (checked-graph analyze snapshot owner true)
        certificates (mapv #(forwarded-certificate snapshot before %) proofs)
        additions (into {} (map (juxt (comp :id :row) :row)) certificates)]
    (check-forwarded-readers! before proofs certificates)
    (when-not (= (count proofs) (count additions) (count (set (map :address certificates))))
      (reject! :unproved-forwarded-identity))
    (let [updated (-> snapshot (assoc :snapshot-id (random-uuid)) (update :bindings merge additions))
          after (checked-graph analyze updated owner true)
          prior (get-in before [:public-input :bindings])
          current (get-in after [:public-input :bindings])]
      (when-not (and (preserves-occupancy? prior current certificates)
                     (external/preserves-capture-inputs? before after certificates (constantly false))
                     (preserved-field-checks? before after certificates)
                     (every? (fn [{:keys [address row public-path source-names target-variable] :as cert}]
                               (let [added (get current address)
                                     source [[owner []] (:projection-path row)]]
                                 (and (or (nil? target-variable)
                                          (projected-variable? before after cert))
                                      (= (:id row) (:binding-id added)) (= owner (:origin-fn-id added))
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
