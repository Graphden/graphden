(ns graphden.crud.entities.list
  "The graph READ side of `/api/graph/entities` — the five scopes the
   editor pulls (tree / namespace / search / index / subtree), the
   light-row projection they share, and the view-impl filter the
   tenancy addon installs over the dump.

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
   :parent-ids :return-type-fn-id :package-owned
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
        role-of (fn [f]
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
                    (owned/owned-fn-id? (:id f)) (assoc :package-owned true)))]
    {:base base
     :role-of role-of
     :roled-fns (delay (mapv role-of (:fns base)))
     ;; Whole-graph reverse-ref tallies — realised only for the scopes
     ;; that project fn rows (`:namespace` / `:search` / `:subtree`).
     :rev-index (delay (reverse-ref-index base))
     ;; Per-fn diagnostic counts for the CURRENT branch (error-
     ;; tolerance Phase 3) — a cheap in-memory map lookup; realised
     ;; only by the `:tree` / `:subtree` scopes that surface them.
     :diag-counts (delay (into {}
                               (map (fn [[fid ds]] [fid (count ds)]))
                               (diag/branch-errors (vcore/current-branch-id storage))))
     :namespaces (delay (vec (sp/query-entities storage :ns {})))}))


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
  [{:keys [base diag-counts namespaces roled-fns]}]
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
        ;; Secret-leaf ids resolved WITHOUT a query: registry tag → base-fn
        ;; NAMES (globally unique for base-fns) → id match over the
        ;; in-memory graph. Empty when web.vault isn't loaded.
        secret-leaf-ids (let [names (into #{} (map name)
                                          (registry/fn-names-with-tag :secret-shape))]
                          (into #{}
                                (comp (filter (comp names str :name)) (map :id))
                                (:fns base)))
        secret-shaped? (fn [f] (boolean (some #(secret-shape/secret-fn? f %) secret-leaf-ids)))
        counts (->> @roled-fns
                    (filter :name)
                    (group-by :namespace-id)
                    (mapv (fn [[nid fns]]
                            (let [errs (get ns-err nid 0)
                                  types (count (filter (comp types-api/type-lens-roles :role) fns))
                                  plain (count (remove #(or (types-api/type-lens-roles (:role %))
                                                            (secret-shaped? %))
                                                       fns))]
                              (cond-> {:namespace-id nid :count (count fns)}
                                (pos? errs) (assoc :type-error-count errs)
                                (pos? types) (assoc :type-count types)
                                (pos? plain) (assoc :fn-count plain))))))
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
   match. `/` in the needle normalizes to `.` — the canonical
   `ns.path/name` spelling the rest of the product prints is accepted
   verbatim. Capped at `*default-search-limit*`. Replaces the
   client-side scan over the (former) full-fns mirror in the sidebar
   filter box, the fn / namespace / MI-reparent pickers, and name→id
   resolution."
  [{:keys [base rev-index role-of namespaces]} q]
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
                   (str/includes? (str/lower-case (qualified f)) needle) 2)))
        matches (when needle
                  (->> (:fns base)
                       (keep #(when (:name %)
                                (when-let [t (tier %)] [t %])))
                       (sort-by first)
                       (mapv second)))
        limited (into [] (take *default-search-limit*) matches)]
    {:fns (mapv (comp (partial light-fn-row @rev-index) role-of) limited)
     :truncated? (boolean (and needle (> (count matches) *default-search-limit*)))}))


