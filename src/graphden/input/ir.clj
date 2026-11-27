(ns graphden.input.ir
  "Pure lowering of a checked input plan for the fixture compiler. This is
   an internal execution IR, not authoring syntax or a production entry point."
  (:require
    [graphden.input.descriptor :as descriptor]
    [graphden.input.literal-lookup :as literal-lookup]
    [graphden.types.core :as types]))


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


(defn- field-read-type
  [analysis [[_ scope] :as address] expected op]
  (cond
    (or (contains? #{:any :null :bool :text :int :float :decimal :numeric
                     :keyword :uuid :timestamptz :bytes :jsonb [:list :any]} expected)
        (and (= [:map :keyword :any] expected) (#{:invoke :literal :read} op))) expected
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
              (literal-lookup/keyword-map-read-type analysis address expected actual-type)))))))


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
                  (when (= expected-type (:type closure)) expected-type)
                  (field-read-type analysis address expected-type op)))]
    (when (and (= :value-type kind) (= :field-read trigger) (types/fn-type? expected-type) (= :supplied op))
      (reject! :supplied-callable-validation-required details))
    (when-not check (reject! :runtime-obligations-required details))
    (when-not closure [address check])))


(defn- value-obligations
  [analysis expressions closures]
  (let [obligations (distinct (concat (:obligations analysis)
                                      (mapcat :obligations (vals (:scopes analysis)))))
        validators (into {} (keep (partial read-validator analysis expressions closures)) obligations)
        by-address (group-by :address obligations)]
    (doseq [[address entries] by-address]
      (when-not (= 1 (count entries))
        (reject! :conflicting-runtime-obligations {:address address})))
    ;; The closure is built by the compiler with the already-proved
    ;; invocation contract. Validating an arbitrary host function cannot
    ;; establish a structural callable type.
    validators))


