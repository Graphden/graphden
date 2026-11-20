(ns graphden.input.source
  "Read-only source transition planning over a caller-prepared legacy module.
   Source forms and raw ledgers are private artifacts, not log messages. A
   supported rewrite is not proof of runtime or whole-corpus equivalence."
  (:require
    [clojure.edn :as edn]
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.input.module :as module]
    [graphden.input.source-context :as source-context]
    [graphden.input.source-rename :as rename]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as types]
    [graphden.packages.records.wire :as wire]))


(def ^:private type-keys
  [:type :refine :list :union :map :tuple :variant :marker :fn-type])


(defn- definition-key
  [definition]
  [(or (:namespace definition) "") (:name definition)])


(defn- definition-kind
  [definition]
  (cond
    (or (:parent definition) (:parents definition)) :composed
    (some #(contains? definition %) type-keys) :type
    :else :base))


(defn- blocker
  [source code path]
  (cond-> {:code code :definition (definition-key source) :source-path path}
    (:source-file source) (assoc :file (:source-file source))
    (:source-line source) (assoc :line (:source-line source))))


(defn- normalize-source
  [source context]
  (rebase/rewrite-references
    source #(parse/normalize-ref % (:name->id context) (:namespace source))))


(defn- inline-alignment-blocker
  [source field body]
  (let [[arg spec] (when (map? (:args body)) (first (:args body)))]
    (if (and (:parent body) (map? (:args body)) (= 1 (count (:args body))) (map? spec) (= #{:ref} (set (keys spec))))
      (blocker source :inline-binding-origin-required [:args field :args arg])
      (blocker source :inline-identity-or-source-mapping-required [:args field]))))


(defn- alignment-blockers
  "Preparation adds an empty args map and fn-id, and may extract anonymous
   computations. Only that extraction is a recognized semantic difference."
  [source prepared normalized]
  (let [args (or (:args normalized) {})
        prepared-args (:args prepared)
        fields (distinct (concat (keys args) (keys prepared-args)))
        composed? (= :composed (definition-kind source))
        blockers (keep (fn [field]
                         (let [body (get args field)
                               expected (if (and composed? (format/legacy-inline? body))
                                          (:name (parse/inline-declaration body [(:name source) field]
                                                                           (:namespace source)))
                                          body)]
                           (when-not (and (contains? args field) (contains? prepared-args field)
                                          (= expected (get prepared-args field)))
                             (inline-alignment-blocker source field body))))
                       fields)]
    (cond-> (vec blockers)
      (not= (ids/fn-id (:namespace source) (:name source)) (:fn-id prepared))
      (conj (blocker source :prepared-identity-mismatch []))
      (not= (dissoc normalized :args) (dissoc prepared :args :fn-id))
      (conj (blocker source :prepared-source-mismatch [])))))


(defn- parse-one
  [context definition]
  (parse/parse-prepared-module (assoc context :fn-defs [definition])))


(defn- ref-index
  [context]
  (into {} (keep (fn [[ref id]] (when (qualified-keyword? ref) [id ref])))
        (:name->id context)))


(defn- binding-expression
  [binding names]
  (cond
    (:ref-fn-id binding) [:ref (get names (:ref-fn-id binding))]
    (:value-present binding) [:value (:value binding)]
    :else nil))


(def ^:private binding-bookkeeping
  #{:kind :id :fn-id :slot-id :org-id :value :value-present :ref-fn-id})


(def ^:private binding-policy-columns
  #{:type-override-fn-id :required :terminal :description :resolver-fn-id
    :list-append :list-closed :override-kind})


(defn- binding-policy
  "Retain every non-bookkeeping column. Known absent policies are omitted;
   unknown columns survive even when nil so new semantics cannot go unnoticed."
  [binding]
  (into {}
        (remove (fn [[column value]]
                  (or (contains? binding-bookkeeping column)
                      (and (contains? binding-policy-columns column) (nil? value)))))
        binding))


(defn- argument-ledger
  "Resolve through the legacy slot walker, including rename and ref scopes.
   Keep the resulting address even when lowering that address is unsupported."
  [context source definition rows]
  (let [{:keys [name->id defs-by-name]} context
        own-ref (slots/own-ref definition defs-by-name)
        by-slot (into {} (map (juxt :slot-id identity))
                      (filter #(= :binding (:kind %)) rows))
        own-sources (into {} (keep #(when (and (= :slot (:kind %)) (:source-slot-id %))
                                      [(:id %) (:source-slot-id %)])) rows)
        grouped (group-by :kind rows)
        own (some #(when (= (:fn-id definition) (:id %)) %) (:fn grouped))
        names (::ref-index context)]
    (mapv (fn [[arg value]]
            (if (types/own-slot-declaration? value)
              (let [source-slot-id (get own-sources (ids/slot-id (:fn-id definition) arg))]
                (cond-> {:source-path [:args arg] :kind :declaration :spec value}
                  source-slot-id (assoc :source-slot-id source-slot-id)))
              (let [[owner field] (slots/resolve-slot-owner own-ref arg defs-by-name value)
                    owner-id (get name->id owner)
                    slot-id (ids/slot-id owner-id field)
                    binding (get by-slot slot-id)
                    body (get-in source [:args arg])
                    target-type (or (slots/slot-type-of owner field defs-by-name)
                                    (slots/rename-source-type owner field defs-by-name))
                    rename (rename/origin own grouped arg body)
                    ;; Separate a primitive inherited field from an erased rich
                    ;; declaration or another same-named field in the ref tree.
                    rename (when (and rename (= arg field) (= slot-id (:field-id rename))
                                      (= target-type (get-in rename [:declaration :type]))
                                      (= (:id binding) (ids/binding-id (:id own) slot-id))
                                      (empty? (binding-policy binding)))
                             rename)
                    inline-id (when (and (format/legacy-inline? body) (keyword? value))
                                (ids/fn-id (:namespace source) value))]
                (cond-> {:source-path [:args arg]
                         :kind :binding
                         :origin {:fn-id (:fn-id definition)
                                  :slot-id slot-id :binding-id (:id binding)}
                         :target {:owner owner :field field
                                  :type target-type}
                         :expression (if (and inline-id (= inline-id (:ref-fn-id binding)))
                                       [:ref body]
                                       (binding-expression binding names))
                         :policy (binding-policy binding)}
                  rename (assoc :rename rename :expression [:project [(:alias rename)]])))))
          (:args definition))))


(defn- typed-binding?
  [value]
  (and (map? value) (format/configuration-type? (:type value))
       (contains? #{#{:type :ref} #{:type :value}} (set (keys value)))))


(defn- source-argument-blockers
  [source renames]
  (into []
        (mapcat (fn [[arg value]]
                  (when (map? value)
                    (cond-> []
                      (contains? value :secret-path)
                      (conj (blocker source :secret-path-binding-required [:args arg]))
                      (and (contains? value :as) (not (contains? renames arg)))
                      (conj (blocker source :rename-projection-required [:args arg]))
                      (and (types/own-slot-declaration? value)
                           (not (source-context/own-field-declaration? value)))
                      (conj (blocker source :own-field-declaration-required [:args arg]))
                      (and (not (typed-binding? value)) (some #(contains? value %) [:value :ref])
                           (seq (dissoc value :value :ref)))
                      (conj (blocker source :binding-annotations-required [:args arg]))))))
        (:args source)))


(defn- ancestor-definitions
  [definition context]
  (let [defs (:defs-by-name context)]
    (keep #(get defs %) (rest (slots/chain-of (slots/own-ref definition defs) defs)))))


(defn- argument-blockers
  [source entry ancestor-ids]
  (let [{:keys [source-path origin expression policy target]} entry
        problem #(blocker source % source-path)
        typed? (typed-binding? (get-in source source-path))
        unknown-columns (vec (sort (remove binding-policy-columns (keys policy))))]
    ;; The owner identity is checked separately from the binding's own fn-id:
    ;; binding rows can address free inputs in an arbitrary referenced graph.
    (cond-> []
      (and (format/legacy-inline? (get-in source source-path))
           (not= [:ref (get-in source source-path)] expression))
      (conj (problem :inline-reference-mismatch))
      (:source-slot-id entry)
      (conj (problem :field-projection-required))
      (and (= :binding (:kind entry)) (nil? (:binding-id origin)))
      (conj (problem :missing-binding-origin))
      (and (= :binding (:kind entry)) (not (contains? ancestor-ids (:owner target))))
      (conj (problem :ref-scope-required))
      (or (:list-append policy) (:list-closed policy))
      (conj (problem :list-builder-required))
      (:resolver-fn-id policy)
      (conj (problem :resolver-binding-required))
      (contains? policy :override-kind)
      (conj (problem :legacy-override-kind-required))
      (seq unknown-columns)
      (conj (assoc (problem :unsupported-binding-columns) :columns unknown-columns))
      (and typed? (not (uuid? (:type-override-fn-id policy))))
      (conj (problem :unresolved-binding-type))
      (or (and (contains? policy :type-override-fn-id) (not typed?))
          (some #(contains? policy %) [:required :terminal :description]))
      (conj (problem :binding-metadata-required))
      (and (= :binding (:kind entry)) (nil? expression)
           (not (or (:list-append policy) (:list-closed policy))))
      (conj (problem :non-value-binding-required))
      (and (= :ref (first expression)) (nil? (second expression)))
      (conj (problem :missing-reference-identity)))))


(defn- inherited-blockers
  "Conservatively block ancestor input transformations and occupied targets.
   No attempt is made to reimplement legacy rename/capture compilation."
  [source definition context arguments]
  (let [ancestor-defs (vec (ancestor-definitions definition context))
        ancestor-rows (mapcat (::records-of context) ancestor-defs)
        occupied (into #{} (comp (filter #(and (= :binding (:kind %))
                                               (or (:value-present %) (:ref-fn-id %)
                                                   (:terminal %))))
                                 (map :slot-id)) ancestor-rows)
        transformed? (some #(and (= :slot (:kind %)) (:source-slot-id %)) ancestor-rows)]
    (cond-> (into []
                  (keep (fn [{:keys [origin source-path]}]
                          (when (contains? occupied (:slot-id origin))
                            (blocker source :inherited-occupancy-required source-path))))
                  arguments)
      transformed? (conj (blocker source :inherited-projection-required [:args])))))


(defn- composed-plan
  [source definition context rows]
  (let [arguments (argument-ledger context source definition rows)
        defs (:defs-by-name context)
        chain (set (slots/chain-of (slots/own-ref definition defs) defs))
        aliases (keep :rename arguments)
        ;; Mixed own declarations and simultaneous renames have independent
        ;; slot-position/collision rules. Keep their existing blockers.
        collision? (when-let [exposed (:alias (first aliases))]
                     (some #(contains? (:args %) exposed) (cons definition (ancestor-definitions definition context))))
        rename-fields (when (and (not collision?) (= 1 (count aliases))
                                 (not-any? #(or (= :declaration (:kind %)) (seq (:policy %))) arguments))
                        (into #{} (keep #(when (:rename %) (second (:source-path %)))) arguments))
        blockers (vec (distinct
                        (concat (when collision? [(blocker source :rename-field-collision [:args])])
                                (source-argument-blockers source rename-fields)
                                (mapcat #(argument-blockers source % chain) arguments)
                                (inherited-blockers source definition context arguments))))
        declarations (into (array-map) (filter (comp types/own-slot-declaration? val)) (:args source))
        declarations (into declarations (map (juxt :alias :declaration)) aliases)
        binds (filter #(= :binding (:kind %)) arguments)
        configuration (into {} (keep (fn [[field spec]]
                                       (when (typed-binding? spec) [[field] (select-keys spec [:type])])))
                            (:args source))
        bindings (into (array-map)
                       (map (fn [{:keys [source-path expression]}]
                              [(second source-path) expression])) binds)]
    {:arguments arguments
     :blockers blockers
     :proposed (when (empty? blockers)
                 (cond-> (dissoc source :args)
                   (seq declarations) (assoc :input declarations)
                   (or (seq bindings) (and (contains? source :args) (empty? declarations)))
                   (assoc :bind bindings)
                   (seq configuration) (assoc :configure configuration)))}))


(defn- lower-inline-argument
  [source prepared context {:keys [source-path expression] :as argument}]
  (let [body (get-in source source-path)]
    (if-not (and (format/legacy-inline? body) (:args body) (= [:ref body] expression))
      argument
      (let [name (get-in prepared source-path)
            [child & duplicates] (get (::prepared context) [(or (:namespace source) "") name])]
        (if (or (nil? child) (seq duplicates))
          (assoc argument :blockers [(blocker source :missing-inline-declaration source-path)])
          (let [child-source (assoc body :name name :namespace (:namespace source))
                plan (composed-plan child-source child context ((::records-of context) child))
                [field raw] (first (:args body))
                [op payload] (get-in plan [:proposed :bind field])
                bind (if (= :ref op) [:ref raw] (if (map? raw) [:value payload] raw))
                v2 {:parent (:parent body) :bind {field bind}}]
            (cond
              (seq (:blockers plan))
              (assoc argument :blockers
                     (mapv #(merge % (blocker source (:code %) (into source-path (:source-path %)))) (:blockers plan)))
              (not (and (#{:ref :value} op) (migration/same-edn? body (format/inline-origin v2))))
              (assoc argument :blockers [(blocker source :inline-binding-origin-required source-path)])
              :else (assoc argument :expression [:ref v2]))))))))


(defn- lower-inlines
  [source definition context plan]
  (if-not (some #(and (:args %) (format/legacy-inline? %)) (vals (:args source)))
    plan
    (let [args (mapv #(lower-inline-argument source definition context %) (:arguments plan))
          blockers (into (:blockers plan) (mapcat :blockers) args)]
      (assoc plan :arguments args :blockers blockers
             :proposed (when (empty? blockers)
                         (reduce (fn [proposal {:keys [source-path expression]}]
                                   (if expression (assoc-in proposal [:bind (second source-path)] expression) proposal))
                                 (:proposed plan) args))))))


(defn- classify
  [source definition context rows]
  (case (definition-kind source)
    :base {:blockers []
           :proposed (cond-> (dissoc source :args)
                       (contains? source :args) (assoc :input (:args source)))}
    :type {:blockers [] :proposed source}
    :composed (lower-inlines source definition context (composed-plan source definition context rows))))


(defn- record-origins
  [rows]
  (mapv #(select-keys % [:kind :id :fn-id :slot-id :source-slot-id :binding-id :position]) rows))


(defn- plan-definition
  [source prepared context]
  (try
    (let [normalized (normalize-source source context)
          alignment (alignment-blockers source prepared normalized)
          rows ((::records-of context) prepared)
          classified (when (empty? alignment) (classify source prepared context rows))
          blockers (into alignment (:blockers classified))]
      (cond-> {:source source :identity (:fn-id prepared)
               :kind (definition-kind source)
               :status (if (seq blockers) :blocked :supported)
               :blockers blockers
               :legacy {:records rows :rich-input (:args source)
                        :arguments (:arguments classified)}
               :origins (record-origins rows)}
        (empty? blockers) (assoc :proposed (:proposed classified))))
    (catch Exception e
      {:source source :status :blocked
       :blockers [(assoc (blocker source :legacy-analysis-failed [])
                         :cause-type (:type (ex-data e)))]})))


(defn plan-module
  "Plan source-only rewrites from ORIGINAL forms and parse/prepare-module's
   complete context (:fn-defs, :name->id, :defs-by-name). The caller performs
   preparation: it registers marker types and is not a pure operation.

   Every original namespace/name must match exactly one prepared definition.
   Unsupported definitions have blockers and no :proposed form. :legacy and
   :source deliberately retain literal data and rich declarations for offline
   verification; log only top-level :blockers/:counts, never the whole result.

   This checks legacy interpretation, not new-loader/runtime equivalence.
   No files, storage, registries or runtime graph are changed. Preparation
   failures before this call remain the caller's responsibility."
  [sources context]
  (let [originals (group-by definition-key sources)
        prepared (group-by definition-key (:fn-defs context))
        planning-context (assoc context
                                ::records-of (memoize (partial parse-one context))
                                ::ref-index (ref-index context)
                                ::prepared prepared)
        definitions
        (mapv (fn [source]
                (let [key (definition-key source)
                      matches (get prepared key)
                      code (cond
                             (not (keyword? (:name source))) :invalid-definition-identity
                             (some #(contains? source %) [:input :bind :configure]) :already-structured-source
                             (> (count (get originals key)) 1) :duplicate-source-definition
                             (empty? matches) :missing-prepared-definition
                             (> (count matches) 1) :duplicate-prepared-definition)]
                  (if code
                    {:source source :status :blocked :blockers [(blocker source code [])]}
                    (plan-definition source (first matches) planning-context)))) sources)]
    {:format :graphden/structured-input-source-plan-v1
     :definitions definitions
     :blockers (vec (mapcat :blockers definitions))
     :counts (merge {:supported 0 :blocked 0} (frequencies (map :status definitions)))}))


(defn- binding-meaning
  [record]
  (cond-> (select-keys record [:id :fn-id :slot-id :value-present])
    (:type-override-fn-id record) (assoc :type-override-fn-id (:type-override-fn-id record))
    (:value-present record) (assoc :value (:value record))
    (:ref-fn-id record) (assoc :ref-fn-id (:ref-fn-id record))))


(defn- authored-meaning
  [declaration context bindings]
  {:declaration (dissoc declaration :args :fn-id :namespace)
   :base-args (when (= :base (definition-kind declaration)) (or (:args declaration) {}))
   :context (select-keys context [:fn-id :contract :declaration-records :origin-ledger])
   :bindings (mapv binding-meaning (sort-by :id bindings))})


(defn- read-emitted
  [form]
  (edn/read-string {:readers wire/wire-readers}
                   (migration/stable-edn (wire/encode-unreadable-kws form))))


(defn- legacy-inline-meaning
  [environment source prepared {:keys [source-path origin]}]
  (let [body (get-in source source-path)]
    (when (format/legacy-inline? body)
      (let [id (ids/fn-id (:namespace source) (get-in prepared source-path))
            child (get-in environment [:definition-by-id id])]
        (when-not child
          (throw (ex-info "Missing legacy inline declaration"
                          {:code :missing-inline-declaration :path source-path})))
        (let [records ((:records-of environment) child)
              bindings (filterv #(= :binding (:kind %)) records)
              ctx (source-context/declaration-context environment child records bindings)]
          [[(:slot-id origin)] [body (authored-meaning child ctx bindings)]])))))


(defn- verify-inline-children!
  [environment {:keys [source legacy]} prepared v2]
  (let [expected (into {} (keep #(legacy-inline-meaning environment source prepared %)) (:arguments legacy))
        actual (into {} (map (fn [[path child]]
                               (let [bindings (mapv migration/legacy-binding (:bindings child))]
                                 [path [(format/inline-origin (:inline-body child))
                                        (authored-meaning (dissoc (:source child) :bind) child bindings)]])))
                     (:inline-definitions v2))]
    (when-not (migration/same-edn? expected actual)
      (throw (ex-info "Inline source identities or declarations changed"
                      {:type :input/source-verification :code :inline-declaration-mismatch})))))


(defn- inline-binding-origins
  [environment definition]
  (let [id (ids/fn-id (:namespace definition) (:name definition))]
    (when-let [child (get-in environment [:definition-by-id id])]
      (filterv #(= :binding (:kind %)) ((:records-of environment) child)))))


(defn- verify-definition
  [environment definition]
  (let [{definition-id :identity :keys [source proposed legacy]} definition
        prepared (get-in environment [:definition-by-id definition-id])
        records (:records legacy)
        bindings (filterv #(= :binding (:kind %)) records)
        legacy-context (source-context/declaration-context environment prepared records bindings)
        context-for (fn [v2]
                      (let [[lowered rows] (source-context/lower-declarations environment v2)]
                        (source-context/declaration-context environment lowered rows
                                                            (if (= (:name lowered) (:name source))
                                                              bindings
                                                              (inline-binding-origins environment lowered)))))
        options {:context-for context-for
                 :normalize-inline-parent
                 (fn [ns-id parent]
                   (let [names (get-in environment [:context :name->id])
                         ref (parse/normalize-ref parent names ns-id)]
                     (when (uuid? (get names ref)) ref)))}
        proposed-module {:input-format 2 :namespace (:namespace source) :fns [proposed]}
        parsed (module/parse-module-v2 proposed-module options)
        emitted (module/emit-module-v2 parsed options)
        reread (module/parse-module-v2 (read-emitted emitted) options)
        v2 (first (:definitions reread))
        [lowered] (source-context/lower-declarations environment (assoc (:source v2) :namespace (:namespace v2)))
        legacy-meaning (assoc (authored-meaning prepared legacy-context bindings)
                              :asserted-types (into {} (keep (fn [[field spec]]
                                                               (when (typed-binding? spec) [[field] (:type spec)])))
                                                    (when (= :composed (:kind definition)) (:args prepared))))
        v2-meaning (assoc (authored-meaning lowered v2 (mapv (partial rename/legacy-binding (:declaration-records v2)) (:bindings v2)))
                          :asserted-types (into {} (map (fn [[path assertion]] [path (:type assertion)]))
                                                (:configurations v2)))]
    (verify-inline-children! environment definition prepared v2)
    (when-not (migration/same-edn? (dissoc parsed :definitions) (dissoc reread :definitions))
      (throw (ex-info "Structured source records changed on round trip"
                      {:type :input/source-verification :code :v2-round-trip-mismatch})))
    (when-not (migration/same-edn? legacy-meaning v2-meaning)
      (throw (ex-info "Structured source changed authored meaning"
                      {:type :input/source-verification :code :legacy-v2-meaning-mismatch})))
    (assoc definition :verification :authored-source-equivalent :v2 v2)))


(defn verify-module
  "Verify each supported plan entry with a source-derived v2 resolver.
   Compares declared field contracts, row identities, authored metadata and
   whole-value/ref binding meaning after v2 parse, emit, EDN read and reparse.
   Ancestor declarations come from the frozen prepared context. Ref execution,
   residual interfaces, whole-corpus and database proofs are separate stages.

   Keep the returned v2 rows and contracts private like the source plan.
   Use :counts, :blockers and per-definition :verification for reports."
  [plan context]
  (let [environment (source-context/environment context)
        definitions
        (mapv (fn [{:keys [source status] :as definition}]
                (if (= :supported status)
                  (try
                    (verify-definition environment definition)
                    (catch Exception failure
                      (let [{:keys [type code path]} (ex-data failure)]
                        (-> definition
                            (dissoc :proposed :verification :v2)
                            (assoc :status :blocked
                                   :blockers [(assoc (blocker source (or code :v2-verification-failed) (or path []))
                                                     :cause-type type)])))))
                  definition))
              (:definitions plan))]
    (assoc plan :definitions definitions
           :blockers (vec (mapcat :blockers definitions))
           :counts (merge {:supported 0 :blocked 0} (frequencies (map :status definitions))))))
