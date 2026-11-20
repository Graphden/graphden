(ns graphden.input.source-snapshot
  "Offline package-declaration adapter for certified legacy reference uses."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.migration :as migration]
    [graphden.input.source :as source]
    [graphden.input.source-context :as source-context]
    [graphden.input.source-named-types :as named-types]
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
    {:prepared (assoc prepared ::rename/ambiguous-type-ids ambiguous)
     :markers (shapes/markers-snapshot)
     :declarations (into {} (map (juxt definition-id identity)) (vals (:defs-by-name prepared)))
     :originals by-id}))


(defn- plan-entry
  [{:keys [prepared declarations originals]} id]
  (let [[original & more] (get originals id)
        definition (get declarations id)]
    (when-not original (reject! :missing-original-declaration id))
    (when (seq more) (reject! :ambiguous-original-declaration id))
    (when-not (= id (:fn-id definition)) (reject! :missing-prepared-declaration id))
    (let [context (assoc prepared :fn-defs [definition])]
      (first (:definitions (source/verify-module (source/plan-module [original] context) context))))))


(defn- omit-argument
  "Replan the owner without the one flat binding under examination. The
   prepared package had already resolved names and assigned anonymous IDs.
   Update both lookup paths used by the legacy parser and v2 replay."
  [{:keys [declarations] :as package} owner field]
  (let [omit #(update % :args dissoc field)
        baseline (omit (get declarations owner))]
    (-> package
        (update-in [:originals owner] #(mapv omit %))
        (assoc-in [:declarations owner] baseline)
        (update-in [:prepared :defs-by-name]
                   #(update-vals % (fn [d] (if (= owner (:fn-id d)) baseline d)))))))


(defn- owned-declaration
  [{:keys [fn-id declaration-records] :as v2}]
  (assoc v2 :owner (some #(when (and (= :fn (:kind %)) (= fn-id (:id %))) %) declaration-records)))


(defn- direct-inline-entry
  "The verified parent's round trip compared the child's original body, use-site
   UUID and authored rows. Admit a leaf only through its matching local edge."
  [package {:keys [fn-id bindings inline-definitions]}]
  (let [[[path child] & more] (seq inline-definitions)
        id (:fn-id child)
        entry (owned-declaration child)
        edge (some #(when (and (= path (:target-path %)) (empty? (:scope-path %))) %) bindings)]
    (when-not (and (empty? more) (= 1 (count path)) (uuid? id)
                   (= fn-id (:fn-id edge)) (= "ref" (:expression-kind edge)) (= id (:ref-fn-id edge))
                   (= (ids/binding-id fn-id (first path)) (:id edge))
                   (= id (get-in entry [:owner :id]) (get-in package [:declarations id :fn-id]))
                   (uuid? (:input-type-id child)) (= (:input-type-id child) (get-in entry [:owner :input-type-fn-id]))
                   (= 1 (count (get-in entry [:owner :parent-ids])))
                   (every? empty? ((juxt :inline-definitions :list-builders :configurations :scoped-inputs) child)))
      (reject! :unsupported-scope-declaration fn-id))
    (when (contains? (:originals package) id) (reject! :conflicting-inline-declaration id))
    entry))


(defn- closure-entries
  [package id resolve-named]
  (let [{:keys [verification blockers v2]} (plan-entry package id)]
    (when-not (= :authored-source-equivalent verification)
      (throw (ex-info "Unverified dependency in source closure"
                      {:type :input/source-snapshot :code :unverified-source-declaration
                       :fn-id id :blockers (mapv :code blockers)})))
    (when-not (:input-type-id v2) (reject! :unsupported-scope-declaration id))
    (let [generated (mapv (partial list-signature/entry id (get-in package [:prepared :name->id]) resolve-named)
                          (vals (:list-builders v2)))]
      (cond-> (conj generated (owned-declaration v2))
        (seq (:inline-definitions v2)) (conj (direct-inline-entry package v2))))))


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
             :source-return-types (into {} (keep (fn [[id {:keys [owner list-implementation]}]]
                                                   (when list-implementation [[id (:return-type-fn-id owner)] (get-in list-implementation [:root-entry :return])]))) entries)
             :source-field-types (source-field-types entries) :markers markers))))


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
    (when-not (every? #(exact-projection-pin? type-ids contract bindings %) (vals configurations))
      (reject! :configuration-evidence-required (:id owner)))
    (when-not (and (or (and (seq (:parent-ids owner)) (nil? return))
                       (and (contains? type-ids return)
                            (= (get type-ids return) (:return-type-fn-id owner)))
                       (rich/list-return? (:return-type-fn-id owner) return))
                   (every? (fn [{:keys [type type-id declaration]}]
                             (and (or (= type declaration)
                                      (and (map? declaration) (= type (:type declaration))
                                           (every? #{:type :required :description} (keys declaration))))
                                  (or (descriptor/stored-source-type? type type-id named-ids)
                                      (and (uuid? type-id) (= type-id (get named-ids type)))
                                      (rich/callable-field? type-rows type-id type))))
                           (:fields contract)))
      (reject! :rich-type-source-required (:id owner)))))


(defn- verified-context
  [package entries resolve-named]
  (let [named (named-types/evidence entries resolve-named)
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
    ;; Custom rules may consult signatures outside parent/ref edges. A closed
    ;; rule registry is a separate prerequisite for running them offline.
    (when (some entry [:return-type-rule :slot-types-rule :nav-types-rule])
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


(defn freeze-closure
  "Build the immutable stage-4 context and checker options from the original
   package preparation, verified v2 rows, and frozen rich implementation
   entries keyed by base UUID. Standalone custom type rules require a full
   signature view and are rejected here. Include any ref introduced by an overlay in
   the starting function IDs. A closure or type evidence gap is an error."
  [package function-ids roots]
  (let [resolve-named (named-types/lookup package (partial plan-entry package))
        entries (closure package function-ids resolve-named)
        ctx (verified-context package entries resolve-named)]
    {:context ctx :options (implementation-options ctx entries roots)}))


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
          ctx (verified-context package entries resolve-named)
          options (try (implementation-options ctx entries roots)
                       (catch clojure.lang.ExceptionInfo error
                         (if (#{:implementation-signature-required :rule-signature-view-required} (:code (ex-data error)))
                           {:check-expression (named-types/checked-expression ctx check/check-input-binding)
                            :gap (select-keys (ex-data error) [:code :fn-id])}
                           (throw error))))
          graph (analysis/analyze-effective-input ctx owner (dissoc options :gap))
          projections (confirmed-projections owner bindings graph)]
      (cond-> (assoc (select-keys graph [:status :coverage :required-addresses :obligations :diagnostics])
                     :fn-id owner :projections projections)
        (empty? projections) (-> (assoc :status :incomplete) (update :diagnostics conj {:code :no-evaluated-projection}))
        (:gap options) (-> (assoc :status :incomplete) (update :diagnostics conj (:gap options)))))
    (catch clojure.lang.ExceptionInfo error
      (let [data (ex-data error)
            code (or (:code data) (when (= :types/check-failed (:type data)) :types/check-failed))]
        (if code
          {:fn-id owner :status :blocked :diagnostics [(assoc (select-keys data [:fn-id :blockers :address :reason]) :code code)]}
          (throw error))))))


(defn- scope-evidence
  [package owner roots]
  (let [entry (plan-entry package owner)
        [blocker & others] (:blockers entry)
        path (:source-path blocker)]
    (when-not (and (= :ref-scope-required (:code blocker)) (empty? others)
                   (= 2 (count path)) (= :args (first path)))
      (reject! :unsupported-scope-source owner))
    (let [baseline (omit-argument package owner (second path))
          _ (when-not (source-context/has-reference-use? (:prepared baseline) (get-in baseline [:declarations owner]))
              (reject! :missing-reference-use owner))
          ;; The removed value may itself be a reference. Its declaration
          ;; must already belong to the frozen closure before overlay replay.
          targets (keep :ref-fn-id (filter #(= :binding (:kind %)) (get-in entry [:legacy :records])))
          {:keys [context options]} (freeze-closure baseline (into [owner] targets) roots)]
      {:entry entry :baseline baseline
       :certificate (scope/verify-binding entry path context #(analysis/analyze-effective-input %1 %2 options))})))


(defn verify-binding
  "Certify one flat binding with a single ref-scope blocker in the original
   plan. Walk verified parent/ref declarations by UUID in a private baseline.
   roots are frozen registry entries keyed by base function ID, including
   implementation type rules. The existing signature and directional checkers
   analyze both snapshots. Returns evidence; does not promote a source plan."
  [package owner roots]
  (:certificate (scope-evidence package owner roots)))


(defn migrate-binding
  "Write a unique certified reference input as a public path key in :bind. Verify
   the resulting v2 rows against the original package's legacy authored rows.
   Missing type evidence, unavailable uses and ambiguous occurrences refuse."
  [package owner roots]
  (let [{:keys [entry baseline certificate]} (scope-evidence package owner roots)
        {:keys [source-path public-path]} certificate
        arguments (mapv #(cond-> % (= source-path (:source-path %)) (assoc :scope-certificate certificate))
                        (get-in entry [:legacy :arguments]))
        arg (some #(when (:scope-certificate %) %) arguments)
        proposed (:proposed (plan-entry baseline owner))]
    (when-not (and proposed arg (not (contains? (:bind proposed) public-path))) (reject! :unsupported-scope-source owner))
    (let [candidate (-> entry (assoc :status :supported :blockers []
                                     :proposed (assoc-in proposed [:bind public-path] (:expression arg)))
                        (assoc-in [:legacy :arguments] arguments))]
      (first (:definitions (source/verify-module {:definitions [candidate]} (:prepared package)))))))
