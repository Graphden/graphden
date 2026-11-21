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


(defn- ordered-positions?
  [birth binding positions]
  (and (every? (fn [row]
                 (and (nat-int? (:position row)) (uuid? (:change-id row))
                      (pos-int? (:change-revision row)) (nil? (:deleted-at row))
                      (instance? (class (:created-at birth)) (:created-at row))
                      (neg? (compare (:created-at binding) (:created-at row))))) positions)
       (every? (fn [[a b]]
                 (and (< (:change-revision a) (:change-revision b))
                      (neg? (compare (:created-at a) (:created-at b)))))
               (partition 2 1 (cons birth (sort-by :change-revision positions))))))


(defn- position-history-issue
  "The ordinary writer stamps same-transaction inserts at separate instants.
   Position changes preserve the live alias on its root branch or a direct
   fork. Merge windows use independent wall-clock cutoffs."
  [{:keys [branches merge-branches]} memberships [binding & more]]
  (let [birth-keys [:branch-id :change-id :change-revision :author-id :author-label]
        grouped (group-by #(= (select-keys binding birth-keys) (select-keys % birth-keys)) memberships)
        [birth & extra] (get grouped true)
        positions (group-by :branch-id (get grouped false))
        root (:branch-id binding)
        touched (conj (set (keys positions)) root)]
    (cond
      (not (and binding birth (empty? more) (empty? extra) (uuid? (:change-id birth))
                (pos-int? (:change-revision birth)) (nat-int? (:position birth))
                (nil? (:deleted-at birth)) (nil? (:deleted-at binding))
                (inst? (:created-at birth)) (instance? (class (:created-at birth)) (:created-at binding))))
      :rename-atomic-origin-required
      (not (and (uuid? root) (contains? branches root) (nil? (get branches root))
                (every? #(or (= root %) (= root (get branches %))) touched)))
      :rename-branch-origin-required
      (some merge-branches touched) :rename-merge-cutoff-required
      (not-every? (partial ordered-positions? birth binding) (vals positions)) :rename-position-history-required)))


(defn- history-issue
  [type-id membership binding membership-versions binding-versions topology]
  (let [memberships (get membership-versions (:id membership))
        bindings (get binding-versions (:id binding))]
    (cond
      (not (every? #(= (select-keys membership [:fn-id :slot-id]) (select-keys % [:fn-id :slot-id])) memberships))
      :historical-membership-move
      (not (every? #(and (rename-binding? type-id %) (= (select-keys binding [:fn-id :slot-id :type-override-fn-id])
                                                        (select-keys % [:fn-id :slot-id :type-override-fn-id]))) bindings))
      :rename-binding-history-changed
      (= (frequencies (map #(select-keys % stamp-fields) memberships))
         (frequencies (map #(select-keys % stamp-fields) bindings))) nil
      :else (position-history-issue topology memberships bindings))))


(defn evidence
  "Certify single-parent, same-primitive renames with no stored expression.
   Return verified alias memberships or a history exception for an otherwise
   eligible binding. Alternate value bindings and scoped inputs stay blocked."
  [{:keys [slot binding fn-slot binding-version fn-slot-version branch branch-merge]} fns by-owner]
  (let [fields (into {} (map (juxt :id identity)) slot)
        bindings (group-by (juxt :fn-id :slot-id) binding)
        binding-versions (group-by :binding-id binding-version)
        membership-versions (group-by :fn-slot-id fn-slot-version)
        topology {:branches (into {} (map (juxt :id :base-branch-id)) branch)
                  :merge-branches (into #{} (mapcat (juxt :source-branch-id :target-branch-id)) branch-merge)}
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
                               (rename-binding? type-id origin))
                      [(:id origin)
                       (if-let [code (history-issue type-id membership origin membership-versions binding-versions topology)]
                         {:exception {:code code :entity :binding :id (:id origin)}}
                         {:alias-id alias-id :membership-id member-id})]))))
          fn-slot)))
