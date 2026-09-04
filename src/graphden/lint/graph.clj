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
    [graphden.storage.protocol.core :as sp]))


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


(defn graph->fn-defs
  "Rebuild every COMPOSED fn row of a graph snapshot as an EDN fn-def
   (`:id` / `:name` / `:namespace` / `:parents` / `:args` /
   `:return-type` / `:lambda-params` / `:expects-effects` /
   `:branch-local?`), plus the set of base-fn / type-row names refs may
   resolve to. `ns-rows` are the `:ns` rows the snapshot does not
   carry."
  [{:keys [fns slots fn-slots bindings list-items]} ns-rows]
  (let [ns-path (ns-paths ns-rows)
        fn-by-id (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        items-by-binding (group-by :binding-id list-items)
        bindings-by-fn (group-by :fn-id bindings)
        fn-slots-by-fn (group-by :fn-id fn-slots)
        ref (partial ref-kw fn-by-id ns-path)
        slot-name (fn [slot-id] (some-> (get slot-by-id slot-id) :name keyword))
        args-of (fn [row]
                  (let [bound (into {}
                                    (keep (fn [b]
                                            (when-let [k (slot-name (:slot-id b))]
                                              [k (binding-value ref (get items-by-binding (:id b)) b)])))
                                    (get bindings-by-fn (:id row)))
                        renames (keep (fn [fs]
                                        (let [s (get slot-by-id (:slot-id fs))]
                                          (when-let [src (:source-slot-id s)]
                                            [(slot-name src) (keyword (:name s))])))
                                      (get fn-slots-by-fn (:id row)))]
                    (reduce (fn [m [src-name new-name]]
                              (if src-name
                                (update m src-name with-rename new-name)
                                m))
                            bound
                            renames)))
        fn-defs (into []
                      (comp (filter composed-row?)
                            (map (fn [row]
                                   (cond-> {:id (:id row)
                                            :name (fn-name-kw row)
                                            :namespace (or (ns-path (:namespace-id row)) "")
                                            :parents (into [] (keep ref) (:parent-ids row))
                                            :args (args-of row)}
                                     (:return-type-fn-id row) (assoc :return-type (ref (:return-type-fn-id row)))
                                     (some? (:lambda-params row)) (assoc :lambda-params (:lambda-params row))
                                     (some? (:expects-effects row)) (assoc :expects-effects (:expects-effects row))
                                     (:branch-local? row) (assoc :branch-local? true)))))
                      fns)
        vocab (into #{} (comp (remove composed-row?) (map fn-name-kw)) fns)]
    {:fn-defs fn-defs :base-fn-names vocab}))


;; -----------------------------------------------------------------------------
;; Lint a snapshot / a branch
;; -----------------------------------------------------------------------------

(defn platform-fn?
  "Package-synced this boot — the engine's platform predicate."
  [fd]
  (owned/owned-fn-id? (:id fd)))


(defn lint-graph
  "Warnings over a graph snapshot. `suppress` is the set of
   `lint/finding-key`s the author marked as not-an-issue."
  [graph ns-rows suppress]
  (let [{:keys [fn-defs base-fn-names]} (graph->fn-defs graph ns-rows)]
    (lint/warnings
      (lint/lint fn-defs {:base-fn-names base-fn-names
                          :platform-fn? platform-fn?
                          :suppress suppress}))))


(def ^:private memo
  "One-entry memo for the CACHED path: the last snapshot object linted
   and its result. The snapshot is replaced (not mutated) on every graph
   write, so identity is the freshness check; a different suppression
   set recomputes."
  (atom nil))


(defn- ns-rows
  [ctx]
  (vec (sp/query-entities (request/require-storage ctx) :ns {})))


(defn lint-branch
  "The current branch's lint warnings over the per-ctx graph snapshot
   (`cached-or-load-graph`), recomputed only when the snapshot object or
   the suppression set changed. The snapshot is what every reader sees:
   writes splice it inline and a load-on-miss that a write outran is
   discarded (`executor.context/fill-graph-cache!`), so a read right after
   an edit is the post-edit graph — no storage bypass needed."
  [ctx suppress]
  (let [suppress (set suppress)
        graph (types-api/cached-or-load-graph ctx)
        hit @memo]
    (if (and hit (identical? (:graph hit) graph) (= (:suppress hit) suppress))
      (:findings hit)
      (let [findings (lint-graph graph (ns-rows ctx) suppress)]
        (reset! memo {:graph graph :suppress suppress :findings findings})
        findings))))
