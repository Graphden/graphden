(ns graphden.input.captures
  "Resolve callable parameters and lexical inputs by stable field identity."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.resolvers :as resolvers]
    [graphden.input.surface :as surface]
    [graphden.input.variables :as variables]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(defn- source-address
  [graph [[owner use-path] field-path]]
  (let [boundary (reduce max 0 (keep (fn [path]
                                       (when (and (<= (count path) (count use-path))
                                                  (= path (subvec use-path 0 (count path))))
                                         (count path)))
                                     (:lexical-boundaries graph)))]
    (some (fn [length]
            (let [parent (subvec use-path 0 length)
                  contract (get-in graph [:scopes parent :configured-contract])]
              (when (descriptor/find-node contract field-path) [[owner parent] field-path])))
          (range (dec (count use-path)) (dec boundary) -1))))


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
  [ctx target]
  (when-let [params (or (get-in ctx [:fns target :lambda-params])
                        (get-in ctx [:signatures target :lambda-params]))]
    (let [params (mapv keyword params)]
      (when-not (= (count params) (count (set params)))
        (context/reject! :invalid-lambda-params {:fn-id target}))
      params)))


(defn- nested-parameter-fields
  [fields]
  (->> fields
       (filter #(= :reference-inputs (:kind %)))
       (mapcat #(tree-seq (fn [field] (= :reference-inputs (:kind field))) :fields %))
       (filter #(= :input-field (:kind %)))
       (group-by :name)))


(defn- whole-parameter-field?
  [{:keys [address whole-bindable?]}]
  (and whole-bindable? (= 1 (count (second address)))))


(defn- matching-parameter-readers?
  [fields]
  (and (seq fields)
       (every? whole-parameter-field? fields)
       (apply = (map :type fields))
       (apply = (map #(contains? % :fields) fields))))


(defn- matching-scalar-readers?
  [fields]
  (and (matching-parameter-readers? fields) (not (contains? (first fields) :fields))))


(defn- explicit-parameter-fields
  [direct nested params target]
  (into []
        (mapcat (fn [param]
                  (if-let [field (get direct param)]
                    [field]
                    (let [matches (get @nested param)]
                      (when-not (and (seq matches)
                                     (every? whole-parameter-field? matches)
                                     (or (= 1 (count matches))
                                         (and (= 1 (count params)) (matching-parameter-readers? matches))))
                        (context/reject! :invalid-lambda-params {:fn-id target}))
                      matches))))
        params))


(defn- nested-structural-readers
  [direct nested names]
  (when (= 1 (count names))
    (let [parameter (first names)]
      (when-not (contains? direct parameter)
        (let [matches (get @nested parameter)]
          (when (matching-scalar-readers? matches) matches))))))


(defn- list-append-control?
  [ctx graph {[[_ path] field-path] :target}]
  (let [builder (get-in graph [:scopes path :fn-id])
        {:keys [tail tail-id]} (get-in ctx [:source-list-fragments builder])]
    (and (= :open tail) (uuid? tail-id) (= [tail-id] field-path))))


(defn- implicit-candidates
  [ctx graph fields lexical]
  (into []
        (comp (remove #(fully-sourced? % lexical))
              (remove (fn [field]
                        (let [leaves (seq (direct-routes field []))]
                          (and leaves (every? (partial list-append-control? ctx graph) leaves)))))
              (map :name))
        fields))


(defn- callable-plan
  [ctx graph {:keys [from to binding-id target-fn-id]} fields]
  (let [entry (get-in graph [:scopes (second (first from)) :bindings from])
        expected (reference/expected-callable (:expected-type entry))
        lexical (into [] (mapcat #(lexical-routes graph % :capture binding-id)) fields)
        declared (declared-params ctx target-fn-id)
        direct (into {} (map (juxt :name identity)) fields)
        nested (delay (nested-parameter-fields fields))
        structural-names (when (types/fn-type? expected) (keys (types/fn-args expected)))
        structural-readers (when-not declared (nested-structural-readers direct nested structural-names))
        params (reference/select-call-site-params
                 {:structural-names structural-names
                  :declared declared
                  :free-names (cond-> (mapv :name fields) structural-readers (conj (first structural-names)))
                  :candidate-names (delay (implicit-candidates ctx graph fields lexical))
                  :own-names (into #{} (comp (filter #(= :input-field (:kind %))) (map :name)) fields)})
        selected (set params)
        parameter-fields (cond
                           declared (explicit-parameter-fields direct nested declared target-fn-id)
                           structural-readers structural-readers
                           :else (filter #(contains? selected (:name %)) fields))
        captured-fields (remove (set parameter-fields) fields)
        call-sites (into [] (mapcat (fn [field]
                                      (map #(assoc % :parameter (:name field)) (direct-routes field [])))) parameter-fields)
        parameter-addresses (set (map :target call-sites))
        captures (into [] (comp (mapcat #(lexical-routes graph % :capture binding-id))
                                (remove #(parameter-addresses (:from %))))
                       captured-fields)
        target-info (or (get-in graph [:scopes (second to) :signature])
                        (variables/signature-in-scope to (get-in ctx [:signatures target-fn-id])))
        raw (reference/callable-contract (assoc target-info :args (into {} (map (juxt :name :type))
                                                                        (concat fields parameter-fields))))
        callable (if (types/fn-type? expected)
                   (reference/call-site-contract expected raw (assoc target-info :lambda-params params)) raw)]
    {:parameters params :call-sites call-sites :captures captures :callable-type callable
     :external-captures (into [] (comp (mapcat #(direct-routes % []))
                                       (remove #(or (parameter-addresses (:target %))
                                                    (supplied-by? (:target %) captures)))) captured-fields)
     :hidden (into parameter-addresses (keep :address) parameter-fields)}))


(defn prepare-edge
  "Partition one incoming use after its target's outgoing uses are resolved."
  [ctx graph {:keys [from to binding-id mode] :as edge}]
  (if (= :resolved-value mode)
    (resolvers/bind-argument graph edge)
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
              (update :hidden-input-addresses into (map :from routes))))))))


(defn initialize
  [graph]
  (assoc graph :input-routes [] :closures {} :resolver-arguments {} :hidden-input-addresses #{}))


(defn prepare
  "Partition from deepest uses outward. The shared runtime selector preserves
   authored [], zero-argument captures, positional inference and ambiguity
   rejection. Lexical routes require stable full field-ID paths; a matching
   display name is never evidence of shared identity."
  [ctx graph]
  (reduce (partial prepare-edge ctx)
          (initialize graph)
          (sort-by (fn [{:keys [to from]}] [(- (count (second to))) (context/address-sort-key from)])
                   (filter #(contains? #{:value :callable-producer :graph-callable :resolved-value} (:mode %)) (:dependencies graph)))))
