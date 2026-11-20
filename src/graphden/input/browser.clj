(ns graphden.input.browser
  "Offline format-2 export of a checked structured-input graph."
  (:require
    [graphden.executor.browser-plan :as browser]
    [graphden.executor.capabilities :as capabilities]
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
  [id primitive args]
  {:id id :primitive (str primitive) :aliases [] :env [] :args args})


(def ^:private browser-input-types #{:any :null :bool :text :int :numeric :keyword})


(defn- supplied-type!
  [type]
  (cond
    (contains? browser-input-types type) (name type)
    (and (types/union-type? type)
         (seq (types/union-members type))
         (every? browser-input-types (types/union-members type)))
    {:anyOf (mapv name (types/union-members type))}
    :else (ir/reject! :unsupported-browser-input-type {})))


(defn- browser-expression
  [{:keys [op value source scope fields type]} id address-ids scope-ids encode]
  (case op
    :literal {:kind "literal" :value (encode value)}
    :read (call-expr (get address-ids source))
    :invoke (call-expr (get scope-ids scope))
    :supplied {:kind "supplied" :slot id :type (supplied-type! type)}
    :record {:kind "record"
             :fields (mapv (fn [[key address]]
                             (let [field-id (get address-ids address)]
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
                                 :invoke (visit :scope scope child-depth)
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
  [nodes address-ids encode]
  (mapv (fn [{:keys [name address fields] :as node}]
          (let [field {:name (subs (str name) 1) :key (encode name)}]
            (if (contains? node :fields)
              (assoc field :fields (input-fields fields address-ids encode))
              (assoc field :slot (get address-ids address)))))
        nodes))


(defn- input-plan
  [{:keys [presence-program required-addresses parameters]} surface address-ids encode]
  (let [fields (input-fields (get-in surface [:root :fields]) address-ids encode)
        slots (into [] (keep :slot) (tree-seq #(contains? % :fields) :fields {:fields fields}))]
    (when-not (and (= (set slots) (into #{} (map address-ids) parameters))
                   (= (count slots) (count parameters)))
      (ir/reject! :browser-input-route-mismatch {}))
    {:structured {:fields fields
                  :presence (mapv (fn [{:keys [address kind sources]}]
                                    {:id (get address-ids address) :kind (name kind)
                                     :sources (mapv address-ids sources)})
                                  presence-program)
                  :required (vec (sort (map address-ids required-addresses)))}}))


(defn- export*
  [analysis {:keys [allow-fn?] :as options} closed?]
  (when-not (fn? allow-fn?)
    (ir/reject! :browser-visibility-policy-required {}))
  (let [{:keys [root-scope expressions scopes parameters value-obligations] :as plan}
        (ir/lower-checked analysis options)]
    (when (and closed? (seq parameters))
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
          inputs (input-plan plan (:public-input analysis) address-ids encode)
          scope-plans (map (fn [[scope {:keys [base-fn-id argument-paths]}]]
                             (function-plan (get scope-ids scope) base-fn-id
                                            (scope-arguments scope argument-paths address-ids)))
                           scopes)
          expression-plans (map (fn [[address expression]]
                                  (let [id (get address-ids address)
                                        obligation (get value-obligations address)
                                        supplied? (= :supplied (:op expression))
                                        expression (cond-> expression (and supplied? obligation) (assoc :type obligation))
                                        emitted (browser-expression expression id address-ids scope-ids encode)]
                                    (function-plan id const-id
                                                   [{:name "value"
                                                     :expr (validate-expression emitted (when-not supplied? obligation))}])))
                                expressions)]
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
