(ns graphden.input.field-default
  "Stored defaults for optional scalar inputs admitted by the pinned package reader."
  (:require
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(def nullable-text [:union :null :text])


(def nullable-text-id (ids/fn-id "core.refinements" :nullable-text))


(defn supported?
  [required type value]
  (and (false? required) (= nullable-text type) (or (nil? value) (string? value))))


(defn read-envelope
  [field type envelope]
  (when-not (and (map? envelope) (= #{:value} (set (keys envelope)))
                 (= nullable-text-id (:type-fn-id field))
                 (supported? (:required field) type (:value envelope)))
    (context/reject! :invalid-field-default {:field-id (:id field)}))
  (:value envelope))


(defn occurrence-envelope
  "Encode present member defaults in canonical slot UUID order. A single
   fallback keeps its original on-disk shape."
  [values]
  (let [entries (mapv (fn [[id value]] {:target-path [id] :value value}) (sort-by key values))]
    (case (count entries) 0 nil 1 (first entries) {:members entries})))


(defn- member-entry?
  [entry]
  (and (map? entry) (= #{:target-path :value} (set (keys entry)))
       (let [{:keys [target-path value]} entry]
         (and (vector? target-path) (= 1 (count target-path)) (string? (first target-path))
              (or (nil? value) (string? value))))))


(defn member-values
  "Read an old single path or a canonically ordered group of direct members.
   Returns nil for malformed input. An explicit nil remains a present map key."
  [envelope]
  (let [entries (if (and (map? envelope) (= #{:members} (set (keys envelope))))
                  (let [group (:members envelope)] (when (and (vector? group) (< 1 (count group))) group))
                  [envelope])]
    (when (and (seq entries) (every? member-entry? entries))
      (let [ids (mapv (comp first :target-path) entries)]
        (when (= ids (vec (sort (set ids))))
          (zipmap ids (map :value entries)))))))


(defn apply-envelope
  "Overlay the exact child UUIDs on this resolved input occurrence only."
  [field node envelope own-input?]
  (if (and (map? envelope) (or (contains? envelope :target-path) (contains? envelope :members)))
    (let [values (member-values envelope)
          fields (:fields node)
          targets (filterv #(contains? values (str (:id %))) fields)]
      (when-not (and own-input? (map? (:type node)) values (= (count values) (count targets))
                     (every? #(and (= nullable-text-id (:type-id %)) (not (contains? % :default))
                                   (supported? (:required %) (:type %) (get values (str (:id %))))) targets))
        (context/reject! :invalid-field-default {:field-id (:id field)}))
      (assoc node :fields (mapv (fn [child]
                                  (if-let [entry (find values (str (:id child)))]
                                    (assoc child :default (val entry)) child)) fields)))
    (assoc node :default (read-envelope field (:type node) envelope))))
