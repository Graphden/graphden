(ns graphden.input.list-positions
  "Source-declared owner inputs read by positional list operands."
  (:require
    [graphden.input.format :as format]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as types]))


(defn marker?
  [spec]
  (and (map? spec) (simple-keyword? (:as spec))
       (every? #{:as :type} (keys spec))
       (or (not (contains? spec :type)) (format/configuration-type? (:type spec)))))


(defn inputs
  "Check each positional anchor against the actual owned slot and membership.
   Retain the position emitted by the legacy parser, including set iteration."
  [owner grouped type-names]
  (let [links (into #{} (keep #(when (and (= owner (:fn-id %)) (:list-append %)) (:id %))) (:binding grouped))
        slots (group-by :id (:slot grouped))
        members (group-by :slot-id (filter #(= owner (:fn-id %)) (:fn-slot grouped)))]
    (reduce (fn [result {:keys [value literal binding-id]}]
              (if (and (contains? links binding-id) (not literal) (marker? value))
                (let [field (:as value)
                      id (ids/slot-id owner field)
                      [slot & duplicate-slots] (get slots id)
                      [member & duplicate-members] (get members id)
                      type (get value :type :any)
                      input {:id id :position (:position member) :declaration {:type type :required false}}]
                  (when-not (and (empty? duplicate-slots) (empty? duplicate-members) (= (name field) (:name slot)) (false? (:required slot))
                                 (nil? (:source-slot-id slot)) (nil? (:description slot))
                                 (= (:type-fn-id slot) (types/resolve-type-ref type type-names))
                                 (= (ids/fn-slot-id owner id) (:id member)) (nat-int? (:position member))
                                 (or (not (contains? result field)) (= input (get result field))))
                    (throw (ex-info "Positional owner field differs from its source" {:type :input/list-builder :code :positional-list-field-mismatch})))
                  (assoc result field input))
                result))
            {} (:binding-list-item grouped))))


(defn authored-inputs
  [owner rows type-names source]
  (let [anchors (inputs owner rows type-names)]
    (into {} (keep (fn [{:keys [as] :as marker}]
                     (when (marker? marker)
                       [as (assoc-in (get anchors as) [:declaration :type] (get marker :type :any))])))
          (filter map? (if (vector? source) source (:append source))))))


(defn declarations
  [arguments]
  (into (array-map) (map (fn [[field anchor]] [field (:declaration anchor)]))
        (sort-by (comp :position val) (into {} (mapcat :list-inputs) arguments))))


(defn projected-names
  [bind]
  (into #{} (comp (filter #(and (vector? %) (= :list-builder (first %))))
                  (mapcat #(get-in % [1 :items]))
                  (keep (fn [[op path]] (when (= :project op) (first path)))))
        (when (map? bind) (vals bind))))
