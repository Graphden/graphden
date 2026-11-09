(ns graphden.input.ir
  "Pure lowering of a checked input plan for the fixture compiler. This is
   an internal execution IR, not authoring syntax or a production entry point.")


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
    (reject! :snapshot-mismatch {}))
  (when (seq (:closures analysis))
    (reject! :closure-compilation-required {})))


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
    (:value :callable-producer)
    (let [dependency (filter #(= address (:from %)) (:dependencies analysis))]
      (when-not (= 1 (count dependency))
        (reject! :dependency-mismatch {:address address :binding-id binding-id}))
      (let [{:keys [to target-fn-id]} (first dependency)]
        (when-not (and (= (first to) (ffirst address))
                       (= target-fn-id (:fn-id expression)
                          (get-in analysis [:scopes (second to) :fn-id])))
          (reject! :dependency-target-mismatch {:address address}))
        {:op :invoke :scope to :target-id target-fn-id :binding-id binding-id}))
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
  [expressions scope {:keys [base-fn-id impl argument-paths] :as implementation}]
  (when-not (and (uuid? base-fn-id) (fn? impl) (map? argument-paths)
                 (every? keyword? (keys argument-paths)))
    (reject! :resolved-implementation-required {:scope scope}))
  (doseq [[_ path] argument-paths]
    (when-not (and (vector? path) (contains? expressions [scope path]))
      (reject! :implementation-path-missing {:scope scope :path path})))
  (select-keys implementation [:base-fn-id :impl :argument-paths]))


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
                      children (case op :read [source] :invoke [[scope []]]
                                     :record (mapv second fields) [])]
                  (doseq [child children] (visit child (conj pending address)))
                  (vswap! done conj address))))]
      (doseq [address (keys expressions)] (visit address #{})))))


(defn lower-checked
  "Lower supported checked scopes. :implementations maps target UUIDs to an
   explicitly resolved {:base-fn-id :impl :argument-paths {ABI-name UUID-path}}.
   Context checking does not discover a composed function's base output.
   Free leaves remain parameters; unsupported closures/obligations fail closed."
  [analysis {:keys [snapshot-id implementations target-names]}]
  (checked-plan! analysis snapshot-id)
  (let [root (:root-scope analysis)
        routes (group-by :from (:input-routes analysis))
        _ (doseq [[address entries] routes]
            (when-not (= 1 (count entries)) (reject! :duplicate-input-route {:address address})))
        routes (update-vals routes first)
        expressions (into {} (mapcat (fn [[use-path node]]
                                       (lower-tree analysis routes [(first root) use-path]
                                                   (:configured-contract node) []))) (:scopes analysis))
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
     :value-obligations (value-obligations analysis expressions)
     :parameters (into #{} (keep (fn [[address expression]]
                                   (when (= :supplied (:op expression)) address))) expressions)}))
