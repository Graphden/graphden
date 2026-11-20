(ns graphden.input.source
  "Read-only source transition planning over a caller-prepared legacy module.
   Source forms and raw ledgers are private artifacts, not log messages. A
   supported rewrite is not proof of runtime or whole-corpus equivalence."
  (:require
    [clojure.edn :as edn]
    [graphden.input.format :as format]
    [graphden.input.list-positions :as positions]
    [graphden.input.migration :as migration]
    [graphden.input.module :as module]
    [graphden.input.source-context :as source-context]
    [graphden.input.source-list :as source-list]
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


(defn- prepared-inline-ref
  [value]
  (if (map? value) (:ref value) value))


(defn- expected-inline-argument
  [source field form body]
  (let [name (:name (parse/inline-declaration body [(:name source) field] (:namespace source)))]
    (if (contains? form :type) {:ref name :type (:type form)} name)))


(defn- alignment-blockers
  "Preparation adds an empty args map and fn-id, and may extract anonymous
   computations. Only that extraction is a recognized semantic difference."
  [source prepared normalized]
  (let [args (or (:args normalized) {})
        prepared-args (:args prepared)
        fields (distinct (concat (keys args) (keys prepared-args)))
        composed? (= :composed (definition-kind source))
        expanded (delay (:args (first (parse/expand-inline-anons-in-module [normalized]))))
        blockers (keep (fn [field]
                         (let [form (get args field)
                               body (when composed? (format/inline-binding-body form))
                               expected (cond
                                          body (expected-inline-argument source field form body)
                                          (and composed? (source-list/has-inline-items? form)) (get @expanded field)
                                          :else form)]
                           (when-not (and (contains? args field) (contains? prepared-args field)
                                          (= expected (get prepared-args field)))
                             (blocker source :inline-identity-or-source-mapping-required [:args field]))))
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


(declare ^:private inline-list-item)


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
                (cond-> {:source-path [:args arg] :kind :declaration :spec (get-in source [:args arg])}
                  source-slot-id (assoc :source-slot-id source-slot-id)))
              (let [[owner field] (slots/resolve-slot-owner own-ref arg defs-by-name value)
                    owner-id (get name->id owner)
                    slot-id (ids/slot-id owner-id field)
                    binding (get by-slot slot-id)
                    body (get-in source [:args arg])
                    target-type (or (slots/slot-type-of owner field defs-by-name)
                                    (slots/rename-source-type owner field defs-by-name))
                    builder (when (or (:list-append binding) (:list-closed binding))
                              (source-list/trace (assoc context :inline-list-item inline-list-item)
                                                 (::records-of context) (::original-of context)
                                                 definition arg slot-id (:id binding)))
                    rename (rename/origin context definition own grouped arg body)
                    ;; Separate a primitive inherited field from an erased rich
                    ;; declaration or another same-named field in the ref tree.
                    rename (when (and rename (= arg field) (= slot-id (:field-id rename))
                                      (= (:id binding) (ids/binding-id (:id own) slot-id))
                                      (= (cond-> {} (:pin-id rename) (assoc :type-override-fn-id (:pin-id rename)))
                                         (binding-policy binding)))
                             rename)
                    inline-body (format/inline-binding-body body)
                    inline-ref (prepared-inline-ref value)
                    inline-id (when (and inline-body (keyword? inline-ref))
                                (ids/fn-id (:namespace source) inline-ref))]
                (cond-> {:source-path [:args arg]
                         :kind :binding
                         :origin {:fn-id (:fn-id definition)
                                  :slot-id slot-id :binding-id (:id binding)}
                         :target {:owner owner :field field
                                  :type target-type}
                         :expression (if (and inline-id (= inline-id (:ref-fn-id binding)))
                                       [:ref inline-body]
                                       (binding-expression binding names))
                         :policy (binding-policy binding)}
                  (some :source-id (:items (peek (:fragments builder))))
                  (assoc :list-inputs (positions/authored-inputs (:id own) grouped name->id body))
                  builder (merge {:list-builder (dissoc builder :inline-items)}
                                 (source-list/contribution-expression builder body (:namespace source) name->id))
                  rename (assoc :rename rename :expression [:project [(:alias rename)]])))))
          (:args definition))))


