(ns graphden.input.source-rich-types
  "Exact storage evidence for closed legacy callable and collection shapes."
  (:require
    [graphden.input.descriptor :as descriptor]
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as records]
    [graphden.types.core :as types]))


(defn- supported-value-type?
  [type]
  (format/collection-type? #(or (types/primitive? %) (types/type-var? %)) type))


(defn callable-shape?
  [type]
  (and (types/fn-type? type) (map? (types/fn-args type))
       (every? supported-value-type? (vals (types/fn-args type)))
       (supported-value-type? (types/fn-ret type))))


(defn authored-callable-field?
  "Prove the authored anonymous callable row and its exact FK. This is only
   origin evidence: named leaves still require a frozen declaration closure."
  [rows-by-id type-id type]
  (when (and (types/fn-type? type) (map? (types/fn-args type))
             (every? #(format/collection-type? (fn [leaf] (or (keyword? leaf) (types/type-var? leaf))) %)
                     (conj (vec (vals (types/fn-args type))) (types/fn-ret type))))
    (let [[row & more] (records/inline-fn-type-rows-from-form type)
          stored (get rows-by-id type-id)]
      (and (empty? more) (= type-id (:id row)) (seq stored)
           (every? #(migration/same-edn? row %) stored)))))


(defn callable-field?
  "Keep the literal authored callable ID; named leaves are admitted only with
   independently verified frozen type IDs. Never accept an erased :fn FK."
  ([rows-by-id type-id type] (callable-field? rows-by-id type-id type {}))
  ([rows-by-id type-id type named-ids]
   (and (authored-callable-field? rows-by-id type-id type)
        (every? #(format/collection-type? (fn [leaf]
                                            (or (types/primitive? leaf) (types/type-var? leaf)
                                                (uuid? (get named-ids leaf)))) %)
                (conj (vec (vals (types/fn-args type))) (types/fn-ret type))))))


(defn erased-return?
  "Recover a generic, bottom, collection or union return erased by storage.
   Its frozen rich implementation entry must still match the authored type."
  [type-id type]
  (and (or (types/type-var? type) (= :never type)
           (and (vector? type) (contains? #{:list :map :union} (first type))))
       (descriptor/stored-source-type? type type-id)))


(defn- pin-row
  [owner slot type-id]
  {:kind :input-binding :id (ids/binding-id owner slot) :fn-id owner :scope-path [] :target-path [slot]
   :expression-kind nil :value-present false :type-override-fn-id type-id})


(defn exact-self-pin?
  [owner field {:keys [row type target-path type-override-fn-id]} rows-by-id]
  (let [[base & more] (:parent-ids owner)
        slot (:id field)]
    (and row (uuid? base) (empty? more) (uuid? slot) (= :any (:type field)) (= [slot] target-path)
         (= slot (ids/slot-id base (:name field)))
         (= row (pin-row (:id owner) slot type-override-fn-id))
         (callable-field? rows-by-id type-override-fn-id type))))


(defn self-pin-source
  [{:keys [args parent]}]
  (when (and parent (= 1 (count args)))
    (let [[field spec :as entry] (first args)]
      (when (and (map? spec) (= #{:as :type} (set (keys spec)))
                 (= field (:as spec)) (callable-shape? (:type spec)))
        entry))))


(defn self-pin
  "An annotation on a direct parent's free :any slot narrows its declared
   schema. It supplies no value or type substitution. Require the original
   binding, target and callable row before returning the v2 metadata row."
  [{:keys [fn-id] :as definition} field rows binding declared-type]
  (when-let [[pinned spec] (self-pin-source definition)]
    (let [type (:type spec)
          owner (some #(when (= fn-id (:id %)) %) rows)
          [base & more] (:parent-ids owner)
          stored-id (:type-override-fn-id binding)]
      (when (and (= field pinned) (uuid? base) (empty? more) (= :any declared-type)
                 (types/subtype? type declared-type))
        (let [slot (ids/slot-id base field)
              row (pin-row fn-id slot stored-id)]
          (when (and (= {:kind :binding :id (:id row) :fn-id fn-id :slot-id slot :value-present false :type-override-fn-id stored-id}
                        (into {} (remove (comp nil? val)) binding))
                     (callable-field? (group-by :id rows) stored-id type))
            {:type type :target-path [slot] :type-override-fn-id stored-id :row row}))))))