(defn- lower-binding
  [analysis address {:keys [expression reference binding-id]}]
  (case (:mode reference)
    :literal {:op :literal :value (:value expression)}
    :identity {:op :literal :value (:fn-id expression)}
    :projection (cond-> {:op :read :source (:source reference)}
                  (:nil-when-absent? reference) (assoc :nil-when-absent? true))
    (:value :callable-producer :graph-callable)
    (let [dependency (get (::dependencies analysis) address)]
      (when-not (= 1 (count dependency))
        (reject! :dependency-mismatch {:address address :binding-id binding-id}))
      (let [{:keys [to target-fn-id]} (first dependency)]
        (when-not (and (= to [(ffirst address) (conj (second (first address)) binding-id)])
                       (= target-fn-id (:fn-id expression)
                          (get-in analysis [:scopes (second to) :fn-id])))
          (reject! :dependency-target-mismatch {:address address}))
        {:op (if (= :graph-callable (:mode reference)) :closure :invoke)
         :scope to :target-id target-fn-id :binding-id binding-id}))
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
  (when (seq (:lazy-seq-args root-entry))
    (reject! :lazy-sequence-abi-required {:scope scope}))
  (doseq [[_ path] argument-paths]
    (when-not (and (vector? path) (contains? expressions [scope path]))
      (reject! :implementation-path-missing {:scope scope :path path})))
  (select-keys implementation [:base-fn-id :impl :argument-paths :root-entry]))


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
                   (every? #(= (:type %) (get (types/fn-args callable-type) (:name %))) nodes))
      (reject! :callback-parameter-type-mismatch {:scope scope}))
    {:call-sites (into [] (comp (remove :fields) (map #(select-keys % [:name :address :required?]))) tree)
     :required-parameters (into #{} (comp (filter :required?) (map :address)) nodes)
     :record-addresses record-addresses
     :parameter-surface (when (seq record-addresses) {:root {:fields nodes}})}))


(defn- external-addresses!
  [public-routes contract scope expressions parameters captures]
  (let [addresses (into #{} (map :target) captures)]
    (when-not (= (count captures) (count addresses))
      (reject! :unsupported-closure-scope {:scope scope}))
    (doseq [{:keys [target value-path]} captures]
      (let [[target-scope path] target
            field (when (= target-scope scope) (descriptor/find-node contract path))
            [route & more] (get public-routes target)]
        (when-not (and (= [] value-path) (= 1 (count path))
                       field (not (:fields field)) (not (parameters (:name field)))
                       (= :supplied (get-in expressions [target :op]))
                       (= :input-field (:kind route)) (:whole-bindable? route) (empty? more)
                       (= (:type field) (:completion-type route) (get-in expressions [target :type])))
          (reject! :unsupported-closure-scope {:scope scope}))))
    addresses))


(defn- closure-plans
  [analysis expressions routes detached-closures?]
  (let [plans (:closures analysis)
        addresses (into #{} (keep (fn [[address expr]] (when (= :closure (:op expr)) address))) expressions)
        route-keys [:from :source :role :via :required?]
        public-routes (group-by :address (vals (get-in analysis [:public-input :routes])))]
    (when-not (= addresses (set (keys plans)))
      (reject! :closure-plan-mismatch {}))
    (into {}
          (map (fn [[address {:keys [parameters call-sites captures external-captures callable-type]}]]
                 (let [{:keys [scope binding-id type]} (get expressions address)
                       owner (first address)
                       contract (get-in analysis [:scopes (second scope) :configured-contract])
                       reference (get-in analysis [:scopes (second owner) :bindings address :reference])
                       detachable? (and detached-closures? (empty? captures) (empty? external-captures))]
                   (when-not (and (or (= (:root-scope analysis) owner) detachable?)
                                  (:capture-resolved? reference)
                                  (or (nil? external-captures) (vector? external-captures)) (vector? parameters)
                                  (every? keyword? parameters) (= (count parameters) (count (set parameters))))
                     (reject! :unsupported-closure-scope {:address address}))
                   (when-not (and (= callable-type (:callable-type reference))
                                  (types/fn-type? type) (types/fn-type? callable-type)
                                  (not-any? #(types/type-any? types/type-var? %) [type callable-type])
                                  (types/subtype? callable-type type))
                     (reject! :unproved-closure-contract {:address address}))
                   (doseq [{:keys [from source role via] :as capture} captures]
                     (when-not (and (= :capture role) (= binding-id via) (= owner (first source))
                                    (= (select-keys capture route-keys)
                                       (select-keys (get routes from) route-keys)))
                       (reject! :closure-capture-route-mismatch {:address address})))
                   (let [plan (callback-parameters! analysis contract scope expressions parameters call-sites callable-type)
                         external (external-addresses! public-routes contract scope expressions
                                                       (set parameters) external-captures)]
                     [address (cond-> (assoc plan :scope scope :type type :parameters parameters)
                                (seq external) (assoc :external-addresses external)
                                detachable? (assoc :detachable? true))]))))
          plans)))


(defn- frame-layouts
  [root scopes expressions closures program required]
  (let [callbacks (into {} (map (juxt :scope identity)) (vals closures))
        external (into #{} (mapcat :external-addresses) (vals closures))
        owner (fn [[fn-id path]]
                (or (some (fn [length]
                            (let [branch [fn-id (subvec path 0 length)]]
                              (when (contains? callbacks branch) branch)))
                          (range (count path) 0 -1))
                    root))
        ;; A callback's exposed capture is supplied by the surrounding
        ;; invocation. Keep its verified address but allocate its cell in the
        ;; root frame, so callback arguments cannot replace it.
        address-owner #(if (contains? external %) root (owner (first %)))
        by-frame (group-by (comp address-owner :address) program)
        required (group-by address-owner required)]
    (doseq [[address {:keys [op source scope]}] expressions]
      (let [local (owner (first address))]
        (when (case op
                :read (and (not= local (owner (first source)))
                           (or (:detachable? (get callbacks local))
                               (not= root (first source))))
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
  [analysis {:keys [snapshot-id implementations target-names detached-closures?]}]
  (checked-plan! analysis snapshot-id)
  (let [analysis (assoc analysis ::dependencies (group-by :from (:dependencies analysis)))
        root (:root-scope analysis)
        routes (group-by :from (:input-routes analysis))
        _ (doseq [[address entries] routes]
            (when-not (= 1 (count entries)) (reject! :duplicate-input-route {:address address})))
        routes (update-vals routes first)
        expressions (into {} (mapcat (fn [[use-path node]]
                                       (lower-tree analysis routes [(first root) use-path]
                                                   (:configured-contract node) []))) (:scopes analysis))
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
    (let [closures (closure-plans analysis expressions routes detached-closures?)
          record-inputs (into (public-record-inputs analysis expressions)
                              (mapcat :record-addresses) (vals closures))
          program (presence-program {:expressions expressions :root-scope root :record-inputs record-inputs})
          layouts (frame-layouts root scopes expressions closures program (:required-addresses analysis))]
      {:version 1 :root-scope root :snapshot-id snapshot-id :expressions expressions :scopes scopes
       :closures closures :frame-layouts layouts :presence-program program
       :required-addresses (get-in layouts [root :required-addresses])
       :value-obligations (value-obligations analysis expressions closures)
       :parameters (get-in layouts [root :parameters])})))
