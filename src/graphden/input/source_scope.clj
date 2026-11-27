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
  [{owner-id :identity :keys [legacy blockers kind status]} paths snapshot projections]
  (when-not (and (= :composed kind) (= :blocked status) (uuid? owner-id)
                 (seq paths) (= (count paths) (count (set paths)) (count blockers))
                 (= (set (for [path paths] [path :ref-scope-required]))
                    (set (map (juxt :source-path :code) blockers)))
                 (every? #(and (= :binding (:kind %))
                               (or (empty? (:policy %)) (:annotation %) (:scoped-annotation %)
                                   (and (not-any? #{(:source-path %)} paths)
                                        (or (rename/preserved-pin? snapshot owner-id %)
                                            (and (= :list-builder (first (:expression %)))
                                                 (get-in % [:list-builder :records]))))))
                         (:arguments legacy)))
    (reject! :unsupported-scope-source))
  (let [records (:records legacy)
        bindings (filter #(= :binding (:kind %)) records)
        owner (only (filter #(and (= :fn (:kind %)) (= owner-id (:id %))) records)
                    :missing-owner-declaration)
        omitted (mapv #(legacy-origin owner-id (:arguments legacy) bindings %) paths)
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
        capture? (external/captured-input? graph snapshot edge scoped-target)
        certificate (cond-> {:from address :binding-id id :target-fn-id target :origin-fn-id (:fn-id row) :name name}
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
                            (= id (ids/binding-id (:fn-id row) field))))
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
        annotation (:scoped-annotation arg)]
    (when (and annotation (not= declaring (:declaring-id annotation)))
      (reject! :reference-field-origin-mismatch))
    (cond-> {:snapshot-id (:snapshot-id snapshot) :source-path (:source-path arg) :public-path public-path
             :address address :edges edges :field-owner declaring
             :owner-parents (get-in snapshot [:fns owner :parent-ids])
             :row (scoped-row binding address annotation)}
      annotation (assoc :configuration (select-keys annotation [:type :description]))
      (:target-type-origin arg) (assoc :target-type-origin (:target-type-origin arg)))))


(defn- binding-certificates
  [snapshot before literal-witness [arg {:keys [id fn-id slot-id value-present] :as binding} :as origin]]
  (let [addresses (occurrences before slot-id)
        field (peek (:source-path arg))]
    (if (and (next addresses) literal-witness value-present (empty? (:policy arg))
             (every? (fn [[_ contract]]
                       (every? #(or (not= field (:name %)) (= slot-id (:id %))) (:fields contract)))
                     (get-in before [:public-input :scope-contracts]))
             (literal-witness field binding))
      (let [addresses (sort-by context/address-sort-key addresses)
            [primary & additional] (map (partial binding-certificate snapshot before origin) addresses)
            common {:literal-occurrences (set addresses)}]
        (into [(merge primary common)]
              (map (fn [{[[_ use-path]] :address :as certificate}]
                     (-> (merge certificate common)
                         (assoc :copy-of id)
                         (assoc-in [:row :id] (migration/derived-id :scoped-literal-read fn-id use-path slot-id)))))
              additional))
      [(binding-certificate snapshot before origin (occurrence before slot-id))])))


(defn- verified-addition?
  [before after binding {:keys [address row public-path literal-occurrences] :as certificate}]
  (let [added (get-in after [:public-input :bindings address])
        expression (if (:value-present binding)
                     {:kind :value :value (:value binding)}
                     {:kind :ref :fn-id (:ref-fn-id binding)})]
    (and (= (:id binding) (or (:copy-of certificate) (:id row)))
         (if literal-occurrences
           (= literal-occurrences (set (occurrences after (:slot-id binding))))
           (= address (occurrence after (:slot-id binding))))
         (if (contains? certificate :configuration)
           (let [quantities [:address :completion-type :required :required-now? :whole-bindable?]]
             (and (nil? added)
                  (migration/same-edn? (select-keys (get-in before [:public-input :routes public-path]) quantities)
                                       (select-keys (get-in after [:public-input :routes public-path]) quantities))))
           (and (= (:id row) (:binding-id added)) (= (:fn-id binding) (:origin-fn-id added))
                (migration/same-edn? expression (:expression added))))
         (migration/same-edn? (meaning binding)
                              (meaning (migration/legacy-binding (assoc row :scope-path [] :id (:id binding))))))))


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
  [snapshot analyze before after {:keys [address row public-path]}]
  (let [[[root scope-path]] address
        {id :id target :ref-fn-id} row
        use-scope [root (conj scope-path id)]
        edge {:from address :to use-scope :binding-id id :target-fn-id target :mode :value}
        original (get-in before [:public-input :routes public-path :completion-type])
        {:keys [expected-type reference]} (get-in after [:scopes scope-path :bindings address])
        node (get-in after [:scopes (second use-scope)])
        proof (cond
                (and (types/list-type? original) (types/type-any? types/type-var? original) (types/list-type? expected-type)
                     (not (types/type-any? types/type-var? expected-type))) :checked-list
                (and (types/type-var? original) (= original (types/resolve (:substitution before) original))
                     (= :any expected-type)) :checked-open)]
    (when (and proof target (= target (:fn-id node))
               (= :value (:mode reference)) (some #{edge} (:dependencies after))
               (= (types/resolve (:substitution after) original) expected-type
                  (:actual-type reference) (get-in node [:signature :return])))
      (let [graph (checked-graph analyze snapshot target true)]
        (when (= expected-type (get-in graph [:scopes [] :signature :return]))
          [proof {:type expected-type :original original :graph graph}])))))


(defn- reference-field-checks
  "Replay the independently checked target on the new edge. Its own field
   may carry the universal :any check or the proved unary callable contract."
  [snapshot analyze before after {:keys [address row public-path] :as certificate}]
  (when-let [target (:ref-fn-id row)]
    (let [[[root scope-path] field-path] address
          id (:id row)
          use-scope [root (conj scope-path id)]
          edge {:from address :to use-scope :binding-id id :target-fn-id target :mode :value}
          proof (get-in after [:proofs address])
          callable-type (:checked-callable certificate)]
      (when (or callable-type (some #{edge} (:dependencies after)))
        (cond-> (nested-field-checks (or (:external-graph certificate) (get-in certificate [:checked-list :graph]) (get-in certificate [:checked-open :graph])
                                         (checked-graph analyze snapshot target true))
                                     after use-scope)
          (and (or callable-type (:checked-list certificate) (:checked-open certificate)
                   (= :any (get-in before [:public-input :routes public-path :completion-type])
                      (get-in after [:scopes scope-path :bindings address :expected-type])))
               (= [:deferred :runtime-type-obligation] ((juxt :status :reason) proof)))
          (conj {:id [address :value-type :field-read] :address address
                 :scope (first address) :path field-path
                 :kind :value-type :trigger :field-read :expected-type (or callable-type (get-in certificate [:checked-list :type]) :any)}))))))


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


(defn- preserves-scoped-checks?
  [snapshot analyze before after certificates]
  (let [current (set (:obligations after))
        original (into #{}
                       (remove #(and (not (current %)) (literal-lookup/proves-read? before after certificates %)))
                       (literal-checks/replay before after certificates))
        certificates (if (= current original)
                       certificates
                       (mapv (partial reference-certificate snapshot analyze before after) certificates))
        alias-references (callable-alias-reference-addresses snapshot after certificates)
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
                                  (current check) check
                                  (reads/discharged? snapshot before after certificates check) nil
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
                                  (some #(reads/literal-read? snapshot after % check) certificates))) current)))))))


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


(defn- verify-bindings*
  "Check a group of legacy bindings whose complete omitted set is known.
   Resolve all addresses in one frozen baseline. Replay all rows together so
   interdependent type constraints and occupancy use the completed graph.
   Existing field-read obligations must survive exactly. New value ref uses
   may bring the exact field checks of their independently checked targets."
  [{owner :identity :as plan-entry} paths snapshot analyze projections literal-witness]
  (let [omitted (legacy-omissions plan-entry paths snapshot projections)
        before (checked-graph analyze snapshot owner true)
        grouped (mapv (partial binding-certificates snapshot before literal-witness) omitted)
        certificates (into [] cat grouped)
        additions (into {} (map (juxt :id identity)) (map :row certificates))]
    (when-not (= (count certificates) (count additions) (count (set (map :address certificates))))
      (reject! :scope-replay-mismatch))
    (let [updated (-> snapshot (assoc :snapshot-id (random-uuid)) (update :bindings merge additions))
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
  ([entry paths snapshot analyze literal-witness]
   (verify-bindings* entry paths snapshot analyze [] literal-witness)))


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
                           multiple-readers? nested-reader? generic-reference list-operand untyped-rename? named-leaf?]}]
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
        nested-reader? (assoc :nested-reader? true)))))


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


(defn- preserved-field-checks?
  [before after certificates]
  (let [prior (set (:obligations before))
        current (set (:obligations after))
        certified (into {} (map (juxt :address identity)) certificates)
        sources (into {} (map (fn [{:keys [address row]}]
                                [address [[(:fn-id before) []] (:projection-path row)]]))
                      certificates)]
    (and (every? (fn [{:keys [address kind trigger expected-type] :as obligation}]
                   (or (prior obligation)
                       (when-let [{:keys [public-path source-names]} (certified address)]
                         (and (= [:value-type :field-read] [kind trigger])
                              (or (= :any expected-type)
                                  (and (types/list-type? expected-type)
                                       (= expected-type
                                          (get-in before [:public-input :routes public-path :completion-type])
                                          (get-in before [:public-input :routes source-names :completion-type])
                                          (get-in after [:public-input :routes source-names :completion-type]))))))))
                 current)
         (every? (fn [{:keys [address] :as obligation}]
                   (or (when-let [source (get sources address)]
                         (let [check (select-keys obligation [:kind :trigger :expected-type])]
                           (some #(and (= source (:address %))
                                       (= check (select-keys % [:kind :trigger :expected-type]))) current)))
                       (discharged-forwarded-variable? before after certified obligation)))
                 (remove current prior)))))


(defn verify-forwarded-projections
  "Replay the complete forwarding group in one frozen graph. Every destination
   must have one value-reference path. Existing field-read checks remain, or
   move to an equivalent checked source field. A projected list retains the
   exact supplied list contract at its reader."
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
