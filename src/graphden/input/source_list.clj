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


(defn items
  "Read the direct position vector of a legacy list contribution."
  [source]
  (if (vector? source) source (:append source)))


(defn inline-item?
  [item]
  (and (map? item) (or (contains? item :parent) (contains? item :parents))))


(defn has-inline-items?
  [form]
  (let [values (items form)]
    (when (vector? values) (some inline-item? values))))


(defn- replace-inline-items
  [source inline-items]
  (let [with-refs (reduce-kv (fn [values position child]
                               (assoc values position {:ref (:ref child)}))
                             (items source) inline-items)]
    (if (vector? source) with-refs (assoc source :append with-refs))))


(defn source-form
  "Render the authored contribution using independently recovered inline children."
  [source name->id inline-items]
  (reduce-kv (fn [form position child]
               (assoc-in form [1 :items position] [:ref (:inline-body child)]))
             (builder/source-form (replace-inline-items source inline-items) name->id)
             inline-items))


(defn- inline-items
  [{:keys [inline-list-item] :as context} source prepared field original]
  (let [prepared-items (items (get-in prepared [:args field]))]
    (into (sorted-map)
          (keep-indexed (fn [position raw]
                          (when (inline-item? raw)
                            (when-not inline-list-item (reject! :inline-list-item-origin-required))
                            [position (inline-list-item context source [field position] raw (get prepared-items position))])))
          (items original))))


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
  [{:keys [name->id] :as context} definition records original-for target field]
  (let [rows (group-by :kind records)
        owner (:fn-id definition)
        matching (filter #(and (= owner (:fn-id %)) (= target (:slot-id %))) (:binding rows))]
    (when (some #(and (:source-slot-id %) (or (= target (:source-slot-id %)) (= target (:id %))))
                (:slot rows))
      (reject! :list-projection-required))
    (when-let [[binding & more] (seq matching)]
      (when (seq more) (reject! :ambiguous-list-binding))
      (let [original (original-for definition)
            item-rows (filter #(= (:id binding) (:binding-id %)) (:binding-list-item rows))]
        (when-not (contains? (:args original) field) (reject! :list-source-origin-required))
        (let [form (get-in original [:args field])
              inlines (inline-items context original definition field form)
              form (replace-inline-items form inlines)
              typed? #(and (positions/marker? %) (contains? % :type))
              normalize (fn [item]
                          (if (typed? item)
                            (update item :type rebase/rewrite-type-references
                                    (fn [ref] (parse/normalize-ref ref name->id (:namespace original))))
                            item))
              prepared (cond
                         (not-any? typed? (if (vector? form) form (:append form))) form
                         (vector? form) (mapv normalize form)
                         :else (update form :append #(mapv normalize %)))
              fragment (builder/source-fragment binding item-rows prepared (field-names name->id (:namespace original) form))
              anchors (when (some :source-id (:items fragment)) (positions/inputs owner rows name->id))]
          (when-not (every? (set (map :id (vals anchors))) (keep :source-id (:items fragment)))
            (reject! :positional-list-field-mismatch))
          {:fragment fragment :inline-items inlines})))))


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
          inlines (:inline-items trace)
          form (source-form source names inlines)
          refs (keep (fn [[op payload]] (when (and (= :ref op) (keyword? payload)) payload)) (get-in form [1 :items]))]
      {:expression form :list-ref-names (select-keys names refs) :list-inline-items inlines})))


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
  [{:keys [defs-by-name name->id] :as context} records-of original-for definition field target binding-id]
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
      (let [contributions (into [] (keep #(contribution context % (records-of %) original-for target field)) chain)
            fragments (mapv :fragment contributions)
            slot (some #(when (and (= :slot (:kind %)) (= target (:id %))) %) (records-of slot-definition))]
        (when-not (and slot (= binding-id (:binding-id (peek fragments)))) (reject! :list-binding-origin-mismatch))
        (assoc (materialize (assoc (builder/link-fragments fragments) :fragments fragments)
                            {:type type :type-id (:type-fn-id slot)} name->id)
               :inline-items (:inline-items (peek contributions)))))
    (catch clojure.lang.ExceptionInfo error
      (if (= :input/list-builder (:type (ex-data error)))
        (select-keys (assoc (ex-data error) :blocker (:code (ex-data error))) [:blocker :inline-blockers])
        (throw error)))))
