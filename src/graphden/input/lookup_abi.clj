(ns graphden.input.lookup-abi
  "Native lookup arguments whose private map-key variable does not occur elsewhere."
  (:require
    [graphden.input.descriptor :as descriptor]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(def ^:private lookups
  (let [signature (fn [name argument other-args collection-arms]
                    (let [id (ids/fn-id "core.collections" name)]
                      [id {:name name :argument argument :path [(ids/slot-id id argument)]
                           :other-args other-args :collection-arms collection-arms}]))]
    (into {} [(signature :get :coll {:key :any :default :any} #{:null [:list :any]})
              (signature :get-in :map {:path [:list [:union :int :keyword :text]] :default :any} #{:null})])))


(defn collection-argument
  "Match the frozen UUID and complete native ABI, including binder identity."
  [{module-ns :namespace :keys [fn-id name args return]} variable]
  (when-let [lookup (get lookups fn-id)]
    (let [{:keys [argument collection-arms other-args]} lookup]
      (when (and (= "core.collections" module-ns) (= name (:name lookup)) (= return :any)
                 (= other-args (dissoc args argument))
                 (= (conj collection-arms [:map variable :any]) (set (types/union-members (get args argument)))))
        (select-keys lookup [:argument :path])))))


(def ^:private const-id (ids/fn-id "core.logic" :const))
(def ^:private value-path [(ids/slot-id const-id :value)])


(defn identity-record-source
  "Match the native identity argument and its same-scope, declared record projection."
  [node [scope path] expected-type]
  (let [{:keys [base-fn-id root-entry argument-paths]} (:signature-source node)
        arg (get-in node [:bindings [scope path]])
        [from field :as source] (get-in arg [:reference :source])]
    (when (and (types/record-type? expected-type) (= const-id base-fn-id (:fn-id root-entry))
               (= "core.logic" (:namespace root-entry)) (= :const (:name root-entry))
               (= {:value 'a} (:args root-entry)) (= 'a (:return root-entry)) (empty? (:effects root-entry))
               (= {:value value-path} argument-paths) (= value-path path)
               (= scope from) (= 1 (count field))
               (= :projection (get-in arg [:expression :kind]) (get-in arg [:reference :mode]))
               (= source ((juxt :scope :path) (:expression arg)))
               (= expected-type (get-in node [:signature :return]) (:expected-type arg)
                  (get-in arg [:reference :actual-type]) (:type (descriptor/find-node (:configured-contract node) field))))
      source)))


(defn referenced-record-source
  "Follow a checked value reference through the native identity to its record projection."
  [graph [[root use-path] _] {:keys [binding-id expected-type expression reference]}]
  (when (and (= :ref (:kind expression)) (= :value (:mode reference)) (uuid? binding-id)
             (= expected-type (:actual-type reference)))
    (let [path (conj use-path binding-id)
          node (get-in graph [:scopes path])]
      (when (= (:fn-id expression) (:fn-id node))
        (identity-record-source node [[root path] value-path] expected-type)))))


(defn- independent-key-domain
  [node variable expected field native-field]
  (when (and (not (types/type-any? #{variable} (get-in node [:signature :return])))
             (not-any? #(and (not= field (:id %)) (not= native-field (:id %))
                             (types/type-any? #{variable} (:type %)))
                       (get-in node [:configured-contract :fields])))
    (types/resolve {variable :any} expected)))


(defn- projected-collection-domain
  [analysis scopes [scope path :as source] type [reader reader-path :as address]]
  (let [{:keys [root-entry base-fn-id argument-paths]} (get scopes scope)
        node (get-in analysis [:scopes (second scope)])
        {argument :argument native-path :path} (collection-argument root-entry 'a)
        binding (get-in node [:bindings address])
        binder (variables/type-in-scope scope 'a)
        declared (get-in root-entry [:args argument])]
    (when (and argument (= reader scope) (= native-path reader-path (get argument-paths argument))
               (= base-fn-id (:fn-id root-entry))
               (= source (get-in binding [:reference :source]))
               (= :projection (get-in binding [:reference :mode]) (get-in binding [:expression :kind]))
               (= type (:expected-type binding) (get-in binding [:reference :actual-type])
                  (variables/type-in-scope scope declared)))
      (independent-key-domain node binder type (first path) (first native-path)))))


(defn- dictionary-argument
  [{:keys [fn-id name args return] module-ns :namespace :as entry}]
  (or (collection-argument entry 'a)
      (when (and (= fn-id (ids/fn-id "core.collections" :count)) (= ["core.collections" :count] [module-ns name])
                 (= #{} (:effects entry)) (contains? #{:non-negative-int [:refine :int [:>= 0]]} return)
                 (= #{:coll} (set (keys args)))
                 (= '#{:null :text [:map a :any] [:list :any]} (set (types/union-members (:coll args)))))
        {:argument :coll :path [(ids/slot-id fn-id :coll)]})
      (when (and (= fn-id (ids/fn-id "core.collections" :update-vals))
                 (= [module-ns name] ["core.collections" :update-vals])
                 (= return :any)
                 (= args '{:m [:union :null [:map a :any]] :f [:fn {:value v} w :any :unknown]}))
        {:argument :m :path [(ids/slot-id fn-id :m)]})))


(defn collection-read-domain
  "Reify an independent native dictionary-key binder at its argument or its same-scope projected alias."
  [analysis [[_ scope :as owner] path :as address] expected op]
  (let [node (get-in analysis [:scopes scope])
        {:keys [root-entry base-fn-id argument-paths]} (:signature-source node)
        {arg :argument native-path :path} (dictionary-argument root-entry)
        binding (get-in node [:bindings [owner native-path]])
        variable (variables/type-in-scope owner 'a)]
    (when (and arg (or (= op :read) (and (= op :invoke) (#{:update-vals :count} (:name root-entry))))
               (= base-fn-id (:fn-id root-entry)) (= native-path (get argument-paths arg))
               (= expected (variables/type-in-scope owner (get-in root-entry [:args arg])))
               (or (= native-path path)
                   (and (= :projection (get-in binding [:reference :mode]) (get-in binding [:expression :kind]))
                        (= address (get-in binding [:reference :source]))
                        (= expected (:expected-type binding)))))
      (independent-key-domain node variable expected (first path) (first native-path)))))


(defn- single-reader-inputs
  [expressions reader-domain]
  (let [readers (group-by (comp :source val) (filter #(= :read (:op (val %))) expressions))]
    (reduce-kv (fn [result [scope path :as source] {:keys [op type]}]
                 (if-let [domain (when (and (= :supplied op) (= 1 (count path))
                                            (types/type-any? types/type-var? type) (empty? (get readers [scope []])))
                                   (let [[[address] & others] (get readers source)]
                                     (when (and address (empty? others))
                                       (reader-domain source type address))))]
                   (update result source assoc :type domain :declared-collection-type type)
                   result))
               expressions expressions)))


(defn single-collection-inputs
  "Reify the private map-key binder at the sole projection of a native lookup."
  [analysis expressions scopes]
  (single-reader-inputs expressions (partial projected-collection-domain analysis scopes)))


(def ^:private collection-returns
  {:first '[:union :null a] :reverse '[:list a] :sort-by '[:list a] :group-by '[:map b [:list a]] :filter '[:list a] :map '[:list b]})


(defn- verified-native?
  [node implementation]
  (let [keys [:base-fn-id :root-entry :argument-paths]]
    (= (select-keys implementation keys) (select-keys (:signature-source node) keys))))


(defn- collection-abi
  [analysis scopes scope]
  (let [node (get-in analysis [:scopes (second scope)])
        {:keys [base-fn-id root-entry argument-paths]} (:signature-source node)
        {:keys [name args return]} root-entry
        keyed? (#{:sort-by :group-by} name)
        module (if (or keyed? (#{:filter :map} name)) "core.hof" "core.collections")
        expected (cond-> '{:coll [:list a]}
                   (= :map name) (assoc :func '[:fn {:item a} b :any :unknown])
                   keyed? (assoc :key-fn '[:fn {:item a} b #{} :unknown])
                   (= :filter name) (assoc :pred '[:fn {:item a} :bool #{} :unknown]))]
    (when (and (verified-native? node (get scopes scope)) (contains? collection-returns name) (= module (:namespace root-entry))
               (= (ids/fn-id module name) base-fn-id (:fn-id root-entry))
               (= expected args) (= (collection-returns name) return) (= #{} (:effects root-entry))
               (= argument-paths (into {} (map (fn [k] [k [(ids/slot-id base-fn-id k)]])) (keys expected))))
      {:name name :paths argument-paths})))


(defn- fixed-keyword-rows?
  [analysis expressions scopes closures address]
  (let [edge (get expressions address)
        producer (:scope edge)
        {kind :name paths :paths} (collection-abi analysis scopes producer)
        callback (get closures [producer (:func paths)])
        node (get-in analysis [:scopes (second (:scope callback))])
        {:keys [root-entry base-fn-id argument-paths]} (:signature-source node)
        native (ids/fn-id "core.collections" :zipmap)
        key-facts (get-in node [:signature :resolved-bindings :keys :list-elements])
        row (get-in node [:signature :return])
        actual (get-in analysis [:scopes (second (first address)) :bindings address :reference :actual-type])]
    ;; The checked ordered list includes its entire tail. An open record type
    ;; alone would also admit non-keyword entries.
    (and (= :invoke (:op edge)) (= :map kind) (= [:item] (:parameters callback))
         (= [native "core.collections" :zipmap '{:keys [:list k] :vals [:list v]} '[:map k v] #{}]
            ((juxt :fn-id :namespace :name :args :return :effects) root-entry))
         (verified-native? node (get scopes (:scope callback))) (= native base-fn-id)
         (= {:keys [(ids/slot-id native :keys)] :vals [(ids/slot-id native :vals)]} argument-paths)
         (types/record-type? row) (vector? key-facts) (seq key-facts)
         (every? #(and (true? (:value-present %)) (= :keyword (:type %)) (keyword? (:value %))) key-facts)
         (= (set (keys row)) (set (map :value key-facts)))
         (= row (types/fn-ret (:type callback)))
         (= [:list row] actual (get-in analysis [:scopes (second producer) :signature :return])))))


(defn- callback-list-type
  [expressions callback item]
  (let [sites (:call-sites callback)
        readers (mapv #(get expressions (:address %)) sites)
        domain (:type (first readers))]
    (when (and (= [:item] (:parameters callback)) (= item (get (types/fn-args (:type callback)) :item))
               domain (some #(= item (:declared-collection-type %)) readers)
               (every? #(= :item (:name %)) sites)
               (every? #(and (= :supplied (:op %)) (:declared-collection-type %) (= domain (:type %))) readers))
      [:list domain])))


(defn lookup-item-list-type
  "Follow native operations that retain element values to a key or
   predicate callback. Reuse the runtime domain proved for its
   native lookup readers, retaining the list and per-element checks."
  [analysis expressions scopes closures [scope path] expected]
  (when (types/list-type? expected)
    (loop [scope scope
           path path]
      (let [{:keys [name paths]} (collection-abi analysis scopes scope)
            address [scope path]
            item (types/list-elem expected)
            edge (get expressions address)]
        (when (and name (= path (:coll paths))
                   (or (= :invoke (:op edge))
                       (and (= :filter name) (= :read (:op edge))
                            (= scope (first (:source edge))) (= :supplied (get-in expressions [(:source edge) :op]))))
                   (= expected (get-in analysis [:scopes (second scope) :bindings address :expected-type])))
          (if-let [callback-path (or (:key-fn paths) (:pred paths) (:func paths))]
            (let [callback (get closures [scope callback-path])]
              (or (callback-list-type expressions callback item)
                  (when (and (= :filter name) (= item [:map :keyword :any])
                             (= {:item item} (types/fn-args (:type callback)))
                             (= [:item] (:parameters callback))
                             (fixed-keyword-rows? analysis expressions scopes closures address))
                    expected)))
            (let [child (:scope edge)
                  next-abi (collection-abi analysis scopes child)]
              (when (and (#{:reverse :sort-by} (:name next-abi))
                         (= expected (get-in analysis [:scopes (second child) :signature :return])))
                (recur child (get-in next-abi [:paths :coll]))))))))))


(defn single-list-inputs
  "A list supplied directly to a single native filter has the same item
   domain as that filter's positional lookup reader."
  [analysis expressions scopes closures]
  (if (seq closures)
    (single-reader-inputs
      expressions
      (fn [[scope] type [reader-scope :as address]]
        (when (and (= scope reader-scope) (types/list-type? type) (= type (get-in expressions [address :type])))
          (lookup-item-list-type analysis expressions scopes closures address type))))
    expressions))