(defn- typed-binding?
  [value]
  (and (map? value) (format/configuration-type? (:type value))
       (or (contains? #{#{:type :ref} #{:type :value}} (set (keys value)))
           (format/inline-binding-body value))))


(defn- field-assertions
  [definition arguments]
  (into {} (keep (fn [{:keys [rename source-path]}]
                   (let [spec (get-in definition source-path)]
                     (when (or (:pin-id rename) (typed-binding? spec))
                       [[(second source-path)] (select-keys spec [:type])])))) arguments))


(defn- authored-own-field?
  [value]
  (and (types/own-slot-declaration? value) (not (format/inline-binding-body value))))


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
                      (and (authored-own-field? value) (not (source-context/own-field-declaration? value)))
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
        raw (get-in source source-path)
        typed? (or (get-in entry [:rename :pin-id]) (typed-binding? raw))
        inline-body (format/inline-binding-body raw)
        unknown-columns (vec (sort (remove binding-policy-columns (keys policy))))]
    ;; The owner identity is checked separately from the binding's own fn-id:
    ;; binding rows can address free inputs in an arbitrary referenced graph.
    (cond-> (vec (get-in entry [:list-builder :inline-blockers]))
      (and (source-list/has-inline-items? raw) (not (:list-builder entry)))
      (conj (problem :inline-list-item-origin-required))
      (and inline-body (not= [:ref inline-body] expression))
      (conj (problem :inline-reference-mismatch))
      (:source-slot-id entry)
      (conj (problem :field-projection-required))
      (and (= :binding (:kind entry)) (nil? (:binding-id origin)))
      (conj (problem :missing-binding-origin))
      (and (= :binding (:kind entry)) (not (contains? ancestor-ids (:owner target))))
      (conj (problem :ref-scope-required))
      (and (or (:list-append policy) (:list-closed policy)) (not= :list-builder (first expression)))
      (conj (problem :list-builder-required))
      (get-in entry [:list-builder :record-blocker])
      (conj (problem (get-in entry [:list-builder :record-blocker])))
      (and (get-in entry [:list-builder :blocker]) (not (seq (get-in entry [:list-builder :inline-blockers]))))
      (conj (problem (get-in entry [:list-builder :blocker])))
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


(defn- local-arguments
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
                                 (not-any? #(or (= :declaration (:kind %))
                                                (and (not (:rename %)) (not= :list-builder (first (:expression %))) (seq (:policy %)))) arguments))
                        (into #{} (keep #(when (:rename %) (second (:source-path %)))) arguments))
        blockers (concat (when collision? [(blocker source :rename-field-collision [:args])])
                         (source-argument-blockers source rename-fields)
                         (mapcat #(argument-blockers source % chain) arguments))]
    {:arguments arguments :aliases aliases :blockers blockers}))


(defn- inherited-fields
  "A scalar projection occupies its destination and exposes its own stable
   alias slot. Prove each stored link against the declaring definition's
   local source lowering before a descendant can use the alias."
  [context ancestor]
  (let [rows ((::records-of context) ancestor)
        owner-id (ids/fn-id (:namespace ancestor) (:name ancestor))
        owner (some #(when (and (= :fn (:kind %)) (= owner-id (:id %))) %) rows)
        bindings (filter #(= :binding (:kind %)) rows)
        links (into {} (keep #(when (and (= :slot (:kind %)) (:source-slot-id %))
                                [(:id %) (:source-slot-id %)])) rows)
        occupied (into (set (vals links))
                       (comp (filter #(or (:value-present %) (:ref-fn-id %) (:terminal %))) (map :slot-id)) bindings)]
    (cond-> {:owner (:id owner) :parents (:parent-ids owner) :occupied occupied :addresses (mapv :slot-id bindings)}
      (seq links)
      (assoc :proven? (let [original (or ((::original-of context) ancestor) ancestor)
                            {:keys [aliases blockers]} (local-arguments original ancestor context rows)]
                        (and (empty? blockers) (= links (into {} (map (juxt :alias-id :field-id)) aliases))))))))


(defn- shared-implementation?
  [lineage parent-ids]
  (let [owners (set (map :owner lineage))
        roots (filter #(empty? (:parents %)) lineage)
        addresses (mapcat :addresses lineage)]
    (and (= 1 (count roots)) (every? uuid? owners)
         (every? owners parent-ids) (every? owners (mapcat :parents lineage))
         (= (count addresses) (count (set addresses))))))


(defn- inherited-blockers
  [source definition context arguments rows]
  (let [parents (some #(when (= (:id %) (:fn-id definition)) (:parent-ids %)) rows)
        lineage (ancestor-definitions definition context)
        states (mapv (::inherited-fields context) lineage)
        occupied (into #{} (mapcat :occupied) states)
        transformed (filter #(contains? % :proven?) states)
        unproven? (and (seq transformed) (or (not-every? :proven? transformed)
                                             (not (shared-implementation? states parents))))]
    (cond-> (into []
                  (keep (fn [{:keys [origin source-path]}]
                          (when (contains? occupied (:slot-id origin))
                            (blocker source :inherited-occupancy-required source-path))))
                  arguments)
      unproven? (conj (blocker source :inherited-projection-required [:args])))))


(defn- composed-plan
  [source definition context rows]
  (let [{:keys [arguments aliases blockers]} (local-arguments source definition context rows)
        blockers (vec (distinct (concat blockers (inherited-blockers source definition context arguments rows))))
        declarations (into (array-map) (filter (comp authored-own-field? val)) (:args source))
        declarations (into (into declarations (map (juxt :alias :declaration)) aliases) (positions/declarations arguments))
        binds (filter #(= :binding (:kind %)) arguments)
        configuration (field-assertions source arguments)
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


(declare ^:private lower-inlines)


(defn- inline-child-plan
  [body child context]
  (let [source (assoc body :name (:name child) :namespace (:namespace child))
        plan (composed-plan source child context ((::records-of context) child))]
    (lower-inlines source child context plan)))


(defn- prepared-inline-child
  [context ns-id name]
  (let [[child & duplicates] (or (get (::prepared context) [(or ns-id "") name])
                                 (when (keyword? name)
                                   (when-let [visible (get (:defs-by-name context)
                                                           (keyword (or ns-id "") (clojure.core/name name)))]
                                     [visible])))]
    (when-not (seq duplicates) child)))


(defn- inline-list-item
  [context source [field position] raw prepared-item]
  (let [body (format/legacy-inline? raw)
        ref (prepared-inline-ref prepared-item)
        ns-id (:namespace source)
        path (cond-> [:args field] (map? (get-in source [:args field])) (conj :append) true (conj position))
        err (fn [code problems]
              (throw (ex-info "Unverified inline list item"
                              {:type :input/list-builder :code code
                               :inline-blockers (or problems [(blocker source code path)])})))]
    (when-not (and body (keyword? ref)) (err :inline-list-item-origin-required nil))
    (let [normalized (dissoc (normalize-source (assoc raw :namespace ns-id) context) :namespace)
          expected (parse/inline-declaration normalized [(:name source) field position] ns-id)
          child (prepared-inline-child context ns-id ref)]
      (when-not (and (= (:name expected) ref) child) (err :inline-list-item-origin-mismatch nil))
      (let [{:keys [proposed arguments blockers]} (when (contains? raw :args) (inline-child-plan raw child context))
            v2 (if (contains? raw :args) (when proposed (format/migrate-inline raw proposed)) raw)]
        (when (seq blockers)
          (err :inline-list-item-declaration-required
               (mapv #(merge % (blocker source (:code %) (into path (:source-path %)))) blockers)))
        (when-not (and v2 (migration/same-edn? raw (format/inline-origin v2))) (err :inline-binding-origin-required nil))
        {:ref ref :fn-id (:fn-id child) :inline-body v2 :body raw :arguments arguments}))))


(defn- lower-inline-argument
  [source prepared context {:keys [source-path expression] :as argument}]
  (let [body (format/inline-binding-body (get-in source source-path))]
    (if-not (and body (contains? body :args) (= [:ref body] expression))
      argument
      (let [name (prepared-inline-ref (get-in prepared source-path))
            ns-id (:namespace source)
            child (prepared-inline-child context ns-id name)
            problem #(assoc argument :blockers [(blocker source % source-path)])]
        (if (nil? child)
          (problem :missing-inline-declaration)
          (let [{:keys [proposed arguments blockers]} (inline-child-plan body child context)
                v2 (when proposed (format/migrate-inline body proposed))]
            (cond
              (seq blockers)
              (assoc argument :blockers
                     (mapv #(merge % (blocker source (:code %) (into source-path (:source-path %)))) blockers))
              (not (and v2 (migration/same-edn? body (format/inline-origin v2))))
              (problem :inline-binding-origin-required)
              :else (assoc argument :expression [:ref v2] :inline-arguments arguments))))))))


(defn- lower-inlines
  [source definition context plan]
  (if-not (some #(contains? (format/inline-binding-body %) :args) (vals (:args source)))
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
        planning-context (assoc (rename/prepare-context context)
                                ::records-of (memoize (partial parse-one context))
                                ::original-of (fn [definition]
                                                (let [[original & more] (get originals (definition-key definition))]
                                                  (when-not more original)))
                                ::ref-index (ref-index context)
                                ::prepared prepared)
        planning-context (assoc planning-context ::inherited-fields (memoize (partial inherited-fields planning-context)))
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


(defn- legacy-authored-meaning
  [definition context bindings arguments]
  (assoc (authored-meaning definition context bindings)
         :asserted-types (update-vals (field-assertions definition arguments) :type)))


(defn- v2-authored-meaning
  [definition parsed]
  (let [bindings (mapv (partial rename/legacy-binding (:declaration-records parsed)) (:bindings parsed))]
    (assoc (authored-meaning definition parsed bindings)
           :asserted-types (into {} (map (fn [[path assertion]] [path (:type assertion)]))
                                 (:configurations parsed)))))


(defn- read-emitted
  [form]
  (edn/read-string {:readers wire/wire-readers}
                   (migration/stable-edn (wire/encode-unreadable-kws form))))


(declare ^:private legacy-inline-meanings)


(defn- legacy-inline-meaning
  [environment ns-id id body arguments]
  (let [child (get-in environment [:definition-by-id id])]
    (when-not child
      (throw (ex-info "Missing legacy inline declaration"
                      {:code :missing-inline-declaration})))
    (let [records ((:records-of environment) child)
          bindings (filterv #(= :binding (:kind %)) records)
          ctx (source-context/declaration-context environment child records bindings)]
      {:origin body
       :meaning (legacy-authored-meaning child ctx bindings arguments)
       :children (legacy-inline-meanings environment (assoc body :namespace ns-id) child arguments)})))


(defn- legacy-inline-argument
  [environment source prepared {:keys [source-path origin inline-arguments list-inline-items]}]
  (let [ns-id (:namespace source)
        slot-id (:slot-id origin)]
    (if-let [body (format/inline-binding-body (get-in source source-path))]
      (let [id (ids/fn-id ns-id (prepared-inline-ref (get-in prepared source-path)))]
        [[[slot-id] (legacy-inline-meaning environment ns-id id body inline-arguments)]])
      (map (fn [[position {:keys [fn-id body arguments]}]]
             [[slot-id position] (legacy-inline-meaning environment ns-id fn-id body arguments)])
           list-inline-items))))


(defn- legacy-inline-meanings
  [environment source prepared arguments]
  (into {} (mapcat (partial legacy-inline-argument environment source prepared)) arguments))


(defn- v2-inline-meaning
  [[path child]]
  [path {:origin (format/inline-origin (:inline-body child))
         :meaning (v2-authored-meaning (dissoc (:source child) :bind :input :configure) child)
         :children (into {} (map v2-inline-meaning) (:inline-definitions child))}])


(defn- verify-inline-children!
  [environment {:keys [source legacy]} prepared v2]
  (let [expected (legacy-inline-meanings environment source prepared (:arguments legacy))
        actual (into {} (map v2-inline-meaning) (:inline-definitions v2))]
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
        list-traces (source-list/verified-traces (:arguments legacy))
        list-ids (set (map (comp :binding-id peek :fragments) (vals list-traces)))
        ordinary (fn [rows] (remove #(contains? list-ids (:id %)) rows))
        legacy-context (source-context/declaration-context environment prepared records bindings)
        context-for (fn [v2]
                      (let [[lowered rows] (source-context/lower-declarations environment v2)]
                        (if (= (:name lowered) (:name source))
                          (source-list/replay-context
                            (source-context/declaration-context environment lowered rows bindings)
                            list-traces (:arguments legacy) (get-in environment [:context :name->id]))
                          (source-context/declaration-context environment lowered rows
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
        legacy-meaning (assoc (legacy-authored-meaning prepared legacy-context (ordinary bindings) (:arguments legacy))
                              :list-builders list-traces)
        v2-meaning (assoc (v2-authored-meaning lowered (update v2 :bindings ordinary))
                          :list-builders (or (:list-builders v2) {}))]
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
