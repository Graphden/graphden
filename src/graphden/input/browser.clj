(ns graphden.input.browser
  "Offline format-2 export of a checked structured-input graph."
  (:require
    [graphden.executor.browser-plan :as browser]
    [graphden.executor.capabilities :as capabilities]
    [graphden.input.browser-default :as browser-default]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.field-default :as field-default]
    [graphden.input.ir :as ir]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(def ^:private const-id (ids/fn-id "core.logic" :const))


(defn- scope-primitive!
  [allow-fn? {:keys [target-id base-fn-id root-entry]}]
  (when-not (and (true? (allow-fn? target-id)) (true? (allow-fn? base-fn-id)))
    (ir/reject! :browser-visibility-denied {}))
  (let [op (capabilities/browser-op base-fn-id)]
    (when-not (and op (not= "server-call" op) (empty? (:effects root-entry)))
      (ir/reject! :unsupported-browser-primitive {:base-fn-id base-fn-id}))
    {:id (str base-fn-id) :op op}))


(defn- call-expr
  [id]
  {:kind "call" :fn id :renames []})


(defn- function-plan
  [id primitive owner args]
  {:id id :primitive (str primitive) :frame owner :aliases [] :env [] :args args})


(def ^:private browser-input-types #{:any :null :bool :text :int :numeric :keyword})


(defn- supplied-type!
  [type]
  (cond
    (contains? browser-input-types type) (name type)
    (and (types/union-type? type)
         (seq (types/union-members type))
         (every? browser-input-types (types/union-members type)))
    {:anyOf (mapv name (types/union-members type))}
    (types/list-type? type) {:listOf (supplied-type! (types/list-elem type))}
    (types/map-type? type) {:mapOf [(supplied-type! (types/map-key type))
                                    (supplied-type! (types/map-val type))]}
    :else (ir/reject! :unsupported-browser-input-type {})))


(defn- wire-presence
  [{:keys [presence-program required-addresses]} address-ids]
  {:presence (mapv (fn [{:keys [address kind sources required-fields default-present?]}]
                     (cond-> {:id (get address-ids address) :kind (if default-present? "always" (name kind))
                              :sources (mapv address-ids sources)}
                       (seq required-fields) (assoc :required-fields (mapv address-ids required-fields))))
                   presence-program)
   :required (vec (sort (map address-ids required-addresses)))})


(defn- closure-expr
  [{:keys [scope call-sites]} {:keys [scope-ids layouts address-ids]}]
  (when (> (count call-sites) 1)
    (ir/reject! :unsupported-browser-callback-arity {}))
  (merge {:kind "checked-closure" :fn (get scope-ids scope)
          :slots (mapv (comp address-ids :address) call-sites)}
         (wire-presence (get layouts scope) address-ids)))


(defn- browser-expression
  [{:keys [op value source scope fields type] :as expression} address {:keys [address-ids scope-ids encode closures] :as wire}]
  (case op
    :literal {:kind "literal" :value (encode value)}
    :read (call-expr (get address-ids source))
    :invoke (call-expr (get scope-ids scope))
    :closure (closure-expr (get closures address) wire)
    :supplied (cond-> {:kind "supplied" :slot (get address-ids address) :type (supplied-type! type)}
                (contains? expression :default)
                (assoc :kind "supplied-default" :fallback (encode (:default expression))))
    :record {:kind "record"
             :fields (mapv (fn [[key child]]
                             (let [field-id (get address-ids child)]
                               {:key (encode key) :present field-id :expr (call-expr field-id)}))
                           fields)}))


(defn- validate-expression
  [expression type]
  (if type
    {:kind "validate" :type (supplied-type! type) :expr expression}
    expression))


(defn- check-depth!
  [expressions scopes root]
  ;; Count the actual browser function chain, including the const wrappers.
  ;; The checked IR already proves acyclicity. Bound the traversal itself,
  ;; and account for previous visits reached at a shallower depth.
  (let [max-depths (volatile! {})]
    (letfn [(visit
              [kind key depth]
              (when (> depth browser/max-call-depth)
                (ir/reject! :browser-call-depth-limit {}))
              (when (> depth (get @max-depths [kind key] 0))
                (vswap! max-depths assoc [kind key] depth)
                (let [child-depth (inc depth)]
                  (case kind
                    :scope (doseq [path (vals (get-in scopes [key :argument-paths]))]
                             (visit :address [key path] child-depth))
                    :address (let [{:keys [op source scope fields]} (get expressions key)]
                               (case op
                                 :read (visit :address source child-depth)
                                 (:invoke :closure) (visit :scope scope child-depth)
                                 :record (doseq [[_ child] fields]
                                           (visit :address child child-depth))
                                 nil))))))]
      (visit :scope root 1))))


(defn- plan-ids
  [prefix identifiers]
  (into {} (map-indexed (fn [index key] [key (str prefix index)]))
        (sort-by pr-str identifiers)))


(defn- scope-arguments
  [scope paths address-ids]
  (mapv (fn [[arg path]]
          (let [id (get address-ids [scope path])]
            {:name (subs (str arg) 1) :present id :expr (call-expr id)}))
        (sort-by key paths)))


(defn- input-fields
  [nodes address-ids record-inputs encode]
  (mapv (fn [{:keys [name address fields] :as node}]
          (let [field {:name (subs (str name) 1) :key (encode name)}]
            (if (contains? node :fields)
              (cond-> (assoc field :fields (input-fields fields address-ids record-inputs encode))
                (contains? record-inputs address) (assoc :slot (get address-ids address)))
              (assoc field :slot (get address-ids address)))))
        nodes))


(defn- input-plan
  [{:keys [parameters record-inputs] :as layout} surface address-ids encode]
  (let [fields (input-fields (get-in surface [:root :fields]) address-ids record-inputs encode)
        slots (into [] (keep :slot) (tree-seq #(contains? % :fields) :fields {:fields fields}))
        expected (into #{} (map address-ids) (into parameters record-inputs))]
    (when-not (and (= (set slots) expected) (= (count slots) (count expected)))
      (ir/reject! :browser-input-route-mismatch {}))
    {:structured (assoc (wire-presence layout address-ids) :fields fields)}))


(defn- frame-owners
  [layouts scope-ids]
  (into {} (mapcat (fn [[scope {:keys [scopes presence-program]}]]
                     (let [id (get scope-ids scope)]
                       (for [owner (concat scopes (map :address presence-program))] [owner id]))))
        layouts))


(defn- expression-plan
  [{:keys [address-ids value-obligations] :as wire} owners [address expression]]
  (let [obligation (get value-obligations address)
        supplied? (= :supplied (:op expression))
        expression (cond-> expression (and supplied? obligation) (assoc :type obligation))
        emitted (browser-expression expression address wire)]
    (function-plan (get address-ids address) const-id (get owners address)
                   [{:name "value"
                     :expr (validate-expression emitted (when-not supplied? obligation))}])))


(defn- check-defaults!
  [analysis {:keys [expressions root-scope frame-layouts parameters]} options]
  (let [occurrence? (browser-default/occurrence-checker options (disj browser-input-types :any))]
    (doseq [[[scope path :as address] expression] expressions
            :when (contains? expression :default)]
      (let [node-scope (get-in analysis [:scopes (second scope)])
            node (descriptor/node-at (:configured-contract node-scope) path)
            fallback (:default expression)]
        (when-not (and (= :supplied (:op expression)) (contains? parameters address)
                       (= field-default/nullable-text-id (:type-id node))
                       (field-default/supported? (:required node) (:type node) fallback)
                       (or (= 1 (count path))
                           (and (= 2 (count path)) (= root-scope scope)
                                ;; Named input routing supplies the record slot on every explicit
                                ;; member write. The fallback alone cannot activate this record.
                                (contains? (get-in frame-layouts [root-scope :record-inputs]) [scope [(first path)]])
                                (occurrence? node-scope path fallback))))
          (ir/reject! :browser-field-default-unsupported {}))))))


(defn- export*
  [analysis {:keys [allow-fn?] :as options} closed?]
  (when-not (fn? allow-fn?)
    (ir/reject! :browser-visibility-policy-required {}))
  (let [{:keys [root-scope expressions scopes parameters frame-layouts] :as plan}
        (ir/lower-checked analysis options)]
    (check-defaults! analysis plan options)
    (when (and closed?
               (or (seq parameters)
                   (let [{:keys [record-inputs required-addresses]} (get frame-layouts root-scope)]
                     (some record-inputs required-addresses))))
      (ir/reject! :browser-input-validation-required {}))
    (when (> (+ (count expressions) (count scopes)) browser/max-functions)
      (ir/reject! :browser-function-limit {}))
    (check-depth! expressions scopes root-scope)
    (let [primitives (into #{{:id (str const-id) :op "const"}}
                           (map #(scope-primitive! allow-fn? %)) (vals scopes))
          encode (browser/literal-encoder)
          scope-ids (plan-ids "scope-" (keys scopes))
          address-ids (plan-ids "field-" (keys expressions))
          entry (get scope-ids root-scope)
          inputs (input-plan (get frame-layouts root-scope) (:public-input analysis) address-ids encode)
          owners (frame-owners frame-layouts scope-ids)
          wire (assoc plan :address-ids address-ids :scope-ids scope-ids :encode encode :layouts frame-layouts)
          scope-plans (map (fn [[scope {:keys [base-fn-id argument-paths]}]]
                             (function-plan (get scope-ids scope) base-fn-id (get owners scope)
                                            (scope-arguments scope argument-paths address-ids)))
                           scopes)
          expression-plans (map (partial expression-plan wire owners) expressions)]
      {:format 2 :primitiveAbi capabilities/browser-primitive-abi
       :entries {"root" entry}
       :inputs {entry inputs}
       :primitives (vec (sort-by :id primitives))
       :functions (vec (sort-by :id (concat scope-plans expression-plans)))})))


(defn export-closed
  "Emit format 2 for a closed checked graph. The caller authorizes every
   source in the frozen closure before supplying implementations and the
   :allow-fn? visibility policy. No storage reads or route activation."
  [analysis options]
  (export* analysis options true))


(defn export-named
  "Emit format 2 with the graph's named residual routes and primitive leaf
   checks. The source authorization contract is the same as export-closed."
  [analysis options]
  (export* analysis options false))
