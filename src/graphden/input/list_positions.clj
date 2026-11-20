(ns graphden.input.list-positions
  "Source-declared owner inputs read by positional list operands."
  (:require
    [graphden.input.format :as format]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as types]))


(defn occurrence-policy
  [spec]
  (select-keys spec [:description :required]))


(defn expression
  [field item]
  (let [policy (occurrence-policy item)]
    (cond-> [:project [field]] (seq policy) (conj policy))))


(defn valid-policy?
  [policy]
  (and (map? policy) (every? #{:description :required} (keys policy))
       (or (not (contains? policy :description)) (string? (:description policy)))
       (or (not (contains? policy :required)) (false? (:required policy)))))


(defn marker?
  [spec]
  (and (map? spec) (simple-keyword? (:as spec))
       (every? #{:as :type :description :required} (keys spec)) (valid-policy? (occurrence-policy spec))
       (or (not (contains? spec :type)) (format/configuration-type? (:type spec)))))


(defn- source-type-id
  [type names]
  (try
    (types/resolve-type-ref type names)
    (catch clojure.lang.ExceptionInfo error
      (throw (ex-info "Unresolved positional input type"
                      {:type :input/list-builder :code :positional-list-type-unresolved} error)))))


(defn inputs
  "Check each positional anchor against the actual owned slot and membership.
   Retain the position emitted by the legacy parser, including set iteration."
  [owner grouped type-names]
  (when-let [items (seq (filter #(and (not (:literal %)) (marker? (:value %))) (:binding-list-item grouped)))]
    (let [links (into #{} (keep #(when (and (= owner (:fn-id %)) (:list-append %)) (:id %))) (:binding grouped))
          slots (group-by :id (:slot grouped))
          members (group-by :slot-id (filter #(= owner (:fn-id %)) (:fn-slot grouped)))]
      (reduce (fn [result {{field :as :as marker} :value}]
                (let [id (ids/slot-id owner field)
                      [slot & duplicate-slots] (get slots id)
                      [member & duplicate-members] (get members id)
                      type (get marker :type :any)
                      input {:id id :position (:position member) :declaration {:type type :required false}}]
                  (when-not (and (empty? duplicate-slots) (empty? duplicate-members)
                                 (= (name field) (:name slot)) (false? (:required slot))
                                 (nil? (:source-slot-id slot)) (nil? (:description slot))
                                 (= (:type-fn-id slot) (source-type-id type type-names))
                                 (= (ids/fn-slot-id owner id) (:id member)) (nat-int? (:position member))
                                 (or (not (contains? result field)) (= input (get result field))))
                    (throw (ex-info "Positional owner field differs from its source"
                                    {:type :input/list-builder :code :positional-list-field-mismatch})))
                  (assoc result field input)))
              {} (filter #(contains? links (:binding-id %)) items)))))


(defn authored-inputs
  [owner rows type-names source]
  (when-let [anchors (inputs owner rows type-names)]
    (into {} (keep (fn [item]
                     (when-let [anchor (and (marker? item) (get anchors (:as item)))]
                       [(:as item) (assoc-in anchor [:declaration :type] (get item :type :any))])))
          (if (vector? source) source (:append source)))))


(defn declarations
  [arguments]
  (into (array-map) (map (fn [[field anchor]] [field (:declaration anchor)]))
        (sort-by (comp :position val) (into {} (mapcat :list-inputs) arguments))))


(defn projected-names
  [bind]
  (into #{} (comp (filter #(and (vector? %) (= :list-builder (first %))))
                  (mapcat #(get-in % [1 :items]))
                  (keep (fn [item] (when (and (vector? item) (= :project (first item))) (get-in item [1 0])))))
        (when (map? bind) (vals bind))))
