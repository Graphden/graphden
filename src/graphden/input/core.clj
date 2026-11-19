(ns graphden.input.core
  "Pure structured-input binding algebra. Not yet a production execution path.

   Contracts carry rich :type values unchanged, with ordered :fields describing
   record structure and stable field identities. Optionality belongs to fields,
   not to rich-type maps. Resolved aliases/variants are supplied by the caller.
   The algebra never evaluates an expression or infers a ref's calling mode.
   Binding-state arguments must be maps returned by this module (or an empty
   map); storage adapters validate persisted address shapes before entry.")


(defn- reject!
  [code facts]
  (throw (ex-info "Invalid structured input binding"
                  (assoc facts :type :input/invalid-binding :code code))))


(defn- normalize-node
  [node depth]
  (when (> depth 128)
    (reject! :contract-depth {}))
  (when-not (and (map? node) (contains? node :type))
    (reject! :invalid-contract {}))
  (if-not (contains? node :fields)
    node
    (let [fields (:fields node)]
      (when-not (and (vector? fields)
                     (every? #(and (map? %) (some? (:id %))
                                   (keyword? (:name %))
                                   (or (not (contains? % :required))
                                       (boolean? (:required %)))) fields))
        (reject! :invalid-fields {}))
      (when-not (and (= (count fields) (count (set (map :id fields))))
                     (= (count fields) (count (set (map :name fields)))))
        (reject! :duplicate-field {}))
      (assoc node :fields
             (mapv #(normalize-node (merge {:required true} %) (inc depth)) fields)))))


(defn input-contract
  "Validate an already resolved record descriptor and normalize required flags.
   IDs and rich types come from the graph/type resolver, never from field names.
   Atomic dictionaries have no :fields; repeated record occurrences may reuse
   child IDs because their full paths differ. Does not resolve global aliases."
  [descriptor]
  (let [contract (normalize-node descriptor 0)]
    (when-not (contains? contract :fields)
      (reject! :record-input-required {}))
    contract))


(defn- field-by
  [node key-fn value]
  (when-not (contains? node :fields)
    (reject! :atomic-field {}))
  (or (some #(when (= value (key-fn %)) %) (:fields node))
      (reject! :unknown-field {})))


(defn resolve-address
  "Resolve a vector of public field names to a scoped, stable field-ID path.
   Scope is an existing input occurrence, not a request to clone a shared ref."
  [contract scope names]
  (when-not (and (some? scope) (vector? names))
    (reject! :invalid-address {}))
  (let [[node path] (reduce (fn [[node path] field-name]
                              (let [field (field-by node :name field-name)]
                                [field (conj path (:id field))]))
                            [contract []] names)]
    {:scope scope :path path :type (:type node)}))


(defn- prefix?
  [prefix path]
  (and (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))


(defn- overlap?
  [[left-scope left-path] [right-scope right-path]]
  (and (= left-scope right-scope)
       (or (prefix? left-path right-path) (prefix? right-path left-path))))


(defn- validate-expression!
  [expression]
  (when-not (and (map? expression)
                 (case (:kind expression)
                   :value (contains? expression :value)
                   :ref (some? (:fn-id expression))
                   :projection (and (some? (:scope expression))
                                    (vector? (:path expression)))
                   false))
    (reject! :invalid-expression {})))


(defn- binding-entries
  [node scope path expression]
  (if (= :partial (:kind expression))
    (do
      (when-not (and (contains? node :fields) (map? (:fields expression)))
        (reject! :partial-record-required {:scope scope :path path}))
      (mapcat (fn [[field-id child]]
                (binding-entries (field-by node :id field-id)
                                 scope (conj path field-id) child))
              (:fields expression)))
    (do
      (validate-expression! expression)
      (when-not (some? (:binding-id expression))
        (reject! :binding-identity-required {:scope scope :path path}))
      [[[scope path] {:binding-id (:binding-id expression)
                      :expression (dissoc expression :binding-id)
                      :expected-type (:type node)}]])))


(defn- insert-binding
  [bindings [address entry] allow-shared?]
  (let [same-id (some (fn [[other-address other]]
                        (when (and (= (first address) (first other-address))
                                   (= (:binding-id entry) (:binding-id other)))
                          [other-address other])) bindings)]
    (cond
      (and allow-shared? (= [address entry] same-id)) bindings
      same-id (reject! :binding-identity-conflict {:scope (first address) :path (second address)})
      (some #(overlap? address %) (keys bindings))
      (reject! :occupied {:scope (first address) :path (second address)})
      :else (assoc bindings address entry))))


(defn bind-input
  "Bind a canonical expression to one input occurrence, returning a new map.
   Partial-record :fields keys are field IDs; whole value/ref/projection closes
   its entire subtree. Each leaf supplies a new :binding-id. A failed delta
   leaves the immutable old state unchanged; no expressions are evaluated.
   Type compatibility and ref dependencies are checked by the calling layer."
  [contract bindings scope expression]
  (when-not (some? scope)
    (reject! :invalid-address {}))
  (reduce #(insert-binding %1 %2 false) bindings
          (binding-entries contract scope [] expression)))


(defn merge-bindings
  "Join inherited binding sets. Only the SAME binding identity and contents
   at the SAME address deduplicate (MI diamond). Independent equal bindings
   conflict within an input scope. The same definition may occur in independent
   scopes without changing its binding IDs. Unlike user bind-input, a diamond
   join is idempotent."
  [left right]
  (reduce #(insert-binding %1 %2 true) left right))


(defn- scoped-bindings
  [bindings scope]
  (into {} (keep (fn [[[entry-scope path] entry]]
                   (when (= scope entry-scope) [path entry]))) bindings))


(defn- remaining-node
  [node entries path required-now?]
  (if-let [bound (get entries path)]
    {:descriptor (assoc node :state :bound :bindable? false
                        :required-now? false :binding bound)
     :missing []}
    (let [active? (boolean (some #(prefix? path %) (keys entries)))
          record-node? (contains? node :fields)
          children (when record-node?
                     (mapv (fn [field]
                             (remaining-node field entries (conj path (:id field))
                                             (and (or required-now? active?) (:required field))))
                           (:fields node)))
          child-missing (if record-node?
                          (into [] (mapcat :missing) children)
                          (if required-now? [path] []))
          missing (if (and record-node? required-now? (seq path)
                           (not active?) (empty? child-missing))
                    [path]
                    child-missing)]
      {:descriptor (cond-> (assoc node :state (if active? :partial :free)
                                  :bindable? (not active?) :required-now? (boolean required-now?))
                     record-node? (assoc :fields (mapv :descriptor children)))
       :missing missing})))


(defn remaining-input
  "Describe occupancy and local required fields without erasing rich types or
   constraints. :bindable? concerns binding the WHOLE node; partial nodes can
   still have bindable descendants. Missing paths are field-ID vectors.
   Ref expressions are returned as dependencies, NOT treated as ready values:
   their own free/captured inputs belong to the later graph-resolution layer."
  [contract bindings scope]
  (when-not (some? scope)
    (reject! :invalid-address {}))
  (let [entries (scoped-bindings bindings scope)
        result (remaining-node contract entries [] true)]
    (assoc result :dependencies
           (into {} (filter (fn [[_ entry]]
                              (contains? #{:ref :projection} (get-in entry [:expression :kind]))))
                 entries))))
