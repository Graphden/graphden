(ns graphden.lint.graph
  "The graph lint over a LIVE branch — the editor's side of
   `graphden.lint.core`.

   The corpus gate lints fns.edn; the editor lints what the branch
   resolves to right now. Both feed the same engine, so this namespace
   is only a translation: the per-ctx graph snapshot (fn / slot /
   fn-slot / binding / list-item rows, org-sliced and branch-resolved —
   `crud.types-api/cached-or-load-graph`) is rebuilt into the EDN
   fn-def shape the loader produces, with every reference spelled as
   a namespace-qualified keyword so the engine's index resolves it the
   way package sync would.

   Rows the package sync wrote (`packages.owned`) are platform fn-defs
   for the engine's `:platform-fn?` — never dead-code subjects, never a
   finding on their own.

   Nothing here is persisted (see `types.diagnostics` for the rule):
   findings are recomputed from the snapshot, memoised only while the
   snapshot object itself is unchanged."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.types-api :as types-api]
    [graphden.lint.core :as lint]
    [graphden.packages.owned :as owned]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vcore]))


;; -----------------------------------------------------------------------------
;; Rows → EDN fn-defs
;; -----------------------------------------------------------------------------

(defn ns-paths
  "Map ns-id → dotted path (`\"app.editor\"`) over `:ns` rows."
  [ns-rows]
  (let [by-id (into {} (map (juxt :id identity)) ns-rows)
        path (fn path
               [id]
               (when-let [r (get by-id id)]
                 (if-let [p (:parent-id r)]
                   (str (or (path p) "?") "." (:name r))
                   (:name r))))]
    (into {} (map (fn [r] [(:id r) (path (:id r))])) ns-rows)))


(defn- composed-row?
  [row]
  (boolean (seq (:parent-ids row))))


(defn- fn-name-kw
  "The row's name as the engine sees it — nameless rows get a
   generated `_anon-<id>` label (the engine skips `_anon-` subjects,
   expands them at their ref sites)."
  [row]
  (keyword (or (:name row) (str "_anon-" (:id row)))))


(defn- ref-kw
  "A reference to `fn-id` as a keyword: `:ns.path/name` for composed
   rows (qualified so per-namespace duplicate names resolve exactly;
   root-namespace rows get the empty namespace), the bare name for
   base-fns and type-rows (globally unique, resolved through
   `:base-fn-names`)."
  [fn-by-id ns-path fn-id]
  (when-let [row (get fn-by-id fn-id)]
    (if (composed-row? row)
      (keyword (or (ns-path (:namespace-id row)) "") (name (fn-name-kw row)))
      (fn-name-kw row))))


(defn- list-item-value
  [ref it]
  (cond
    (:ref-fn-id it) (ref (:ref-fn-id it))
    (:literal it) {:value (:value it) :literal? true}
    :else {:value (:value it)}))


(defn- binding-value
  "One binding row (+ its list items) → the fns.edn arg value shape."
  [ref items b]
  (let [type-kw (some-> (:type-override-fn-id b) ref)
        base (cond
               (:list-append b) (mapv #(list-item-value ref %) (sort-by :position items))
               (:ref-fn-id b) (ref (:ref-fn-id b))
               (:value-present b) {:value (:value b)}
               :else nil)
        spec (cond-> {}
               type-kw (assoc :type type-kw)
               (:terminal b) (assoc :terminal true)
               (:required b) (assoc :required true)
               (:resolver-fn-id b) (assoc :resolver (ref (:resolver-fn-id b))))]
    (cond
      (vector? base) base
      (and (keyword? base) (seq spec)) (assoc spec :ref base)
      (keyword? base) base
      (map? base) (merge base spec)
      (seq spec) spec
      :else {})))


(defn- with-rename
  "Fold a renamed-view slot into the arg value: `{:as new-name}` on top
   of whatever the source slot binds."
  [v new-name]
  (cond
    (nil? v) {:as new-name}
    (keyword? v) {:ref v :as new-name}
    (map? v) (assoc v :as new-name)
    :else v))


(defn- graph-indexes
  "The per-fn groupings `fn-def-of` reads — built once per snapshot."
  [{:keys [fns slots fn-slots bindings list-items]} ns-rows]
  (let [fn-by-id (into {} (map (juxt :id identity)) fns)
        ns-path (ns-paths ns-rows)]
    {:fn-by-id fn-by-id
     :ns-path ns-path
     :ref (partial ref-kw fn-by-id ns-path)
     :slot-by-id (into {} (map (juxt :id identity)) slots)
     :items-by-binding (group-by :binding-id list-items)
     :bindings-by-fn (group-by :fn-id bindings)
     :fn-slots-by-fn (group-by :fn-id fn-slots)}))


(defn- fn-def-of
  "One COMPOSED fn row as an EDN fn-def (`:id` / `:name` / `:namespace` /
   `:parents` / `:args` / `:return-type` / `:lambda-params` /
   `:expects-effects` / `:branch-local?`), every reference spelled as a
   namespace-qualified keyword."
  [{:keys [ns-path ref slot-by-id items-by-binding bindings-by-fn fn-slots-by-fn]} row]
  (let [slot-name (fn [slot-id] (some-> (get slot-by-id slot-id) :name keyword))
        bound (into {}
                    (keep (fn [b]
                            (when-let [k (slot-name (:slot-id b))]
                              [k (binding-value ref (get items-by-binding (:id b)) b)])))
                    (get bindings-by-fn (:id row)))
        renames (keep (fn [fs]
                        (let [s (get slot-by-id (:slot-id fs))]
                          (when-let [src (:source-slot-id s)]
                            [(slot-name src) (keyword (:name s))])))
                      (get fn-slots-by-fn (:id row)))
        args (reduce (fn [m [src-name new-name]]
                       (if src-name
                         (update m src-name with-rename new-name)
                         m))
                     bound
                     renames)]
    (cond-> {:id (:id row)
             :name (fn-name-kw row)
             :namespace (or (ns-path (:namespace-id row)) "")
             :parents (into [] (keep ref) (:parent-ids row))
             :args args}
      (:return-type-fn-id row) (assoc :return-type (ref (:return-type-fn-id row)))
      (some? (:lambda-params row)) (assoc :lambda-params (:lambda-params row))
      (some? (:expects-effects row)) (assoc :expects-effects (:expects-effects row))
      (:branch-local? row) (assoc :branch-local? true))))


