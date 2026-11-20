(ns graphden.input.browser
  "Offline format-2 export of a checked structured-input graph."
  (:require
    [graphden.executor.browser-plan :as browser]
    [graphden.executor.capabilities :as capabilities]
    [graphden.input.ir :as ir]
    [graphden.packages.records.ids :as ids]))


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
  [id primitive arguments]
  {:id id :primitive (str primitive) :aliases [] :env []
   :args (mapv (fn [[arg expression]] {:name (subs (str arg) 1) :expr expression})
               (sort-by key arguments))})


(defn- browser-expression
  [expression address-ids scope-ids present encode]
  (case (:op expression)
    :literal {:kind "literal" :value (encode (:value expression))}
    :read (call-expr (get address-ids (:source expression)))
    :invoke (call-expr (get scope-ids (:scope expression)))
    :record {:kind "record"
             :fields (mapv (fn [[key address]]
                             {:key (encode key)
                              :expr (call-expr (get address-ids address))})
                           (filter (comp present second) (:fields expression)))}))


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
                                 :literal nil))))))]
      (visit :scope root 1))))


(defn- plan-ids
  [prefix identifiers]
  (into {} (map-indexed (fn [index key] [key (str prefix index)]))
        (sort-by pr-str identifiers)))


(defn- scope-arguments
  [scope paths present address-ids]
  (into {} (keep (fn [[name path]]
                   (let [address [scope path]]
                     (when (get present address)
                       [name (call-expr (get address-ids address))]))))
        paths))


(defn export-closed
  "Emit a format-2 browser plan for the checked closed subset. The supplied
   frozen snapshot and implementations are trusted host data. The caller
   authorizes the entire source closure before lowering, including its
   inheritance and type sources, and supplies :allow-fn? for a second check
   of every lowered target and base. No storage reads or route activation."
  [analysis {:keys [allow-fn?] :as options}]
  (when-not (fn? allow-fn?)
    (ir/reject! :browser-visibility-policy-required {}))
  (let [{:keys [root-scope expressions scopes parameters value-obligations required-addresses] :as plan}
        (ir/lower-checked analysis options)]
    (when (or (seq parameters) (seq value-obligations))
      (ir/reject! :browser-input-validation-required {}))
    (when (> (+ (count expressions) (count scopes)) browser/max-functions)
      (ir/reject! :browser-function-limit {}))
    (check-depth! expressions scopes root-scope)
    (let [present (ir/present-addresses plan {})
          _ (when (some #(not (get present %)) required-addresses)
              (ir/reject! :missing-required-input {}))
          primitives (into #{{:id (str const-id) :op "const"}}
                           (map #(scope-primitive! allow-fn? %)) (vals scopes))
          encode (browser/literal-encoder)
          scope-ids (plan-ids "scope-" (keys scopes))
          address-ids (plan-ids "field-" (keys expressions))
          entry (get scope-ids root-scope)
          scope-plans (map (fn [[scope {:keys [base-fn-id argument-paths]}]]
                             (function-plan (get scope-ids scope) base-fn-id
                                            (scope-arguments scope argument-paths present address-ids)))
                           scopes)
          expression-plans (map (fn [[address expression]]
                                  (function-plan (get address-ids address) const-id
                                                 {:value (browser-expression expression address-ids scope-ids present encode)}))
                                expressions)]
      {:format 2 :primitiveAbi capabilities/browser-primitive-abi
       :entries {"root" entry}
       :inputs {entry {:accepted [] :required [] :destinations []}}
       :primitives (vec (sort-by :id primitives))
       :functions (vec (sort-by :id (concat scope-plans expression-plans)))})))
