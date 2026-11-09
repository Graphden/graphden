(ns graphden.input.rows
  "Validated persisted addresses and expressions for offline structured inputs."
  (:require
    [graphden.input.core :as input]))


(defn- reject!
  [code]
  (throw (ex-info "Invalid structured input storage row"
                  {:type :input/invalid-row :code code})))


(defn uuid-path
  "JSONB may decode UUIDs as strings. Normalize before identity comparison."
  [path]
  (when-not (and (vector? path) (<= (count path) 128))
    (reject! :invalid-path))
  (mapv (fn [id]
          (cond
            (uuid? id) id
            (string? id) (or (parse-uuid id) (reject! :invalid-path))
            :else (reject! :invalid-path))) path))


(defn normalize
  [row]
  (when-not (and (uuid? (:id row)) (uuid? (:fn-id row)))
    (reject! :invalid-owner))
  (let [kind (:expression-kind row)
        row (-> row
                (update :scope-path uuid-path)
                (update :target-path uuid-path))]
    (when-not (case kind
                nil (and (not (:value-present row)) (nil? (:ref-fn-id row)))
                "value" (and (true? (:value-present row)) (nil? (:ref-fn-id row)))
                "ref" (and (uuid? (:ref-fn-id row)) (not (:value-present row))
                           (nil? (:resolver-fn-id row)))
                "projection" (and (not (:value-present row)) (nil? (:ref-fn-id row))
                                  (nil? (:resolver-fn-id row)))
                false)
      (reject! :invalid-expression))
    (cond-> row
      (= "projection" kind)
      (-> (update :projection-scope-path uuid-path)
          (update :projection-path uuid-path)))))


(defn occupied?
  [row]
  (some? (:expression-kind row)))


(defn state
  "Build occupancy keyed by owner/use scope and field path. Metadata alone
   does not occupy a field. Reuse the stage-one overlap and origin laws."
  [rows]
  (reduce (fn [state row]
            (let [row (normalize row)]
              (if-not (occupied? row)
                state
                (input/merge-bindings
                  state
                  {[[(:fn-id row) (:scope-path row)] (:target-path row)]
                   {:binding-id (:id row)
                    :expression (select-keys row [:expression-kind :value :value-present
                                                  :ref-fn-id :resolver-fn-id
                                                  :projection-scope-path :projection-path])}}))))
          {} rows))
