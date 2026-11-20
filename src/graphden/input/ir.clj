(ns graphden.input.ir
  "Pure lowering of a checked input plan for the fixture compiler. This is
   an internal execution IR, not authoring syntax or a production entry point."
  (:require
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
        (when-not (and (= to [(ffirst address) (conj (second (first address)) binding-id)])
                       (= target-fn-id (:fn-id expression)
                          (get-in analysis [:scopes (second to) :fn-id])))
          (reject! :dependency-target-mismatch {:address address}))
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
                      children (case op :read [source] :invoke [[scope []]]
                                     :record (mapv second fields) [])]
                  (doseq [child children] (visit child (conj pending address)))
                  (vswap! done conj address))))]
      (doseq [address (keys expressions)] (visit address #{})))))


(defn- presence-dependencies
  [expression]
  (case (:op expression)
    :read [(:source expression)]
    :record (mapv second (:fields expression))
    []))


(defn presence-program
  "Return field presence rules in dependency order, excluding value reads.
   Both execution targets distinguish an absent field from a present nil."
  [{:keys [expressions root-scope]}]
  (let [seen (volatile! #{})
        rules (volatile! [])]
    (letfn [(visit
              [address]
              (when-not (contains? @seen address)
                (vswap! seen conj address)
                (let [expression (get expressions address)
                      sources (presence-dependencies expression)
                      kind (case (:op expression)
                             :supplied :supplied
                             :read :any-present
                             :record (if (= address [root-scope []]) :always :any-present)
                             :always)]
                  (doseq [source sources] (visit source))
                  (vswap! rules conj {:address address :kind kind :sources sources}))))]
      (doseq [address (sort-by pr-str (keys expressions))] (visit address))
      @rules)))


(defn present-addresses
  "Return address presence without forcing any field or reference."
  ([program supplied] (present-addresses program supplied {}))
  ([program supplied inherited]
   (reduce (fn [presence {:keys [address kind sources]}]
             (assoc presence address
                    (case kind
                      :always true
                      :supplied (contains? supplied address)
                      :any-present (boolean (some presence sources)))))
           inherited
           program)))


(defn- call-site!
  [contract scope expressions {:keys [parameter target value-path]}]
  (let [[target-scope path] target
        field (when (= target-scope scope) (descriptor/find-node contract path))]
    (when-not (and field (= [] value-path) (= 1 (count path))
                   (= parameter (:name field)) (= :supplied (:op (get expressions target))))
      (reject! :callback-parameter-shape-required {:scope scope}))
    {:name parameter :address target :required? (:required field)}))


(defn- closure-plans
  [analysis expressions routes]
  (let [plans (:closures analysis)
        addresses (into #{} (keep (fn [[address expr]] (when (= :closure (:op expr)) address))) expressions)
        route-keys [:from :source :role :via :required?]]
    (when-not (= addresses (set (keys plans)))
      (reject! :closure-plan-mismatch {}))
    (into {}
          (map (fn [[address {:keys [parameters call-sites captures external-captures]}]]
                 (let [{:keys [scope binding-id]} (get expressions address)
                       owner (first address)
                       contract (get-in analysis [:scopes (second scope) :configured-contract])
                       reference (get-in analysis [:scopes (second owner) :bindings address :reference])]
                   (when-not (and (= (:root-scope analysis) owner) (:capture-resolved? reference)
                                  (empty? external-captures) (vector? parameters)
                                  (every? keyword? parameters) (= (count parameters) (count (set parameters))))
                     (reject! :unsupported-closure-scope {:address address}))
                   (doseq [{:keys [from source role via] :as capture} captures]
                     (when-not (and (= :capture role) (= binding-id via) (= owner (first source))
                                    (= (select-keys capture route-keys)
                                       (select-keys (get routes from) route-keys)))
                       (reject! :closure-capture-route-mismatch {:address address})))
                   (let [sites (mapv (partial call-site! contract scope expressions) call-sites)]
                     (when-not (= (frequencies parameters) (frequencies (map :name sites)))
                       (reject! :callback-parameter-shape-required {:scope scope}))
                     [address {:scope scope :parameters parameters :call-sites sites}]))))
          plans)))


(defn- frame-layouts
  [root scopes expressions closures program required]
  (let [callbacks (into {} (map (juxt :scope :call-sites)) (vals closures))
        owner (fn [[fn-id path]]
                (let [branch [fn-id (subvec path 0 (min (count path) 1))]]
                  (if (contains? callbacks branch) branch root)))
        by-frame (group-by (comp owner first :address) program)
        required (group-by (comp owner first) required)]
    (doseq [[address {:keys [op source scope]}] expressions]
      (let [local (owner (first address))]
        (when (case op
                :read (and (not= local (owner (first source))) (not= root (first source)))
                :invoke (not= local (owner scope))
                false)
          (reject! :cross-frame-expression {:address address}))))
    (into {} (map (fn [[frame scopes]]
                    (let [rules (get by-frame frame)
                          parameters (into #{} (comp (filter #(= :supplied (:kind %))) (map :address)) rules)
                          sites (get callbacks frame)]
                      (when (and sites (not= parameters (set (map :address sites))))
                        (reject! :unrouted-callback-input {:scope frame}))
                      [frame {:scopes scopes :presence-program rules :parameters parameters
                              :required-addresses (into (set (get required frame))
                                                        (comp (filter :required?) (map :address)) sites)}])))
          (group-by owner (keys scopes)))))


(defn lower-checked
  "Lower supported checked scopes. :implementations maps target UUIDs to an
   explicitly resolved {:base-fn-id :impl :root-entry frozen-rich-signature
   :argument-paths {ABI-name UUID-path}}. Special base ABIs fail closed.
   Context checking does not discover a composed function's base output.
   Public and callback-local leaves have separate frames. Unsupported
   closures and obligations fail closed."
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
    (let [closures (closure-plans analysis expressions routes)
          program (presence-program {:expressions expressions :root-scope root})
          layouts (frame-layouts root scopes expressions closures program (:required-addresses analysis))]
      {:version 1 :root-scope root :snapshot-id snapshot-id :expressions expressions :scopes scopes
       :closures closures :frame-layouts layouts :presence-program program
       :required-addresses (get-in layouts [root :required-addresses])
       :value-obligations (value-obligations analysis expressions)
       :parameters (get-in layouts [root :parameters])})))
