(ns graphden.crud.entities.list
  "The graph READ side of `/api/graph/entities` — the five scopes the
   editor pulls (tree / namespace / search / index / subtree), the
   light-row projection they share, and the view-impl filter
   the tenancy addon installs over the dump.

   Split out of `crud.entities` as the one purely-read topic in that
   tree: no write path calls into it and it calls into none of them."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as secret-shape]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.owned :as owned]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]
    [graphden.types.diagnostics :as diag]
    [graphden.util.counters :as counters]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.storage.core :as vcore]))


(defn- subtree-fn-id-closure
  "BFS the set of fn-ids transitively reachable from `root-id` (or a
   collection of root ids) via:
   - `parent-ids` (inheritance chain)
   - `binding.ref-fn-id` for bindings owned by an in-set fn
   - `binding.type-override-fn-id` for those same bindings
   - `binding-list-item.ref-fn-id` for items under those bindings
   - `slot.type-fn-id` for slots in any in-set fn's `fn-slots` row

   These are exactly the edges that the editor + layout + runtime
   need to render or execute the root fn. Nothing else in the graph
   contributes to that view.

   `graph` is the full graph map from `cached-or-load-graph`."
  [graph root-id]
  (let [fns-by-id        (into {} (map (juxt :id identity)) (:fns graph))
        fn-slots-by-fn   (group-by :fn-id (:fn-slots graph))
        slots-by-id      (into {} (map (juxt :id identity)) (:slots graph))
        bindings-by-fn   (group-by :fn-id (:bindings graph))
        items-by-binding (group-by :binding-id (:list-items graph))
        seen (java.util.HashSet.)
        stack (java.util.ArrayDeque.)
        push! (fn [^java.util.UUID id]
                (when (and id (not (java.util.HashSet/.contains seen id)))
                  (java.util.ArrayDeque/.push stack id)))]
    (if (coll? root-id) (run! push! root-id) (push! root-id))
    (while (not (java.util.ArrayDeque/.isEmpty stack))
      (let [fid (java.util.ArrayDeque/.pop stack)]
        (when-not (java.util.HashSet/.contains seen fid)
          (java.util.HashSet/.add seen fid)
          (when-let [fn-row (get fns-by-id fid)]
            (doseq [pid (:parent-ids fn-row)] (push! pid))
            ;; The fn's own type-fn / impl references — so a fn's subtree is
            ;; self-contained for by-id type resolution once the editor no
            ;; longer holds a full-fns mirror. `base-fn-id` (composed → its
            ;; base), `return-type-fn-id` (base-fn's declared return type),
            ;; `element-fn-id` (a list type-row's element type). Each resolves
            ;; to a small base-fn / type-row.
            (push! (:base-fn-id fn-row))
            (push! (:return-type-fn-id fn-row))
            (push! (:element-fn-id fn-row))
            (doseq [b (get bindings-by-fn fid)]
              (push! (:ref-fn-id b))
              (push! (:type-override-fn-id b))
              (doseq [it (get items-by-binding (:id b))]
                (push! (:ref-fn-id it))))
            (doseq [fs (get fn-slots-by-fn fid)]
              (when-let [slot (get slots-by-id (:slot-id fs))]
                (push! (:type-fn-id slot))))))))
    (set seen)))


(defn- filter-graph-to-fn-ids
  "Filter every row in `graph` down to those that participate in the
   given `fn-id-set`. Mirrors the `subtree-fn-id-closure` edge rules:
   own bindings + own list-items + own fn-slots + their referenced
   slots."
  [graph fn-id-set]
  (let [kept-fns        (filterv #(contains? fn-id-set (:id %))     (:fns graph))
        kept-fn-slots   (filterv #(contains? fn-id-set (:fn-id %))  (:fn-slots graph))
        kept-slot-ids   (into #{} (map :slot-id) kept-fn-slots)
        kept-slots      (filterv #(contains? kept-slot-ids (:id %)) (:slots graph))
        kept-bindings   (filterv #(contains? fn-id-set (:fn-id %))  (:bindings graph))
        kept-binding-ids (into #{} (map :id) kept-bindings)
        kept-items      (filterv #(contains? kept-binding-ids (:binding-id %))
                                 (:list-items graph))]
    {:fns        kept-fns
     :slots      kept-slots
     :fn-slots   kept-fn-slots
     :bindings   kept-bindings
     :list-items kept-items}))


(defonce ^:private blanked-rows
  ;; raw fn row → the same row with `:parent-ids []`. Weak keys: an entry
  ;; lives as long as the snapshot holding its raw row.
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))


(defn- blanked-row
  "`row` with its parents blanked — the SAME object for the same raw row
   across calls, so a concealed copy of the next snapshot shares it with
   the last one and row-identity diffs over concealed copies (the lint
   memo's delta re-lint) see an unchanged concealed fn as unchanged."
  [row]
  (or (java.util.Map/.get blanked-rows row)
      (let [b (assoc row :parent-ids [])]
        (java.util.Map/.put blanked-rows row b)
        b)))


(defn strip-impl-of
  "Hide the internal COMPOSITION of the fns whose ids are in `hidden-fn-ids`
   from a graph dump: blank each hidden fn's `:parent-ids` and drop its
   bindings + binding-list-items, leaving its SIGNATURE (name / namespace /
   return-type / fn-slots / slots) intact. The fn stays discoverable and
   executable — only how it is built is concealed; the executor runs the full
   graph server-side, so hiding this from a viewer never affects execution.

   Pure: the caller decides which ids are hidden (own-org ownership /
   `:view-impl` grant — see the tenancy filter). Gracefully no-ops on dump
   shapes without `:fns` (`:tree` / `:namespace` / `:search`)."
  [graph hidden-fn-ids]
  (if (empty? hidden-fn-ids)
    graph
    (let [dropped-binding-ids (into #{}
                                    (comp (filter #(contains? hidden-fn-ids (:fn-id %)))
                                          (map :id))
                                    (:bindings graph))]
      (cond-> graph
        (:fns graph)        (update :fns
                                    (fn [fns]
                                      (mapv #(if (contains? hidden-fn-ids (:id %))
                                               (blanked-row %)
                                               %)
                                            fns)))
        (:bindings graph)   (update :bindings
                                    (fn [bs]
                                      (filterv #(not (contains? hidden-fn-ids (:fn-id %))) bs)))
        (:list-items graph) (update :list-items
                                    (fn [items]
                                      (filterv #(not (contains? dropped-binding-ids (:binding-id %)))
                                               items)))))))


;; Seam: a `(fn [graph-dump] -> graph-dump)` the tenancy addon installs to
;; strip the composition of fns the CURRENT viewer lacks `:view-impl` on —
;; `strip-impl-of` with the hidden set computed from the request's grants +
;; org. nil (no addon / single-tenant) = identity, everything visible. Held
;; in an atom so the addon installs it at init with no compile-time dep from
;; this layer up into tenancy. (`defonce` takes no docstring — hence the
;; comment; `defonce` so a namespace reload doesn't wipe the installed filter.)
(defonce view-impl-filter (atom nil))


(defn apply-view-impl-filter
  "Run the installed `view-impl-filter` over a graph dump; identity when the
   seam is unset (single-tenant / no tenancy addon)."
  [graph]
  (if-let [f @view-impl-filter]
    (f graph)
    graph))


(defn hidden-fn-ids
  "The ids among `fn-rows` whose composition the installed view-impl
   filter conceals from the current viewer. Probes the seam ONCE with a
   dump carrying one synthetic binding per fn: the filter drops a hidden
   fn's bindings, so a fn whose binding did not survive is hidden. `#{}`
   with no filter installed. For reads that must decide about MANY fns'
   internals without shipping a graph dump (an execution trace's frames)."
  [fn-rows]
  (if (or (empty? fn-rows) (nil? @view-impl-filter))
    #{}
    (let [ids (into #{} (keep :id) fn-rows)
          seen (into #{} (map :fn-id)
                     (:bindings (apply-view-impl-filter
                                  {:fns (vec fn-rows)
                                   :bindings (mapv (fn [id] {:fn-id id}) ids)})))]
      (into #{} (remove seen) ids))))


(defn impl-visible?
  "Would the installed view-impl filter show `fn-row`'s composition to the
   current viewer? The one-fn case of `hidden-fn-ids`. Always true with no
   filter installed. For reads that answer about ONE fn's internals
   without shipping a graph dump (`describe-fn`'s unread bindings)."
  [fn-row]
  (empty? (hidden-fn-ids [fn-row])))


(defn unknown-fn-hidden?
  "Would the viewer be denied the internals of a fn the reader cannot
   identify (a row it cannot read, a frame whose ancestry is lost)? The
   filter is asked about an ownerless, namespace-less row: a tenant sees
   only its own org's (or granted) internals, so such a row reads as
   hidden; no filter / the platform context sees it. Reads that must fail
   CLOSED on missing information key on this instead of guessing."
  []
  (not (impl-visible? {:id (java.util.UUID/randomUUID)})))


(defn- visible-subtree
  "The five-table slice reachable from `root-id` AS THE CURRENT VIEWER MAY
   SEE IT: the view-impl filter runs over the raw closure, then the closure
   is re-walked over what survived. Stripping only after the walk (as the
   whole-dump scopes do) would still ship every fn a hidden fn is BUILT
   from — its helpers' names and, for the ones the viewer can see, their
   full wiring — which is exactly the composition being concealed.
   Identity-cheap when nothing is hidden: the filter hands back the same
   map and the second walk is skipped."
  [base root-id]
  (let [raw (filter-graph-to-fn-ids base (subtree-fn-id-closure base root-id))
        seen (apply-view-impl-filter raw)]
    (if (identical? seen raw)
      raw
      (filter-graph-to-fn-ids seen (subtree-fn-id-closure seen root-id)))))


(defn- has-composition?
  "Does `f` carry anything `strip-impl-of` would conceal — parents or
   own bindings? Only such rows need the (per-fn, grant-reading)
   filter's verdict: a base-fn or a binding-less type-row reads the
   same concealed or not."
  [bound-fn-ids f]
  (boolean (or (seq (:parent-ids f)) (contains? bound-fn-ids (:id f)))))


(def ^:private concealed-memo-cap
  "(snapshot, hidden-set) pairs whose concealed copy is kept — one per
   live branch ctx × distinct viewer grant set."
  32)


(defonce ^:private concealed-memo
  ;; [[graph hidden concealed] …], most recent last.
  (atom []))


(defn- memo-strip
  "`strip-impl-of graph hidden`, memoised on the snapshot's IDENTITY +
   the hidden set, so the identity-keyed memos downstream (layout
   lookups, the lint memo) keep hitting between writes for one viewer
   grant set."
  [graph hidden]
  (or (some (fn [[g h c]] (when (and (identical? g graph) (= h hidden)) c))
            @concealed-memo)
      (let [c (strip-impl-of graph hidden)]
        (swap! concealed-memo
               (fn [entries]
                 (conj (vec (take-last (dec concealed-memo-cap) entries))
                       [graph hidden c])))
        c)))


(defn concealed-view
  "`{:graph :hidden :scope}` — `graph` (a five-table dump) AS THE CURRENT
   VIEWER MAY SEE IT, and the ids whose composition was concealed. With
   `root-ids`, only the fns reachable from them (`subtree-fn-id-closure`
   over the raw graph — returned as `:scope`; nil = the whole graph, as
   is every answer with no filter installed) are put to the filter: a
   walk from those roots
   over the concealed copy never gets beyond that set, and a verdict
   over the whole graph would read every fn's grants to draw one card.
   `:graph` is the SAME map when nothing is hidden (or no filter is
   installed) and a memoised copy otherwise."
  ([graph] (concealed-view graph nil))
  ([graph root-ids]
   (if (nil? @view-impl-filter)
     {:graph graph :hidden #{}}
     (let [scope (when root-ids (subtree-fn-id-closure graph root-ids))
           bound (into #{} (map :fn-id) (:bindings graph))
           rows (filterv #(and (or (nil? scope) (contains? scope (:id %)))
                               (has-composition? bound %))
                         (:fns graph))
           hidden (hidden-fn-ids rows)]
       {:graph (if (empty? hidden) graph (memo-strip graph hidden))
        :hidden hidden
        :scope scope}))))


(defn concealed-export-rows
  "The raw five-table `rows` (`packages.export/read-graph` — what a BYO
   executor loads, `GET /api/export/graph-rows`) as the CURRENT viewer may
   see them. A fn whose composition is hidden ships as a SIGNATURE-ONLY
   row: `:parent-ids []` and `:concealed? true` (so the executor tells it
   from a type-row and refuses to run it — `:execution-error/fn-concealed`),
   with none of its bindings / list items and none of its fn-slots that
   rename an inherited slot (their source slot is an internal of its
   chain). Rows reachable ONLY through hidden composition — the anonymous
   helpers a hidden fn is built from — are left out altogether: every row
   that ships is visible or a named fn the viewer can already find.
   Identity when nothing is hidden (or no filter is installed)."
  [rows]
  (let [{g :graph hidden :hidden} (concealed-view rows)]
    (if (empty? hidden)
      rows
      (let [slot-by-id (into {} (map (juxt :id identity)) (:slots g))
            renames-internal? (fn [fs]
                                (and (contains? hidden (:fn-id fs))
                                     (:source-slot-id (get slot-by-id (:slot-id fs)))))
            g (update g :fn-slots #(filterv (complement renames-internal?) %))
            ;; Every row roots the walk except an anonymous COMPOSED one
            ;; (a helper — it ships only when something shipped refs it)
            ;; and an anonymous hidden one (nothing of it is the viewer's).
            anon-helper? #(and (nil? (:name %))
                               (or (seq (:parent-ids %)) (contains? hidden (:id %))))
            roots (into [] (comp (remove anon-helper?) (map :id)) (:fns g))
            kept (filter-graph-to-fn-ids g (subtree-fn-id-closure g roots))]
        (update kept :fns (fn [fs]
                            (mapv #(cond-> % (contains? hidden (:id %)) (assoc :concealed? true))
                                  fs)))))))


(defn ancestor-ids
  "`fn-id` and every fn in its `:parent-ids` closure over the in-memory
   `{fn-id → fn-row}` map."
  [fns-by-id fn-id]
  (loop [queue [fn-id] seen #{}]
    (if-let [cur (first queue)]
      (if (contains? seen cur)
        (recur (rest queue) seen)
        (recur (concat (rest queue) (:parent-ids (get fns-by-id cur))) (conj seen cur)))
      seen)))


(defn conceal-parents
  "`fns-by-id` with the parents of every fn in the ancestor closures of
   `fn-ids` whose composition the viewer may not see blanked — the
   inheritance chains exactly as far as the viewer may follow them. A
   walk up the result names only ancestors the viewer could have read
   off the graph; comparing its `ancestor-ids` with the raw map's tells
   whether a fn's chain passes through concealed composition. The same
   map when nothing in those chains is hidden (or no filter installed)."
  [fns-by-id fn-ids]
  (if (nil? @view-impl-filter)
    fns-by-id
    (let [rows (into [] (comp (mapcat #(ancestor-ids fns-by-id %))
                              (distinct)
                              (keep fns-by-id)
                              (filter (comp seq :parent-ids)))
                     fn-ids)]
      (reduce (fn [m id] (assoc-in m [id :parent-ids] []))
              fns-by-id
              (hidden-fn-ids rows)))))


(defn chain-concealed?
  "Does `fn-id`'s inheritance chain (itself included) run through a fn
   whose composition the viewer may not see? `seen-by-id` is
   `conceal-parents` of `fns-by-id` over (at least) `fn-id`; the chain
   is concealed where the viewer's walk stops short of the raw one."
  [fns-by-id seen-by-id fn-id]
  (and (not (identical? seen-by-id fns-by-id))
       (not= (ancestor-ids fns-by-id fn-id) (ancestor-ids seen-by-id fn-id))))


(defn viewer-rule-owner
  "`registry/rule-owner-info-of-id` as the CURRENT viewer may know it.
   The owner is the base-fn at the root of the fn's primary-parent chain
   — part of how the fn is built — so it is nil for a fn the viewer's
   graph does not hold (another org's private fn) and for one whose
   chain runs through concealed composition (`chain-concealed?`).
   Unchanged with no filter installed."
  [ctx fn-id]
  (when-let [info (registry/rule-owner-info-of-id fn-id)]
    (if (nil? @view-impl-filter)
      info
      (let [fns-by-id (into {} (map (juxt :id identity))
                            (:fns (types-api/cached-or-load-graph ctx)))]
        (when (and (contains? fns-by-id fn-id)
                   (not (chain-concealed? fns-by-id (conceal-parents fns-by-id [fn-id]) fn-id)))
          info)))))


(def concealed-entry-fields
  "Rich-types registry entry fields that ARE a fn's composition: the
   bindings made anywhere in its chain, its primary parent (the chain
   itself) and the per-binding effect contributions (keyed by binding
   name). The rest — `:args` / `:return` / `:effects` / the slot types
   of its free args — is its signature."
  [:resolved-bindings :primary-parent :arg-effects])


(defn viewer-rich-entry
  "Registry `entry` of the fn `fn-id` as the CURRENT viewer may read it.
   The registry's by-id index is org-agnostic (every org's fns land in
   the global index, and the executor needs them there), so a
   request-facing read by id asks the viewer's own storage first: nil
   when it cannot read the fn (another org's private fn — not even its
   existence is the viewer's), the entry minus `concealed-entry-fields`
   when its composition is hidden, the entry itself otherwise. Identity
   with no filter installed — the single-tenant path reads nothing."
  [storage fn-id entry]
  (if (or (nil? entry) (nil? @view-impl-filter))
    entry
    (when-let [row (sp/read-entity storage :fn fn-id)]
      (when (seq (:fns (tctx/apply-graph-read-filter {:fns [row]})))
        (if (impl-visible? row)
          entry
          (apply dissoc entry concealed-entry-fields))))))


(def ^:private light-fn-fields
  "The per-fn columns the editor's sidebar / picker / search views
   actually read. Every other column (slots, bindings, and the bulk of
   the scalar fn columns) is fetched on demand via `:subtree` when a fn
   is opened. Keep this in sync with the fields consumed in
   `editor-sidebar.js` / `editor-fn-picker.js` / `editor-data.js`.

   `:used-as-parent-count` / `:used-as-ref-count` are server-computed
   reverse-reference counts over the WHOLE graph (see `reverse-ref-index`),
   so the editor's delete/edit gate stays correct once it no longer holds
   a full-fns mirror to count against. Both are omitted (→ 0 client-side)
   when zero.

   `:org-id` rides along so the view-impl filter (tenancy) can tell a
   viewer's OWN-org fns (internals visible) from public / shared ones
   (internals hidden) in the light scopes too; it is dropped from the wire
   when nil (single-tenant) by the `remove nil? val` projection."
  [:id :name :namespace-id :org-id :role :description :constraint
   :parent-ids :return-type-fn-id :package-owned :type-error-count
   :used-as-parent-count :used-as-ref-count])


(defn- reverse-ref-index
  "Reverse-reference tallies over the ENTIRE graph, so a caller holding
   only a slice can still answer \"how many fns depend on X\":

   - `:as-parent` — fn-id → #fns listing it in their `parent-ids`.
   - `:as-ref`    — fn-id → #bindings + #list-items whose `ref-fn-id`
     points at it.

   These are exactly the dependency kinds the delete guard blocks on
   (`web/crud` `:_delete-fn-*`), so the editor's up-front gate matches the
   server's 409 instead of drifting from it. `resolver-fn-id` counts as a
   ref: the resolver runs at the owner's arg-resolution time, so deleting
   an in-use resolver breaks EXECUTION (fn-not-found at first force), not
   just typing. `type-override-fn-id` / `slot.type-fn-id` are intentionally
   NOT counted — typing degrades gracefully and the delete guard doesn't
   block on them either."
  [graph]
  {:as-parent (reduce (fn [m f] (reduce (fn [m pid] (update m pid (fnil inc 0))) m (:parent-ids f)))
                      {} (:fns graph))
   :as-ref (as-> {} m
                 (reduce (fn [m b]
                           (let [m (if-let [r (:ref-fn-id b)] (update m r (fnil inc 0)) m)]
                             (if-let [rz (:resolver-fn-id b)] (update m rz (fnil inc 0)) m)))
                         m (:bindings graph))
                 (reduce (fn [m it] (if-let [r (:ref-fn-id it)] (update m r (fnil inc 0)) m)) m (:list-items graph)))})


(defn- with-ref-counts
  "Annotate a fn row with its reverse-reference counts from `rev`, omitting
   either count when zero (an absent key reads as 0 client-side)."
  [rev f]
  (let [ap (get (:as-parent rev) (:id f) 0)
        ar (get (:as-ref rev) (:id f) 0)]
    (cond-> f
      (pos? ap) (assoc :used-as-parent-count ap)
      (pos? ar) (assoc :used-as-ref-count ar))))


(defn light-fn-row
  "Project a (roled) fn row — annotated with reverse-ref counts from `rev`
   — down to `light-fn-fields`, dropping nils so the wire payload carries
   no `\"x\":null` churn (an absent key reads as `undefined` client-side,
   identical to the editor's truthy checks)."
  [rev f]
  (into {} (remove (comp nil? val)) (select-keys (with-ref-counts rev f) light-fn-fields)))


(def ^:dynamic *default-search-limit*
  "Cap on `:search` results. The sidebar filter / fn-picker only render a
   bounded list; an unbounded match on a huge graph would defeat the
   whole point of moving the filter server-side. `:truncated?` in the
   response tells the client more matched than were returned.

   Dynamic (and public — `entities-test` binds it from another ns) so
   tests can `binding` it low, thread-local, instead of a
   process-global `with-redefs` — it's a cold constant read on the
   search path, so the Var deref costs nothing that matters."
  200)


;; --- the graph reads' per-scope projections ----------------------------------
;; One shared lazily-realised env (`graph-list-env`) + one defn- per scope
;; (round-3 readability split of the former 6-branch cond body). Each branch
;; forces only the delays it needs. (The :tree scope projects no fn rows,
;; but since the per-ns :type-count it does realise `roled-fns` — a cheap
;; in-memory classification pass, no extra I/O.)

(defn- graph-list-env
  "Shared lazy environment for the per-scope projections: the cached
   graph `:base`, the role-annotator, and delays over the expensive
   whole-graph derivations."
  [ctx storage]
  (let [base (types-api/cached-or-load-graph ctx)
        fn-slots-by-fn (group-by :fn-id (:fn-slots base))
        rich-snapshot (delay (registry/rich-types-snapshot))
        ;; Per-fn diagnostic counts for the CURRENT branch (error-
        ;; tolerance Phase 3) — a cheap in-memory map lookup. Stamped on
        ;; every projected row (the type-errors lens's ⚠ N marker reads
        ;; it off the light rows too, not only the subtree) and summed
        ;; per namespace by the `:tree` scope.
        diag-counts (delay (into {}
                                 (map (fn [[fid ds]] [fid (count ds)]))
                                 (diag/branch-errors (vcore/current-branch-id storage))))
        role-of (fn [f]
                  (let [errs (get @diag-counts (:id f) 0)]
                    (cond-> (assoc f :role
                                   (types-api/compute-fn-role
                                     f
                                     (boolean (seq (get fn-slots-by-fn (:id f))))
                                     @rich-snapshot))
                      ;; Package-synced fns are API-read-only (package-guard
                      ;; answers 403 on binding writes + deletes). The flag
                      ;; rides out with the row so the editor can HIDE those
                      ;; affordances instead of offering a click that fails.
                      ;; Omitted when false — costs nothing on user fns.
                      (owned/owned-fn-id? (:id f)) (assoc :package-owned true)
                      (pos? errs) (assoc :type-error-count errs))))]
    {:base base
     :branch-id (vcore/current-branch-id storage)
     :rich-snapshot rich-snapshot
     :role-of role-of
     :roled-fns (delay (mapv role-of (:fns base)))
     ;; Whole-graph reverse-ref tallies — realised only for the scopes
     ;; that project fn rows (`:namespace` / `:search` / `:subtree`).
     :rev-index (delay (reverse-ref-index base))
     :diag-counts diag-counts
     :namespaces (delay (vec (sp/query-entities storage :ns {})))}))


(def ^:private tree-kinds-memo
  "Per-branch memo of the sidebar's per-namespace KIND counts (named fns
   / type-rows / plain fns): branch-id → the graph snapshot object and
   rich-types snapshot they were computed from, and the counts. Those
   counts are a pure function of the two snapshots, and computing them
   meant annotating the role of EVERY fn on every sidebar paint — the
   one O(all-fns) walk left on the tree path after the O(namespaces)
   redesign, and the 4× drift the perf trend flagged in 2026-09. A
   write replaces the snapshot object (`executor.context/splice-graph-
   cache!`), so identity is the freshness check; the per-namespace
   diagnostic counts are NOT memoised here (they move without a graph
   write) and stay per request. Bounded like the lint memo."
  (atom {}))


(def ^:private tree-kinds-memo-cap 16)


(defn forget-branch!
  "Release a deleted branch's sidebar snapshots without clearing other
   branches. The stamp also vetoes publication by an in-flight old read."
  [branch-id]
  (swap! tree-kinds-memo
         #(with-meta (dissoc % branch-id) {:generation (Object.)}))
  nil)


(defn- ns-kind-counts
  "`{namespace-id {:count n :types n :plain n}}` over the named fns —
   the memoised half of the tree payload."
  [base roled-fns]
  (let [;; Secret-leaf ids resolved WITHOUT a query: registry tag → base-fn
        ;; NAMES (globally unique for base-fns) → id match over the
        ;; in-memory graph. Empty when web.vault isn't loaded.
        secret-leaf-ids (let [names (into #{} (map name)
                                          (registry/fn-names-with-tag :secret-shape))]
                          (into #{}
                                (comp (filter (comp names str :name)) (map :id))
                                (:fns base)))
        secret-shaped? (fn [f] (boolean (some #(secret-shape/secret-fn? f %) secret-leaf-ids)))]
    (counters/count! :sidebar/tree-kinds-computed)
    (into {}
          (map (fn [[nid fns]]
                 [nid {:count (count fns)
                       :types (count (filter (comp types-api/type-lens-roles :role) fns))
                       :plain (count (remove #(or (types-api/type-lens-roles (:role %))
                                                  (secret-shaped? %))
                                             fns))}]))
          (group-by :namespace-id (filter :name @roled-fns)))))


(defn- tree-kinds
  [branch-id base rich-snapshot roled-fns generation]
  (let [rich @rich-snapshot
        hit (get @tree-kinds-memo branch-id)]
    (if (and hit (identical? (:base hit) base) (identical? (:rich hit) rich))
      (:kinds hit)
      (let [kinds (ns-kind-counts base roled-fns)]
        (swap! tree-kinds-memo
               (fn [m]
                 (if-not (identical? generation (:generation (meta m)))
                   m
                   (let [m (assoc m branch-id {:base base :rich rich :kinds kinds :at (System/nanoTime)})]
                     (if (> (count m) tree-kinds-memo-cap)
                       (dissoc m (key (apply min-key (comp :at val) m)))
                       m)))))
        kinds))))


(defn- list-scope-tree
  "Sidebar init: the namespace list + a per-namespace count of NAMED fns
   (anonymous fns are never shown as leaves). No fn rows at all — leaves
   load lazily via `:namespace`. This is the O(namespaces) replacement
   for the O(all-fns) `:index` pull that the editor fetched on every
   init AND every post-mutation refresh. Each count row additively
   carries (when >0):
   - `:type-error-count` — recorded diagnostics on fns of that
     namespace, current branch; the sidebar's per-namespace warning chip.
   - `:type-count` — NAMED type-rows (roles per
     `types-api/type-lens-roles`).
   - `:fn-count` — NAMED plain fns: not a type-row, not secret-shaped
     (parents = exactly a `:secret-shape`-tagged base-fn; those are the
     secrets lens's kind, resolved in-memory via the registry tag).
   The kind counts let the sidebar's fn/types lenses keep a
   not-yet-loaded namespace visible instead of silently hiding every
   namespace whose leaves were never fetched. (A service-backed or
   app-routed fn still counts here — the server doesn't classify those
   kinds; the rare namespace holding ONLY such fns over-shows.)"
  [{:keys [base diag-counts namespaces roled-fns rich-snapshot branch-id tree-generation]}]
  (let [ns-of-fn (when (seq @diag-counts)
                   (into {} (map (juxt :id :namespace-id)) (:fns base)))
        ;; Count ONLY fns present in `base` (the viewer's own+public
        ;; slice). The diagnostics store is keyed branch×fn with no
        ;; org dimension, so on the shared default branch it also
        ;; holds foreign orgs' fn-ids — those must not surface as
        ;; phantom per-namespace error counts. A viewer's own
        ;; diagnosed fn (named OR anonymous) is always in `base`, and
        ;; a legitimately namespace-less root fn maps to nil — kept,
        ;; because `contains?` (not `get`) does the dropping.
        ns-err (reduce (fn [m [fid n]]
                         (if (contains? ns-of-fn fid)
                           (update m (get ns-of-fn fid) (fnil + 0) n)
                           m))
                       {} @diag-counts)
        counts (mapv (fn [[nid {n :count :keys [types plain]}]]
                       (let [errs (get ns-err nid 0)]
                         (cond-> {:namespace-id nid :count n}
                           (pos? errs) (assoc :type-error-count errs)
                           (pos? types) (assoc :type-count types)
                           (pos? plain) (assoc :fn-count plain))))
                     (tree-kinds branch-id base rich-snapshot roled-fns tree-generation))
        ;; Namespaces whose only diagnosed fns are anonymous still
        ;; get a chip row (count 0 reads falsy client-side).
        covered (into #{} (map :namespace-id) counts)
        extra (into []
                    (comp (remove (fn [[nid _]] (contains? covered nid)))
                          (map (fn [[nid errs]]
                                 {:namespace-id nid :count 0
                                  :type-error-count errs})))
                    ns-err)]
    {:namespaces @namespaces
     :counts (into counts extra)}))


(defn- list-scope-namespace
  "Lazy per-namespace expand: light rows for one namespace's named fns.
   A `nil` `namespace-id` intentionally selects the \"(root)\" bucket —
   the namespace-less fns the sidebar renders under its `(primitives)`
   node — since `nil = (:namespace-id f)` matches them."
  [{:keys [base rev-index role-of]} namespace-id]
  {:fns (into []
              (comp (filter #(and (:name %) (= namespace-id (:namespace-id %))))
                    (map (comp (partial light-fn-row @rev-index) role-of)))
              (:fns base))})


(defn- list-scope-search
  "Server-side filter: case-insensitive substring on each fn's QUALIFIED
   dotted name (`core.logic.assert-eq`; a root fn is its bare name), so
   a bare-name, a namespace-prefixed, and a namespace-only needle all
   match; with `descriptions?` (the `:search-text` scope) also — ranked
   last — on the fn's `:description`, so a caller who knows the CONCEPT
   but not the name (an AI over `search-fns`) still lands. The editor's
   sidebar filter keeps the name-only `:search` scope: its rows must all
   visibly carry the needle. `/` in the needle normalizes to `.` — the canonical
   `ns.path/name` spelling the rest of the product prints is accepted
   verbatim. Capped at `*default-search-limit*` (light rows already
   carry `:description`, so a description hit shows WHY it matched).
   Replaces the client-side scan over the (former) full-fns mirror in
   the sidebar filter box, the fn / namespace / MI-reparent pickers,
   and name→id resolution."
  [{:keys [base rev-index role-of namespaces]} q descriptions?]
  (let [needle (some-> q str/lower-case str/trim not-empty
                       (str/replace "/" "."))
        paths (when needle (ns-path/path-map @namespaces))
        qualified (fn [f]
                    (let [p (get paths (:namespace-id f))]
                      (if (seq p) (str p "." (:name f)) (:name f))))
        ;; Rank BEFORE capping: an exact-name hit must survive the cap even
        ;; when a short needle also matches a whole namespace's worth of
        ;; qualified names (`str` matches everything under `core.strings`).
        tier (fn [f]
               (let [n (str/lower-case (:name f))]
                 (cond
                   (= n needle) 0
                   (str/includes? n needle) 1
                   (str/includes? (str/lower-case (qualified f)) needle) 2
                   (and descriptions?
                        (str/includes? (str/lower-case (or (:description f) "")) needle)) 3)))
        matches (when needle
                  (->> (:fns base)
                       (keep #(when (:name %)
                                (when-let [t (tier %)] [t %])))
                       (sort-by first)
                       (mapv second)))
        limited (into [] (take *default-search-limit*) matches)]
    {:fns (mapv (comp (partial light-fn-row @rev-index) role-of) limited)
     :truncated? (boolean (and needle (> (count matches) *default-search-limit*)))}))


(defn- concealed-list-env
  "`env` over the graph AS THE CURRENT VIEWER MAY SEE IT (the view-impl
   seam): a fn whose composition is hidden keeps its signature and loses
   its parent-ids / bindings / list-items, so every axis evaluated over
   the env — `uses` (no match THROUGH a hidden fn: the answer would be a
   membership oracle for its internals), `unused`, a graph view's decoded
   filters — sees only what the viewer could read, and the light rows
   ship the concealed `:parent-ids`. Roles stay those of the full rows
   (a hidden composed fn still reads `:composed`, as in every other light
   scope). The same env when nothing is hidden."
  [env]
  (let [base (:base env)
        seen (apply-view-impl-filter base)]
    (if (identical? seen base)
      env
      (let [parents-of (into {} (map (juxt :id :parent-ids)) (:fns seen))
            roled (:roled-fns env)]
        (assoc env
               :base seen
               :roled-fns (delay (mapv #(assoc % :parent-ids (get parents-of (:id %) []))
                                       @roled)))))))


(defn filter-env
  "Shared graph projections over the current viewer's visible composition."
  [ctx]
  (concealed-list-env (graph-list-env ctx (:storage ctx))))


(defn- list-scope-index
  "Only `{:fns :namespaces}`, nil-valued fields dropped from each fn
   row. This is a sidebar / picker payload fetched fresh on every editor
   refresh (~3900 fns), and most fns leave the majority of columns null
   (org-id, deleted-at, anonymous-hash, constraint, base-fn-id,
   element-fn-id, return-type-fn-id…). Serialising `\"x\":null` ~3900×
   per column was ~25% of the ~1.9 MB response — pure churn on every
   keep-alive-closed fetch. An absent key reads as `undefined` in the
   editor's truthy checks exactly like `null`, so no data is lost; the
   per-fn detail (with all fields) still comes from the `:subtree`
   fetch on select."
  [{:keys [roled-fns namespaces]}]
  {:fns (mapv (fn [f] (into {} (remove (comp nil? val)) f)) @roled-fns)
   :namespaces @namespaces})


(defn- list-scope-subtree
  "Only the fns transitively reachable from `root-id` via inheritance +
   binding refs + type overrides + list-item refs + own-slot
   type-fn-ids, plus the rows they own — annotated with whole-graph
   reverse-ref counts (the graph-view delete/edit gate reads them off
   the fn row) and `:type-error-count` where diagnostics exist."
  [{:keys [base roled-fns rev-index diag-counts namespaces]} root-id]
  (let [roled-by-id (into {} (map (juxt :id identity)) @roled-fns)
        sub (visible-subtree base root-id)
        sub-roled-fns (mapv (fn [f]
                              (let [row (with-ref-counts @rev-index
                                          (or (get roled-by-id (:id f)) f))
                                    errs (get @diag-counts (:id row) 0)]
                                (cond-> row
                                  (pos? errs) (assoc :type-error-count errs))))
                            (:fns sub))
        ;; Include each fn's namespace AND its parent chain so
        ;; the sidebar can render the full path (e.g. `web.crud
        ;; .branches` needs `web` + `web.crud` + `web.crud
        ;; .branches`). Without the parent walk a leaf-only ns
        ;; slice has no recoverable label tree.
        ns-by-id (into {} (map (juxt :id identity)) @namespaces)
        ns-ids (loop [acc #{} pending (into #{} (keep :namespace-id) sub-roled-fns)]
                 (if-let [nid (first pending)]
                   (if (contains? acc nid)
                     (recur acc (disj pending nid))
                     (let [n (get ns-by-id nid)
                           p (:parent-id n)]
                       (recur (conj acc nid)
                              (cond-> (disj pending nid)
                                (and p (not (contains? acc p)))
                                (conj p)))))
                   acc))
        sub-namespaces (filterv #(contains? ns-ids (:id %)) @namespaces)]
    (assoc sub :fns sub-roled-fns :namespaces sub-namespaces)))


(defn- env
  "The shared lazy environment every projection below reads."
  [ctx]
  (graph-list-env ctx (request/require-storage ctx)))


;; The storage rows the editor renders the graph from, one projection per
;; shape over the shared lazy `graph-list-env` — which routes through the
;; shared graph-cache (populated by layout / compile-runtime), so editor
;; refreshes after mutations don't re-query the same five tables. Each
;; fn-row carries a `:role` so the sidebar groups Types vs Functions
;; without a round-trip through `/api/types`. Which projection a request
;; wants is graph composition: `GET /api/graph/entities` dispatches on its
;; `scope` in `web/crud-routes` (`:all-entities-handler`), and each MCP
;; tool binds the one it needs.

(defn graph-tree
  "`{:namespaces :counts}` — the O(namespaces) sidebar init."
  [ctx]
  (let [generation (:generation (meta @tree-kinds-memo))]
    (list-scope-tree (assoc (env ctx) :tree-generation generation))))


(defn graph-namespace-fns
  "`{:fns}` — one namespace's light rows (nil = the root bucket)."
  [ctx namespace-id]
  (list-scope-namespace (env ctx) namespace-id))


(defn graph-search
  "`{:fns}` — capped light rows whose qualified name contains `q`; with
   `descriptions?`, description matches too, ranked last."
  [ctx q descriptions?]
  (list-scope-search (env ctx) q (boolean descriptions?)))


(defn graph-index
  "`{:fns :namespaces}`, nil fields dropped (CLI / batch)."
  [ctx]
  (list-scope-index (env ctx)))


(defn graph-subtree
  "The fn-view slice reachable from `root-id` — empty rows for a nil or
   unknown root."
  [ctx root-id]
  (list-scope-subtree (env ctx) root-id))


(defn graph-full
  "Every `{:fns :slots :fn-slots :bindings :list-items :namespaces}` —
   ~4.5 MB on a 3000-fn graph; the editor's initial load."
  [ctx]
  (let [e (env ctx)]
    (-> (:base e)
        (assoc :fns @(:roled-fns e))
        (assoc :namespaces @(:namespaces e)))))
