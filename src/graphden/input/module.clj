(ns graphden.input.module
  "Offline v2 source reader/export. Contexts are resolved by the caller, not
   inferred from a global registry. This SOURCE API accepts name-derived UUIDs;
   it does not solve arbitrary DB-owned identity migration or type checking."
  (:require
    [graphden.input.core :as input]
    [graphden.input.format :as format]
    [graphden.input.rows :as rows]
    [graphden.input.syntax :as syntax]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def ^:private type-keys
  [:type :refine :list :union :map :tuple :variant :marker :fn-type])


(defn- reject!
  [code]
  (throw (ex-info "Invalid structured input module"
                  {:type :input/invalid-module :code code})))


(defn- type-only?
  [definition]
  (and (not (or (:parent definition) (:parents definition)))
       (some #(contains? definition %) type-keys)))


(defn- field-configuration?
  [[path spec]]
  (and (vector? path) (= 1 (count path)) (simple-keyword? (first path))
       (map? spec) (= #{:type} (set (keys spec))) (format/configuration-type? (:type spec))))


(defn- validate-source!
  [module]
  (format/require-v2! module)
  (when (some #(contains? module %) [:args :configure])
    (reject! :unsupported-module-form))
  (when-not (and (or (nil? (:namespace module)) (string? (:namespace module)))
                 (vector? (:fns module)))
    (reject! :invalid-module-shape))
  (doseq [definition (:fns module)]
    (when-not (and (map? definition) (simple-keyword? (:name definition)))
      (reject! :invalid-definition-identity))
    (when (and (contains? definition :namespace)
               (not= (or (:namespace definition) "") (or (:namespace module) "")))
      (reject! :definition-namespace-mismatch))
    (when (some #(contains? definition %) [:args :input-format])
      (reject! :unsupported-definition-form))
    (when (and (contains? definition :configure)
               (not (and (map? (:configure definition)) (every? field-configuration? (:configure definition)))))
      (reject! :unsupported-field-configuration))
    (when (and (type-only? definition) (some #(contains? definition %) [:input :bind :configure]))
      (reject! :type-definition-input)))
  (when-not (= (count (:fns module)) (count (set (map :name (:fns module)))))
    (reject! :duplicate-definition)))


(defn- declaration-record
  [record]
  (when-not (and (map? record) (uuid? (:id record))
                 (case (:kind record)
                   :fn (and (or (nil? (:parent-ids record))
                                (and (vector? (:parent-ids record)) (every? uuid? (:parent-ids record))))
                            (every? #(or (nil? (get record %)) (uuid? (get record %)))
                                    [:input-type-fn-id :return-type-fn-id :base-fn-id :element-fn-id]))
                   :slot (and (string? (:name record)) (uuid? (:type-fn-id record))
                              (or (nil? (:source-slot-id record)) (uuid? (:source-slot-id record))))
                   :fn-slot (and (uuid? (:fn-id record)) (uuid? (:slot-id record))
                                 (nat-int? (:position record)))
                   false))
    (reject! :invalid-declaration-record))
  record)


(defn- unique-records
  "Shared declaration rows can repeat identically; an identity cannot describe
   two different rows. Binding identities remain global within their table."
  [records]
  (:rows
    (reduce (fn [{:keys [seen] :as acc} record]
              (let [key [(:kind record) (:id record)]]
                (if-let [prior (get seen key)]
                  (if (= prior record) acc (reject! :conflicting-record-identity))
                  (-> acc (assoc-in [:seen key] record) (update :rows conj record)))))
            {:seen {} :rows []} records)))


(defn- declared-contract
  [contract]
  (let [contract (input/input-contract contract)]
    (doseq [node (tree-seq #(contains? % :fields) :fields contract)]
      (when (some #(contains? node %) [:state :binding :bindable? :required-now?])
        (reject! :residual-contract-forbidden))
      (when-not (every? #(uuid? (:id %)) (:fields node))
        (reject! :invalid-field-identity)))
    contract))


(defn- context
  [definition context-for]
  (let [ctx (context-for definition)
        owner (ids/fn-id (:namespace definition) (:name definition))
        declarations (:declaration-records ctx)]
    (when-not (and (map? ctx) (= owner (:fn-id ctx))
                   (or (not (contains? ctx :scope)) (= [owner []] (:scope ctx)))
                   (vector? declarations) (map? (:origin-ledger ctx)))
      (reject! :invalid-definition-context))
    (let [declarations (unique-records (mapv declaration-record declarations))
          own (first (filter #(and (= :fn (:kind %)) (= owner (:id %))) declarations))]
      (when-not own (reject! :missing-owner-declaration))
      (if (type-only? definition)
        (do
          (when (or (:input-type-fn-id own) (:input-type-id ctx) (:contract ctx))
            (reject! :type-definition-context))
          (assoc ctx :declaration-records declarations))
        (do
          (when-not (and (= [owner []] (:scope ctx))
                         (uuid? (:input-type-id ctx))
                         (= (:input-type-id ctx) (:input-type-fn-id own))
                         (map? (:name->id ctx))
                         (every? (fn [[ref id]] (and (keyword? ref) (uuid? id))) (:name->id ctx))
                         (ifn? (:binding-id ctx)))
            (reject! :invalid-input-context))
          (assoc ctx :declaration-records declarations :contract (declared-contract (:contract ctx))))))))


(defn- binding-row
  [configuration [[[owner scope-path] path] {:keys [binding-id expression]}]]
  (rows/normalize
    (merge {:kind :input-binding :id binding-id :fn-id owner
            :scope-path scope-path :target-path path}
           (select-keys (get configuration path) [:type-override-fn-id])
           (case (:kind expression)
             :value {:expression-kind "value" :value-present true :value (:value expression)}
             :ref {:expression-kind "ref" :value-present false :ref-fn-id (:fn-id expression)}
             (reject! :unsupported-binding-expression)))))


(defn- resolve-configurations
  [{:keys [contract scope resolve-config-type]} state configuration]
  (into {}
        (map (fn [[names {:keys [type]}]]
               (let [path (:path (input/resolve-address contract scope names))]
                 (when-not (contains? state [scope path])
                   (reject! :configuration-requires-bound-field))
                 (when-not (ifn? resolve-config-type)
                   (reject! :configuration-type-resolver-required))
                 (let [resolved (resolve-config-type type)]
                   (when-not (and (format/configuration-type? (:type resolved))
                                  (uuid? (:type-override-fn-id resolved)))
                     (reject! :unresolved-configuration-type))
                   [names (assoc (select-keys resolved [:type :type-override-fn-id]) :target-path path)]))))
        configuration))


(declare ^:private parse-definition)


(defn- normalize-inline-origin
  [body origin module-namespace normalize-ref]
  (let [resolve-ref #(normalize-ref module-namespace %)
        [_ form] (first (:bind body))
        parent (resolve-ref (:parent origin))]
    (when-not (keyword? parent) (reject! :unresolved-inline-parent))
    (when (and (keyword? form) (resolve-ref form))
      (reject! :inline-binding-origin-mismatch))
    (when (and (vector? form) (= :ref (first form)) (not (keyword? (resolve-ref (second form)))))
      (reject! :unresolved-inline-reference))
    (rebase/rewrite-references origin #(or (resolve-ref %) %))))


(defn- inline-definitions
  [source module-namespace {:keys [contract scope]} {:keys [normalize-inline-parent] :as options}]
  (into (sorted-map)
        (keep (fn [[field expression]]
                (when (and (vector? expression) (= :ref (first expression)) (map? (second expression)))
                  (let [body (second expression)
                        origin (format/inline-origin body)]
                    (when-not (and (= 2 (count expression)) origin
                                   (or (:parent source) (:parents source)))
                      (reject! :unsupported-inline-declaration))
                    (when-not (ifn? normalize-inline-parent) (reject! :inline-parent-resolver-required))
                    (let [normalized (normalize-inline-origin body origin module-namespace normalize-inline-parent)
                          named (parse/inline-declaration normalized [(:name source) field] module-namespace)
                          child (merge (dissoc named :args) (select-keys body [:bind]))
                          path (:path (input/resolve-address contract scope [field]))]
                      [path (assoc (parse-definition child module-namespace options) :inline-body body)])))))
        (when (map? (:bind source)) (:bind source))))


(defn- parse-definition
  [source module-namespace {:keys [context-for] :as options}]
  (let [definition (assoc source :namespace module-namespace)
        ctx (context definition context-for)
        common {:source source :namespace module-namespace :fn-id (:fn-id ctx)
                :declaration-records (:declaration-records ctx) :origin-ledger (:origin-ledger ctx)}]
    (if (type-only? definition)
      common
      (let [inlines (inline-definitions source module-namespace ctx options)
            expression (syntax/parse-expression (:contract ctx) (:scope ctx) (get source :bind {})
                                                (assoc ctx :inline-at inlines))
            state (input/bind-input (:contract ctx) {} (:scope ctx) expression)
            configs (resolve-configurations ctx state (:configure source))
            by-path (into {} (map (juxt :target-path identity)) (vals configs))
            bindings (mapv (partial binding-row by-path) (sort-by (comp pr-str key) state))]
        (assoc common :input-type-id (:input-type-id ctx) :contract (:contract ctx)
               :expression expression :binding-state state :bindings bindings :configurations configs
               :inline-definitions inlines)))))


(defn- definition-records
  [parsed]
  (concat (:declaration-records parsed) (:bindings parsed)
          (mapcat definition-records (vals (:inline-definitions parsed)))))


(defn parse-module-v2
  "Parse an explicitly versioned module against caller-verified DECLARED input
   contexts. Each callback receives the original definition with module
   namespace attached. Contexts supply source-derived :fn-id, :scope [id []],
   :input-type-id, :contract, resolved :name->id, :binding-id, declaration rows
   and an opaque :origin-ledger. For direct inline refs, :normalize-inline-parent
   resolves semantic refs in the child to their canonical legacy spelling,
   and context-for also receives the derived child declaration. Type-only
   contexts need no callable input.

   Returns authored declaration/input-binding rows only. Origin ledgers and
   rich :input declarations are retained; no refs execute and no records are
   written. This does not establish type compatibility or residual interfaces.
   All definitions and rows validate before a result can be returned."
  [module {:keys [context-for] :as options}]
  (validate-source! module)
  (when-not (ifn? context-for) (reject! :context-provider-required))
  (let [definitions (mapv #(parse-definition % (:namespace module) options) (:fns module))
        records (unique-records (mapcat definition-records definitions))
        owners (mapcat (fn [d] (cons (:fn-id d) (map :fn-id (vals (:inline-definitions d))))) definitions)]
    (when-not (= (count owners) (count (set owners))) (reject! :duplicate-definition))
    (rows/state (filter #(= :input-binding (:kind %)) records))
    {:input-format 2 :module (dissoc module :fns) :definitions definitions :records records}))


(defn- id->name
  [names]
  ;; Prefer explicit qualification, then lexical order; never depend on map
  ;; iteration order when both bare and qualified spellings name the same fn.
  (reduce (fn [index [ref id]] (if (contains? index id) index (assoc index id ref)))
          {} (sort-by (fn [[ref _]] [(if (qualified-keyword? ref) 0 1) (str ref)]) names)))


(defn- emit-definition
  [parsed context-for]
  (let [{module-namespace :namespace :keys [source]} parsed
        ctx (context (assoc source :namespace module-namespace) context-for)]
    (when-not (= (select-keys parsed [:fn-id :input-type-id :contract :declaration-records :origin-ledger])
                 (select-keys ctx [:fn-id :input-type-id :contract :declaration-records :origin-ledger]))
      (reject! :export-context-drift))
    (if (contains? source :bind)
      (assoc source :bind (syntax/emit-expression (:contract ctx) (:expression parsed)
                                                  {:id->name (id->name (:name->id ctx))
                                                   :inline-at (:inline-definitions parsed)}))
      source)))


(defn emit-module-v2
  "Export validated offline output using the same frozen identities. Reparse
   verifies callback-produced binding origins and row contents before returning
   the source. Formatting/comments belong to the separate CST writer."
  [parsed {:keys [context-for] :as options}]
  (when-not (and (= 2 (:input-format parsed)) (map? (:module parsed))
                 (vector? (:definitions parsed)) (ifn? context-for))
    (reject! :invalid-export))
  (let [module (assoc (:module parsed) :fns (mapv #(emit-definition % context-for) (:definitions parsed)))
        reparsed (parse-module-v2 module options)]
    (when-not (and (= (:records parsed) (:records reparsed))
                   (= (mapv #(select-keys % [:configurations :inline-definitions]) (:definitions parsed))
                      (mapv #(select-keys % [:configurations :inline-definitions]) (:definitions reparsed))))
      (reject! :export-origin-drift))
    module))
