(ns graphden.input.migration-renames
  "Identify legacy scalar renames whose identity and history agree with the
   parser's original empty binding and immutable slot link."
  (:require
    [graphden.packages.records.ids :as ids]))


(def ^:private stamp-fields [:branch-id :change-id :change-revision :created-at :deleted-at])


(defn- rename-binding?
  [type-id row]
  (and (not (:value-present row)) (contains? #{nil type-id} (:type-override-fn-id row))
       (not-any? #(some? (get row %)) [:value :ref-fn-id :description
                                       :list-append :list-closed :terminal :required :resolver-fn-id
                                       :override-kind])))


(defn- matching-history?
  [type-id membership binding membership-versions binding-versions]
  (let [memberships (get membership-versions (:id membership))
        bindings (get binding-versions (:id binding))]
    (and (every? #(= (select-keys membership [:fn-id :slot-id]) (select-keys % [:fn-id :slot-id])) memberships)
         (every? #(and (rename-binding? type-id %) (= (select-keys binding [:fn-id :slot-id :type-override-fn-id])
                                                      (select-keys % [:fn-id :slot-id :type-override-fn-id]))) bindings)
         (= (frequencies (map #(select-keys % stamp-fields) memberships))
            (frequencies (map #(select-keys % stamp-fields) bindings))))))


(defn evidence
  "Certify single-parent, same-primitive renames with no stored expression.
   Return exact alias membership IDs by original binding ID. Alternate value
   bindings, scoped reference inputs and unpaired branch lifetimes stay blocked."
  [{:keys [slot binding fn-slot binding-version fn-slot-version]} fns by-owner]
  (let [fields (into {} (map (juxt :id identity)) slot)
        bindings (group-by (juxt :fn-id :slot-id) binding)
        binding-versions (group-by :binding-id binding-version)
        membership-versions (group-by :fn-slot-id fn-slot-version)
        allowed-types (into #{} (map ids/primitive-fn-id) [:text :bool :int :numeric :keyword :uuid :null])]
    (into {}
          (keep (fn [{owner :fn-id alias-id :slot-id member-id :id :as membership}]
                  (let [{destination :source-slot-id type-id :type-fn-id name :name :as field} (get fields alias-id)
                        source (get fields destination)
                        owner-row (get fns owner)
                        [parent & others] (:parent-ids owner-row)
                        [origin & duplicate] (get bindings [owner destination])]
                    (when (and (uuid? destination) (string? name) (uuid? parent) (empty? others)
                               (not= (:name source) name) (nil? (:source-slot-id source))
                               (allowed-types type-id) (= type-id (:type-fn-id source))
                               (false? (:required field)) (= alias-id (ids/slot-id owner name))
                               (= member-id (ids/fn-slot-id owner alias-id))
                               (= (:org-id owner-row) (:org-id origin) (:org-id membership) (:org-id field))
                               (empty? (:parent-ids (get fns parent))) (empty? (get bindings [parent destination]))
                               (= 1 (count (filter #(= destination (:slot-id %)) (get by-owner parent))))
                               (= 1 (count (filter #(= destination (get-in fields [(:slot-id %) :source-slot-id]))
                                                   (get by-owner owner))))
                               origin (empty? duplicate) (= (:id origin) (ids/binding-id owner destination))
                               (rename-binding? type-id origin)
                               (matching-history? type-id membership origin membership-versions binding-versions))
                      [(:id origin) {:alias-id alias-id :membership-id member-id}]))))
          fn-slot)))
