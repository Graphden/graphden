(ns graphden.input.source-snapshot
  "Offline package-declaration adapter for certified legacy reference uses."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.format :as format]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.legacy-use :as legacy-use]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.migration :as migration]
    [graphden.input.signatures :as signatures]
    [graphden.input.source :as source]
    [graphden.input.source-annotations :as annotations]
    [graphden.input.source-context :as source-context]
    [graphden.input.source-joint :as joint]
    [graphden.input.source-legacy-readers :as readers]
    [graphden.input.source-named-types :as named-types]
    [graphden.input.source-origins :as origins]
    [graphden.input.source-primitive :as primitive]
    [graphden.input.source-reference-alias :as reference-alias]
    [graphden.input.source-rename :as rename]
    [graphden.input.source-rich-types :as rich]
    [graphden.input.source-scope :as scope]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check :as check]
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(defn- reject!
  [code id]
  (throw (ex-info "Cannot freeze source declaration closure"
                  {:type :input/source-snapshot :code code :fn-id id})))


(defn- definition-id
  [definition]
  (ids/fn-id (:namespace definition) (:name definition)))


(defn package-index
  "Index original source forms of the package and its visible dependencies.
   prepared is the existing package preparation, including imported names.
   Call in the marker policy scope of preparation. Duplicate originals are
   ambiguous. Unreachable source need not be lowered."
  [prepared originals]
  (let [by-id (group-by definition-id originals)
        ambiguous (into #{} (keep (fn [[id ds]] (when (next ds) id))) by-id)
        declarations (into {} (map (juxt definition-id identity)) (vals (:defs-by-name prepared)))]
    {:prepared (-> (assoc prepared ::rename/ambiguous-type-ids ambiguous
                          ::source-context/reference-alias-witness reference-alias/witness)
                   (source-context/with-declaration-index declarations)
                   (source/with-reference-index))
     :markers (shapes/markers-snapshot)
     :declarations declarations
     :named-type-ids (delay (named-types/named-id-index (:name->id prepared) declarations))
     :originals by-id
     :inline-origins (delay (origins/index prepared originals))}))


(defn- plan-entry
  [{:keys [prepared declarations originals staged-proposals closure-baselines]} id]
  (let [[original & more] (get originals id)
        definition (get declarations id)]
    (when-not original (reject! :missing-original-declaration id))
    (when (seq more) (reject! :ambiguous-original-declaration id))
    (when-not (= id (:fn-id definition)) (reject! :missing-prepared-declaration id))
    (let [context (assoc prepared :fn-defs [definition] ::source/originals-by-id originals)
          certified (get closure-baselines id)
          staged (get staged-proposals id)]
      (if (and certified (nil? staged))
        certified
        (let [entry (or certified (first (:definitions (source/plan-module [original] context))))
              entry (cond-> entry staged
                            (update :proposed #(merge (select-keys % [:namespace :source-file]) staged)))]
          (first (:definitions (source/verify-module {:definitions [entry]} context))))))))


(defn with-staged-module
  "Replay all version-2 forms from one staged file against its original
   legacy module and visible preparer's package index. Compiling a root
   reuses these authored forms and verifies them in its usual closure."
  [{:keys [originals] :as package} legacy staged]
  (format/require-legacy! legacy "<original staged module>")
  (format/require-v2! staged)
  (let [module-ns (:namespace legacy)
        original (:fns legacy)
        proposed (:fns staged)
        expected-header (assoc (dissoc legacy :fns) :input-format 2)]
    (when-not (and (vector? original) (seq original) (every? map? original)
                   (vector? proposed) (every? map? proposed)
                   (migration/same-edn? expected-header (dissoc staged :fns)))
      (reject! :staged-module-ledger-mismatch nil))
    (let [names (mapv :name original)]
      (when-not (and (every? simple-keyword? names)
                     (= (count names) (count (set names)))
                     (= names (mapv :name proposed)))
        (reject! :staged-module-ledger-mismatch nil)))
    (let [by-id (into {} (map (fn [definition draft]
                                (let [source (assoc definition :namespace module-ns)
                                      id (definition-id source)
                                      [indexed & more] (get originals id)]
                                  (when-not (and indexed (empty? more)
                                                 (migration/same-edn? (dissoc source :source-file)
                                                                      (dissoc indexed :source-file)))
                                    (reject! :staged-original-mismatch id))
                                  [id draft]))
                              original proposed))
          package (assoc package :staged-proposals by-id)]
      (doseq [id (keys by-id)]
        (when-not (= :authored-source-equivalent (:verification (plan-entry package id)))
          (reject! :unverified-staged-declaration id)))
      package)))


(defn- omit-arguments
  "Replan the owner without the flat bindings under examination. The
   prepared package had already resolved names and assigned anonymous IDs.
   Update both lookup paths used by the legacy parser and v2 replay."
  [{:keys [declarations] :as package} owner fields]
  (let [omit #(update % :args (fn [args] (apply dissoc args fields)))
        baseline (omit (get declarations owner))
        declarations (assoc declarations owner baseline)]
    (-> package
        (update-in [:originals owner] #(mapv omit %))
        (assoc :declarations declarations)
        (update :prepared
                (fn [prepared]
                  (-> (update prepared :defs-by-name
                              #(update-vals % (fn [d] (if (= owner (:fn-id d)) baseline d))))
                      (source-context/with-declaration-index declarations)))))))


(defn- owned-declaration
  [{:keys [fn-id declaration-records] :as v2}]
  (assoc v2 :owner (some #(when (and (= :fn (:kind %)) (= fn-id (:id %))) %) declaration-records)))


(defn- exact-annotation?
  [owner contract {:keys [address target-path] :as config}]
  (if address
    (annotations/exact-scope? owner config)
    (annotations/exact? owner (some #(when (= [(:id %)] target-path) %) (:fields contract)) config)))


(defn- inline-binding?
  [{:keys [fn-id scoped-inputs]} field edge child]
  (let [{:keys [scope-path target-path ref-fn-id]} edge]
    (and (= [field] target-path) (= (:fn-id child) ref-fn-id)
         (or (empty? scope-path)
             (some (fn [{:keys [address row expression inline-body]}]
                     (and (= [[fn-id scope-path] target-path] address)
                          (= {:kind :ref :fn-id ref-fn-id} expression)
                          (migration/same-edn? edge row)
                          (migration/same-edn? (:inline-body child) inline-body)))
                   (vals scoped-inputs))))))


(defn- inline-entry
  "The verified parent's round trip compared the child's original body, use-site
   UUID and authored rows. Match its local or scoped field or stored list position."
  [package {:keys [fn-id] :as parent} edges fragments [path child]]
  (let [[field position] path
        id (:fn-id child)
        entry (owned-declaration child)
        edge (get edges (ids/binding-id fn-id field))]
    (when-not (and (uuid? id) (= fn-id (:fn-id edge)) (= "ref" (:expression-kind edge))
                   (= (ids/binding-id fn-id field) (:id edge))
                   (case (count path)
                     1 (inline-binding? parent field edge child)
                     2 (list-signature/inline-operand? (get fragments (:ref-fn-id edge)) edge position id)
                     false)
                   (= id (get-in entry [:owner :id]) (get-in package [:declarations id :fn-id]))
                   (uuid? (:input-type-id child)) (= (:input-type-id child) (get-in entry [:owner :input-type-fn-id]))
                   (= 1 (count (get-in entry [:owner :parent-ids])))
                   (or (and (empty? (:scoped-inputs child)) (not-any? :address (vals (:configurations child))))
                       (let [proof (get-in package [:closure-baselines id])
                             evidence [:fn-id :input-type-id :declaration-records :contract
                                       :binding-state :bindings :scoped-inputs :configurations]]
                         (and (= :authored-source-equivalent (:verification proof))
                              (migration/same-edn? (select-keys (:v2 proof) evidence)
                                                   (select-keys child evidence)))))
                   ;; Pins on binding rows are checked by check-type-provenance!.
                   (every? (fn [config]
                             (or (nil? (:row config))
                                 (exact-annotation? (:owner entry) (:contract child) config)))
                           (vals (:configurations child))))
      (reject! :unsupported-scope-declaration fn-id))
    (when (contains? (:originals package) id) (reject! :conflicting-inline-declaration id))
    child))


(defn- inline-entries
  [package {:keys [bindings inline-definitions] :as v2} generated]
  (let [edges (into {} (map (juxt :id identity)) bindings)
        fragments (into {} (map (juxt (comp :id :owner) :list-fragment)) generated)]
    (mapv (partial inline-entry package v2 edges fragments) inline-definitions)))


(defn- verified-entry-tree
  [package {:keys [fn-id list-builders] :as v2} resolve-named]
  (let [generated (mapv (partial list-signature/entry fn-id (get-in package [:prepared :name->id]) resolve-named)
                        (vals list-builders))
        children (inline-entries package v2 generated)
        owner (update (owned-declaration v2) :bindings into (mapcat :owner-bindings generated))]
    (into (conj generated owner)
          (mapcat #(verified-entry-tree package % resolve-named))
          children)))


(defn- closure-entries
  [package id resolve-named]
  (let [{:keys [verification blockers v2]} (plan-entry package id)]
    (when-not (= :authored-source-equivalent verification)
      (throw (ex-info "Unverified dependency in source closure"
                      {:type :input/source-snapshot :code :unverified-source-declaration
                       :fn-id id :blockers (mapv :code blockers)})))
    (when-not (:input-type-id v2) (reject! :unsupported-scope-declaration id))
    (verified-entry-tree package v2 resolve-named)))


(defn- declaration-evidence
  "Compare verified rows and rich declarations, not alternate spelling of bind.
   The round trip already checked inline-body against its enclosing source;
   expression, origins and child evidence retain its resolved meaning."
  [entry]
  (cond-> (dissoc entry :inline-body)
    (:source entry) (update :source dissoc :bind)
    (:inline-definitions entry) (update :inline-definitions #(update-vals % declaration-evidence))))


(defn- add-closure-entry
  [entries {:keys [owner] :as entry}]
  (let [id (:id owner)]
    (when (and (contains? entries id)
               (not (migration/same-edn? (declaration-evidence (get entries id))
                                         (declaration-evidence entry))))
      (reject! :conflicting-declaration-record id))
    (assoc entries id entry)))


(defn- declaration-package
  [package id]
  (if (contains? (:originals package) id)
    package
    (if-let [{:keys [declaration]} (when-let [index (:inline-origins package)]
                                     (origins/declaration @index id))]
      ;; The child keeps its parser UUID and exact enclosing source location.
      ;; Its own authored rows still need the ordinary complete round trip.
      (assoc-in package [:originals id] [declaration])
      (reject! :missing-original-declaration id))))


(defn- closure
  [package roots resolve-named]
  (loop [pending (vec roots) entries {}]
    (if-let [id (peek pending)]
      (if (contains? entries id)
        (recur (pop pending) entries)
        (let [added (closure-entries (declaration-package package id) id resolve-named)
              parents (mapcat #(get-in % [:owner :parent-ids]) added)
              refs (into [] (comp (mapcat :bindings) (keep :ref-fn-id)) added)]
          (recur (into (into (pop pending) parents) refs) (reduce add-closure-entry entries added))))
      entries)))


(defn- merge-record
  [records {:keys [kind id] :as row}]
  (let [prior (get-in records [kind id])]
    (when (and prior (not (migration/same-edn? prior row)))
      (reject! :conflicting-declaration-record id))
    (assoc-in records [kind id] row)))


(defn- source-field-types
  [entries]
  (reduce (fn [witnesses {:keys [id type-id type]}]
            (let [key [id type-id]]
              (when (and (contains? witnesses key) (not= (get witnesses key) type))
                (reject! :conflicting-source-field-type id))
              (assoc witnesses key type)))
          {} (into [] (comp (mapcat #(get-in % [:contract :fields])) (remove (comp keyword? :type))) (vals entries))))


(defn- exact-projection-pin?
  [type-ids contract row {:keys [target-path type type-override-fn-id]} type-rows]
  (let [field-by-id (into {} (map (juxt :id identity)) (:fields contract))
        target (get field-by-id (first target-path))
        source (get field-by-id (first (:projection-path row)))]
    (and (= "projection" (:expression-kind row)) (= [] (:scope-path row) (:projection-scope-path row))
         (= [(:id target)] target-path) (= [(:id source)] (:projection-path row))
         (uuid? type-override-fn-id) (= type-override-fn-id (:type-override-fn-id row) (:type-id source))
         (= type (:type source))
         (or (descriptor/stored-source-type? type type-override-fn-id type-ids)
             (and type-rows (= type-override-fn-id (rich/projected-callable-id (:fn-id row) target-path (:projection-path row)
                                                                               contract type-rows type type-ids)))))))


(defn- exact-reference-pin?
  [type-ids owner contract {:keys [id fn-id scope-path target-path expression-kind ref-fn-id
                                   value-present type-override-fn-id]}
   {:keys [type] :as config}]
  (let [field (some #(when (= [(:id %)] target-path) %) (:fields contract))]
    (and field (= target-path (:target-path config)) (= "ref" expression-kind) (= [] scope-path)
         (uuid? fn-id) (= (:id owner) fn-id) (= id (ids/binding-id fn-id (:id field)))
         (uuid? ref-fn-id) (false? value-present)
         (= type (:type field))
         (uuid? type-override-fn-id)
         (= type-override-fn-id (:type-override-fn-id config) (:type-id field))
         (descriptor/stored-source-type? type type-override-fn-id type-ids))))


(defn- exact-literal-pin?
  [owner source contract binding {:keys [target-path type type-override-fn-id]}]
  (let [field (some #(when (= [(:id %)] target-path) %) (:fields contract))
        name (:name field)
        value (:value binding)]
    (and field (= "value" (:expression-kind binding)) (true? (:value-present binding))
         (nil? (:resolver-fn-id binding)) (= [] (:scope-path binding))
         (= (:id owner) (:fn-id binding)) (= (:id binding) (ids/binding-id (:id owner) (:id field)))
         (contains? #{:null :bool :int :float :text :keyword :uuid} type)
         (= type (literals/classify-literal value))
         (= (ids/primitive-fn-id type) type-override-fn-id (:type-override-fn-id binding))
         (= {:type type} (get-in source [:configure [name]]))
         (migration/same-edn? [:value value] (get-in source [:bind name])))))


(defn- original-type-pin
  [type-ids owner source contract bindings {:keys [target-path] :as config} & [type-rows]]
  (let [binding (some #(when (= target-path (:target-path %)) %) bindings)]
    (when (and binding (or (exact-projection-pin? type-ids contract binding config type-rows)
                           (exact-reference-pin? type-ids owner contract binding config)
                           (exact-literal-pin? owner source contract binding config)))
      binding)))


(defn- optional-rename-bindings
  [entries fields]
  (let [any-id (ids/primitive-fn-id :any)]
    (into {}
          (for [{:keys [owner bindings declaration-records]} (vals entries)
                :let [owner-id (:id owner)
                      owned-fields (into #{}
                                         (keep #(when (= owner-id (:fn-id %)) (:slot-id %)))
                                         declaration-records)]
                {:keys [id fn-id target-path projection-path scope-path projection-scope-path expression-kind]} bindings
                :let [[source-id] projection-path
                      [target-id] target-path
                      source (get fields source-id)]
                :when (and (= "projection" expression-kind)
                           (= owner-id fn-id) (= [] scope-path projection-scope-path)
                           (= [source-id] projection-path) (= [target-id] target-path)
                           (uuid? target-id) (contains? owned-fields source-id)
                           (= id (ids/binding-id owner-id target-id))
                           (= target-id (:source-slot-id source))
                           (false? (:required source))
                           (= any-id (:type-fn-id source) (get-in fields [target-id :type-fn-id])))]
            [id {:origin-fn-id owner-id :source-id source-id :target-id target-id}]))))


(defn- snapshot
  [entries markers named]
  (let [{fns :fn fields :slot members :fn-slot bindings :input-binding}
        (reduce merge-record {} (concat (mapcat val (:type-rows named))
                                        (mapcat #(concat (:declaration-records %) (:bindings %)) (vals entries))))
        inputs (into {} (map (fn [{:keys [input-type-id contract]}] [input-type-id (:type contract)])) (vals entries))]
    (context/build-context
      (assoc (dissoc named :type-rows)
             :fns (or fns {}) :fields (or fields {}) :bindings (or bindings {})
             :source-optional-renames (optional-rename-bindings entries fields)
             :source-reference-aliases
             (into {} (map (juxt :reference-binding-id identity))
                   (mapcat #(vals (get-in % [:origin-ledger :mapping :occupied-reference-aliases])) (vals entries)))
             :members (merge (zipmap (keys inputs) (repeat [])) (group-by :fn-id (vals members)))
             :type-bodies (merge (:type-bodies named) inputs)
             :source-return-types
             (into {} (keep (fn [[id {:keys [owner source list-implementation]}]]
                              (let [type-id (:return-type-fn-id owner)
                                    return (:return-type source)]
                                (cond
                                  list-implementation [[id type-id] (get-in list-implementation [:root-entry :return])]
                                  (rich/erased-return? type-id return (:alias-ids named)) [[id type-id] return]))))
                   entries)
             :source-list-fragments (into {} (keep (fn [[id entry]]
                                                     (when-let [fragment (:list-fragment entry)] [id fragment]))) entries)
             :source-field-types (merge (:source-field-types named) (source-field-types entries))
             :source-binding-types
             (into {}
                   (for [{:keys [owner source contract configurations bindings]} (vals entries)
                         {:keys [row type type-override-fn-id] :as config} (vals configurations)
                         :when type-override-fn-id
                         :let [binding (if row
                                         (when (exact-annotation? owner contract config) row)
                                         (original-type-pin (:alias-ids named) owner source contract bindings config))]
                         :when binding]
                     [[(:id binding) type-override-fn-id] type]))
             :markers markers))))


(defn- root-id
  [context id]
  (let [order (inheritance/declaration-order context id)
        roots (filterv #(empty? (get-in context [:fns % :parent-ids])) order)]
    (when-not (= 1 (count roots)) (reject! :multiple-implementation-roots id))
    (first roots)))


(defn- check-type-provenance!
  [named-ids type-rows {:keys [source contract owner configurations bindings]}]
  (let [primitives (ids/primitive-fn-ids)
        type-ids (merge primitives named-ids)
        return (:return-type source)]
    ;; A v1 storage primitive may erase an authored rich field or return.
    ;; Only freeze types whose declared forms match their exact row IDs.
    ;; A base field's documenting :value stays metadata, never a binding/default.
    (when-not (every? (fn [{:keys [row target-path] :as config}]
                        (if row
                          (let [field (some #(when (= [(:id %)] target-path) %) (:fields contract))]
                            (and (some #{row} bindings)
                                 (or (exact-annotation? owner contract config)
                                     (rich/exact-self-pin? owner field config type-rows))))
                          (original-type-pin type-ids owner source contract bindings config type-rows)))
                      (vals configurations))
      (reject! :configuration-evidence-required (:id owner)))
    (when-not (and (or (and (seq (:parent-ids owner)) (nil? return))
                       (and (contains? type-ids return)
                            (= (get type-ids return) (:return-type-fn-id owner)))
                       (rich/erased-return? (:return-type-fn-id owner) return named-ids)
                       (rich/inline-record-return? type-rows (:return-type-fn-id owner) return type-ids))
                   (every? (fn [{:keys [type type-id declaration]}]
                             (and (or (= type declaration)
                                      (and (map? declaration) (= type (:type declaration))
                                           (every? #{:type :required :description :value} (keys declaration))))
                                  (or (descriptor/stored-source-type? type type-id named-ids)
                                      (and (uuid? type-id) (= type-id (get named-ids type)))
                                      (rich/callable-field? type-rows type-id type named-ids))))
                           (:fields contract)))
      (reject! :rich-type-source-required (:id owner)))))


(defn- rule-type-aliases
  [entries roots]
  (reduce (fn [aliases declared-aliases]
            (reduce-kv (fn [aliases type-alias qualified]
                         (when (and (contains? aliases type-alias) (not= qualified (get aliases type-alias)))
                           (reject! :conflicting-rule-type-alias nil))
                         (assoc aliases type-alias qualified))
                       aliases declared-aliases))
          {}
          (for [id (keys entries)
                :let [signature (get roots id)]
                :when (and (seq (:local-type-rule-evidence signature))
                           (signatures/local-type-rules? signature))]
            (:local-type-rule-aliases signature {}))))


(defn- rule-type-forms
  [entries roots]
  (let [fields (into #{} (keep (fn [[id {:keys [contract]}]]
                                 (when-let [argument (signatures/validated-type-argument (get roots id))]
                                   (some #(when (= argument (:name %)) (:id %)) (:fields contract))))) entries)]
    (for [entry (vals entries)
          {:keys [target-path value-present value]} (:bindings entry)
          :when (and value-present (= 1 (count target-path)) (contains? fields (first target-path)))]
      value)))


(defn- verified-context
  [package entries resolve-named roots]
  (let [named (named-types/evidence entries resolve-named (rule-type-aliases entries roots) (rule-type-forms entries roots))
        rows-by-id (group-by :id (mapcat :declaration-records (vals entries)))]
    (doseq [entry (vals entries) :when (nil? (:list-implementation entry))]
      (check-type-provenance! (:alias-ids named) rows-by-id entry))
    (snapshot entries (:markers package) named)))


(defn- implementation
  [context entries roots id]
  (let [base (root-id context id)
        entry (get roots base)
        declaration (get entries base)
        fields (get-in declaration [:contract :fields])
        args (into {} (map (juxt :name (comp types/resolve-alias :type))) fields)
        return (:return-type (:source declaration))]
    (when-not (and (= base (:fn-id entry)) (= (get-in declaration [:source :name]) (:name entry))
                   (= (:namespace declaration) (:namespace entry))
                   (= (set (:effects (:source declaration))) (:effects entry))
                   (= args (:args entry)) (= (types/resolve-alias return) (:return entry)))
      (reject! :implementation-signature-required base))
    ;; Audited callbacks use only binding facts and frozen type/signature policy.
    ;; A rule's additional named types must have verified source UUIDs too.
    (when-not (signatures/local-type-rules? entry)
      (reject! :rule-signature-view-required base))
    {:base-fn-id base :root-entry entry
     :argument-paths (into {} (map (fn [{:keys [name id]}] [name [id]])) fields)
     :argument-declarations (into {} (map (juxt :name identity)) fields)}))


(defn- implementation-options
  [ctx entries roots]
  (context/call-with-types
    ctx
    (fn []
      {:implementations (into {} (map (fn [[id entry]]
                                        [id (or (:list-implementation entry) (implementation ctx entries roots id))])) entries)
       :check-expression (named-types/checked-expression ctx check/check-input-binding)
       :infer-signature check/infer-input-signature})))


(defn freeze-declarations
  "Freeze verified input declarations for source analysis without admitting
   an executable implementation or publishing its inferred signature."
  ([package function-ids] (freeze-declarations package function-ids {}))
  ([package function-ids roots]
   (let [resolve-named (named-types/lookup package (partial plan-entry package))
         entries (closure package function-ids resolve-named)]
     {:entries entries :context (verified-context package entries resolve-named roots)})))


(defn freeze-closure
  "Build the immutable stage-4 context and checker options from the original
   package preparation, verified v2 rows, and frozen rich implementation
   entries keyed by base UUID. Standalone custom type rules require a full
   signature view and are rejected here. Include any ref introduced by an overlay in
   the starting function IDs. A closure or type evidence gap is an error."
  [package function-ids roots]
  (let [{:keys [context entries]} (freeze-declarations package function-ids roots)]
    ;; The checker retains the narrowed callable at its field-read boundary.
    ;; An external function has no verified invocation signature. Executable
    ;; admission needs a proved graph closure or a suitable runtime validator.
    (doseq [[id {:keys [configurations contract owner]}] entries
            {:keys [row] :as config} (vals configurations)
            :when (and row (not (exact-annotation? owner contract config)))]
      (throw (ex-info "Execution requires proof of the callable field-read contract"
                      {:type :input/source-snapshot :code :callable-self-pin-obligation-required :fn-id id
                       :binding-id (:id row) :target-path (:target-path row) :type-override-fn-id (:type-override-fn-id row)})))
    {:context context :options (implementation-options context entries roots)}))


(defn freeze-source-analysis
  "Prepare verified declarations and checker options without an executable
   verdict. Source replay compares read obligations, and checked lowering
   must enforce each obligation before executing a compiled package."
  [package function-ids roots]
  (let [{:keys [context entries]} (freeze-declarations package function-ids roots)]
    {:context context :options (implementation-options context entries roots)}))


(defn- freeze-legacy-use
  [package owner roots {:keys [binding declarations]}]
  (let [baseline (reduce (fn [package [declaration field]]
                           (when-not (contains? (get-in package [:declarations declaration :args]) field)
                             (reject! :missing-legacy-use-declaration declaration))
                           (-> (omit-arguments package declaration [field])
                               (update :closure-baselines dissoc declaration)))
                         package declarations)
        targets (cond-> [owner] (:ref-fn-id binding) (conj (:ref-fn-id binding)))
        {:keys [context options]} (freeze-source-analysis baseline targets roots)
        context (analysis/with-use-bindings context {owner [binding]})
        graph (analysis/analyze-effective-input context owner options)
        address [[owner []] (:target-path binding)]
        actual (get-in graph [:scopes [] :bindings address])
        mode (if (:ref-fn-id binding) :value :literal)]
    (when-not (and (= ((juxt :id :fn-id) binding) ((juxt :binding-id :origin-fn-id) actual))
                   (= mode (get-in actual [:reference :mode])))
      (reject! :legacy-use-binding-mismatch owner))
    {:baseline baseline :graph graph}))


(defn replay-legacy-use
  "Analyze a legacy initializer at its final owner and retain its stored
   identity. This is a local typed comparison. It does not certify source
   emission or how other functions inherit the original declaration."
  [package legacy-graph owner roots]
  (if-let [selected (legacy-use/select-binding legacy-graph owner)]
    (:graph (freeze-legacy-use package owner roots selected))
    (reject! :unsupported-legacy-use owner)))


(defn migrate-legacy-leaf
  "Re-emit a checked lexical initializer as a binding on its local reader.
   The legacy graph must cover the whole corpus so inherited shadowing
   cannot disappear outside the declaring package."
  [package legacy-graph owner roots]
  (let [{:keys [binding stored-slot declarations] :as selected} (legacy-use/select-binding legacy-graph owner)
        [[_ old-name]] declarations
        source-path [:args old-name]
        entry (plan-entry package owner)
        [argument & more] (filter #(= source-path (:source-path %)) (get-in entry [:legacy :arguments]))
        [blocker & other-blockers] (:blockers entry)]
    (when-not (and (= owner (:fn-id binding)) (= [[owner old-name]] declarations)
                   (empty? (get-in legacy-graph [:children-by-fn owner]))
                   argument (empty? more) (:expression argument)
                   (= source-path (:source-path blocker))
                   (= :ref-scope-required (:code blocker)) (empty? other-blockers))
      (reject! :unsupported-legacy-leaf owner))
    (let [{:keys [baseline graph]} (freeze-legacy-use package owner roots selected)
          path (:target-path binding)
          fields (get-in graph [:scopes [] :configured-contract :fields])
          field (some #(when (= path [(:id %)]) (:name %)) fields)
          proposed (:proposed (plan-entry baseline owner))]
      (when-not (and (= :checked (:status graph)) (empty? (:diagnostics graph))
                     (every? #(= [:value-type :field-read] ((juxt :kind :trigger) %)) (:obligations graph))
                     (simple-keyword? field) proposed (not (contains? (:bind proposed) field))
                     (or (nil? (:inline-fn-id argument))
                         (and (= old-name field) (= (:inline-fn-id argument) (:ref-fn-id binding)))))
        (reject! :unverified-legacy-leaf owner))
      (let [candidate (assoc entry
                             :status :supported :blockers []
                             :proposed (assoc-in proposed [:bind field] (:expression argument))
                             :legacy-leaf {:row binding :slot-id stored-slot})]
        (first (:definitions (source/verify-module {:definitions [candidate]} (:prepared package))))))))


(defn- freeze-source-declarations
  [declarations roots]
  (let [entries (into {} (map (fn [{:keys [fn-id declaration-records] :as declaration}]
                                (let [owner (some #(when (and (= :fn (:kind %)) (= fn-id (:id %))) %)
                                                  declaration-records)]
                                  [fn-id (assoc declaration :owner owner)]))) declarations)
        ctx (verified-context {:markers (shapes/markers-snapshot)} entries {} roots)]
    {:context ctx :options (implementation-options ctx entries roots)}))


(defn freeze-primitive
  "Freeze a staged primitive without a legacy original or dependency context."
  [module root roots]
  (freeze-source-declarations [(primitive/prepare module root)] roots))


(defn freeze-literal-root
  "Freeze a composed root and its visible primitive parent from one v2 file."
  [module root roots visible-fn-ids]
  (freeze-source-declarations (primitive/prepare-literal-root module root visible-fn-ids) roots))


(defn- source-binding
  "Preserve the identity of a declaration that supplies a projected field
   at this use. Only the occurrence address is rebased under a reference."
  [owner by-id graph [scope path :as address]]
  (when-let [entry (get-in graph [:scopes (second scope) :bindings address])]
    (let [id (:binding-id entry)
          row (get by-id id)
          kind (:expression-kind row)]
      (when-not (and row (= (:fn-id row) (:origin-fn-id entry))
                     (empty? (:scope-path row)) (= path (:target-path row))
                     (= id (ids/binding-id (:fn-id row) (first path)))
                     (= kind (name (get-in entry [:expression :kind]))))
        (reject! :projection-source-binding-mismatch owner))
      {:address address :binding-id id :origin-fn-id (:fn-id row) :expression-kind kind})))


(defn- confirmed-projections
  [owner bindings graph]
  (let [by-id (into {} (map (juxt :id identity)) bindings)]
    (mapv (fn [{[scope path :as address] :from :as route}]
            (let [bound (get-in graph [:scopes (second scope) :bindings address])
                  id (:binding-id bound)
                  row (get by-id id)
                  supplied (source-binding owner by-id graph (:source route))]
              (when-not (and (= "projection" (:expression-kind row)) (= (:fn-id row) (:origin-fn-id bound))
                             (= path (:target-path row)) (= [scope (:projection-path row)] (:source route)))
                (reject! :projection-origin-mismatch owner))
              (cond-> (assoc route :binding-id id :origin-fn-id (:fn-id row) :proof (get-in graph [:proofs address]))
                supplied (assoc :source-binding supplied))))
          (:projections graph))))


(defn- projection-analysis
  [ctx owner options]
  (try
    {:graph (analysis/analyze-effective-input ctx owner (dissoc options :gap)) :gap (:gap options)}
    (catch clojure.lang.ExceptionInfo error
      (let [data (ex-data error)]
        (if (and (:implementations options) (= :conflicting-runtime-obligations (:code data)))
          ;; Preserve already verified source routes when signature inference
          ;; needs an unrepresented intersection of runtime checks. This is
          ;; an incomplete diagnostic view, never an executable certificate.
          {:graph (analysis/analyze-effective-input ctx owner (select-keys options [:check-expression]))
           :gap (select-keys data [:code :address])}
          (throw error))))))


(defn projection-readiness
  "Analyze verified rename rows at each traversed use with the scoped analyzer.
   Keep runtime obligations and missing implementation/signature coverage
   visible even when all field and binding identities are established."
  [package owner roots]
  (try
    (let [resolve-named (named-types/lookup package (partial plan-entry package))
          entries (closure package [owner] resolve-named)
          bindings (mapcat :bindings (vals entries))
          _ (when-not (some #(and (= "projection" (:expression-kind %)) (empty? (:scope-path %))) bindings) (reject! :missing-source-projection owner))
          ctx (verified-context package entries resolve-named roots)
          options (try (implementation-options ctx entries roots)
                       (catch clojure.lang.ExceptionInfo error
                         (if (#{:implementation-signature-required :rule-signature-view-required} (:code (ex-data error)))
                           {:check-expression (named-types/checked-expression ctx check/check-input-binding)
                            :gap (select-keys (ex-data error) [:code :fn-id])}
                           (throw error))))
          {:keys [graph gap]} (projection-analysis ctx owner options)
          projections (confirmed-projections owner bindings graph)]
      (cond-> (assoc (select-keys graph [:status :coverage :required-addresses :obligations :diagnostics])
                     :fn-id owner :projections projections)
        (empty? projections) (-> (assoc :status :incomplete) (update :diagnostics conj {:code :no-evaluated-projection}))
        gap (-> (assoc :status :incomplete) (update :diagnostics conj gap))))
    (catch clojure.lang.ExceptionInfo error
      (let [data (ex-data error)
            code (or (:code data) (when (= :types/check-failed (:type data)) :types/check-failed))]
        (if code
          {:fn-id owner :status :blocked :diagnostics [(assoc (select-keys data [:fn-id :blockers :address :reason]) :code code)]}
          (throw error))))))


(defn- scope-evidence
  [package owner roots]
  (let [entry (plan-entry package owner)
        blockers (:blockers entry)
        paths (mapv :source-path blockers)]
    (when-not (and (seq paths) (every? #(= :ref-scope-required (:code %)) blockers)
                   (every? #(and (= 2 (count %)) (= :args (first %))) paths))
      (reject! :unsupported-scope-source owner))
    (let [baseline (omit-arguments package owner (mapv second paths))
          _ (when-not (source-context/has-reference-use? (:prepared baseline) (get-in baseline [:declarations owner]))
              (reject! :missing-reference-use owner))
          ;; The removed values may be references. Their declarations must
          ;; belong to the frozen closure before the aggregate overlay runs.
          targets (keep :ref-fn-id (filter #(= :binding (:kind %)) (get-in entry [:legacy :records])))
          {:keys [context options]} (freeze-source-analysis baseline (into [owner] targets) roots)]
      {:entry entry :baseline baseline
       :certificates (scope/verify-bindings entry paths context #(analysis/analyze-effective-input %1 %2 options))})))


(defn verify-binding
  "Certify a single legacy flat binding when its native reference use can
   be identified uniquely. Returns private evidence for source replay."
  [package owner roots]
  (let [[certificate & more] (:certificates (scope-evidence package owner roots))]
    (when (seq more) (reject! :single-scope-source-required owner))
    certificate))


(defn- add-scoped-form
  [owner proposed {:keys [scope-certificate expression]}]
  (let [path (:public-path scope-certificate)
        keyspace (if (contains? scope-certificate :configuration) :configure :bind)]
    (when (contains? (get proposed keyspace) path)
      (reject! :unsupported-scope-source owner))
    (assoc-in proposed [keyspace path] (get scope-certificate :configuration expression))))


(defn- migrate-scoped-binding
  "Write the checked reference inputs as explicit public paths in :bind and
   compare the complete v2 declaration with the original legacy source."
  [package owner roots]
  (let [{:keys [entry baseline certificates]} (scope-evidence package owner roots)
        by-path (into {} (map (juxt :source-path identity)) certificates)
        arguments (mapv (fn [{:keys [source-path] :as arg}]
                          (if-let [certificate (get by-path source-path)]
                            (assoc arg :scope-certificate certificate)
                            arg))
                        (get-in entry [:legacy :arguments]))
        scoped (filter :scope-certificate arguments)
        proposed (:proposed (plan-entry baseline owner))]
    (when-not (and proposed (= (count scoped) (count certificates)))
      (reject! :unsupported-scope-source owner))
    (let [candidate (-> entry
                        (assoc :status :supported :blockers []
                               :proposed (reduce (partial add-scoped-form owner) proposed scoped))
                        (assoc-in [:legacy :arguments] arguments))]
      (first (:definitions (source/verify-module {:definitions [candidate]} (:prepared package)))))))


(defn migrate-binding
  "Prefer an existing reference-use proof. A legacy initializer that creates
   its own use needs whole-corpus evidence that the owner has no inheritors."
  [{:keys [legacy-graph] :as package} owner roots]
  (try
    (migrate-scoped-binding package owner roots)
    (catch clojure.lang.ExceptionInfo failure
      (if (and legacy-graph (= :missing-reference-use (:code (ex-data failure))))
        (migrate-legacy-leaf package legacy-graph owner roots)
        (throw failure)))))


(defn migrate-forwarded-fields
  "Freeze both free addresses, then verify the exact scoped projection and
   write it into the source proposal. Require the stock type checker and the
   same implementation registry evidence used by other source certificates."
  [{:keys [prepared] :as package} owner fields roots]
  (let [prepared (readers/prepare prepared owner roots)
        original (plan-entry package owner)
        {:keys [proofs entry]} (source/forwarded-fields-baseline prepared original fields)]
    (when-not (= :authored-source-equivalent (:verification entry))
      (reject! :unverified-forwarded-baseline owner))
    (let [baseline (assoc-in package [:closure-baselines owner] entry)
          {:keys [context options]} (freeze-source-analysis baseline [owner] roots)
          certificates (scope/verify-forwarded-projections context proofs
                                                           #(analysis/analyze-effective-input %1 %2 options))]
      (source/rewrite-forwarded-fields prepared original fields certificates))))


(defn migrate-forwarded-field
  "Single-field convenience form of the atomic forwarding replay."
  [package owner field roots]
  (migrate-forwarded-fields package owner [field] roots))


(defn- migrate-joint-owner
  [package owner roots]
  (let [original (plan-entry package owner)
        {:keys [fields paths]} (joint/groups original)
        reduced-package (omit-arguments package owner (mapv second paths))
        baseline-entry (plan-entry reduced-package owner)
        _ (joint/check-full-reference-origins! (:prepared package) original (:prepared reduced-package) baseline-entry)
        projected (migrate-forwarded-fields reduced-package owner fields roots)
        baseline (assoc-in reduced-package [:closure-baselines owner] projected)
        targets (keep :ref-fn-id (filter #(= :binding (:kind %)) (get-in original [:legacy :records])))
        {:keys [context options]} (freeze-source-analysis baseline (into [owner] targets) roots)
        certificates (scope/verify-bindings-after-forwarding original projected context
                                                             #(analysis/analyze-effective-input %1 %2 options))
        candidate (joint/replay-entry original projected certificates)
        result (first (:definitions (source/verify-module {:definitions [candidate]} (:prepared package))))]
    (when-not (= :authored-source-equivalent (:verification result))
      (reject! :unverified-joint-declaration owner))
    ;; Nothing is published until both graph replays and the complete authored
    ;; source round trip succeed. A failed leaf leaves the caller's package intact.
    (-> package
        (assoc-in [:closure-baselines owner] result)
        (update :prepared source/with-forwarded-parent result))))


(defn with-migrated-bindings
  "Certify legacy binding owners in dependency order. Record the exact checked
   rows so later owners can freeze a closure containing those dependencies."
  [package owners roots]
  (reduce (fn [package owner]
            (let [{:keys [verification] :as result} (migrate-binding package owner roots)]
              (when-not (= :authored-source-equivalent verification)
                (reject! :unverified-scoped-declaration owner))
              (assoc-in package [:closure-baselines owner] result)))
          package owners))


(defn with-migrated-forwarding
  "Keep one verified forwarding declaration available to later closures and
   to staged module replay. Bound occurrence evidence is kept in the package."
  [package owner field roots]
  (let [fields (if (vector? field) field [field])
        {:keys [verification] :as result} (migrate-forwarded-fields package owner fields roots)]
    (when-not (= :authored-source-equivalent verification)
      (reject! :unverified-forwarded-declaration owner))
    (-> package
        (assoc-in [:closure-baselines owner] result)
        (update :prepared source/with-forwarded-parent result))))


(defn- inherited-candidate?
  [blockers]
  (and (some #(= :inherited-projection-required (:code %)) blockers)
       (every? (fn [{:keys [code source-path]}]
                 (if (= :inherited-projection-required code)
                   (= [:args] source-path)
                   (and (#{:ref-scope-required :field-projection-required} code)
                        (= 2 (count source-path)) (= :args (first source-path)))))
               blockers)))


(declare certify-owner)


(defn- certify-inline-children
  [package owner paths roots active]
  (let [updated (reduce (fn [package path]
                          (let [origin (origins/child-at @(:inline-origins package) owner path)
                                id (:fn-id origin)]
                            (when-not id (reject! :missing-inline-source-origin owner))
                            (when (contains? (:originals package) id)
                              (reject! :conflicting-inline-declaration id))
                            (let [with-child (certify-owner (declaration-package package id) id roots active)
                                  proof (get-in with-child [:closure-baselines id])]
                              (-> with-child (update :originals dissoc id)
                                  (update :prepared source/with-certified-inline proof)))))
                        package (sort-by pr-str paths))]
    ;; An invalid list operand hides subsequent ones until its own proof is
    ;; installed. A path that still fails after its proof made no progress.
    (when (some (comp paths origins/inline-binding-path) (:blockers (plan-entry updated owner)))
      (reject! :unproved-inline-binding owner))
    (certify-owner updated owner roots (disj active owner))))


(defn- certify-inherited
  [package owner entry roots active]
  (let [parents (some #(when (and (= :fn (:kind %)) (= owner (:id %))) (:parent-ids %))
                      (get-in entry [:legacy :records]))
        next-package (reduce #(certify-owner %1 %2 roots active) package parents)
        next-entry (plan-entry next-package owner)]
    (when (some #(= :inherited-projection-required (:code %)) (:blockers next-entry))
      (reject! :unproved-inherited-projection owner))
    (certify-owner next-package owner roots (disj active owner))))


(defn- certify-owner-step
  [package owner roots active]
  (let [entry (plan-entry package owner)
        blockers (:blockers entry)
        children (into #{} (keep origins/inline-binding-path) blockers)]
    (cond
      (= :authored-source-equivalent (:verification entry))
      (assoc-in package [:closure-baselines owner] entry)

      (seq children)
      (certify-inline-children package owner children roots active)

      (inherited-candidate? blockers)
      (certify-inherited package owner entry roots active)

      (= #{:field-projection-required :ref-scope-required} (set (map :code blockers)))
      (migrate-joint-owner package owner roots)

      (and (seq blockers) (every? #(= :ref-scope-required (:code %)) blockers))
      (with-migrated-bindings package [owner] roots)

      (and (seq blockers) (every? #(= :field-projection-required (:code %)) blockers))
      (with-migrated-forwarding package owner (mapv (comp second :source-path) blockers) roots)

      :else (reject! :unsupported-owner-certificate owner))))


(defn- certify-owner
  [package owner roots active]
  (when (contains? active owner) (reject! :cyclic-source-certificate owner))
  (let [active (conj active owner)
        outcome (try {:package (certify-owner-step package owner roots active)}
                     (catch clojure.lang.ExceptionInfo failure
                       (let [{:keys [code fn-id]} (ex-data failure)]
                         (if (and (= :unverified-source-declaration code) (uuid? fn-id)
                                  (not (contains? (:closure-baselines package) fn-id)))
                           {:dependency fn-id}
                           (throw failure)))))]
    (if-let [dependency (:dependency outcome)]
      (let [updated (certify-owner package dependency roots active)]
        (certify-owner updated owner roots (disj active owner)))
      (:package outcome))))


(defn with-certified-owner
  "Certify one owner and the exact dependencies needed by its proof as one
   immutable transaction. Inherited links require the declaring owner's
   existing forwarding/rename evidence; scoped additions still run the full
   aggregate availability, type and occupancy checks. Any failure returns no
   partially certified package. Cycles and unknown blocker kinds stay blocked."
  [package owner roots]
  (certify-owner package owner roots #{}))
