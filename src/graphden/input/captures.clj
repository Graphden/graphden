(ns graphden.input.captures
  "Resolve callable parameters and lexical inputs by stable field identity."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.surface :as surface]
    [graphden.input.variables :as variables]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(defn- source-address
  [graph [[owner use-path] field-path]]
  (some (fn [length]
          (let [parent (subvec use-path 0 length)
                contract (get-in graph [:scopes parent :configured-contract])]
            (when (descriptor/find-node contract field-path) [[owner parent] field-path])))
        (range (dec (count use-path)) -1 -1)))


(defn- direct-routes
  [node value-path]
  (if (seq (:fields node))
    (mapcat #(direct-routes % (conj value-path (:name %))) (:fields node))
    (when (:address node) [{:target (:address node) :value-path value-path}])))


(defn- lexical-routes
  [graph node role via]
  (let [source (when (and (:address node) (:whole-bindable? node))
                 (source-address graph (:address node)))]
    (cond
      source [{:from (:address node) :source source :required? (:required-now? node) :role role :via via}]
      (seq (:fields node)) (mapcat #(lexical-routes graph % role via) (:fields node))
      :else [])))


(defn- fully-sourced?
  [node routes]
  (let [sources (set (map :from routes))]
    (or (contains? sources (:address node))
        (and (seq (:fields node)) (every? #(fully-sourced? % routes) (:fields node))))))


(defn- supplied-by?
  [address routes]
  (some (fn [{[scope path] :from}]
          (let [[target-scope target-path] address]
            (and (= scope target-scope) (<= (count path) (count target-path))
                 (= path (subvec target-path 0 (count path)))))) routes))


(defn- declared-params
  [ctx target fields]
  (when-let [params (or (get-in ctx [:fns target :lambda-params])
                        (get-in ctx [:signatures target :lambda-params]))]
    (let [names (set (map :name fields))
          params (mapv keyword params)]
      (when-not (and (= (count params) (count (set params))) (every? names params))
        (context/reject! :invalid-lambda-params {:fn-id target}))
      params)))


(defn- callable-plan
  [ctx graph {:keys [from to binding-id target-fn-id]} fields]
  (let [entry (get-in graph [:scopes (second (first from)) :bindings from])
        expected (reference/expected-callable (:expected-type entry))
        lexical (into [] (mapcat #(lexical-routes graph % :capture binding-id)) fields)
        params (reference/select-call-site-params
                 {:structural-names (when (types/fn-type? expected) (keys (types/fn-args expected)))
                  :declared (declared-params ctx target-fn-id fields)
                  :free-names (mapv :name fields)
                  :candidate-names (mapv :name (remove #(fully-sourced? % lexical) fields))
                  :own-names (into #{} (comp (filter #(= :input-field (:kind %))) (map :name)) fields)})
        selected (set params)
        parameter-fields (filter #(contains? selected (:name %)) fields)
        captured-fields (remove #(contains? selected (:name %)) fields)
        call-sites (into [] (mapcat (fn [field]
                                      (map #(assoc % :parameter (:name field)) (direct-routes field [])))) parameter-fields)
        captures (into [] (mapcat #(lexical-routes graph % :capture binding-id)) captured-fields)
        target-info (variables/signature-in-scope to (get-in ctx [:signatures target-fn-id]))
        raw (reference/callable-contract (assoc target-info :args (into {} (map (juxt :name :type)) fields)))
        callable (if (types/fn-type? expected)
                   (reference/call-site-contract expected raw (assoc target-info :lambda-params params)) raw)]
    {:parameters params :call-sites call-sites :captures captures :callable-type callable
     :external-captures (into [] (comp (mapcat #(direct-routes % []))
                                       (remove #(supplied-by? (:target %) captures))) captured-fields)
     :hidden (into (set (map :target call-sites)) (keep :address) parameter-fields)}))


(defn- apply-edge
  [ctx graph {:keys [from to binding-id mode] :as edge}]
  (let [fields (:fields (surface/input-at-scope graph (second to)))]
    (if (= :graph-callable mode)
      (let [{:keys [captures hidden callable-type] :as plan} (callable-plan ctx graph edge fields)]
        (-> graph
            (update :input-routes into captures)
            (update :hidden-input-addresses into (into hidden (map :from captures)))
            (assoc-in [:closures from] (dissoc plan :hidden))
            (update-in [:scopes (second (first from)) :bindings from :reference]
                       assoc :capture-resolved? true :callable-type callable-type)))
      (let [routes (into [] (mapcat #(lexical-routes graph % :dependency-input binding-id)) fields)]
        (-> graph
            (update :input-routes into routes)
            (update :hidden-input-addresses into (map :from routes)))))))


(defn prepare
  "Partition from deepest uses outward. The shared runtime selector preserves
   authored [], zero-argument captures, positional inference and ambiguity
   rejection. Lexical routes require stable full field-ID paths; a matching
   display name is never evidence of shared identity."
  [ctx graph]
  (reduce (partial apply-edge ctx)
          (assoc graph :input-routes [] :closures {} :hidden-input-addresses #{})
          (sort-by (fn [{:keys [to from]}] [(- (count (second to))) (context/address-sort-key from)])
                   (filter #(contains? #{:value :callable-producer :graph-callable} (:mode %)) (:dependencies graph)))))
