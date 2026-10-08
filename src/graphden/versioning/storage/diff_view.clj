(ns graphden.versioning.storage.diff-view
  "Display model for the branch-diff surface (diff v2).

   `diff-branches-view` lifts the flat symmetric `mrg/diff-branches`
   result into the structure the editor's diff modal renders: entries
   GROUPED under the fn that owns them, each entry carrying human
   labels (slot name, item position) and — for `:modified` rows — the
   per-field before/after pairs (`field-diff`, shared with the merge
   conflict payload). Id-like values are resolved to names where one
   exists (owning fn, slot, fn-typed ref fields) and ids are
   stringified: this is a read-only projection for rendering, not a
   wire schema. The JSON API keeps serving the v1 shape from
   `diff-branches` unchanged."
  (:require
    [clojure.string :as str]
    [graphden.storage.protocol.core :as sp]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.field-diff :as fd]
    [graphden.versioning.storage.merge :as mrg]))


(defn- side-version
  "The present side of a diff row — source when it has one, else target."
  [{:keys [source-version target-version]}]
  (or source-version target-version))


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
          (str "value = " (fd/display-value fn-names :value (:value m))))
        (when (:ref-fn-id m)
          (str "ref → " (fd/fn-label fn-names (:ref-fn-id m))))
        (when (:type-override-fn-id m)
          (str "type ⇒ " (fd/fn-label fn-names (:type-override-fn-id m))))
        (when (:terminal m) "terminal")
        (when (:list-append m) "list-append")
        (when (:list-closed m) "list-closed")
        (when (some? (:description m))
          (str "“" (fd/truncate (:description m) 60) "”"))]
       (remove nil?)
       (str/join " · ")
       (not-empty)))


(defn- entry-preview
  [fn-names {:keys [entity-name] :as diff}]
  (let [m (side-version diff)]
    (case entity-name
      :fn (when-let [d (:description m)] (str "“" (fd/truncate d 80) "”"))
      :fn-slot (some->> (:position m) (str "at position "))
      :binding (binding-preview fn-names m)
      :binding-list-item
      (->> [(when (some? (:value m))
              (fd/display-value fn-names :value (:value m)))
            (when (:ref-fn-id m) (str "→ " (fd/fn-label fn-names (:ref-fn-id m))))]
           (remove nil?)
           (str/join " ")
           (not-empty))
      ;; Frontend-asset override (Operate → Assets) — no owning fn;
      ;; the served path is the whole story.
      :resource-override (some-> (:path m) str)
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
                 (fd/field-entries fn-names source-version target-version))]
    (cond-> {:entity-name entity-name
             :entity-id (str entity-id)
             :change change}
      slot-id (assoc :slot-id (str slot-id)
                     :slot-name (:name (get slots-by-id slot-id)))
      (= :binding entity-name) (assoc :binding-id (str entity-id))
      ;; The RAW ref ids beside the labels: the editor's compare mode
      ;; draws the compared branch's subtree for a replaced ref, and a
      ;; label cannot be fetched.
      (:ref-fn-id source-version) (assoc :source-ref (str (:ref-fn-id source-version)))
      (:ref-fn-id target-version) (assoc :target-ref (str (:ref-fn-id target-version)))
      (= :binding-list-item entity-name)
      (assoc :binding-id (str (:binding-id (side-version diff)))
             :item-id (str entity-id)
             :position (:position (side-version diff)))
      (seq fields) (assoc :fields fields)
      (not= :modified change)
      (assoc :preview (entry-preview fn-names diff)))))


(def ^:private entry-rank
  {:fn 0 :fn-slot 1 :binding 2 :binding-list-item 3 :resource-override 4})


(defn- ns-path-fn
  "A `namespace-id → \"a.b.c\"` resolver over one read of the ns table."
  [base-storage]
  (ns-path/path-map (sp/query-entities base-storage :ns {})))


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
        ref-ids (fd/ref-ids (mapcat (juxt :source-version :target-version) diffs))
        name-ids (into ref-ids (keep ::owner rows))
        fn-names (fd/resolve-fn-names base-storage name-ids
                                      source-branch-id target-branch-id)
        ;; Namespace PATH per owning fn — the Explorer's compare-mode
        ;; aggregates and ghost rows group by it, and the CLIENT can't
        ;; derive it for a fn that exists only on the compared branch
        ;; (its lookups hold the current branch only). `:ns` rows are
        ;; identity-plane (unversioned), so one small read covers both
        ;; sides.
        owner-ids (vec (distinct (keep ::owner rows)))
        owner-fns (if (seq owner-ids)
                    (sp/read-entities base-storage :fn owner-ids)
                    {})
        ns-path (ns-path-fn base-storage)
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
                                    (fd/fn-label fn-names owner)
                                    (if (every? #(= :resource-override (:entity-name %)) rs)
                                      "(assets)"
                                      "(unowned)"))
                        :ns-path (some-> (get owner-fns owner)
                                         :namespace-id ns-path)
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


(defn affected-fns
  "Fns whose BEHAVIOUR may differ although none of their own rows did:
   everything that transitively depends on a changed fn — through
   `parent-ids`, ref bindings, type overrides, resolvers — under the
   compiler's own reverse-dependency index (`deps/build-reverse-deps`),
   so \"changed inside\" means exactly what the compiler would recompile.

   Returns `{fn-id-str {:via changed-fn-id-str :depth n}}` for every
   affected fn that is NOT itself in `changed-ids`: `:via` is the nearest
   changed fn on the way (the breadth-first frontier that reached it
   first; seeds are walked in sorted order so ties are stable), `:depth`
   how many dependency hops away it sits. The editor rings such a card
   dashed and offers the `:via` fn as the thing to open or expand."
  [reverse-deps changed-ids]
  (let [seeds (->> changed-ids (keep #(some-> % str parse-uuid)) distinct (sort-by str))
        seed? (set seeds)]
    (loop [frontier (mapv (fn [id] [id id 0]) seeds)
           seen seed?
           acc {}]
      (if (empty? frontier)
        acc
        (let [[id via depth] (first frontier)
              nexts (->> (get reverse-deps id)
                         (remove seen)
                         (sort-by str))
              seen' (into seen nexts)
              acc' (if (seed? id)
                     acc
                     (assoc acc (str id) {:via (str via) :depth depth}))]
          (recur (into (subvec frontier 1)
                       (map (fn [n] [n via (inc depth)])) nexts)
                 seen'
                 acc'))))))


(defn affected-view
  "`affected-fns` for the diff-view `groups`, each entry carrying the
   fn's `:ns-path` (the Explorer aggregates the ∿ marks onto namespace
   rows exactly like the +/±/− ones, and it cannot know the namespace
   of a fn it has not loaded yet). One read of the affected fn rows +
   one of the ns table."
  [base-storage reverse-deps groups]
  (let [affected (affected-fns reverse-deps (keep :fn-id groups))
        ids (->> (keys affected) (keep parse-uuid) (vec))
        fns (if (seq ids) (sp/read-entities base-storage :fn ids) {})
        ns-path (if (seq ids) (ns-path-fn base-storage) (constantly nil))]
    (into {}
          (map (fn [[id info]]
                 [id (assoc info :ns-path
                            (some-> (get fns (parse-uuid id)) :namespace-id ns-path))]))
          affected)))
