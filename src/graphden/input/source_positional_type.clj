(ns graphden.input.source-positional-type
  "Exact primitive-any evidence for an untyped legacy positional :as field."
  (:require
    [graphden.input.list-positions :as positions]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]))


(def ^:private version-stamps
  [:branch-id :change-id :change-revision :created-at :deleted-at :author-id :author-label])


(defn- unversioned?
  [row]
  (not-any? #(contains? row %) version-stamps))


(defn- occurrences
  [definition field]
  (for [[name spec] (:args definition)
        :let [items (if (vector? spec) spec (when (map? spec) (:append spec)))]
        :when (vector? items)
        [position item] (map-indexed vector items)
        :when (and (positions/marker? item) (= field (:as item)) (not (contains? item :type)))]
    {:name name :position position :marker item
     :source-path (cond-> [:args name] (map? spec) (conj :append) true (conj position))}))


(defn origin
  "Recover one original untyped positional anchor from its exact parser rows.
   This proves a particular primitive-any slot, never a default for nil types.
   Historical storage versions and structured declarations are not supported."
  [{:keys [name->id defs-by-name]} source definition rows field]
  (when (and source definition (simple-keyword? field)
             (not-any? #(or (contains? source %) (contains? definition %)) [:input :bind :configure])
             (every? #(or (not (contains? % :input-format)) (= 1 (:input-format %))) [source definition]))
    (when-let [occurrence (let [[item & more] (occurrences source field)] (when (empty? more) item))]
      (let [owner (ids/fn-id (:namespace source) (:name source))
            {:keys [name position marker source-path]} occurrence
            grouped (group-by :kind rows)
            [owner-row & duplicate-owners] (filter #(= owner (:id %)) (:fn grouped))
            anchor (get (positions/inputs owner grouped name->id) field)
            id (:id anchor)
            [slot & duplicate-slots] (filter #(= id (:id %)) (:slot grouped))
            [member & duplicate-members] (filter #(and (= owner (:fn-id %)) (= id (:slot-id %))) (:fn-slot grouped))
            [declaring key] (when occurrence
                              (slots/resolve-slot-owner (slots/own-ref definition defs-by-name) name defs-by-name
                                                        (get-in definition [:args name])))
            target (when-let [declaring-id (get name->id declaring)] (ids/slot-id declaring-id key))
            binding (when target (ids/binding-id owner target))
            [link & duplicate-links] (filter #(= binding (:id %)) (:binding grouped))
            item-id (when (and binding (nat-int? position)) (ids/binding-list-item-id binding position))
            [item & duplicate-items] (filter #(= item-id (:id %)) (:binding-list-item grouped))]
        (when (and (= owner (:fn-id definition))
                   (= marker (get-in definition source-path))
                   (= id (ids/slot-id owner field))
                   (= {:type :any :required false} (:declaration anchor))
                   owner-row (empty? duplicate-owners)
                   (empty? duplicate-slots) (empty? duplicate-members) (empty? duplicate-links) (empty? duplicate-items)
                   (every? unversioned? [owner-row slot member link item])
                   (= (ids/primitive-fn-id :any) (:type-fn-id slot)) (false? (:required slot))
                   (= owner (:fn-id link)) (= target (:slot-id link)) (true? (:list-append link))
                   (false? (:value-present link)) (nil? (:ref-fn-id link)) (nil? (:value link))
                   (= binding (:binding-id item)) (= position (:position item))
                   (= marker (:value item)) (not (:literal item)) (nil? (:ref-fn-id item)))
          {:version 1 :owner-id owner :field field :slot-id id :type-fn-id (:type-fn-id slot) :required false
           :membership-id (:id member) :membership-position (:position member)
           :binding-id binding :list-slot-id target :item-id item-id :item-position position :source-path source-path})))))


(defn declared-origin
  "Recover an untyped input introduced by a list item from its prepared
   declaration. The parser must reproduce the slot, membership and item UUIDs."
  [context definition field]
  (when (and definition (seq (occurrences definition field)))
    (origin context definition definition
            (parse/parse-prepared-module (assoc context :fn-defs [definition])) field)))


(defn exact?
  "Match the source witness to the current frozen slot and converted input
   membership. It applies only at the proved leaf, with an absent raw type."
  [{:keys [version owner-id field slot-id type-fn-id required membership-id membership-position
           binding-id list-slot-id item-id item-position source-path] :as witness}
   target snapshot declaring]
  (let [slot (get-in snapshot [:fields slot-id])
        owner (get-in snapshot [:fns owner-id])
        input (:input-type-fn-id owner)
        [member & more] (filter #(= slot-id (:slot-id %)) (get-in snapshot [:members input]))
        expected (when (and (uuid? owner-id) (uuid? slot-id))
                   (migration/input-membership-record
                     owner {:id membership-id :fn-id owner-id :slot-id slot-id :position membership-position}))]
    (and witness (= 1 version) (nil? (:type target)) (= field (:field target)) (= declaring owner-id)
         (simple-keyword? field) (= slot-id (:id slot) (ids/slot-id owner-id field)) (= owner-id (:id owner))
         (= (ids/primitive-fn-id :any) type-fn-id (:type-fn-id slot))
         (= (name field) (:name slot)) (false? required) (false? (:required slot))
         (nil? (:source-slot-id slot)) (nil? (:description slot))
         (every? unversioned? [owner slot member]) (nat-int? membership-position) (empty? more)
         (= membership-id (ids/fn-slot-id owner-id slot-id))
         (= (select-keys expected [:id :fn-id :slot-id :position]) (select-keys member [:id :fn-id :slot-id :position]))
         (= input (:id (migration/input-type-record owner)))
         (uuid? list-slot-id) (= binding-id (ids/binding-id owner-id list-slot-id))
         (nat-int? item-position) (= item-id (ids/binding-list-item-id binding-id item-position))
         (vector? source-path) (= :args (first source-path)) (simple-keyword? (second source-path))
         (or (= source-path [:args (second source-path) item-position])
             (= source-path [:args (second source-path) :append item-position])))))