(defn- vocabulary
  "The base-fn / type-row names refs may resolve to."
  [fns]
  (into #{} (comp (remove composed-row?) (map fn-name-kw)) fns))


(defn graph->fn-defs
  "Rebuild every COMPOSED fn row of a graph snapshot as an EDN fn-def,
   plus the set of base-fn / type-row names refs may resolve to.
   `ns-rows` are the `:ns` rows the snapshot does not carry."
  [{:keys [fns] :as graph} ns-rows]
  (let [ix (graph-indexes graph ns-rows)]
    {:fn-defs (into [] (comp (filter composed-row?) (map #(fn-def-of ix %))) fns)
     :base-fn-names (vocabulary fns)}))


;; -----------------------------------------------------------------------------
;; Lint a snapshot / a branch
;; -----------------------------------------------------------------------------

(defn platform-fn?
  "Package-synced this boot — the engine's platform predicate."
  [fd]
  (owned/owned-fn-id? (:id fd)))


(defn lint-graph
  "Warnings over a graph snapshot, from scratch. `suppress` is the set
   of `lint/finding-key`s the author marked as not-an-issue."
  [graph ns-rows suppress]
  (let [{:keys [fn-defs base-fn-names]} (graph->fn-defs graph ns-rows)]
    (lint/warnings
      (lint/lint fn-defs {:base-fn-names base-fn-names
                          :platform-fn? platform-fn?
                          :suppress suppress}))))


(def ^:private memo-cap
  "Branches whose lint state is kept — enough for the branches an editor
   session flips between; the oldest entry goes when a new branch
   arrives."
  16)


(def ^:private memo
  "Per-branch lint state: branch-id → the last snapshot object linted for
   it, the `:ns` rows, the per-fn EDN fn-defs, the engine's incremental
   state, the suppression set and the findings. The snapshot is replaced
   (not mutated) on every graph write, so identity is the freshness
   check; a new snapshot is diffed against the old one row by row and
   only the fns whose rows moved (plus their referrers) are rebuilt and
   re-linted (`lint/lint-with-state`). Keyed per branch so two branches
   open side by side (or two orgs on one executor) do not evict each
   other on every read."
  (atom {}))


(defn- remember!
  [branch-id entry]
  (swap! memo (fn [m]
                (let [m (assoc m branch-id (assoc entry :at (System/nanoTime)))]
                  (if (> (count m) memo-cap)
                    (dissoc m (key (apply min-key (comp :at val) m)))
                    m)))))


(defn- read-ns-rows
  [ctx]
  (vec (sp/query-entities (request/require-storage ctx) :ns {})))


(defn- rows-by-fn
  "The rows that make up each fn — `{fn-id [fn-row fn-slot-rows binding-rows item-rows]}`
   — so two snapshots can be compared per fn by row identity."
  [{:keys [fns fn-slots bindings list-items]}]
  (let [fs (group-by :fn-id fn-slots)
        bs (group-by :fn-id bindings)
        items (group-by :binding-id list-items)]
    (into {}
          (map (fn [row]
                 [(:id row)
                  [row
                   (get fs (:id row))
                   (get bs (:id row))
                   (mapcat #(get items (:id %)) (get bs (:id row)))]]))
          fns)))


(defn- same-rows?
  "Row-for-row identity: a write splices FRESH row objects for the fns it
   touched and keeps every other object, so identity is the cheap and
   exact 'did this fn's rows move' check."
  [[fn-a slots-a binds-a items-a] [fn-b slots-b binds-b items-b]]
  (and (identical? fn-a fn-b)
       (= (count slots-a) (count slots-b)) (every? true? (map identical? slots-a slots-b))
       (= (count binds-a) (count binds-b)) (every? true? (map identical? binds-a binds-b))
       (= (count items-a) (count items-b)) (every? true? (map identical? items-a items-b))))


(defn changed-fn-ids
  "The fn ids whose rows differ between two snapshots' `rows-by-fn`
   maps — moved, created or deleted."
  [old new]
  (set (concat (keep (fn [[id rows]] (when-not (same-rows? rows (get old id)) id)) new)
               (remove #(contains? new %) (keys old)))))


(defn- full-state
  "Lint a snapshot from scratch — the first read of a branch, or a
   namespace change (every fn-def's dotted path may have moved)."
  [graph ns-rows suppress]
  (let [ix (graph-indexes graph ns-rows)
        fn-defs (into {} (comp (filter composed-row?) (map (fn [row] [(:id row) (fn-def-of ix row)]))) (:fns graph))
        {:keys [findings state]} (lint/lint-with-state
                                   (vals fn-defs)
                                   {:base-fn-names (vocabulary (:fns graph))
                                    :platform-fn? platform-fn?
                                    :suppress suppress}
                                   (lint/empty-state)
                                   :all)]
    {:graph graph :ns-rows ns-rows :rows (rows-by-fn graph) :fn-defs fn-defs
     :lint-state state :suppress suppress :findings findings}))


(defn- delta-state
  "Re-lint after a write: rebuild the EDN of the fns whose rows moved and
   of the fns that reference them (a renamed target changes the referrer's
   spelling), hand the engine those keys as changed."
  [prev graph suppress]
  (let [ix (graph-indexes graph (:ns-rows prev))
        rows (rows-by-fn graph)
        moved (changed-fn-ids (:rows prev) rows)
        key-of lint/fn-key
        old-keys (into {} (map (fn [[id fd]] [id (key-of fd)])) (:fn-defs prev))
        refs-of (lint/referrers (:refs (:lint-state prev)))
        moved-keys (into #{} (keep old-keys) moved)
        rebuild (into moved
                      (comp (mapcat #(get refs-of %))
                            (keep (fn [k] (some (fn [[id kk]] (when (= kk k) id)) old-keys))))
                      moved-keys)
        fn-by-id (:fn-by-id ix)
        fn-defs (reduce (fn [m id]
                          (let [row (get fn-by-id id)]
                            (if (and row (composed-row? row))
                              (assoc m id (fn-def-of ix row))
                              (dissoc m id))))
                        (:fn-defs prev)
                        rebuild)
        changed (set (concat (keep old-keys rebuild)
                             (keep (fn [id] (some-> (get fn-defs id) key-of)) rebuild)))
        {:keys [findings state]} (lint/lint-with-state
                                   (vals fn-defs)
                                   {:base-fn-names (vocabulary (:fns graph))
                                    :platform-fn? platform-fn?
                                    :suppress suppress}
                                   (:lint-state prev)
                                   changed)]
    {:graph graph :ns-rows (:ns-rows prev) :rows rows :fn-defs fn-defs
     :lint-state state :suppress suppress :findings findings}))


(defn lint-branch
  "The current branch's lint warnings over the per-ctx graph snapshot
   (`cached-or-load-graph`). Answered from the branch's memo when the
   snapshot object and the suppression set are unchanged; after a write
   only the fns whose rows moved, and their referrers, are rebuilt and
   re-linted; a namespace change or a first read lints from scratch. The
   snapshot is what every reader sees: writes splice it inline and a
   load-on-miss that a write outran is discarded
   (`executor.context/fill-graph-cache!`), so a read right after an edit
   is the post-edit graph — no storage bypass needed."
  [ctx suppress]
  (let [suppress (set suppress)
        storage (request/require-storage ctx)
        graph (types-api/cached-or-load-graph ctx)
        branch-id (vcore/current-branch-id storage)
        prev (get @memo branch-id)]
    (if (and prev (identical? (:graph prev) graph) (= (:suppress prev) suppress))
      (lint/warnings (:findings prev))
      (let [nss (read-ns-rows ctx)
            entry (if (and prev (= (:ns-rows prev) nss))
                    (delta-state prev graph suppress)
                    (full-state graph nss suppress))]
        (remember! branch-id entry)
        (lint/warnings (:findings entry))))))
