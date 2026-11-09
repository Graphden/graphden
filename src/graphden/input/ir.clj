(ns graphden.input.ir
  "Pure lowering of a checked input plan for the fixture compiler. This is
   an internal execution IR, not authoring syntax or a production entry point."
  (:require
    [clojure.set :as set]
    [graphden.input.address :as address]
    [graphden.input.descriptor :as descriptor]))


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


(defn- value-obligations
  [analysis expressions]
  (let [obligations (distinct (concat (:obligations analysis)
                                      (mapcat :obligations (vals (:scopes analysis)))))]
    (doseq [{:keys [address kind trigger expected-type]} obligations]
      (when-not (and (= :value-type kind) (= :field-read trigger)
                     (contains? #{:any :null :bool :text :int :float :decimal :numeric
                                  :keyword :uuid :timestamptz :bytes :jsonb} expected-type)
                     (contains? expressions address))
        (reject! :runtime-obligations-required {:address address :kind kind :trigger trigger})))
    (let [by-address (group-by :address obligations)]
      (doseq [[address entries] by-address]
        (when-not (= 1 (count entries))
          (reject! :conflicting-runtime-obligations {:address address})))
      (into {} (map (fn [[address entries]] [address (:expected-type (first entries))])) by-address))))


(defn- lower-binding
  [analysis address {:keys [expression reference binding-id]}]
  (case (:mode reference)
    :literal {:op :literal :value (:value expression)}
    :identity {:op :literal :value (:fn-id expression)}
    :projection {:op :read :source (:source reference)}
    (:value :callable-producer :graph-callable)
    (let [dependency (get (::dependencies analysis) address)]
      (when-not (= 1 (count dependency))
        (reject! :dependency-mismatch {:address address :binding-id binding-id}))
      (let [{:keys [to target-fn-id]} (first dependency)]
        (when-not (and (= (first to) (ffirst address))
                       (= target-fn-id (:fn-id expression)
                          (get-in analysis [:scopes (second to) :fn-id])))
          (reject! :dependency-target-mismatch {:address address}))
        (when (and (= :graph-callable (:mode reference)) (not (:capture-resolved? reference)))
          (reject! :closure-capture-plan-required {:address address}))
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
                                           (:fields node))}
                     :else {:op :supplied :type (:type node)})]
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
                      children (case op :read [source] (:invoke :closure) [[scope []]]
                                     :record (mapv second fields) [])]
                  (doseq [child children] (visit child (conj pending address)))
                  (vswap! done conj address))))]
      (doseq [address (keys expressions)] (visit address #{})))))


(defn- closure-plans
  [analysis expressions]
  (let [plans (:closures analysis)]
    (doseq [[address expression] expressions]
      (when (and (= :closure (:op expression)) (not (contains? plans address)))
        (reject! :closure-capture-plan-required {:address address})))
    (into {}
          (map (fn [[address {:keys [parameters call-sites external-captures] :as plan}]]
                 (when-not (and (= :closure (get-in expressions [address :op]))
                                (vector? parameters) (every? keyword? parameters)
                                (= (count parameters) (count (set parameters))))
                   (reject! :invalid-closure-plan {:address address}))
                 (doseq [{:keys [target value-path parameter]} call-sites]
                   (when (seq value-path)
                     (reject! :nested-call-site-projection-required {:address target}))
                   (when-not (and (contains? (set parameters) parameter)
                                  (= :supplied (get-in expressions [target :op])))
                     (reject! :invalid-call-site-route {:address target})))
                 (let [scope (get-in expressions [address :scope])
                       required? (fn [[scope path]]
                                   (:required-now? (descriptor/node-at
                                                     (get-in analysis [:scopes (second scope) :remaining-contract]) path)))]
                   [address (assoc plan
                                   :scope scope
                                   :owned-addresses (filterv #(address/within-scope? scope (first %))
                                                             (keys expressions))
                                   :owned-scopes (mapv #(vector (first scope) %)
                                                       (filter #(address/within-scope? scope [(first scope) %])
                                                               (keys (:scopes analysis))))
                                   :required-parameters (into #{} (comp (map :target) (filter required?)) call-sites)
                                   :retained-addresses (set (map :target external-captures)))]))) plans)))


(defn lower-checked
  "Lower supported checked scopes. :implementations maps target UUIDs to an
   explicitly resolved {:base-fn-id :impl :root-entry frozen-rich-signature
   :argument-paths {ABI-name UUID-path}}. Special base ABIs fail closed.
   Context checking does not discover a composed function's base output.
   Free leaves remain parameters; unsupported call-site shapes/obligations fail closed."
  [analysis {:keys [snapshot-id implementations target-names]}]
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
        closures (closure-plans analysis expressions)
        invocation-parameters (into #{} (mapcat #(map :target (:call-sites %))) (vals closures))
        parameters (into #{} (keep (fn [[address expression]]
                                     (when (= :supplied (:op expression)) address))) expressions)
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
    {:version 1 :root-scope root :snapshot-id snapshot-id :expressions expressions :scopes scopes
     :required-addresses (set (:required-addresses analysis))
     :closures closures :invocation-parameters invocation-parameters
     :value-obligations (value-obligations analysis expressions)
     :parameters (set/difference parameters invocation-parameters)}))
