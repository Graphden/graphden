(ns graphden.input.source-list
  "Prove list constructor origins against the prepared package. This offline
   adapter does not activate a runtime or publish generated declarations."
  (:require
    [graphden.input.list-builder :as builder]
    [graphden.input.list-positions :as positions]
    [graphden.input.list-records :as list-records]
    [graphden.input.migration :as migration]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as types]))


(defn- reject!
  [code]
  (throw (ex-info "Unverified list construction" {:type :input/list-builder :code code})))


(defn- ordered-ancestry
  [definition defs]
  (loop [current definition seen #{} chain ()]
    (let [id (:fn-id current)
          parents (concat (when-let [p (:parent current)] [p]) (:parents current))]
      (when-not (and (uuid? id) (not (contains? seen id)) (<= (count parents) 1))
        (reject! :list-parent-chain-required))
      (if (seq parents)
        (recur (get defs (first parents)) (conj seen id) (conj chain current))
        (conj chain current)))))


(defn field-names
  "Resolve ambiguous bare item refs in their authoring namespace. Only direct
   ref positions participate; literal payloads remain opaque."
  [names module-ns source]
  (reduce (fn [resolved item]
            (let [ref (if (map? item) (:ref item) item)]
              (if (and (keyword? ref) (= types/ambiguous-name (get resolved ref)))
                (assoc resolved ref (get names (parse/normalize-ref ref names module-ns)))
                resolved))) names (if (vector? source) source (:append source))))


(defn- contribution
  [definition records original-for name->id target field]
  (let [rows (group-by :kind records)
        owner (:fn-id definition)
        matching (filter #(and (= owner (:fn-id %)) (= target (:slot-id %))) (:binding rows))]
    (when (some #(and (:source-slot-id %) (or (= target (:source-slot-id %)) (= target (:id %))))
                (:slot rows))
      (reject! :list-projection-required))
    (when-let [[binding & more] (seq matching)]
      (when (seq more) (reject! :ambiguous-list-binding))
      (let [original (original-for definition)
            items (filter #(= (:id binding) (:binding-id %)) (:binding-list-item rows))]
        (when-not (contains? (:args original) field) (reject! :list-source-origin-required))
        (let [form (get-in original [:args field])
              normalize (fn [items]
                          (mapv #(if (and (positions/marker? %) (contains? % :type))
                                   (update % :type rebase/rewrite-type-references
                                           (fn [ref] (parse/normalize-ref ref name->id (:namespace original))))
                                   %) items))
              prepared (cond (vector? form) (normalize form)
                             (vector? (:append form)) (update form :append normalize)
                             :else form)
              fragment (builder/source-fragment binding items prepared (field-names name->id (:namespace original) form))
              anchors (when (some :source-id (:items fragment)) (positions/inputs owner rows name->id))]
          (when-not (every? (set (map :id (vals anchors))) (keep :source-id (:items fragment)))
            (reject! :positional-list-field-mismatch))
          fragment)))))


(defn- materialize
  [trace field name->id]
  (try
    (let [fragments (:fragments trace)
          records (list-records/materialize-chain fragments field name->id)
          owners (mapv :owner-id fragments)
          decoded (list-records/recover-chain owners (:target-id (first fragments)) field name->id records)]
      (when-not (migration/same-edn? trace decoded) (reject! :list-declaration-mismatch))
      (assoc trace :records records :field field))
    (catch clojure.lang.ExceptionInfo error
      (if (= :input/list-builder (:type (ex-data error)))
        (assoc trace :record-blocker (:code (ex-data error)))
        (throw error)))))


(defn contribution-expression
  "Use the authored spelling for checked contributions. Retain only
   reference aliases actually used by this field for the CST replay."
  [trace source module-ns name->id]
  (when (:records trace)
    (let [names (field-names name->id module-ns source)
          form (builder/source-form source names)
          refs (keep (fn [[op payload]] (when (= :ref op) payload)) (get-in form [1 :items]))]
      {:expression form :list-ref-names (select-keys names refs)})))


(defn verified-traces
  [arguments]
  (into {} (keep (fn [{:keys [list-builder origin]}]
                   (when (:records list-builder) [[(:slot-id origin)] list-builder]))) arguments))


(defn replay-context
  [context traces arguments type-names]
  (assoc context
         :list-type-names type-names
         :name->id (into (:name->id context) (mapcat :list-ref-names) arguments)
         :list-history (update-vals traces #(vec (butlast (:fragments %))))))


(defn trace
  "Resolve the actual single-parent chain and ordered appends for one field.
   The retained fragment forms describe each authored contribution separately.
   Unsupported or unavailable ancestry keeps an explicit source diagnostic."
  [{:keys [defs-by-name name->id]} records-of original-for definition field target binding-id]
  (try
    (let [chain (ordered-ancestry definition defs-by-name)
          owner-name (slots/own-ref definition defs-by-name)
          [slot-owner slot-name] (slots/resolve-slot-owner owner-name field defs-by-name (get-in definition [:args field]))
          type (slots/slot-type-of slot-owner slot-name defs-by-name)
          slot-definition (get defs-by-name slot-owner)]
      (when-not (and (uuid? binding-id) (= slot-name field)
                     (= target (ids/slot-id (get name->id slot-owner) field))
                     (or (= type :sequence) (and (vector? type) (= :list (first type))))
                     (some #(= (get name->id slot-owner) (:fn-id %)) chain))
        (reject! :list-target-origin-required))
      (let [fragments (into [] (keep #(contribution % (records-of %) original-for name->id target field)) chain)
            slot (some #(when (and (= :slot (:kind %)) (= target (:id %))) %) (records-of slot-definition))]
        (when-not (and slot (= binding-id (:binding-id (peek fragments)))) (reject! :list-binding-origin-mismatch))
        (materialize (assoc (builder/link-fragments fragments) :fragments fragments)
                     {:type type :type-id (:type-fn-id slot)} name->id)))
    (catch clojure.lang.ExceptionInfo error
      (if (= :input/list-builder (:type (ex-data error)))
        {:blocker (:code (ex-data error))}
        (throw error)))))
