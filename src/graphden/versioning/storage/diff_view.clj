(ns graphden.versioning.storage.diff-view
  "Display model for the branch-diff surface (diff v2).

   `diff-branches-view` lifts the flat symmetric `mrg/diff-branches`
   result into the structure the editor's diff modal renders: entries
   GROUPED under the fn that owns them, each entry carrying human
   labels (slot name, item position) and — for `:modified` rows — the
   per-field before/after pairs. Id-like values are resolved to names
   where one exists (owning fn, slot, fn-typed ref fields) and ids are
   stringified: this is a read-only projection for rendering, not a
   wire schema. The JSON API keeps serving the v1 shape from
   `diff-branches` unchanged."
  (:require
    [clojure.string :as str]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.merge :as mrg]))


(def ^:private ref-fields
  "Version-map fields whose value is a fn-id — displayed as the
   referenced fn's name instead of a bare uuid."
  #{:ref-fn-id :type-override-fn-id :resolver-fn-id :return-type-fn-id
    :base-fn-id :element-fn-id :type-fn-id})


(defn- side-version
  "The present side of a diff row — source when it has one, else target."
  [{:keys [source-version target-version]}]
  (or source-version target-version))


(defn- short-id
  [id]
  (some-> id str (subs 0 8)))


(defn- truncate
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))


(defn- fn-label
  [fn-names id]
  (if-let [n (get fn-names id)]
    (str ":" n)
    (str "#" (short-id id))))


(defn- display-value
  "Human form of one field value: fn-refs become `:name`, strings stay
   bare, everything else pr-str — all truncated for row display."
  [fn-names field v]
  (cond
    (nil? v) "∅"
    (and (contains? ref-fields field) (uuid? v)) (fn-label fn-names v)
    (string? v) (truncate v 120)
    :else (truncate (pr-str v) 120)))


