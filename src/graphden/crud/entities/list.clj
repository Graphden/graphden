(ns graphden.crud.entities.list
  "The graph READ side of `/api/graph/entities` — the five scopes the
   editor pulls (tree / namespace / search / index / subtree), the
   light-row projection they share, the Explorer's structured filter
   evaluation (`view-members` — the ad-hoc chip set and the views saved
   in the graph as `:explorer-view` fn-defs), and the view-impl filter
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
    [graphden.types.diagnostics :as diag]
    [graphden.util.counters :as counters]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.storage.core :as vcore]))


(defn- subtree-fn-id-closure
  "BFS the set of fn-ids transitively reachable from `root-id` via:
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
    (push! root-id)
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
                                               (assoc % :parent-ids [])
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


(defn- light-fn-row
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


;; --- list-all-graph-entities per-scope projections -------------------------
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
  [branch-id base rich-snapshot roled-fns]
  (let [rich @rich-snapshot
        hit (get @tree-kinds-memo branch-id)]
    (if (and hit (identical? (:base hit) base) (identical? (:rich hit) rich))
      (:kinds hit)
      (let [kinds (ns-kind-counts base roled-fns)]
        (swap! tree-kinds-memo
               (fn [m]
                 (let [m (assoc m branch-id {:base base :rich rich :kinds kinds :at (System/nanoTime)})]
                   (if (> (count m) tree-kinds-memo-cap)
                     (dissoc m (key (apply min-key (comp :at val) m)))
                     m))))
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
  [{:keys [base diag-counts namespaces roled-fns rich-snapshot branch-id]}]
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
                     (tree-kinds branch-id base rich-snapshot roled-fns))
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


(defn- reverse-ref-adjacency
  "target-fn-id → [user-fn-ids] over EVERY composition edge: parent-ids,
   binding ref/resolver, list-item refs (through their owner binding).
   One pass over the base rows — the `uses:` rule BFSes this map."
  [{:keys [fns bindings list-items]}]
  (let [owner-of-binding (into {} (map (juxt :id :fn-id)) bindings)
        add (fn [m target user]
              (if (and target user)
                (update m target (fnil conj []) user)
                m))]
    (as-> {} m
          (reduce (fn [m f] (reduce #(add %1 %2 (:id f)) m (:parent-ids f))) m fns)
          (reduce (fn [m b]
                    (-> m
                        (add (:ref-fn-id b) (:fn-id b))
                        (add (:resolver-fn-id b) (:fn-id b))))
                  m bindings)
          (reduce (fn [m li]
                    (add m (:ref-fn-id li)
                         (get owner-of-binding (:binding-id li))))
                  m list-items))))


(defn- transitive-user-ids
  "Every fn-id that transitively USES `target-id` (children, callers,
   callers-of-callers …). Cycle-guarded BFS over `reverse-ref-adjacency`."
  [base target-id]
  (let [adj (reverse-ref-adjacency base)]
    (loop [seen #{} queue (vec (get adj target-id))]
      (if-let [id (first queue)]
        (if (seen id)
          (recur seen (subvec queue 1))
          (recur (conj seen id)
                 (into (subvec queue 1) (get adj id))))
        seen))))


(def ^:private view-result-cap
  "Smart views answer \"which fns belong to this virtual group\" — a
   bounded list keeps a graph-wide rule (`effect:io`) renderable."
  500)


(defn- secret-shaped-pred
  "`(fn [f] bool)` — a secret-shaped fn (parents = exactly a
   `:secret-shape`-tagged base-fn); the same rule `ns-kind-counts` uses,
   with the leaf ids resolved once."
  [base]
  (let [names (into #{} (map name) (registry/fn-names-with-tag :secret-shape))
        leaf-ids (into #{}
                       (comp (filter (comp names str :name)) (map :id))
                       (:fns base))]
    (fn [f] (boolean (some #(secret-shape/secret-fn? f %) leaf-ids)))))


(defn- ns-path-pred
  "`(fn [path] bool)` — `path` is one of `wants` or under it (segment
   match: `core` covers `core.strings`, not `coreutils`). `wants` are
   dotted paths; `/` is accepted as a separator too."
  [wants]
  (let [wants (into #{} (map #(str/replace (str/lower-case (str %)) "/" ".")) wants)]
    (fn [path]
      (let [p (some-> path str/lower-case)]
        (boolean (and p (some #(or (= p %) (str/starts-with? p (str % "."))) wants)))))))


(defn- kind-preds
  "The Explorer's KIND classification, server-side: `{kind (fn [f] bool)}`
   over roled rows. `:apps` (a tenancy notion) is unknown here and
   matches nothing — the editor applies it as its own overlay."
  [{:keys [base namespaces]} storage]
  (let [paths (ns-path/path-map @namespaces)
        secret? (secret-shaped-pred base)
        type? (fn [f] (boolean (types-api/type-lens-roles (:role f))))
        test-ns? (fn [f] (boolean (some #{"tests"} (str/split (str (get paths (:namespace-id f))) #"\."))))
        service-ids (delay (into #{} (map :fn-id) (sp/query-entities storage :service {})))]
    {:types type?
     :secrets secret?
     :tests test-ns?
     :services (fn [f] (contains? @service-ids (:id f)))
     :fn (fn [f] (not (or (type? f) (secret? f) (test-ns? f))))
     :apps (constantly false)}))


(defn- normalise-filters
  "The wire/impl shape of an Explorer filter set → the internal one:
   `{:name text :uses [uuid…] :effects [kw…] :kinds [kw…]
     :namespaces [path…] :exclude [path…] :unused bool :views [uuid…]}`
   (`:views` — ids of `:explorer-view` fn-defs whose members this set
   intersects with; the `also` slot on the wire). Accepts
   strings or keywords for kinds/effects, strings or uuids for ids,
   nil / missing for \"no filter on this axis\"."
  [filters]
  (let [kw (fn [v] (when v (keyword (str/replace (name (if (keyword? v) v (str v))) #"^:" ""))))
        ->uuid (fn [v]
                 (cond (uuid? v) v
                       (string? v) (try (java.util.UUID/fromString v) (catch Exception _ nil))
                       :else nil))
        vec-of (fn [f xs] (into [] (comp (map f) (remove nil?)) (if (sequential? xs) xs (when xs [xs]))))
        text (fn [v] (let [t (some-> v str str/trim)] (when (seq t) t)))]
    {:name (text (:name filters))
     :uses (vec-of ->uuid (:uses filters))
     :effects (vec-of kw (:effects filters))
     :kinds (vec-of kw (:kinds filters))
     :namespaces (vec-of text (:namespaces filters))
     :exclude (vec-of text (:exclude filters))
     :unused (boolean (:unused filters))
     :views (vec-of ->uuid (:views filters))}))


(defn- view-filter-preds
  "One `(fn [f] bool)` per active axis of a normalised filter set.
   Axes AND; within `:kinds` and `:namespaces` the values OR (a row is
   one of the kinds / under one of the roots); `:uses` and `:effects`
   AND (uses ALL of, carries ALL of) — the chips-AND rule, one chip per
   fn / effect."
  [{:keys [base rich-snapshot] :as env} storage
   {:keys [name uses effects kinds namespaces exclude unused]}]
  (let [paths (delay (ns-path/path-map @(:namespaces env)))
        qualified (fn [f]
                    (let [p (get @paths (:namespace-id f))]
                      (if (seq p) (str p "." (:name f)) (:name f))))
        adj (delay (reverse-ref-adjacency base))
        kinds-of (delay (kind-preds env storage))]
    (cond-> []
      name (conj (let [needle (str/lower-case name)]
                   (fn [f] (str/includes? (str/lower-case (qualified f)) needle))))
      (seq uses) (into (map (fn [target]
                              (let [users (transitive-user-ids base target)]
                                (fn [f] (contains? users (:id f)))))
                            uses))
      (seq effects) (into (map (fn [kind]
                                 (fn [f]
                                   (contains? (set (:effects (get @rich-snapshot (keyword (:name f)))))
                                              kind)))
                               effects))
      (seq kinds) (conj (let [preds (keep @kinds-of kinds)]
                          (fn [f] (boolean (some #(% f) preds)))))
      (seq namespaces) (conj (let [in? (ns-path-pred namespaces)]
                               (fn [f] (in? (get @paths (:namespace-id f))))))
      (seq exclude) (conj (let [out? (ns-path-pred exclude)]
                            (fn [f] (not (out? (get @paths (:namespace-id f)))))))
      unused (conj (fn [f] (empty? (get @adj (:id f))))))))


;; ---------------------------------------------------------------------------
;; Views saved in the graph — fn-defs extending the `:explorer-view`
;; base-fn. Listing them (with their bound filters decoded) is a READ
;; projection over the cached graph, like every other scope here.
;; ---------------------------------------------------------------------------

(def explorer-view-base-name
  "Name of the base-fn a graph-saved view extends. Base-fn names are
   globally unique (name-keyed impls registry), so the id is resolved
   over the in-memory graph without a query."
  "explorer-view")


(defn- closest-binding
  "The effective binding of `slot-id` for `fn-id`: the fn's own row,
   else the nearest ancestor's (BFS over `parent-ids`, closer wins)."
  [bindings-by-fn parents-of fn-id slot-id]
  (loop [queue [fn-id] seen #{}]
    (when-let [fid (first queue)]
      (if (seen fid)
        (recur (subvec queue 1) seen)
        (if-let [b (get-in bindings-by-fn [fid slot-id])]
          b
          (recur (into (subvec queue 1) (get parents-of fid)) (conj seen fid)))))))


(defn- decode-view-filters
  "Read a graph-saved view's bound slots back into a filter map (the
   shape `view-members` takes, plus `:also` — the ids of the views it
   intersects with). Unbound slots are absent."
  [{:keys [slots-by-name bindings-by-fn parents-of items-by-binding]} fn-id]
  (let [bound (fn [slot-name]
                (when-let [sid (get slots-by-name slot-name)]
                  (closest-binding bindings-by-fn parents-of fn-id sid)))
        items (fn [b] (->> (get items-by-binding (:id b)) (sort-by :position)))
        ;; A list slot is bound either as item rows (`:list-append`, the
        ;; fns.edn / sequence-API way) or as one `:value` vector (the
        ;; editor's binding form) — read both.
        lit-list (fn [slot-name]
                   (when-let [b (bound slot-name)]
                     (let [vs (if (sequential? (:value b))
                                (vec (:value b))
                                (into [] (keep :value) (items b)))]
                       (when (seq vs) (mapv str vs)))))]
    (cond-> {}
      (some-> (bound :name) :value) (assoc :name (:value (bound :name)))
      (some-> (bound :uses) :ref-fn-id) (assoc :uses [(:ref-fn-id (bound :uses))])
      (true? (:value (bound :unused))) (assoc :unused true)
      (lit-list :effects) (assoc :effects (lit-list :effects))
      (lit-list :kinds) (assoc :kinds (lit-list :kinds))
      (lit-list :namespaces) (assoc :namespaces (lit-list :namespaces))
      (lit-list :exclude) (assoc :exclude (lit-list :exclude))
      (some-> (bound :also) :ref-fn-id) (assoc :also [(:ref-fn-id (bound :also))]))))


(defn- explorer-views-decoded
  "`list-explorer-views` over an already-built list env (the base graph
   it holds) — what `view-members*` reads for the `:views` axis."
  [{:keys [base]}]
  (let [fns (:fns base)
        base-id (some #(when (and (= explorer-view-base-name (:name %))
                                  (empty? (:parent-ids %)))
                         (:id %))
                      fns)]
    (if-not base-id
      []
      (let [children-of (reduce (fn [m f] (reduce #(update %1 %2 (fnil conj []) (:id f)) m (:parent-ids f)))
                                {} fns)
            by-id (into {} (map (juxt :id identity)) fns)
            view-ids (loop [queue (vec (get children-of base-id)) seen #{}]
                       (if-let [id (first queue)]
                         (if (seen id)
                           (recur (subvec queue 1) seen)
                           (recur (into (subvec queue 1) (get children-of id)) (conj seen id)))
                         seen))
            slot-ids (into #{} (comp (filter #(= base-id (:fn-id %))) (map :slot-id)) (:fn-slots base))
            slots-by-name (into {} (comp (filter #(slot-ids (:id %)))
                                         (map (fn [s] [(keyword (:name s)) (:id s)])))
                                (:slots base))
            env {:slots-by-name slots-by-name
                 :bindings-by-fn (reduce (fn [m b] (assoc-in m [(:fn-id b) (:slot-id b)] b)) {} (:bindings base))
                 :parents-of (into {} (map (juxt :id :parent-ids)) fns)
                 :items-by-binding (group-by :binding-id (:list-items base))}]
        (->> view-ids
             (keep by-id)
             (filter :name)
             (sort-by :name)
             (mapv (fn [f]
                     {:id (:id f)
                      :name (:name f)
                      :namespace-id (:namespace-id f)
                      :filters (decode-view-filters env (:id f))})))))))


(defn list-explorer-views
  "Every NAMED fn that extends the `:explorer-view` base-fn (any depth),
   with its filters decoded — `[{:id :name :namespace-id :filters} …]`,
   the Explorer's \"views saved in the graph\" list (`:filters` carries
   `:also`, the ids of the views it intersects with). Empty when the
   base-fn isn't loaded (no `app/views` module) or nothing extends it."
  [ctx]
  (explorer-views-decoded {:base (types-api/cached-or-load-graph ctx)}))


(defn- view-members*
  "Light rows for every NAMED fn matching ALL axes of `filters` (see
   `view-filter-preds`), capped at `view-result-cap`. An EMPTY filter
   set is an empty view — the Explorer never asks for \"everything\"
   this way (that is the lazy tree), and a view that means everything
   is a bug on the caller's side, not a 500-row dump."
  ([env storage filters] (view-members* env storage filters #{}))
  ([{:keys [rev-index] :as env} storage filters seen]
   (let [filters (normalise-filters filters)
         preds (view-filter-preds env storage filters)
         ;; `:views` — intersect with each referenced graph view's OWN
         ;; members (its decoded axes, recursively through its `also`).
         ;; A view already on the path is skipped: a cycle through
         ;; `also` must not recurse forever, and "X also X" means X.
         in-views (when (seq (:views filters))
                    (let [by-id (into {} (map (juxt :id identity)) (explorer-views-decoded env))]
                      (reduce (fn [acc vid]
                                (if (or (seen vid) (not (get by-id vid)))
                                  acc
                                  (let [theirs (view-members* env storage
                                                              (let [f (:filters (get by-id vid))]
                                                                (assoc f :views (:also f)))
                                                              (conj seen vid))
                                        ids (into #{} (map :id) (:fns theirs))]
                                    (if acc (into #{} (filter ids) acc) ids))))
                              nil (:views filters))))
         preds (cond-> preds in-views (conj (fn [f] (contains? in-views (:id f)))))
         ;; Named rows only, and not the `_anon-<hash>` auto-names the
         ;; parser gives inline fn-defs — the tree never shows those as
         ;; leaves, so a view must not either.
         shown? (fn [f] (and (:name f) (not (str/starts-with? (:name f) "_anon-"))))
         matches (if (empty? preds)
                   []
                   (->> @(:roled-fns env)
                        (filterv (fn [f]
                                   (and (shown? f)
                                        (every? #(% f) preds))))))
         limited (into [] (take view-result-cap) matches)]
     {:fns (mapv (partial light-fn-row @rev-index) limited)
      :truncated? (> (count matches) view-result-cap)})))


(defn view-members
  "The Explorer's structured filter evaluation — `filters` is a map
   `{:name :uses :effects :kinds :namespaces :exclude :unused :views}`
   (every key optional; see `normalise-filters`), the answer `{:fns :truncated?}`
   in the `:search` light-row shape. One projection behind BOTH
   `POST /api/views/members` (an ad-hoc chip set) and the
   `:explorer-view` base-fn (a view saved IN the graph as a fn-def)."
  [ctx filters]
  (let [storage (:storage ctx)]
    (view-members* (graph-list-env ctx storage) storage filters)))


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
  (let [closure (subtree-fn-id-closure base root-id)
        roled-by-id (into {} (map (juxt :id identity)) @roled-fns)
        sub (filter-graph-to-fn-ids base closure)
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


(defn list-all-graph-entities
  "Dump every storage row the editor needs to render the graph. Routes
   through the shared graph-cache (populated by layout / compile-
   runtime) so editor refreshes after mutations don't re-query the
   same five tables every time.

   Each fn-row is augmented with a `:role` field so the sidebar can
   group entries into Types vs Functions sections without an extra
   round-trip through `/api/types`.

   `scope` controls payload size — one `list-scope-*` projection per
   shape over the shared lazy `graph-list-env`:

   - `nil` / `:full` (default) — every
     `{:fns :slots :fn-slots :bindings :list-items :namespaces}`.
     ~4.5 MB on a 3000-fn graph; the editor's initial load.
   - `:tree` — `{:namespaces :counts}` only (O(namespaces) sidebar init).
   - `:namespace` with `namespace-id` — one namespace's light rows.
   - `:search` with `q` — capped light rows by name substring.
   - `:search-text` with `q` — as `:search`, plus description matches
     ranked last (the MCP `search-fns` tool).
   - `:index` — `{:fns :namespaces}`, nil fields dropped (CLI/batch).
   - `:subtree` with `root-id` — the fn-view slice; falls back to
     `:full` shape when `root-id` is nil / unresolved."
  ([ctx] (list-all-graph-entities ctx nil nil nil nil))
  ([ctx scope] (list-all-graph-entities ctx scope nil nil nil))
  ([ctx scope root-id] (list-all-graph-entities ctx scope root-id nil nil))
  ([ctx scope root-id namespace-id q]
   (let [storage (request/require-storage ctx)
         env (graph-list-env ctx storage)]
     (cond
       (= scope :tree)               (list-scope-tree env)
       (= scope :namespace)          (list-scope-namespace env namespace-id)
       (= scope :search)             (list-scope-search env q false)
       (= scope :search-text)        (list-scope-search env q true)
       (= scope :index)              (list-scope-index env)
       (and (= scope :subtree) root-id) (list-scope-subtree env root-id)
       :else
       (-> (:base env)
           (assoc :fns @(:roled-fns env))
           (assoc :namespaces @(:namespaces env)))))))
