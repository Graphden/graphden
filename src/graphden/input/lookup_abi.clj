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
  [graph [[root use] _] {:keys [binding-id expected-type expression reference]}]
  (when (and (= :ref (:kind expression)) (= :value (:mode reference)) (uuid? binding-id)
             (= expected-type (:actual-type reference)))
    (let [path (conj use binding-id)
          node (get-in graph [:scopes path])]
      (when (= (:fn-id expression) (:fn-id node))
        (identity-record-source node [[root path] value-path] expected-type)))))


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
                  (variables/type-in-scope scope declared))
               (not (types/type-any? #{binder} (get-in node [:signature :return])))
               (not-any? #(and (not (contains? #{(first path) (first native-path)} (:id %)))
                               (types/type-any? #{binder} (:type %)))
                         (get-in node [:configured-contract :fields])))
      (types/resolve {binder :any} type))))


(defn single-collection-inputs
  "A private map-key binder has no relationship with any other argument or
   result. Reify it at the sole public projection of that native collection."
  [analysis expressions scopes]
  (let [readers (group-by (comp :source val) (filter #(= :read (:op (val %))) expressions))]
    (reduce-kv (fn [result [scope path :as source] {:keys [op type]}]
                 (if-let [domain (when (and (= :supplied op) (= 1 (count path))
                                            (types/type-any? types/type-var? type) (empty? (get readers [scope []])))
                                   (let [[[address] & others] (get readers source)]
                                     (when (and address (empty? others))
                                       (projected-collection-domain analysis scopes source type address))))]
                   (assoc-in result [source :type] domain)
                   result))
               expressions expressions)))
