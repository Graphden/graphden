(ns graphden.input.source-list
  "Prove list constructor origins against the prepared package. This offline
   adapter does not activate a runtime or publish generated declarations."
  (:require
    [graphden.input.list-builder :as builder]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.slot-resolution :as slots]))


(defn- reject!
  [code]
  (throw (ex-info "Unverified list construction" {:type :input/list-builder :code code})))


(defn- ancestors
  [definition defs]
  (loop [current definition seen #{} chain ()]
    (let [id (:fn-id current)
          parents (concat (when-let [p (:parent current)] [p]) (:parents current))]
      (when-not (and (uuid? id) (not (contains? seen id)) (<= (count parents) 1))
        (reject! :list-parent-chain-required))
      (if (seq parents)
        (recur (get defs (first parents)) (conj seen id) (conj chain current))
        (conj chain current)))))


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
        (builder/source-fragment binding items (get-in original [:args field]) name->id)))))


(defn trace
  "Resolve the actual single-parent chain and ordered appends for one field.
   The retained fragment forms describe each authored contribution separately.
   Unsupported or unavailable ancestry keeps an explicit source diagnostic."
  [{:keys [defs-by-name name->id]} records-of original-for definition field target binding-id]
  (try
    (let [chain (ancestors definition defs-by-name)
          owner-name (slots/own-ref definition defs-by-name)
          [slot-owner slot-name] (slots/resolve-slot-owner owner-name field defs-by-name (get-in definition [:args field]))
          type (slots/slot-type-of slot-owner slot-name defs-by-name)]
      (when-not (and (uuid? binding-id) (= slot-name field)
                     (= target (ids/slot-id (get name->id slot-owner) field))
                     (or (= type :sequence) (and (vector? type) (= :list (first type))))
                     (some #(= (get name->id slot-owner) (:fn-id %)) chain))
        (reject! :list-target-origin-required))
      (let [fragments (into [] (keep #(contribution % (records-of %) original-for name->id target field)) chain)]
        (when-not (= binding-id (:binding-id (peek fragments))) (reject! :list-binding-origin-mismatch))
        (assoc (builder/link-fragments fragments) :fragments fragments)))
    (catch clojure.lang.ExceptionInfo error
      (if (= :input/list-builder (:type (ex-data error)))
        {:blocker (:code (ex-data error))}
        (throw error)))))
