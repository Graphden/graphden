(ns graphden.input.source-snapshot
  "Offline package-declaration adapter for certified legacy reference uses."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.format :as format]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.migration :as migration]
    [graphden.input.signatures :as signatures]
    [graphden.input.source :as source]
    [graphden.input.source-annotations :as annotations]
    [graphden.input.source-context :as source-context]
    [graphden.input.source-named-types :as named-types]
    [graphden.input.source-primitive :as primitive]
    [graphden.input.source-rename :as rename]
    [graphden.input.source-rich-types :as rich]
    [graphden.input.source-scope :as scope]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check :as check]
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
        ambiguous (into #{} (keep (fn [[id ds]] (when (next ds) id))) by-id)]
    {:prepared (source/with-reference-index (assoc prepared ::rename/ambiguous-type-ids ambiguous))
     :markers (shapes/markers-snapshot)
     :declarations (into {} (map (juxt definition-id identity)) (vals (:defs-by-name prepared)))
     :originals by-id}))


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
        baseline (omit (get declarations owner))]
    (-> package
        (update-in [:originals owner] #(mapv omit %))
        (assoc-in [:declarations owner] baseline)
        (update-in [:prepared :defs-by-name]
                   #(update-vals % (fn [d] (if (= owner (:fn-id d)) baseline d)))))))


(defn- owned-declaration
  [{:keys [fn-id declaration-records] :as v2}]
  (assoc v2 :owner (some #(when (and (= :fn (:kind %)) (= fn-id (:id %))) %) declaration-records)))


(defn- inline-entry
  "The verified parent's round trip compared the child's original body, use-site
   UUID and authored rows. Match its local field or stored list position."
  [package {:keys [fn-id]} edges fragments [path child]]
  (let [[field position] path
        id (:fn-id child)
        entry (owned-declaration child)
        edge (get edges (ids/binding-id fn-id field))]
    (when-not (and (uuid? id) (= fn-id (:fn-id edge)) (= "ref" (:expression-kind edge))
                   (= (ids/binding-id fn-id field) (:id edge))
                   (case (count path)
                     1 (and (= id (:ref-fn-id edge))
                            (empty? (:scope-path edge)) (= [field] (:target-path edge)))
                     2 (list-signature/inline-operand? (get fragments (:ref-fn-id edge)) edge position id)
                     false)
                   (= id (get-in entry [:owner :id]) (get-in package [:declarations id :fn-id]))
                   (uuid? (:input-type-id child)) (= (:input-type-id child) (get-in entry [:owner :input-type-fn-id]))
                   (= 1 (count (get-in entry [:owner :parent-ids])))
                   (empty? (:scoped-inputs child))
                   (every? (fn [{:keys [target-path] :as config}]
                             (annotations/exact? (:owner entry)
                                                 (some #(when (= [(:id %)] target-path) %) (get-in child [:contract :fields]))
                                                 config))
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


(defn- add-closure-entry
  [entries {:keys [owner] :as entry}]
  (let [id (:id owner)]
    (when (contains? entries id) (reject! :conflicting-declaration-record id))
    (assoc entries id entry)))


(defn- closure
  [package roots resolve-named]
  (loop [pending (vec roots) entries {} undeclared []]
    (if-let [id (peek pending)]
      (cond
        (contains? entries id) (recur (pop pending) entries undeclared)
        ;; An overlay or another dependency can request the anonymous UUID
        ;; before its enclosing source has supplied the verified declaration.
        (not (contains? (:originals package) id)) (recur (pop pending) entries (conj undeclared id))
        :else
        (let [added (closure-entries package id resolve-named)
              parents (mapcat #(get-in % [:owner :parent-ids]) added)
              refs (into [] (comp (mapcat :bindings) (keep :ref-fn-id)) added)]
          (recur (into (into (pop pending) parents) refs) (reduce add-closure-entry entries added) undeclared)))
      (if-let [missing (first (remove #(contains? entries %) undeclared))]
        (reject! :missing-original-declaration missing)
        entries))))


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


(defn- snapshot
  [entries markers named]
  (let [{fns :fn fields :slot members :fn-slot bindings :input-binding}
        (reduce merge-record {} (concat (mapcat val (:type-rows named))
                                        (mapcat #(concat (:declaration-records %) (:bindings %)) (vals entries))))
        inputs (into {} (map (fn [{:keys [input-type-id contract]}] [input-type-id (:type contract)])) (vals entries))]
    (context/build-context
      (assoc (dissoc named :type-rows)
             :fns (or fns {}) :fields (or fields {}) :bindings (or bindings {})
             :members (merge (zipmap (keys inputs) (repeat [])) (group-by :fn-id (vals members)))
             :type-bodies inputs
             :source-return-types
             (into {} (keep (fn [[id {:keys [owner source list-implementation]}]]
                              (let [type-id (:return-type-fn-id owner)
                                    return (:return-type source)]
                                (cond
                                  list-implementation [[id type-id] (get-in list-implementation [:root-entry :return])]
                                  (rich/collection-return? type-id return) [[id type-id] return]))))
                   entries)
             :source-field-types (source-field-types entries)
             :source-binding-types
             (into {}
                   (for [{:keys [owner contract configurations]} (vals entries)
                         {:keys [row type target-path type-override-fn-id] :as config} (vals configurations)
                         :let [field (some #(when (= [(:id %)] target-path) %) (:fields contract))]
                         :when (and type-override-fn-id (annotations/exact? owner field config))]
                     [[(:id row) type-override-fn-id] type]))
             :markers markers))))


(defn- root-id
  [context id]
  (let [order (inheritance/declaration-order context id)
        roots (filterv #(empty? (get-in context [:fns % :parent-ids])) order)]
    (when-not (= 1 (count roots)) (reject! :multiple-implementation-roots id))
    (first roots)))


(defn- exact-projection-pin?
  [type-ids contract bindings {:keys [target-path type type-override-fn-id]}]
  (let [row (some #(when (= target-path (:target-path %)) %) bindings)
        field-by-id (into {} (map (juxt :id identity)) (:fields contract))
        target (get field-by-id (first target-path))
        source (get field-by-id (first (:projection-path row)))]
    (and (= "projection" (:expression-kind row)) (= [] (:scope-path row) (:projection-scope-path row))
         (= [(:id target)] target-path) (= [(:id source)] (:projection-path row))
         (uuid? type-override-fn-id) (= type-override-fn-id (:type-override-fn-id row) (:type-id source))
         (= type (:type source)) (= type-override-fn-id (get type-ids type)))))


(defn- check-type-provenance!
  [named-ids type-rows {:keys [source contract owner configurations bindings]}]
  (let [primitives (ids/primitive-fn-ids)
        type-ids (merge primitives named-ids)
        return (:return-type source)]
    ;; A v1 storage primitive may erase an authored rich field or return.
    ;; Only freeze types whose declared forms match their exact row IDs.
    (when-not (every? (fn [{:keys [row target-path] :as config}]
                        (if row
                          (let [field (some #(when (= [(:id %)] target-path) %) (:fields contract))]
                            (and (some #{row} bindings)
                                 (or (annotations/exact? owner field config)
                                     (rich/exact-self-pin? owner field config type-rows))))
                          (exact-projection-pin? type-ids contract bindings config)))
                      (vals configurations))
      (reject! :configuration-evidence-required (:id owner)))
    (when-not (and (or (and (seq (:parent-ids owner)) (nil? return))
                       (and (contains? type-ids return)
                            (= (get type-ids return) (:return-type-fn-id owner)))
                       (rich/collection-return? (:return-type-fn-id owner) return))
                   (every? (fn [{:keys [type type-id declaration]}]
                             (and (or (= type declaration)
                                      (and (map? declaration) (= type (:type declaration))
                                           (every? #{:type :required :description} (keys declaration))))
                                  (or (descriptor/stored-source-type? type type-id named-ids)
                                      (and (uuid? type-id) (= type-id (get named-ids type)))
                                      (rich/callable-field? type-rows type-id type))))
                           (:fields contract)))
      (reject! :rich-type-source-required (:id owner)))))


(defn- rule-type-aliases
  [entries roots]
  (reduce (fn [aliases callback]
            (reduce-kv (fn [aliases alias qualified]
                         (when (and (contains? aliases alias) (not= qualified (get aliases alias)))
                           (reject! :conflicting-rule-type-alias nil))
                         (assoc aliases alias qualified))
                       aliases (:graphden.input/type-aliases (meta callback) {})))
          {}
          (for [id (keys entries)
                callback (vals (select-keys (get roots id) [:return-type-rule :slot-types-rule :nav-types-rule]))]
            callback)))


(defn- verified-context
  [package entries resolve-named roots]
  (let [named (named-types/evidence entries resolve-named (rule-type-aliases entries roots))
        type-rows (into [] (comp (mapcat :declaration-records) (filter #(= :fn (:kind %)))) (vals entries))
        by-type-id (group-by :id type-rows)]
    (doseq [entry (vals entries) :when (nil? (:list-implementation entry))]
      (check-type-provenance! (:alias-ids named) by-type-id entry))
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
     :argument-paths (into {} (map (fn [{:keys [name id]}] [name [id]])) fields)}))


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
            {:keys [row target-path] :as config} (vals configurations)
            :when (and row (not (annotations/exact? owner (some #(when (= [(:id %)] target-path) %) (:fields contract)) config)))]
      (throw (ex-info "Execution requires proof of the callable field-read contract"
                      {:type :input/source-snapshot :code :callable-self-pin-obligation-required :fn-id id
                       :binding-id (:id row) :target-path (:target-path row) :type-override-fn-id (:type-override-fn-id row)})))
    {:context context :options (implementation-options context entries roots)}))


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
          {:keys [context options]} (freeze-closure baseline (into [owner] targets) roots)]
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
  (let [path (:public-path scope-certificate)]
    (when (contains? (:bind proposed) path)
      (reject! :unsupported-scope-source owner))
    (assoc-in proposed [:bind path] expression)))


(defn migrate-binding
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


(defn migrate-forwarded-fields
  "Freeze both free addresses, then verify the exact scoped projection and
   write it into the source proposal. Require the stock type checker and the
   same implementation registry evidence used by other source certificates."
  [{:keys [prepared] :as package} owner fields roots]
  (let [original (plan-entry package owner)
        {:keys [proofs entry]} (source/forwarded-fields-baseline prepared original fields)]
    (when-not (= :authored-source-equivalent (:verification entry))
      (reject! :unverified-forwarded-baseline owner))
    (let [baseline (assoc-in package [:closure-baselines owner] entry)
          {:keys [context options]} (freeze-closure baseline [owner] roots)
          certificates (scope/verify-forwarded-projections context proofs
                                                           #(analysis/analyze-effective-input %1 %2 options))]
      (source/rewrite-forwarded-fields prepared original fields certificates))))


(defn migrate-forwarded-field
  "Single-field convenience form of the atomic forwarding replay."
  [package owner field roots]
  (migrate-forwarded-fields package owner [field] roots))


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
