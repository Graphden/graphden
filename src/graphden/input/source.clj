(ns graphden.input.source
  "Read-only source transition planning over a caller-prepared legacy module.
   Source forms and raw ledgers are private artifacts, not log messages. A
   supported rewrite is not proof of runtime or whole-corpus equivalence."
  (:require
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as types]))


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


(defn- alignment-blockers
  "Preparation adds an empty args map and fn-id, and may extract anonymous
   computations. Only that extraction is a recognized semantic difference."
  [source prepared normalized]
  (let [expected (assoc normalized :args (or (:args normalized) {}))
        actual (dissoc prepared :fn-id)
        identity (ids/fn-id (:namespace source) (:name source))]
    (cond-> []
      (not= identity (:fn-id prepared))
      (conj (blocker source :prepared-identity-mismatch []))
      (not= (dissoc expected :args) (dissoc actual :args))
      (conj (blocker source :prepared-source-mismatch []))
      (not= (:args expected) (:args actual))
      (conj (blocker source :inline-identity-or-source-mapping-required [:args])))))


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


(defn- binding-policy
  [binding]
  (into {} (remove (comp nil? val))
        (select-keys binding [:type-override-fn-id :required :terminal
                              :description :resolver-fn-id :list-append :list-closed])))


(defn- argument-ledger
  "Resolve through the legacy slot walker, including rename and ref scopes.
   Keep the resulting address even when lowering that address is unsupported."
  [context definition rows]
  (let [{:keys [name->id defs-by-name]} context
        own-ref (slots/own-ref definition defs-by-name)
        by-slot (into {} (map (juxt :slot-id identity))
                      (filter #(= :binding (:kind %)) rows))
        names (::ref-index context)]
    (mapv (fn [[arg value]]
            (if (types/own-slot-declaration? value)
              {:source-path [:args arg] :kind :declaration :spec value}
              (let [[owner field] (slots/resolve-slot-owner own-ref arg defs-by-name value)
                    owner-id (get name->id owner)
                    slot-id (ids/slot-id owner-id field)
                    binding (get by-slot slot-id)]
                {:source-path [:args arg]
                 :kind :binding
                 :origin {:fn-id (:fn-id definition)
                          :slot-id slot-id :binding-id (:id binding)}
                 :target {:owner owner :field field
                          :type (or (slots/slot-type-of owner field defs-by-name)
                                    (slots/rename-source-type owner field defs-by-name))}
                 :expression (binding-expression binding names)
                 :policy (binding-policy binding)})))
          (:args definition))))


(defn- source-argument-blockers
  [source]
  (into []
        (mapcat (fn [[arg value]]
                  (when (map? value)
                    (cond-> []
                      (contains? value :as)
                      (conj (blocker source :rename-projection-required [:args arg]))
                      (types/own-slot-declaration? value)
                      (conj (blocker source :own-field-declaration-required [:args arg]))
                      (and (some #(contains? value %) [:value :ref])
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
        problem #(blocker source % source-path)]
    ;; The owner identity is checked separately from the binding's own fn-id:
    ;; binding rows can address free inputs in an arbitrary referenced graph.
    (cond-> []
      (and (= :binding (:kind entry)) (nil? (:binding-id origin)))
      (conj (problem :missing-binding-origin))
      (and (= :binding (:kind entry)) (not (contains? ancestor-ids (:owner target))))
      (conj (problem :ref-scope-required))
      (or (:list-append policy) (:list-closed policy))
      (conj (problem :list-builder-required))
      (:resolver-fn-id policy)
      (conj (problem :resolver-binding-required))
      (some #(contains? policy %) [:type-override-fn-id :required :terminal :description])
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
  (let [ancestors (vec (ancestor-definitions definition context))
        ancestor-rows (mapcat (::records-of context) ancestors)
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
  (let [arguments (argument-ledger context definition rows)
        defs (:defs-by-name context)
        chain (set (slots/chain-of (slots/own-ref definition defs) defs))
        blockers (vec (distinct
                        (concat (source-argument-blockers source)
                                (mapcat #(argument-blockers source % chain) arguments)
                                (inherited-blockers source definition context arguments))))
        bindings (into (array-map)
                       (map (fn [{:keys [source-path expression]}]
                              [(second source-path) expression])) arguments)]
    {:arguments arguments
     :blockers blockers
     :proposed (when (empty? blockers)
                 (cond-> (dissoc source :args)
                   (contains? source :args) (assoc :bind bindings)))}))


(defn- classify
  [source definition context rows]
  (case (definition-kind source)
    :base {:blockers []
           :proposed (cond-> (dissoc source :args)
                       (contains? source :args) (assoc :input (:args source)))}
    :type {:blockers [] :proposed source}
    :composed (composed-plan source definition context rows)))


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
                                ::ref-index (ref-index context))
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