(defn- changed-fields
  [sv tv]
  (->> (into #{} (concat (keys sv) (keys tv)))
       (remove #{:created-at})
       (filter #(not= (get sv %) (get tv %)))
       (sort)))


(defn- owner-fn-id
  "The fn whose card a diff row belongs under. Mirrors
   `mrg/conflict-owning-fn-id`, but over the already-resolved rows +
   a pre-read bindings map (no per-row queries)."
  [diff bindings-by-id]
  (case (:entity-name diff)
    :fn (:entity-id diff)
    (:fn-slot :binding) (:fn-id (side-version diff))
    :binding-list-item (:fn-id (get bindings-by-id
                                    (:binding-id (side-version diff))))
    nil))


(defn- entry-slot-id
  [diff bindings-by-id]
  (case (:entity-name diff)
    (:fn-slot :binding) (:slot-id (side-version diff))
    :binding-list-item (:slot-id (get bindings-by-id
                                      (:binding-id (side-version diff))))
    nil))


(defn- binding-preview
  "One-line description of a binding's present side — shown for rows
   that exist on only one branch (nothing to pair field-by-field)."
  [fn-names m]
  (->> [(when (some? (:value m))
          (str "value = " (display-value fn-names :value (:value m))))
        (when (:ref-fn-id m)
          (str "ref → " (fn-label fn-names (:ref-fn-id m))))
        (when (:type-override-fn-id m)
          (str "type ⇒ " (fn-label fn-names (:type-override-fn-id m))))
        (when (:terminal m) "terminal")
        (when (:list-append m) "list-append")
        (when (:list-closed m) "list-closed")
        (when (some? (:description m))
          (str "“" (truncate (:description m) 60) "”"))]
       (remove nil?)
       (str/join " · ")
       (not-empty)))


(defn- entry-preview
  [fn-names {:keys [entity-name] :as diff}]
  (let [m (side-version diff)]
    (case entity-name
      :fn (when-let [d (:description m)] (str "“" (truncate d 80) "”"))
      :fn-slot (some->> (:position m) (str "at position "))
      :binding (binding-preview fn-names m)
      :binding-list-item
      (->> [(when (some? (:value m))
              (display-value fn-names :value (:value m)))
            (when (:ref-fn-id m) (str "→ " (fn-label fn-names (:ref-fn-id m))))]
           (remove nil?)
           (str/join " ")
           (not-empty))
      nil)))


(defn- entry
  "One display entry for a diff row: label parts + either the
   field-level before/after pairs (`:modified`) or a one-line preview
   of the present side."
  [fn-names slots-by-id bindings-by-id
   {:keys [entity-name entity-id change source-version target-version]
    :as diff}]
  (let [slot-id (entry-slot-id diff bindings-by-id)
        fields (when (= :modified change)
                 (vec (for [f (changed-fields source-version target-version)]
                        {:field (name f)
                         :source (display-value fn-names f (get source-version f))
                         :target (display-value fn-names f (get target-version f))})))]
    (cond-> {:entity-name entity-name
             :entity-id (str entity-id)
             :change change}
      slot-id (assoc :slot-name (:name (get slots-by-id slot-id)))
      (= :binding-list-item entity-name)
      (assoc :position (:position (side-version diff)))
      (seq fields) (assoc :fields fields)
      (not= :modified change)
      (assoc :preview (entry-preview fn-names diff)))))


(defn- resolve-fn-names
  "Best-effort `{fn-id name}` for `ids`: resolved on the source branch
   first, then the target for the remainder. Anonymous / vanished fns
   simply stay absent from the map."
  [base-storage ids source-branch-id target-branch-id]
  (if (empty? ids)
    {}
    (let [src (mrg/batch-resolve base-storage {:fn (set ids)} source-branch-id)
          missing (set (remove #(:name (get src [:fn %])) ids))
          tgt (when (seq missing)
                (mrg/batch-resolve base-storage {:fn missing} target-branch-id))]
      (into {}
            (keep (fn [id]
                    (when-let [n (or (:name (get src [:fn id]))
                                     (:name (get tgt [:fn id])))]
                      [id n])))
            ids))))


(def ^:private entry-rank
  {:fn 0 :fn-slot 1 :binding 2 :binding-list-item 3})


(defn diff-branches-view
  "Grouped display model of `mrg/diff-branches`:

     {:source-branch-id <uuid> :target-branch-id <uuid>
      :count <total diff rows>
      :groups [{:fn-id \"<uuid>\"          ; nil only if ownerless (defensive)
                :fn-name \"web-server\"    ; nil for anonymous fns
                :fn-label \":web-server\"  ; always printable
                :change :added-in-source | :added-in-target | :modified
                :branch-local? bool
                :entries [<entry> …]}]}

   Group `:change` is the owning fn row's own change when the fn itself
   differs, else `:modified` (only its parts moved). Entries are sorted
   fn-row first, then slots / bindings / list-items by slot name and
   position."
  [base-storage source-branch-id target-branch-id]
  (let [{:keys [diffs]} (mrg/diff-branches base-storage
                                           source-branch-id target-branch-id)
        li-binding-ids (->> diffs
                            (filter #(= :binding-list-item (:entity-name %)))
                            (keep #(:binding-id (side-version %)))
                            (distinct)
                            (vec))
        bindings-by-id (if (seq li-binding-ids)
                         (sp/read-entities base-storage :binding li-binding-ids)
                         {})
        slot-ids (->> diffs
                      (keep #(entry-slot-id % bindings-by-id))
                      (distinct)
                      (vec))
        slots-by-id (if (seq slot-ids)
                      (sp/read-entities base-storage :slot slot-ids)
                      {})
        rows (map #(assoc % ::owner (owner-fn-id % bindings-by-id)) diffs)
        ref-ids (set (for [d diffs
                           side [:source-version :target-version]
                           [k v] (get d side)
                           :when (and (contains? ref-fields k) (uuid? v))]
                       v))
        name-ids (into ref-ids (keep ::owner rows))
        fn-names (resolve-fn-names base-storage name-ids
                                   source-branch-id target-branch-id)
        groups
        (vec
          (sort-by (fn [g]
                     [(if (:fn-name g) 0 1) (or (:fn-name g) "")
                      (or (:fn-id g) "")])
                   (for [[owner rs] (group-by ::owner rows)]
                     (let [fn-row (first (filter #(= :fn (:entity-name %)) rs))
                           entries (->> rs
                                        (mapv #(entry fn-names slots-by-id
                                                      bindings-by-id %))
                                        (sort-by (fn [e]
                                                   [(entry-rank (:entity-name e) 9)
                                                    (or (:slot-name e) "")
                                                    (or (:position e) 0)]))
                                        (vec))]
                       {:fn-id (some-> owner str)
                        :fn-name (get fn-names owner)
                        :fn-label (if owner
                                    (fn-label fn-names owner)
                                    "(unowned)")
                        :change (if fn-row (:change fn-row) :modified)
                        :branch-local? (boolean
                                         (when owner
                                           (bl/effective-branch-local?
                                             base-storage owner)))
                        :entries entries}))))]
    {:source-branch-id source-branch-id
     :target-branch-id target-branch-id
     :count (count diffs)
     :groups groups}))