(defn- view-rule-tokens
  "Parse the smart-view rule string — space-separated `key:value`
   tokens (`uses:core.strings.to-str effect:io name:handler`). A bare
   token is a `name:` substring. Unknown keys are kept (and match
   nothing) rather than silently dropped — a typo should read as an
   empty view, not as \"everything\"."
  [q]
  (->> (str/split (str/trim (or q "")) #"\s+")
       (remove str/blank?)
       (mapv (fn [tok]
               (let [[_ k v] (re-matches #"([a-z-]+):(.*)" tok)]
                 (if (and k (seq v))
                   [(keyword k) v]
                   [:name tok]))))))


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


(defn- list-scope-view
  "Smart-view scope: light rows for every named fn matching ALL rule
   tokens in `q` (see `view-rule-tokens`):

   - `uses:<name>`   — the fn transitively references / extends the
                       named fn (bare or qualified name; the editor's
                       \"virtual namespace\" of everything built on it).
   - `effect:<kind>` — the fn's computed effect footprint carries the
                       kind (`io`, `db`, `state`, …) — same registry
                       data the sidebar's fx marks read.
   - `name:<sub>`    — case-insensitive substring on the qualified name
                       (bare tokens parse as this).

   Powers the Explorer's saved views (editor-smart-views.js). Same
   light-row shape as `:search`, capped at `view-result-cap`."
  [{:keys [base rev-index role-of namespaces]} q]
  (let [rules (view-rule-tokens q)
        paths (ns-path/path-map @namespaces)
        qualified (fn [f]
                    (let [p (get paths (:namespace-id f))]
                      (if (seq p) (str p "." (:name f)) (:name f))))
        resolve-target (fn [nm]
                         (let [needle (str/replace (str/lower-case nm) "/" ".")]
                           (some #(when (and (:name %)
                                             (or (= (str/lower-case (:name %)) needle)
                                                 (= (str/lower-case (qualified %)) needle)))
                                    (:id %))
                                 (:fns base))))
        snapshot (registry/rich-types-snapshot)
        rule-pred (fn [[k v]]
                    (case k
                      :uses (let [target (resolve-target v)
                                  users (when target (transitive-user-ids base target))]
                              (fn [f] (contains? (or users #{}) (:id f))))
                      :effect (let [kind (keyword v)]
                                (fn [f]
                                  (contains? (set (:effects (get snapshot (keyword (:name f)))))
                                             kind)))
                      :name (let [needle (str/lower-case v)]
                              (fn [f] (str/includes? (str/lower-case (qualified f)) needle)))
                      :ns (let [want (str/replace (str/lower-case v) "/" ".")]
                            (fn [f]
                              (let [p (some-> (get paths (:namespace-id f))
                                              str/lower-case)]
                                (boolean (and p (or (= p want)
                                                    (str/starts-with? p (str want "."))))))))
                      :unused (if (contains? #{"true" "yes" "1"} (str/lower-case v))
                                (let [adj (reverse-ref-adjacency base)]
                                  (fn [f] (empty? (get adj (:id f)))))
                                (constantly false))
                      (constantly false)))
        preds (mapv rule-pred rules)
        matches (if (empty? preds)
                  []
                  (->> (:fns base)
                       (filterv (fn [f]
                                  (and (:name f)
                                       (every? #(% f) preds))))))
        limited (into [] (take view-result-cap) matches)]
    {:fns (mapv (comp (partial light-fn-row @rev-index) role-of) limited)
     :truncated? (> (count matches) view-result-cap)}))


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

   - `nil` / `:full` (default, backward compatible) — every
     `{:fns :slots :fn-slots :bindings :list-items :namespaces}`.
     ~4.5 MB on a 3000-fn graph; the editor's initial load.
   - `:tree` — `{:namespaces :counts}` only (O(namespaces) sidebar init).
   - `:namespace` with `namespace-id` — one namespace's light rows.
   - `:search` with `q` — capped light rows by substring.
   - `:view` with `q` — smart-view rules (`uses:` / `effect:` /
     `name:` tokens, AND-combined) — the Explorer's saved views.
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
       (= scope :search)             (list-scope-search env q)
       (= scope :view)               (list-scope-view env q)
       (= scope :index)              (list-scope-index env)
       (and (= scope :subtree) root-id) (list-scope-subtree env root-id)
       :else
       (-> (:base env)
           (assoc :fns @(:roled-fns env))
           (assoc :namespaces @(:namespaces env)))))))
