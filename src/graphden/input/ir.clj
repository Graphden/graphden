(ns graphden.input.ir
  "Pure lowering of a checked input plan for the fixture compiler. This is
   an internal execution IR, not authoring syntax or a production entry point."
  (:require
    [graphden.input.descriptor :as descriptor]
    [graphden.input.lazy-list-abi :as lazy-list]
    [graphden.input.literal-lookup :as literal-lookup]
    [graphden.input.lookup-abi :as lookup-abi]
    [graphden.input.metadata :as metadata]
    [graphden.input.obligations :as obligations]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(defn reject!
  [code details]
  (throw (ex-info "Unsupported canonical input compilation"
                  (merge {:type :input/compile-rejected :code code} details))))


(defn- checked-plan!
  [analysis snapshot-id]
  (when-not (and (= :checked (:status analysis))
                 (every? #(true? (get (:coverage analysis) %))
                         [:recursive-availability :type-checker]))
    (reject! :unchecked-plan {}))
  (when-not (and snapshot-id (= snapshot-id (get-in analysis [:public-input :snapshot-id])))
    (reject! :snapshot-mismatch {})))


(def ^:private hiccup-elements [:union :bool :float :int :null :text [:list :any]])


(defn- native-argument?
  [analysis [[_ scope] path] module-ns native-name argument]
  (let [id (ids/fn-id module-ns native-name)
        {:keys [base-fn-id root-entry argument-paths]} (get-in analysis [:scopes scope :signature-source])]
    (and (= id base-fn-id (:fn-id root-entry))
         (= [module-ns native-name] ((juxt :namespace :name) root-entry))
         (= path [(ids/slot-id id argument)] (get argument-paths argument)))))


(defn- hiccup-children?
  [analysis address expected]
  (let [element (when (types/list-type? expected) (types/list-elem expected))]
    (and (types/union-type? element)
         (= (set (types/union-members hiccup-elements)) (set (types/union-members element)))
         (native-argument? analysis address "web.html" :hiccup :children))))


(defn- generated-list-source
  [analysis [scope path] argument]
  (let [{:keys [generated-list argument-paths]} (get-in analysis [:scopes (second scope) :signature-source])]
    (when (and (map? generated-list)
               (some #(= path (get argument-paths %))
                     (if (= :tail argument) [:tail] (:operands generated-list))))
      (some (fn [{:keys [from to mode]}]
              (when (and (= :value mode) (= scope to)) from))
            (:dependencies analysis)))))


(defn- hiccup-tail?
  [analysis address expected op]
  (and (= :supplied op) (= [:list :hiccup-node] expected)
       (when-let [source (generated-list-source analysis address :tail)]
         (native-argument? analysis source "web.html" :hiccup :children))))


(defn- open-key-map?
  [shape]
  (and (types/map-type? shape) (types/type-var? (types/map-key shape))
       (= :any (types/map-val shape))))


(defn- open-map-read
  [analysis address expected op]
  (when (#{:invoke :read} op)
    (let [outer-list? (types/list-type? expected)
          arms (set (types/union-members (if outer-list? (types/list-elem expected) expected)))
          maps (filter open-key-map? arms)]
      (when (= 1 (count maps))
        (cond
          (and outer-list? (= #{:null} (disj arms (first maps)))
               (native-argument? analysis address "core.collections" :merge :maps))
          [:list [:union :null [:map :any :any]]]
          (and (not outer-list?) (= #{:null [:list :any]} (disj arms (first maps)))
               (native-argument? analysis address "core.collections" :get :coll))
          [:union :null [:list :any] [:map :any :any]])))))


(defn- generated-merge-read
  [analysis [[_ scope] :as address] expected op]
  (let [binding (get-in analysis [:scopes scope :bindings address])
        literal-tail? (and (= :literal op) (= {:kind :value :value []} (:expression binding)))
        element (if literal-tail? (types/list-elem expected) expected)
        [dictionary :as maps] (remove #{:null} (types/union-members element))
        actual (get-in binding [:reference :actual-type])
        map-arm? #(or (#{:null :empty-map} %) (types/map-type? %) (types/record-type? %))]
    (when (and (= 1 (count maps)) (open-key-map? dictionary)
               (= #{:null dictionary} (set (types/union-members element)))
               (or literal-tail?
                   (and (= :invoke op) (= :value (get-in binding [:reference :mode]))
                        (every? map-arm? (or (types/union-members actual) [actual]))))
               (when-let [from (generated-list-source analysis address (if literal-tail? :tail :operand))]
                 (and (native-argument? analysis from "core.collections" :merge :maps)
                      (= '{:maps [:list [:union :null [:map a :any]]]}
                         (get-in analysis [:scopes (second (first from)) :signature-source :root-entry :args]))
                      (= [:map :any :any]
                         (get-in analysis [:scopes (second (first from)) :signature-source :root-entry :return])))))
      (let [element [:union :null [:map :any :any]]]
        (if literal-tail? [:list element] element)))))


(defn- computed-collection-read
  [analysis [[_ scope] :as address] expected op]
  (when (= :invoke op)
    (let [{:keys [mode actual-type]} (get-in analysis [:scopes scope :bindings address :reference])
          dictionary [:map [:union :keyword :text] :any]]
      (when (and (= :value mode)
                 (or
                   (and (= dictionary actual-type)
                        (= #{:null [:list :any] dictionary} (set (types/union-members expected)))
                        (or (native-argument? analysis address "core.collections" :get :coll)
                            (native-argument? analysis address "core.collections" :contains? :coll)))
                   (and (= actual-type [:map :any :any])
                        (or (= expected actual-type) (obligations/materializable-record? expected))
                        (native-argument? analysis address "core.logic" :assert-eq :actual))
                   (and (= actual-type expected)
                        (or (and (obligations/materializable-record? expected)
                                 (native-argument? analysis address "core.logic" :if :then))
                            (and (= [:list :jsonb] expected)
                                 (native-argument? analysis address "core.collections" :zipmap :vals))))))
        expected))))


(defn- field-read-type
  [analysis [[_ scope] :as address] expected op]
  (or (cond
        (or (contains? #{:any :null :bool :text :int :float :decimal :numeric
                         :keyword :uuid :timestamptz :bytes :jsonb [:list :any]} expected)
            (and (= [:map :keyword :any] expected) (#{:invoke :literal :read} op))
            (and (= :read op) (= [:map :any :any] expected)
                 (= {:mode :projection :actual-type expected}
                    (select-keys (get-in analysis [:scopes scope :bindings address :reference]) [:mode :actual-type])))
            (and (types/union-type? expected)
                 (contains? #{#{:input-stream :jsonb} #{:null [:map :keyword :any]} #{:null [:map :any :any]} #{:null [:map :any :any] [:list :any]}}
                            (set (types/union-members expected))))
            (and (types/union-type? expected) (seq (types/union-members expected))
                 (every? #{:null :bool :text :int :float :numeric :keyword [:list :any]}
                         (types/union-members expected)))
            (hiccup-children? analysis address expected)) expected
        (hiccup-tail? analysis address expected op) [:list hiccup-elements]
        (or (and (= [:union :null [:map [:union :keyword :text] :any]] expected)
                 (native-argument? analysis address "web.html" :hiccup :attrs))
            (and (= :supplied op)
                 (contains? #{[:list [:union :null [:map :any :any]]]
                              [:list [:union :null [:map :keyword :any]]]} expected)
                 (when-let [source (generated-list-source analysis address :tail)]
                   (native-argument? analysis source "core.collections" :merge :maps)))
            (and (= :read op) (= [:union :null [:map :any :any]] expected)
                 (when-let [source (generated-list-source analysis address :operand)]
                   (native-argument? analysis source "core.collections" :merge :maps)))) expected
        (and (types/list-type? expected) (#{:invoke :read} op))
        (let [actual (get-in analysis [:scopes scope :bindings address :reference :actual-type])]
          (when (or (= [:list [:map :keyword :any]] expected actual)
                    (and (= [:list :any] actual) (contains? #{:bool :text :int :numeric :keyword :uuid} (types/list-elem expected))))
            expected))
        ;; A checked literal or producer can select a concrete arm without
        ;; interpreting variables in the other arms. Keep the check at the read.
        (types/union-type? expected)
        (let [{:keys [mode actual-type]} (get-in analysis [:scopes scope :bindings address :reference])]
          (when (#{[:invoke :value] [:literal :literal]} [op mode])
            (let [arm (cond
                        (= :null actual-type) :null
                        (= [:map :keyword :any] actual-type) actual-type
                        (types/list-type? actual-type) [:list :any])]
              (or (when (some #{arm} (types/union-members expected)) arm)
                  (literal-lookup/keyword-map-read-type analysis address expected actual-type))))))
      (open-map-read analysis address expected op)
      (generated-merge-read analysis address expected op)
      (computed-collection-read analysis address expected op)
      (lookup-abi/collection-read-domain analysis address expected op)))


(defn- compiled-closure-origin
  [expressions closures address]
  (loop [address address]
    (or (get closures address)
        (let [{:keys [op source]} (get expressions address)]
          (when (= :read op)
            (recur source))))))


(defn- read-validator
  [analysis expressions closures {:keys [address kind trigger expected-type]}]
  (let [op (get-in expressions [address :op])
        details {:address address :kind kind :trigger trigger}
        closure (compiled-closure-origin expressions closures address)
        check (when (and op (= :value-type kind) (= :field-read trigger))
                (if closure
                  (when (or (= expected-type (:type closure))
                            (reference/same-unary-contract? expected-type (:type closure)))
                    expected-type)
                  (or (field-read-type analysis address expected-type op)
                      (when (= :invoke op)
                        (let [node (get-in analysis [:scopes (second (first address))])
                              binding (get-in node [:bindings address])]
                          (when (= expected-type (metadata/asserted-read-type node binding (get-in binding [:reference :actual-type])))
                            expected-type)))
                      (lookup-abi/lookup-item-list-type analysis expressions closures address expected-type))))]
    (when (and (= :value-type kind) (= :field-read trigger) (types/fn-type? expected-type) (= :supplied op))
      (reject! :supplied-callable-validation-required details))
    (when-not check (reject! :runtime-obligations-required details))
    (when-not closure check)))


(defn- native-record-argument?
  [{:keys [base-fn-id root-entry argument-paths]} path expected]
  (let [{:keys [fn-id name args] module-ns :namespace} root-entry]
    (and (uuid? fn-id) (= fn-id base-fn-id) (string? module-ns) (simple-keyword? name)
         (= fn-id (ids/fn-id module-ns name))
         (some (fn [[argument type]]
                 (and (= expected type) (= path [(ids/slot-id fn-id argument)] (get argument-paths argument))))
               args))))


(defn- projected-identity-record
  [analysis expressions scopes [scope path :as address] expected]
  (let [node (get-in analysis [:scopes (second scope)])
        native (get scopes scope)
        source (lookup-abi/identity-record-source node address expected)]
    (when (and source (= path (get-in native [:argument-paths :value]))
               (= (select-keys native [:base-fn-id :root-entry :argument-paths])
                  (select-keys (:signature-source node) [:base-fn-id :root-entry :argument-paths]))
               (= {:op :read :source source} (select-keys (get expressions address) [:op :source])))
      source)))


(defn- container-validator
  [analysis expressions scopes {:keys [address trigger expected-type additional-types]}]
  (let [[scope path] address
        native (get scopes scope)
        binding (get-in analysis [:scopes (second scope) :bindings address])
        expression (get expressions address)
        {collection :argument original-path :path} (lookup-abi/collection-argument (:root-entry native) 'a)
        lookup? (and collection (= (:base-fn-id native) (get-in native [:root-entry :fn-id]))
                     (= path original-path (get-in native [:argument-paths collection])))
        identity-input (projected-identity-record analysis expressions scopes address expected-type)
        identity-source? (= address (projected-identity-record analysis expressions scopes
                                                               [scope (get-in native [:argument-paths :value])] expected-type))
        holder (when (and lookup? (= :invoke (:op expression)) (= :value (get-in binding [:reference :mode]))
                          (= (:binding-id expression) (:binding-id binding))
                          (= (:target-id expression) (get-in binding [:expression :fn-id])))
                 (let [child (:scope expression)]
                   (projected-identity-record analysis expressions scopes
                                              [child (get-in scopes [child :argument-paths :value])] expected-type)))
        source (loop [at (or holder address)]
                 (let [expression (get expressions at)]
                   (if (= :read (:op expression)) (recur (:source expression)) at)))
        origin (get expressions source)
        fields (:fields origin)
        dictionary (cond
                     (and (= 1 (count additional-types))
                          (obligations/materialized-dictionary? expected-type (first additional-types))) :keyword
                     (and (empty? additional-types)
                          (or holder
                              (and (or lookup? identity-input identity-source? (native-record-argument? native path expected-type))
                                   (= :read (:op expression))
                                   (= :projection (get-in binding [:reference :mode]) (get-in binding [:expression :kind]))))
                          (= expected-type (:expected-type binding) (get-in binding [:reference :actual-type]))
                          (obligations/materializable-record? expected-type)) :any)]
    ;; Only the precise constructed record is known to keep field validators
    ;; on its own lazy cells. Check its keys at the materializing read.
    (when-not (and (= :container-materialization trigger) (= :record (:op origin))
                   (= expected-type (:type origin)) dictionary
                   (= (set (keys expected-type)) (set (map first fields)))
                   (every? (fn [[name address]]
                             (= {:op :supplied :type (get expected-type name)}
                                (select-keys (get expressions address) [:op :type]))) fields))
      (reject! :runtime-obligations-required {:address address :kind :container-type :trigger trigger}))
    {:source source :required (vec (sort (keys expected-type))) :key-type dictionary}))


(defn- read-obligations
  [analysis expressions scopes closures]
  (let [obligations (distinct (concat (:obligations analysis) (mapcat :obligations (vals (:scopes analysis)))))
        whole (into #{} (comp (filter #(and (= :input-field (:kind %)) (:whole-bindable? %))) (map :address))
                    (vals (get-in analysis [:public-input :routes])))]
    (reduce (fn [plan {:keys [address expected-type additional-types kind] :as entry}]
              (when (or (contains? (:value-obligations plan) address)
                        (contains? (:container-obligations plan) address))
                (reject! :conflicting-runtime-obligations {:address address}))
              (if (= :container-type kind)
                (let [{:keys [source key-type] :as proof} (container-validator analysis expressions scopes entry)]
                  (cond-> (assoc-in plan [:container-obligations address] (dissoc proof :source))
                    (contains? whole source) (update-in [:whole-record-inputs source]
                                                        #(if (= :keyword %) :keyword key-type))))
                (let [checks (into [] (keep #(read-validator analysis expressions closures (assoc entry :expected-type %)))
                                   (cons expected-type additional-types))]
                  (cond-> plan (seq checks) (assoc-in [:value-obligations address] checks)))))
            {:value-obligations {} :container-obligations {} :whole-record-inputs {}} obligations)))


(defn- captured-value-owner
  [analysis [[root path] field :as address] {:keys [binding-id expression lexical-value-owner origin-fn-id]}]
  (when-let [[owner declaring-path] lexical-value-owner]
    (when (and (vector? declaring-path) (<= (count declaring-path) (count path))
               (some #(contains? (::callback-scopes analysis) [root (subvec path 0 %)])
                     (range (inc (count declaring-path)) (inc (count path)))))
      (let [{:keys [row] original :address} (get (::scoped-references analysis) binding-id)]
        (when-not (and (= root owner) (= path (into declaring-path (:scope-path row)))
                       (= original [[origin-fn-id (:scope-path row)] field])
                       (= binding-id (:id row)) (= origin-fn-id (:fn-id row)) (= field (:target-path row))
                       (= "ref" (:expression-kind row)) (= (:fn-id expression) (:ref-fn-id row)))
          (reject! :unproved-captured-value-owner {:address address}))
        lexical-value-owner))))


(defn- lower-binding
  [analysis address {:keys [expression reference binding-id] :as binding}]
  (case (:mode reference)
    :literal {:op :literal :value (:value expression)}
    :identity {:op :literal :value (:fn-id expression)}
    :projection (cond-> {:op :read :source (:source reference)}
                  (:nil-when-absent? reference) (assoc :nil-when-absent? true))
    (:value :callable-producer :graph-callable)
    (let [dependency (get (::dependencies analysis) address)]
      (when-not (= 1 (count dependency))
        (reject! :dependency-mismatch {:address address :binding-id binding-id}))
      (let [{:keys [to target-fn-id]} (first dependency)
            owner (when (= :value (:mode reference)) (captured-value-owner analysis address binding))]
        (when-not (and (= to [(ffirst address) (conj (second (first address)) binding-id)])
                       (= target-fn-id (:fn-id expression)
                          (get-in analysis [:scopes (second to) :fn-id])))
          (reject! :dependency-target-mismatch {:address address}))
        (cond-> {:op (if (= :graph-callable (:mode reference)) :closure :invoke)
                 :scope to :target-id target-fn-id :binding-id binding-id}
          owner (assoc :lexical-value-owner owner))))
    (reject! :reference-mode-required {:address address :mode (:mode reference)})))


(defn- lower-tree
  [analysis routes scope node path]
  (let [address [scope path]
        entry (get-in analysis [:scopes (second scope) :bindings address])
        route (get routes address)
        expression (cond
                     entry (lower-binding analysis address entry)
                     route {:op :read :source (:source route)}
                     (:fields node) {:op :record :fields
                                     (mapv (fn [field]
                                             [(:name field) [scope (conj path (:id field))]])
                                           (:fields node))
                                     :required-fields (into []
                                                            (comp (filter :required)
                                                                  (map #(vector scope (conj path (:id %)))))
                                                            (:fields node))}
                     :else (assoc (select-keys node [:default]) :op :supplied))]
    (into {address (assoc expression :type (:type node))}
          (when (= :record (:op expression))
            (mapcat (fn [field]
                      (lower-tree analysis routes scope field (conj path (:id field))))
                    (:fields node))))))


(defn- implementation!
  [expressions scope {:keys [base-fn-id impl argument-paths root-entry] :as implementation}]
  (when-not (and (uuid? base-fn-id) (fn? impl) (map? argument-paths)
                 (every? keyword? (keys argument-paths))
                 (map? root-entry) (map? (:args root-entry)) (contains? root-entry :return))
    (reject! :resolved-implementation-required {:scope scope}))
  (when-not (= (set (keys argument-paths)) (set (keys (:args root-entry))))
    (reject! :implementation-abi-mismatch {:scope scope}))
  (when (:compile-time-value? root-entry)
    (reject! :compile-time-value-compilation-required {:scope scope}))
  (doseq [[_ path] argument-paths]
    (when-not (and (vector? path) (contains? expressions [scope path]))
      (reject! :implementation-path-missing {:scope scope :path path})))
  (select-keys implementation [:base-fn-id :impl :argument-paths :root-entry :optional-native-readers
                               :generated-list :compile-delayed-list]))


(defn- native-list-delivery
  [analysis expressions scopes]
  (let [consumers (keep (fn [[scope native]] (when (seq (get-in native [:root-entry :lazy-seq-args])) scope)) scopes)]
    (if (seq consumers)
      (let [reads (into #{} (keep :source) (vals expressions))]
        (reduce (fn [selected scope]
                  (if-let [child (lazy-list/delayed-producer analysis expressions scopes scope reads)]
                    (let [{:keys [compile-delayed-list generated-list]} (get scopes child)]
                      (update selected child assoc :impl (compile-delayed-list (:operands generated-list)) :delayed-list? true))
                    (reject! :lazy-sequence-abi-required {:scope scope})))
                scopes consumers))
      scopes)))


(defn- references!
  [expressions]
  (doseq [[address {:keys [op source]}] expressions]
    (when (and (= :read op) (not (contains? expressions source)))
      ;; Reading a descendant of an opaque whole-record producer needs an
      ;; explicit projection operation with presence semantics, not flattening.
      (reject! :projection-source-required {:address address :source source})))
  (let [done (volatile! #{})]
    (letfn [(visit
              [address pending]
              (when (contains? pending address)
                (reject! :expression-cycle {:address address}))
              (when-not (contains? @done address)
                (let [{:keys [op source scope fields]} (get expressions address)
                      children (case op :read [source] :invoke [[scope []]]
                                     :record (mapv second fields) [])]
                  (doseq [child children] (visit child (conj pending address)))
                  (vswap! done conj address))))]
      (doseq [address (keys expressions)] (visit address #{})))))


(defn- presence-dependencies
  [expression]
  (case (:op expression)
    :read [(:source expression)]
    :record (mapv second (:fields expression))
    []))


(defn presence-program
  "Return field presence rules in dependency order, excluding value reads.
   Both execution targets distinguish an absent field from a present nil."
  [{:keys [expressions root-scope record-inputs]}]
  (let [seen (volatile! #{})
        rules (volatile! [])]
    (letfn [(visit
              [address]
              (when-not (contains? @seen address)
                (vswap! seen conj address)
                (let [expression (get expressions address)
                      sources (presence-dependencies expression)
                      default-members (when (and (= :record (:op expression)) (seq (second address)))
                                        (into #{} (filter #(contains? (get expressions %) :default)) sources))
                      kind (case (:op expression)
                             :supplied :supplied
                             :read (if (:nil-when-absent? expression) :always :any-present)
                             :record (cond
                                       (= address [root-scope []]) :always
                                       (contains? record-inputs address) :supplied-record
                                       :else :any-present)
                             :always)]
                  (doseq [source sources] (visit source))
                  (vswap! rules conj (cond-> {:address address :kind kind :sources sources}
                                       (seq default-members) (assoc :sources (into [] (remove default-members) sources)
                                                                    :default-members default-members)
                                       (contains? expression :default) (assoc :default-present? true)
                                       (= :supplied-record kind)
                                       (assoc :required-fields (:required-fields expression)))))))]
      (doseq [address (sort-by pr-str (keys expressions))] (visit address))
      @rules)))


(defn- any-source-present?
  [presence supplied sources default-members]
  ;; A fallback is available when its parent is active. Only an explicit
  ;; argument at that address can activate the parent through this member.
  (boolean (or (some presence sources)
               (and default-members (some #(contains? supplied %) default-members)))))


(defn present-addresses
  "Return address presence without forcing any field or reference."
  ([program supplied] (present-addresses program supplied {}))
  ([program supplied inherited]
   (reduce (fn [presence {:keys [address kind sources default-present? default-members]}]
             (assoc presence address
                    (case kind
                      :always true
                      :supplied (or (true? default-present?) (contains? supplied address))
                      :supplied-record (or (contains? supplied address) (any-source-present? presence supplied sources default-members))
                      :any-present (any-source-present? presence supplied sources default-members))))
           inherited
           program)))


(defn- parameter-node!
  [scope expressions field path]
  (let [address [scope path]
        structured? (contains? field :fields)
        expected-op (if structured? :record :supplied)]
    (when-not (= expected-op (get-in expressions [address :op]))
      (reject! :callback-parameter-shape-required {:scope scope}))
    (cond-> {:kind :input-field :name (:name field)
             :address address :type (:type field) :required? (:required field)}
      structured? (assoc :fields (mapv #(parameter-node! scope expressions % (conj path (:id %)))
                                       (:fields field))))))


(defn- parameter-routes
  [{:keys [address fields]} path]
  (if (seq fields)
    (mapcat #(parameter-routes % (conj path (:name %))) fields)
    [{:target address :value-path path}]))


(defn- nested-parameter-nodes!
  [analysis [root scope-path :as scope] expressions param call-sites single-parameter?]
  (let [sites (filterv #(= param (:parameter %)) call-sites)
        readers (distinct (map (fn [{[target-scope path] :target}]
                                 [target-scope [(first path)]]) sites))
        end (count scope-path)
        nodes (mapv (fn [[[target-root target-path :as target-scope] path]]
                      (let [contract (get-in analysis [:scopes target-path :configured-contract])
                            field (when contract (descriptor/find-node contract path))]
                        (when-not (and (= root target-root)
                                       (vector? target-path) (> (count target-path) end)
                                       (= scope-path (subvec target-path 0 end))
                                       field (= param (:name field)))
                          (reject! :callback-parameter-shape-required {:scope scope}))
                        (parameter-node! target-scope expressions field path)))
                    readers)]
    (when-not (or (= 1 (count nodes))
                  (and (seq nodes) single-parameter? (apply = (map #(contains? % :fields) nodes))))
      (reject! :callback-parameter-shape-required {:scope scope}))
    nodes))


(defn- callback-parameters!
  [analysis contract scope expressions parameters call-sites callable-type]
  (let [by-name (into {} (map (juxt :name identity)) (:fields contract))
        nodes (into [] (mapcat (fn [name]
                                 (if-let [field (get by-name name)]
                                   [(parameter-node! scope expressions field [(:id field)])]
                                   (nested-parameter-nodes! analysis scope expressions name call-sites
                                                            (= 1 (count parameters))))))
                    parameters)
        routes (mapcat (fn [node]
                         (map #(assoc % :parameter (:name node))
                              (parameter-routes node [])))
                       nodes)
        tree (mapcat #(tree-seq :fields :fields %) nodes)
        record-addresses (into #{} (comp (filter :fields) (map :address)) tree)]
    (when-not (= (frequencies call-sites) (frequencies routes))
      (reject! :callback-parameter-shape-required {:scope scope}))
    (when-not (and (= (set (keys (types/fn-args callable-type))) (set (map :name nodes)))
                   (every? (fn [[name readers]]
                             (let [type (get (types/fn-args callable-type) name)
                                   reader-types (map :type readers)]
                               (or (every? #{type} reader-types)
                                   (and (next readers) (not-any? :fields readers)
                                        (= type (variables/shared-value-domain (cons type reader-types)))))))
                           (group-by :name nodes)))
      (reject! :callback-parameter-type-mismatch {:scope scope}))
    {:call-sites (into [] (comp (remove :fields) (map #(select-keys % [:name :address :required?]))) tree)
     :required-parameters (into #{} (comp (filter :required?) (map :address)) nodes)
     :record-addresses record-addresses
     :parameter-surface (when (seq record-addresses) {:root {:fields nodes}})}))


(defn- external-reader-scope
  "Follow only value references below the callback's public input group."
  [routes expressions group-path leaf-path scope]
  (reduce (fn [[root path :as parent] depth]
            (let [{:keys [kind result-address input-scope reference-mode reference-binding-id target-fn-id]}
                  (get routes (subvec leaf-path 0 depth))
                  edge (get expressions result-address)]
              (if (and (= :reference-inputs kind) (= :value reference-mode)
                       (= parent (first result-address)) (= :invoke (:op edge))
                       (= target-fn-id (:target-id edge)) (= reference-binding-id (:binding-id edge))
                       (= [root (conj path reference-binding-id)] input-scope (:scope edge)))
                input-scope
                (reduced nil))))
          scope (range (inc (count group-path)) (count leaf-path))))


(defn- external-addresses!
  [analysis by-address address scope expressions parameters captures]
  (let [targets (into #{} (map :target) captures)
        routes (get-in analysis [:public-input :routes])
        [[group-path group] & other-groups] (get by-address address)]
    (when-not (= (count captures) (count targets))
      (reject! :unsupported-closure-scope {:scope scope}))
    (doseq [{:keys [target value-path]} captures]
      (let [[target-scope path] target
            field (descriptor/find-node (get-in analysis [:scopes (second target-scope) :configured-contract]) path)
            [[leaf-path route] & siblings] (get by-address target)
            depth (count group-path)]
        (when-not (and (= :reference-inputs (:kind group)) (= :graph-callable (:reference-mode group))
                       (= scope (:input-scope group)) (empty? other-groups)
                       (= (:reference-binding-id group) (get-in expressions [address :binding-id]))
                       (= (:target-fn-id group) (get-in expressions [address :target-id]))
                       (> (count leaf-path) depth) (= group-path (subvec leaf-path 0 depth))
                       (= value-path (subvec leaf-path (inc depth))) (not (parameters (nth leaf-path depth)))
                       (= target-scope (external-reader-scope routes expressions group-path leaf-path scope))
                       (= 1 (count path)) field (not (:fields field)) (= (:id field) (:origin-field-id route))
                       (= (:name field) (:name route) (peek leaf-path)) (empty? siblings)
                       (= :supplied (get-in expressions [target :op]))
                       (= :input-field (:kind route)) (:whole-bindable? route)
                       (= (:type field) (:completion-type route)
                          (or (get-in expressions [target :declared-collection-type]) (get-in expressions [target :type]))))
          (reject! :unsupported-closure-scope {:scope scope}))))
    targets))


(defn- root-projection-scopes
  [analysis expressions]
  (let [root (:root-scope analysis)]
    (into #{}
          (mapcat (fn [[[[owner path] :as address] {:keys [op source]}]]
                    (when (and (seq path) (= :read op) (= root (first source)) (contains? expressions source)
                               (= {:kind :projection :scope root :path (second source)}
                                  (get-in analysis [:scopes path :bindings address :expression])))
                      ;; Escaping an enclosing callback would also lose the
                      ;; root cells needed by its nested callbacks.
                      (map (fn [depth] [owner (subvec path 0 depth)]) (range 1 (inc (count path)))))))
          expressions)))


(defn- checked-closure-subtype?
  [actual expected]
  (and (types/fn-type? actual) (types/fn-type? expected)
       ;; Equal scoped argument and result expressions stay equal under every
       ;; substitution. The ordinary subtype check still bounds effects and targets.
       (or (and (= (types/fn-args actual) (types/fn-args expected))
                (or (= (types/fn-ret actual) (types/fn-ret expected)) (= :any (types/fn-ret expected))))
           (not-any? #(types/type-any? types/type-var? %) [actual expected]))
       (types/subtype? actual expected)))


(defn- enclosing-value-callbacks
  [expressions]
  (into #{}
        (mapcat (fn [[[[root path]] {:keys [lexical-value-owner]}]]
                  (when lexical-value-owner
                    (map #(vector root (subvec path 0 %))
                         (range (inc (count (second lexical-value-owner))) (inc (count path)))))))
        expressions))


(defn- closure-plans
  [analysis expressions routes detached-closures?]
  (let [plans (:closures analysis)
        addresses (into #{} (keep (fn [[address expr]] (when (= :closure (:op expr)) address))) expressions)
        root-dependent (when (and detached-closures? (seq plans))
                         (into (root-projection-scopes analysis expressions) (enclosing-value-callbacks expressions)))
        route-keys [:from :source :role :via :required?]
        by-address (group-by (fn [[_ route]] (or (:address route) (:result-address route)))
                             (get-in analysis [:public-input :routes]))]
    (when-not (= addresses (set (keys plans)))
      (reject! :closure-plan-mismatch {}))
    (into {}
          (map (fn [[address {:keys [parameters call-sites captures external-captures callable-type]}]]
                 (let [{:keys [scope binding-id type]} (get expressions address)
                       owner (first address)
                       contract (get-in analysis [:scopes (second scope) :configured-contract])
                       reference (get-in analysis [:scopes (second owner) :bindings address :reference])
                       detachable? (and detached-closures? (empty? captures) (empty? external-captures)
                                        (not (contains? root-dependent scope)))]
                   (when-not (and (:capture-resolved? reference)
                                  (or (nil? external-captures) (vector? external-captures)) (vector? parameters)
                                  (every? keyword? parameters) (= (count parameters) (count (set parameters))))
                     (reject! :unsupported-closure-scope {:address address}))
                   (when-not (and (= callable-type (:callable-type reference))
                                  (checked-closure-subtype? callable-type type))
                     (reject! :unproved-closure-contract {:address address}))
                   (doseq [{:keys [from source role via] :as capture} captures]
                     (when-not (and (= :capture role) (= binding-id via) (= owner (first source))
                                    (= (select-keys capture route-keys)
                                       (select-keys (get routes from) route-keys)))
                       (reject! :closure-capture-route-mismatch {:address address})))
                   (let [plan (callback-parameters! analysis contract scope expressions parameters call-sites callable-type)
                         external (external-addresses! analysis by-address address scope expressions (set parameters) external-captures)]
                     [address (cond-> (assoc plan :scope scope :type type :parameters parameters)
                                (seq captures) (assoc :capture-sources (into {} (map (juxt :from :source)) captures))
                                (seq external) (assoc :external-addresses external)
                                detachable? (assoc :detachable? true))]))))
          plans)))


(defn- frame-layouts
  [root scopes expressions closures program required]
  (let [callbacks (into {} (map (juxt :scope identity)) (vals closures))
        external (into #{} (mapcat :external-addresses) (vals closures))
        initializers (into {} (keep (fn [[address {:keys [scope lexical-value-owner]}]]
                                      (when lexical-value-owner [scope [address lexical-value-owner]]))) expressions)
        ;; A captured initializer and its ordinary dependencies live with the
        ;; declaring invocation. Each callback inside it still owns a fresh frame.
        owner (reduce (fn [result [id path :as scope]]
                        (let [[_ declared-at] (get initializers scope)
                              parent (when (seq path) [id (pop path)])]
                          (assoc result scope
                                 (cond
                                   (contains? callbacks scope) scope
                                   declared-at (get result declared-at)
                                   :else (get result parent root)))))
                      {} (sort-by (comp count second) (keys scopes)))
        lexical-addresses (into {} (map (fn [[_ [address declared-at]]] [address (owner declared-at)])) initializers)
        address-owner (fn [address]
                        (cond
                          (contains? external address) root
                          (contains? lexical-addresses address) (get lexical-addresses address)
                          :else (owner (first address))))
        by-frame (group-by (comp address-owner :address) program)
        required (group-by address-owner required)]
    (doseq [[creator {:keys [scope capture-sources]}] closures
            [destination source] capture-sources]
      (when-not (and (= scope (owner (first destination))) (= (owner (first creator)) (address-owner source)))
        (reject! :closure-capture-route-mismatch {:address creator})))
    (doseq [[address {:keys [op source scope]}] expressions]
      (let [local (address-owner address)]
        (when (case op
                :read (and (not= local (if (external source) (owner (first source)) (address-owner source)))
                           (or (:detachable? (get callbacks local))
                               (and (not= root (first source))
                                    (not (and (contains? lexical-addresses source) (= (first address) (first source))))
                                    (not= source (get-in callbacks [local :capture-sources address])))))
                :invoke (not= local (owner scope))
                false)
          (reject! :cross-frame-expression {:address address}))))
    (into {} (map (fn [[frame scopes]]
                    (let [rules (get by-frame frame)
                          inputs-by-kind (group-by :kind rules)
                          parameters (into #{} (map :address) (:supplied inputs-by-kind))
                          records (into #{} (map :address) (:supplied-record inputs-by-kind))
                          callback (get callbacks frame)]
                      (when (and callback (or (not= parameters (into #{} (map :address) (:call-sites callback)))
                                              (not= records (:record-addresses callback))))
                        (reject! :unrouted-callback-input {:scope frame}))
                      [frame {:scopes scopes :presence-program rules :parameters parameters
                              :record-inputs records
                              :required-addresses (into (set (get required frame))
                                                        (:required-parameters callback))}])))
          (group-by owner (keys scopes)))))


(defn- optional-native-source
  [expressions scopes readers originals leaf]
  (loop [[scope path :as address] leaf]
    (let [{:keys [op source]} (get expressions address)
          uses (vals (get-in scopes [scope :argument-paths]))]
      (cond
        (or (not= 1 (count path)) (some empty? uses) (seq (get readers [scope []]))
            (if (= leaf address) (not= 1 (count (filter #{path} uses))) (some #{path} uses))) nil
        (= :supplied op) (when-not (contains? (get expressions address) :default) address)
        (#{:literal :invoke} op) ::bound
        (= :read op)
        (when (and (= [address] (get readers source)) (= 1 (count (second source)))
                   (= (first path) (get originals (first (second source)))))
          (recur source))))))


(defn- optional-native-inputs
  "A V1 optional primitive receives a nil argument when its required public
   rename is omitted. Only follow a unique chain of physical source FKs to a
   supplied field. A separate use of that field still owns its validation."
  [expressions scopes originals]
  (let [leaves (for [[scope {:keys [optional-native-readers argument-paths]}] scopes
                     local optional-native-readers :when (some #{[local]} (vals argument-paths))]
                 [scope [local]])]
    (if (empty? leaves)
      expressions
      (let [readers (reduce-kv (fn [index address {:keys [op source]}]
                                 (if (= :read op) (update index source (fnil conj []) address) index)) {} expressions)]
        (reduce (fn [result leaf]
                  (let [address (when-not (seq (get readers leaf))
                                  (optional-native-source expressions scopes readers originals leaf))]
                    (cond
                      (= ::bound address) result
                      address (update result address assoc :default nil :optional-native-read? true)
                      :else (reject! :unproved-optional-native-reader {:address leaf}))))
                expressions leaves)))))


(defn- public-record-inputs
  [analysis expressions]
  (let [root (get-in analysis [:public-input :root])
        records (into #{} (keep (fn [{:keys [kind fields address]}]
                                  (when (and (= :input-field kind) (some? fields))
                                    address)))
                      (tree-seq #(contains? % :fields) :fields root))]
    (when-not (every? #(= :record (get-in expressions [% :op])) records)
      (reject! :public-record-not-lowered {}))
    records))


(defn lower-checked
  "Lower supported checked scopes. :implementations maps target UUIDs to an
   explicitly resolved {:base-fn-id :impl :root-entry frozen-rich-signature
   :argument-paths {ABI-name UUID-path}}. Special base ABIs fail closed.
   Context checking does not discover a composed function's base output.
   Public and callback-local leaves have separate frames. Unsupported
   closures and obligations fail closed."
  [analysis {:keys [snapshot-id implementations target-names detached-closures? original-projections scoped-references]}]
  (checked-plan! analysis snapshot-id)
  (let [analysis (assoc analysis ::dependencies (group-by :from (:dependencies analysis)))
        analysis (assoc analysis ::scoped-references scoped-references
                        ::callback-scopes (into #{} (comp (filter #(= :graph-callable (:mode %))) (map :to))
                                                (:dependencies analysis)))
        root (:root-scope analysis)
        routes (group-by :from (:input-routes analysis))
        _ (doseq [[address entries] routes]
            (when-not (= 1 (count entries)) (reject! :duplicate-input-route {:address address})))
        routes (update-vals routes first)
        expressions (into {} (map (fn [[address {:keys [type op] :as expression}]]
                                    [address (cond-> expression
                                               (hiccup-tail? analysis address type op)
                                               (assoc :type [:list hiccup-elements]))]))
                          (mapcat (fn [[use-path node]]
                                    (lower-tree analysis routes [(first root) use-path] (:configured-contract node) []))
                                  (:scopes analysis)))
        scopes (into {} (map (fn [[use-path node]]
                               (let [scope [(first root) use-path]]
                                 [scope (assoc (implementation! expressions scope
                                                                (get implementations (:fn-id node)))
                                               :target-id (:fn-id node)
                                               :name (get target-names (:fn-id node)))]))) (:scopes analysis))]
    (when-not (and (= root [(:fn-id analysis) []]) (contains? scopes root))
      (reject! :root-scope-mismatch {}))
    (doseq [[address {:keys [source]}] routes]
      (when-not (= {:op :read :source source}
                   (select-keys (get expressions address) [:op :source]))
        (reject! :input-route-not-lowered {:address address})))
    (references! expressions)
    (let [scopes (native-list-delivery analysis expressions scopes)
          expressions (lookup-abi/single-collection-inputs analysis expressions scopes)
          expressions (optional-native-inputs expressions scopes original-projections)
          closures (closure-plans analysis expressions routes detached-closures?)
          expressions (lookup-abi/single-list-inputs analysis expressions closures)
          record-inputs (into (public-record-inputs analysis expressions)
                              (mapcat :record-addresses) (vals closures))
          program (presence-program {:expressions expressions :root-scope root :record-inputs record-inputs})
          layouts (frame-layouts root scopes expressions closures program (:required-addresses analysis))]
      (merge (read-obligations analysis expressions scopes closures)
             {:version 1 :root-scope root :snapshot-id snapshot-id :expressions expressions :scopes scopes
              :closures closures :frame-layouts layouts :presence-program program
              :required-addresses (get-in layouts [root :required-addresses])
              :parameters (get-in layouts [root :parameters])}))))
