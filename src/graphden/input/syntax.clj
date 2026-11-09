(ns graphden.input.syntax
  "Explicit EDN expressions for structured inputs, independent of package IO.
   The caller supplies a frozen reference index and binding identity function.
   Literal contents are opaque; parsing never loads or executes a graph."
  (:require
    [graphden.input.core :as input]))


(defn- reject!
  [code path]
  (throw (ex-info "Invalid structured input expression"
                  {:type :input/invalid-syntax :code code :path path})))


(defn- field-index
  [node path]
  (when-not (contains? node :fields)
    (reject! :partial-record-required path))
  (into {} (map (juxt :name identity)) (:fields node)))


(declare ^:private parse-node)


(defn- parse-partial
  [node path form context]
  (let [fields (field-index node path)]
    (when-not (every? #(contains? fields %) (keys form))
      (reject! :unknown-field path))
    {:kind :partial
     :fields (into {}
                   (keep (fn [{:keys [id name] :as field}]
                           (when (contains? form name)
                             [id (parse-node field (conj path id) (get form name) context)])))
                   (:fields node))}))


(defn- parse-leaf
  [path form {:keys [name->id binding-id scope]}]
  (when-not (and (vector? form) (= 2 (count form)))
    (reject! :explicit-expression-required path))
  (let [[operator payload] form
        expression (case operator
                     :value {:kind :value :value payload}
                     :ref (if-let [id (and (keyword? payload) (get name->id payload))]
                            {:kind :ref :fn-id id}
                            (reject! :unresolved-ref path))
                     (reject! :unknown-operator path))
        origin (binding-id scope path)]
    (when-not (some? origin)
      (reject! :binding-identity-required path))
    (assoc expression :binding-id origin)))


(defn- parse-node
  [node path form context]
  (if (map? form)
    (parse-partial node path form context)
    (parse-leaf path (if (coll? form) form [:value form]) context)))


(defn parse-expression
  "Normalize partial maps and [:value data]/[:ref name] into the binding AST.
   contract must be a resolved input descriptor. context supplies :name->id
   and :binding-id (fn [scope field-ID-path]), preserving migration origins.
   Bare scalars are literals (including keywords); refs are always explicit.
   Literal collections require [:value data], separate from partial maps.
   Run the result through bind-input for occupancy and identity validation."
  [contract scope form {:keys [name->id binding-id] :as context}]
  (when-not (and (some? scope) (map? name->id) (ifn? binding-id))
    (reject! :invalid-context []))
  (parse-node (input/input-contract contract) [] form (assoc context :scope scope)))


(declare ^:private emit-node)


(defn- emit-partial
  [node path expression context]
  (let [fields (:fields node)
        children (:fields expression)]
    (field-index node path)
    (when-not (and (map? children)
                   (every? (set (map :id fields)) (keys children)))
      (reject! :unknown-field path))
    (into (array-map)
          (keep (fn [{:keys [id name] :as field}]
                  (when (contains? children id)
                    [name (emit-node field (conj path id) (get children id) context)])))
          fields)))


(defn- emit-node
  [node path expression {:keys [id->name] :as context}]
  (case (:kind expression)
    :partial (emit-partial node path expression context)
    :value (if (contains? expression :value)
             [:value (:value expression)]
             (reject! :missing-value path))
    :ref (if-let [ref-name (get id->name (:fn-id expression))]
           (if (keyword? ref-name)
             [:ref ref-name]
             (reject! :unresolved-ref path))
           (reject! :unresolved-ref path))
    (reject! :unsupported-expression path)))


(defn emit-expression
  "Render an AST using public field/ref names from the same frozen context.
   Binding identities remain in the migration mapping, not literal payloads.
   Unsupported expressions fail rather than disappearing during export."
  [contract expression {:keys [id->name] :as context}]
  (when-not (map? id->name)
    (reject! :invalid-context []))
  (emit-node (input/input-contract contract) [] expression context))
