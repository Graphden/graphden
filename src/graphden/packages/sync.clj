(ns graphden.packages.sync
  "Package → storage synchronisation: the non-wiring concern lifted out
   of `graphden.system.core`. Given the loaded package set, this walks
   the fn-defs and:

   - pre-computes deterministic fn-ids (`compute-all-fn-name-ids`),
   - registers structural type-aliases + markers (`register-type-aliases!`),
   - registers base-fn impls + rows (`register-base-fns-from-packages!`),
   - syncs composed fn-defs, heals namespace-moved identities
     (`reconcile-moved-identities!`), snapshots rich-types, and runs the
     topological type-check sweep (`sync-fn-entities-from-packages!`).

   `bootstrap-from-packages!` composes the two syncs for out-of-band
   (test) callers; the production `:exec/base-fns` + `:exec/fn-entities`
   integrant init-keys (in `graphden.system.init.packages`) call the same
   helpers, so any drift stays localized here.

   Kept under `packages/` (not `system/`) because it is package-domain
   logic, not integrant lifecycle wiring."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.walk]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.registry.interface :as registry]
    [graphden.packages.loader :as pkg]
    [graphden.packages.owned :as owned]
    [graphden.packages.records :as records]
    [graphden.packages.records.parse :as records-parse]
    [graphden.services.port-check :as port-check]
    [graphden.storage.protocol.config :as sp-config]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check :as types-check]
    [graphden.types.check.narrowing :as types-narrowing]
    [graphden.types.core :as types]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.identity-repair :as idrepair]
    [graphden.versioning.storage.core :as vs]
    [graphden.web.route-shape :as route-shape]))


(defn compute-all-fn-name-ids
  "Pre-compute deterministic fn-ids for every named def across the
   loaded packages — base-fns + composed fn-defs (incl. `:fn-type`
   declarations) combined. Threaded into both syncs so cross-module
   references (e.g. a base-fn's `:return-type` pointing at a type-row
   in another module) resolve.

   `:fn-type` declarations get the standard `(fn-id ns name)`
   deterministic UUID — they now produce real fn-rows whose
   `:constraint` carries the structural `[:fn args ret]` shape
   (mirrors how unions / variants stash their payload). Pre-fix this
   path aliased them to `primitive-fn-id :fn`, leaving every
   `:return-type :http-server-handle`-style reference pointing at the
   bare-`:fn` row and erasing the structural shape from storage.

   The map is DUAL-keyed to the same contract as `parse-module`'s
   `name->id` (which seeds from it): every named def lands under its
   always-qualified form (`:core.strings/upper`; root ns = the
   empty-ns spelling) AND its bare name — where a bare name is
   claimed by two different `(ns, name)` identities it maps to the
   `ambiguous-name` sentinel instead of silently keeping the
   last-write id (a cross-package duplicate used to hand every
   downstream reference the wrong row with no diagnostic; the
   sentinel makes parse's fail-loud path demand qualification)."
  [packages]
  (let [pairs (concat
                (keep (fn [[fn-name fn-def]]
                        (when fn-name [fn-name (:namespace fn-def)]))
                      (:base-fn-defs packages))
                (keep (fn [fd]
                        (when (:name fd) [(:name fd) (:namespace fd)]))
                      (:fn-defs packages)))]
    (reduce (fn [m [n ns-path]]
              (let [id (records/fn-id ns-path n)
                    existing (get m n)]
                (-> (assoc m n (if (and existing (not= existing id))
                                 records-parse/ambiguous-name
                                 id))
                    (assoc (keyword (str ns-path) (name n)) id))))
            {}
            pairs)))


(defn register-type-aliases!
  "Walk every fn-def that declares a structural type (refinement,
   record, list, union, fn-type) and register it as a type-alias so
   the type-checker's `resolve-alias` can expand the keyword when it
   appears as a `:type` reference in another fn-def. Without this,
   `:http-server :args {:port :port}` would store the bare keyword
   and a downstream literal like `{:port 8080}` would trigger a
   bogus `:int ⊆ :port` primitive subtype check.

   Two passes — the second resolves now that all top-level names
   are known. This lets `:ring-handler` reference `:ring-request`
   regardless of declaration order in fns.edn.

   Registration tries each alias even if some fail validation
   (e.g. references an unknown type) — the second pass usually
   resolves those. Genuine errors surface later through the
   type-checker on first use."
  [fn-defs]
  ;; Marker-type declarations register FIRST — a marker tag must be
  ;; known before any alias body (or slot type) using `[<tag> T]` is
  ;; validated by `well-formed?`. `{:name :pii :marker {:hide-result?
  ;; bool}}` — the graph-declared instance of the seeded `:secret`
  ;; (types.core.shapes/register-marker!).
  (doseq [fd fn-defs
          :when (:marker fd)]
    (try (types/register-marker! (:name fd) (:marker fd))
         (catch Exception e
           (log/warn e "register-marker! failed for" (:name fd)))))
  (let [alias-body
        (fn [fd]
          (cond
            (:refine fd)
            (let [{:keys [base constraint]} (:refine fd)]
              (when base [:refine base (or constraint [:any])]))

            (and (:type fd) (map? (:type fd)))
            (:type fd)

            (:list fd)
            [:list (:list fd)]

            (:union fd)
            (into [:union] (:union fd))

            ;; Homogeneous map alias — `:map {:key K :value V}` is sugar
            ;; for the structural `[:map K V]`. Without this branch the
            ;; alias-body fn ignored the declaration and downstream slot
            ;; references (`:list-entities :where :_storage-where-map`)
            ;; saw a bare keyword the alias registry didn't know about,
            ;; so the type-checker treated it as opaque and a literal
            ;; `{:value {}}` failed against it.
            (and (:map fd) (map? (:map fd)))
            (let [{:keys [key value]} (:map fd)]
              (when (and key value) [:map key value]))

            ;; `:variant [:tag1 T1 :tag2 T2 …]` desugars to a union of
            ;; tag-pinned records (see types/desugar-variant). Without
            ;; this branch the EDN-declared `:result-text`,
            ;; `:result-int`, `:validation` aliases never reached
            ;; `register-type-alias!` and the type-checker treated
            ;; them as unknown keywords — defeating the whole point
            ;; of a variant declaration.
            (:variant fd)
            (types/desugar-variant (:variant fd))

            (:fn-type fd)
            (let [[args ret] (:fn-type fd)]
              [:fn (or args {}) ret])))
        edn-ns? (fn [ns-path]
                  ;; Version-materialized namespaces
                  ;; (`web.components@1-2-0`) contain `@` — invalid in
                  ;; a keyword ns, so those rows register bare-only
                  ;; (mirrors export's `edn-keyword-ns?`).
                  (boolean (and ns-path
                                (re-matches #"[A-Za-z0-9._-]+" ns-path))))
        candidates (for [fd fn-defs
                         :when (:name fd)
                         :let [body (alias-body fd)]
                         :when body]
                     ;; Owner id = the type-row's deterministic sync id —
                     ;; feeds the cross-owner collision diagnostic. The
                     ;; QUALIFIED `:ns.path/name` variant registers
                     ;; alongside the bare name: per-ns names may
                     ;; legally repeat, and when they do the bare form
                     ;; goes ambiguous (resolve-alias throws) while the
                     ;; qualified forms stay precise.
                     [(:name fd) body (records/fn-id (:namespace fd) (:name fd))
                      (when (edn-ns? (:namespace fd))
                        (keyword (:namespace fd) (name (:name fd))))])
        try-once
        (fn [pending]
          ;; Returns the subset of [name body] pairs whose validation
          ;; still fails — caller iterates until fixed point.
          (reduce
            (fn [still-pending [nm body owner qualified]]
              (try (types/register-type-alias! nm body owner qualified)
                   still-pending
                   (catch Exception _
                     (conj still-pending [nm body owner qualified]))))
            []
            pending))]
    ;; Iterate to fixed point — each pass widens `aliases-snapshot`,
    ;; which `well-formed?` consults for inner-keyword refs. Bound
    ;; the loop count so a true cycle (or a body referencing an
    ;; unknown type) terminates instead of spinning.
    (loop [pending candidates, iter 0]
      (let [next-pending (try-once pending)]
        (cond
          (empty? next-pending) nil
          (or (= (count next-pending) (count pending))
              (>= iter 8))
          (doseq [[nm _] next-pending]
            (log/warn "register-type-alias! failed for" nm
                      "— body references an unknown type"))
          :else (recur next-pending (inc iter)))))))


(defn- validate-no-name-collisions!
  "Per-ns names (ADR-identity-model.md stage 5) — two guards remain:

   1. `(namespace, name)` must be UNIQUE across base-fns AND composed
      fn-defs: the pair IS the deterministic `records/fn-id`, so a
      duplicate silently upserts over the other row at sync (parent-ids
      replacing a base-fn's return-type marker while the impl registry
      still holds the impl) with no error.
   2. BASE-FN bare names must be unique among base-fns regardless of
      namespace: the Clojure impls registry
      (`exec/register-base-fn!`) is name-keyed — two same-named impls
      in different namespaces would clobber each other's Clojure fn.

   Same-named composed fn-defs in DIFFERENT namespaces are LEGAL —
   ambiguous bare references rewrite to qualified form at parse entry
   (`normalize-qualified-refs`) or fail loud demanding qualification.
   Anonymous defs (name = nil) are content-hash-deduped and excluded.

   Base-fn pairs come from the loader's UNCOLLAPSED `:base-fn-pairs`
   when present (`load-packages`). Deriving them from `:base-fn-defs` —
   a bare-name-keyed MAP — instead made guard 2 DEAD: two same-named
   base-fns in different namespaces already collapse to one map entry
   upstream, so `frequencies` never saw a count > 1. Fall back to the
   map for hand-built `packages` (tests / registry) that carry no
   `:base-fn-pairs`."
  [packages]
  (let [base-pairs (if (contains? packages :base-fn-pairs)
                     (:base-fn-pairs packages)
                     (map (fn [[n d]] [(:namespace d) n]) (:base-fn-defs packages)))
        def-pairs  (keep (fn [d] (when (:name d) [(:namespace d) (:name d)]))
                         (:fn-defs packages))
        pair-dups (->> (concat base-pairs def-pairs)
                       frequencies
                       (keep (fn [[p c]] (when (> c 1) p)))
                       vec)
        base-name-dups (->> (map second base-pairs)
                            frequencies
                            (keep (fn [[n c]] (when (> c 1) n)))
                            vec)]
    (when (seq pair-dups)
      (throw (ex-info (str "Colliding (namespace, name) pairs: "
                           (pr-str pair-dups)
                           " — the pair IS the deterministic fn-id; a "
                           "duplicate silently overwrites the other row at sync.")
                      {:type :packages/fn-name-collision
                       :colliding-pairs pair-dups})))
    (when (seq base-name-dups)
      (throw (ex-info (str "Colliding BASE-FN names across namespaces: "
                           (pr-str base-name-dups)
                           " — the Clojure impls registry is name-keyed; "
                           "base-fn names must stay globally unique.")
                      {:type :packages/base-fn-name-collision
                       :colliding-names base-name-dups})))))


(defn- validate-route-handler-shapes!
  "Bare (middleware-less) routes — `:get-route`/`:post-route` parents —
   call their handler with the raw ring request positionally; a handler
   whose DECLARED `:lambda-params` is anything but `[]`/`[:request]`
   mis-binds the request silently at the wire (see
   `graphden.web.route-shape`). Fail loud at sync so an external /
   third-party package can't ship the class the repo's own corpus is
   pinned against (`route-handler-shape-guard-test`); the editor write
   path is guarded by `crud.validation/route-handler-shape-rej`.

   Direct-parent detection mirrors the guard test: package routes
   parent the templates directly. A qualified parent ref only counts
   when it names the owning module (`app.routes.method`)."
  [packages]
  (let [fn-defs (:fn-defs packages)
        by-name (into {} (keep (fn [d] (when (:name d) [(:name d) d]))) fn-defs)
        bare? (fn [p]
                (and (keyword? p)
                     (contains? route-shape/bare-route-parents
                                (keyword (name p)))
                     (or (nil? (namespace p))
                         (= route-shape/bare-route-template-ns
                            (namespace p)))))
        offenders (for [r fn-defs
                        :when (or (bare? (:parent r))
                                  (some bare? (:parents r)))
                        :let [h (get-in r [:args :handler])
                              h (if (map? h) (:ref h) h)
                              handler (when (keyword? h)
                                        (or (by-name h)
                                            (by-name (keyword (name h)))))
                              lp (:lambda-params handler)]
                        :when (and handler
                                   (not (route-shape/valid-handler-lambda-params? lp)))]
                    {:route (:name r) :handler h :lambda-params (vec lp)})]
    (when (seq offenders)
      (throw (ex-info (str "Bare-route handlers must declare :lambda-params"
                           " [] or [:request] — the raw positional ring call"
                           " breaks any other shape: " (pr-str offenders))
                      {:type :packages/route-handler-shape
                       :offenders (vec offenders)})))))


(defn register-base-fns-from-packages!
  "Pure side-effects: sync namespaces, register type-aliases, register
   base-fn impls in the global registry, sync base-fn rows to storage.
   `extra-base-fns` is an optional map of `{fn-name → impl}` merged on
   top of the package impls (test overrides). Returns
   `{:ns-id-map :all-name->id :base-fns}` so callers can thread the
   resolved name→id map into a subsequent
   `sync-fn-entities-from-packages!` call.

   Shared by the production `:exec/base-fns` integrant init-key and the
   out-of-band `bootstrap-from-packages!` test helper."
  ([storage packages]
   (register-base-fns-from-packages! storage packages nil))
  ([storage packages extra-base-fns]
   ;; Fail loud BEFORE any DB write if a base-fn and a fn-def share a name,
   ;; or a bare route's handler declares a wire-breaking :lambda-params.
   (validate-no-name-collisions! packages)
   (validate-route-handler-shapes! packages)
   ;; Trusted-bootstrap batch ceiling — same rationale as the binding in
   ;; `sync-fn-entities-from-packages!` (which see): `*max-batch-size*`
   ;; guards USER writes; the bundled sync legitimately writes every
   ;; ns/base-fn row in single batches and must not die as the set grows.
   (binding [sp-config/*max-batch-size* (max sp-config/*max-batch-size* 100000)]
     (let [base-fn-defs (:base-fn-defs packages)
           ;; Sync namespace entities first (creates ns hierarchy in DB)
           ns-id-map (pkg/sync-namespaces! storage (:namespaces packages)
                                           (:ns-descriptions packages))
           ;; Full name→id map covering base-fns + composed fn-defs so
           ;; either sync can resolve a reference into the other set.
           all-name->id (compute-all-fn-name-ids packages)
           ;; Map of {fn-name → impl} threaded through to `:exec/context`
           ;; via integrant — sidesteps the process-global registry so
           ;; concurrent test-scope start! calls can't race on the atom.
           ;; `extra-base-fns` is merged on top of package impls. Same
           ;; map is also pushed into the global registry for back-compat
           ;; with direct `exec/get-base-fn` / REPL callers.
           base-fns-map (merge (registry/compute-base-fns-map base-fn-defs)
                               extra-base-fns)]
       (registry/sync-primitives! storage)
       ;; Feed the package-write guard: every deterministic id this sync
       ;; is about to write (base-fns + composed fn-defs + primitives)
       ;; becomes API-read-only — see `graphden.packages.owned`.
       (owned/record-owned-ids! (vals all-name->id))
       (owned/record-owned-ids! (vals (records/primitive-fn-ids)))
       ;; Register refinement type-aliases BEFORE base-fn rich-type
       ;; recording so `:http-server :args {:port :port}` stores the
       ;; structural `[:refine :int …]` form, not the bare keyword.
       (register-type-aliases! (:fn-defs packages))
       (doseq [[fn-name impl] base-fns-map]
         (exec/register-base-fn! fn-name impl))
       (registry/sync-defs-to-storage! storage base-fn-defs ns-id-map all-name->id)
       {:ns-id-map ns-id-map
        :all-name->id all-name->id
        :base-fns base-fns-map}))))


(defn sync-bundle!
  "Sync a BUNDLE of fn-defs into `storage` and return their deterministic
   fn-ids: namespace upsert (`pkg/sync-namespaces!`) →
   `fn-composition/sync-fns-to-storage!` → `records/fn-id` per def. The
   shared core of the registry's fork/materialize apply-cores and the MCP
   branch sync (formerly three verbatim copies); each caller owns its
   divergent tail — ns-rewrite prefix, invalidation target, branch switch."
  [storage fn-defs]
  (let [ns-id-map (pkg/sync-namespaces! storage (into #{} (keep :namespace) fn-defs))]
    (fn-composition/sync-fns-to-storage! storage fn-defs ns-id-map)
    (mapv #(records/fn-id (:namespace %) (:name %)) fn-defs)))


(defn- ns-path-index
  "`{ns-id → dotted-path}` resolver over storage's `:ns` rows (memoised
   walk up `:parent-id`)."
  [storage]
  (let [ns-by-id (into {} (map (juxt :id identity)) (sp/query-entities storage :ns {}))]
    (fn ns-path
      [nsid]
      (when-let [r (ns-by-id nsid)]
        (if-let [p (:parent-id r)]
          (str (ns-path p) "." (:name r))
          (:name r))))))


(defn drop-orphan-anon-defs
  "Strip `_anon-*` top-level defs that nothing else in the bundle
   references. The exporter lifts every inline anonymous def to a
   synthetic top-level `_anon-<hash>` entry; when the referencing OWNERS
   are dropped from an import (platform-owned defs are skipped —
   `import-bundle!`), their anons become pure orphans, and syncing a
   whole-graph bundle then floods the target with thousands of duplicate
   anon identities (observed poisoning the compiled router's coercion
   closures). Anons a KEPT def reaches — directly or through other kept
   anons — stay, so user-authored inline anons round-trip untouched.
   Returns the filtered vector."
  [fn-defs]
  (let [anon-name? #(str/starts-with? (name %) "_anon-")
        anon-defs (into {} (comp (filter #(anon-name? (:name %)))
                                 (map (juxt :name identity)))
                        fn-defs)
        refs-of (fn [d]
                  (let [acc (volatile! #{})]
                    (clojure.walk/postwalk
                      (fn [x]
                        (when (and (keyword? x) (anon-name? x)
                                   (contains? anon-defs (keyword (name x))))
                          (vswap! acc conj (keyword (name x))))
                        x)
                      [(:args d) (:parent d) (:parents d) (:return-type d)
                       (:type d) (:input d)])
                    @acc))
        roots (remove #(anon-name? (:name %)) fn-defs)]
    (loop [kept (into #{} (mapcat refs-of) roots)]
      (let [next-kept (into kept (mapcat #(refs-of (anon-defs %))) kept)]
        (if (= next-kept kept)
          (filterv #(or (not (anon-name? (:name %)))
                        (contains? kept (:name %)))
                   fn-defs)
          (recur next-kept))))))


(defn adopt-bundle-identities!
  "Canonicalise IDENTITIES before a bundle sync: for each def whose
   `(namespace, name)` already exists as a row with a RANDOM (editor-
   created) id — and whose deterministic id does NOT exist yet — repoint
   every ref at the deterministic id and purge the old identity, so the
   following `sync-bundle!` re-creates the fn under its canonical
   `uuid-v5(ns, name)` id instead of minting a same-name sibling.

   This is the import-side half of cross-install push/pull: an fn born in
   the editor has a random id locally; the hub's sync of the pushed
   bundle minted the deterministic id; without adoption the first PULL
   after a push lands a DUPLICATE name next to the original. Same
   trade-off as the boot reconciler's MOVE: refs survive (identity + all
   branch version rows, `idrepair/repoint-refs!`), the old row's own
   per-branch version history does not — the bundle carries the current
   state and the sync recreates it on the target branch.

   Returns the adopted names (empty = nothing to do)."
  [storage fn-defs]
  (let [ns-path (ns-path-index storage)
        adoptable
        (for [d fn-defs
              :let [det-id (records/fn-id (:namespace d) (:name d))
                    rows (sp/query-entities storage :fn {:name (name (:name d))})
                    same-ns (filterv #(= (:namespace d) (some-> (:namespace-id %) ns-path))
                                     rows)]
              :when (and (not-any? #(= det-id (:id %)) same-ns)
                         (= 1 (count same-ns)))]
          [(first same-ns) det-id])]
    (doseq [[row det-id] adoptable]
      (log/info "adopting editor-created identity onto its deterministic id"
                {:name (:name row) :from (:id row) :to det-id})
      (idrepair/repoint-refs! storage {(:id row) det-id})
      (idrepair/purge-fn-subgraph! storage (:id row)))
    (mapv (fn [[row _]] (:name row)) adoptable)))


(defn reconcile-bundle-scope!
  "Declarative PRUNE for a runtime bundle import (`POST /api/import/graph`
   `?prune=true` — the push/pull snapshot semantics): within EXACTLY the
   namespaces the bundle covers, a named fn row whose id equals its own
   deterministic derivation but which the bundle no longer contains is
   TOMBSTONED on `storage` — the branch-switched VersionedStorage — so it
   resolves ABSENT here and on descendants while the identity survives.
   This rides the normal merge flow: the tombstone is a branch version
   row, so the deletion shows up in the diff and propagates on merge, and
   is revertable. Never the boot reconciler's identity-plane purge, which
   reaches across branches.

   The tombstone (not a hard delete) is load-bearing: a bare
   `delete-entity` is `*tombstone-delete?* false` by default, and a hard
   delete of a branch's version rows is a SILENT NO-OP for a fn INHERITED
   from the base branch (there is no branch-local version row to remove) —
   so a snapshot that drops an fn present on main would fail to hide it.
   `*tombstone-delete?* true` writes a `:deleted-at` version instead, so
   inherited AND branch-local removals both take effect.

   Guards mirror `reconcile-moved-identities!`: `_anon-*` / anonymous
   shapes and random-id (editor-created) rows are never touched, and a
   row something still references is KEPT and reported instead of
   deleted (`idrepair/inbound-refs` — a referenced removal stays loud).
   The inbound check is conservative: a stale row referenced only by
   ANOTHER stale row is kept this round; re-importing the same snapshot
   converges (the referencing row is gone by then). Returns
   `{:pruned [names] :kept-referenced [names]}`.

   Scope is derived from the bundle's OWN namespaces, so an EMPTY bundle
   covers no namespaces and prunes nothing — `?prune=true` on an empty
   snapshot is a deliberate fail-closed NO-OP, never a wipe. (Clearing a
   namespace's entire contents is done by importing a bundle that still
   declares that namespace but omits the fns, not by importing nothing.)"
  [storage fn-defs]
  (let [bundle-namespaces (into #{} (map :namespace) fn-defs)
        expected (into #{} (map #(records/fn-id (:namespace %) (:name %))) fn-defs)
        ns-path (ns-path-index storage)
        stale (vec
                (for [row (sp/query-entities storage :fn {})
                      :when (and (:name row)
                                 (nil? (:anonymous-hash row))
                                 (not (str/starts-with? (:name row) "_anon-")))
                      :let [path (some-> (:namespace-id row) ns-path)]
                      :when (and (contains? bundle-namespaces path)
                                 (= (:id row) (records/fn-id path (keyword (:name row))))
                                 (not (contains? expected (:id row))))]
                  row))
        {kept true pruned false}
        (group-by #(boolean (seq (idrepair/inbound-refs storage (:id %)))) stale)]
    (binding [vs/*tombstone-delete?* true]
      (doseq [row pruned]
        (log/info "bundle prune: tombstoning fn absent from the imported snapshot"
                  {:name (:name row) :id (:id row)})
        (sp/delete-entity storage :fn (:id row))))
    (doseq [row kept]
      (log/warn "bundle prune: fn absent from the snapshot but STILL REFERENCED — kept"
                {:name (:name row) :id (:id row)}))
    {:pruned (mapv :name pruned)
     :kept-referenced (mapv :name kept)}))


(defn- run-type-check-sweep!
  "Topological-order type-check sweep across `expanded-fn-defs`.

   - **Pass 1** isolates each fn-def's per-fn check; failures populate
     the registry with whatever rich-type the partial run could
     produce and get DEBUG-logged.
   - **Pass 2/3** rebuilds caller-narrowings (Phase α') + ref-return
     overrides (Phase #170) over the topologically-sorted list and
     re-runs each fn-def with both bound — that fixpoint replaces
     pass 1's isolation view; the FINAL failure set is what the
     allowlist gates against.
   - Sweep summary WARN runs when any fn-def failed (DEBUG-logged
     per-fn).
   - **Diagnostics store** — the FINAL failure set's structured
     ex-data is recorded per-fn into `graphden.types.diagnostics`
     under `branch-id` (the branch the sync ran on; nil = default),
     and sweep-covered fns that now pass get their entries cleared.
     Recording happens BEFORE the allowlist gate so a red gate still
     leaves the diagnostics readable.
   - **Allowlist gate** — `types-check/allowed-type-check-failures`
     enumerates the known-failing fn-defs (closed over time as the
     type system gains expressiveness). Any failure NOT in the
     allowlist is a regression; any allowlisted name that's NO LONGER
     failing must be removed from the allowlist. Throws at sync time
     so CI catches both — the package-corpus gate stays HARD:
     tolerance is for user CRUD, our corpus stays at zero.
     `skip-allowlist-gate?` opts a test bootstrap out — useful when
     loading a SUBSET of production packages."
  [expanded-fn-defs skip-allowlist-gate? branch-id]
  (let [sorted (deps/topological-sort expanded-fn-defs)
        fd-fn-id (fn [fd]
                   (when (and (:name fd) (:namespace fd))
                     (records/fn-id (:namespace fd) (:name fd))))
        failures (atom {})
        collect! (fn [fd e]
                   (swap! failures assoc (:name fd)
                          {:message (ex-message e)
                           :fn-id (fd-fn-id fd)
                           :diagnostic (diag/from-ex e)})
                   (log/debug "Type-check failed for fn-def" (:name fd) "—"
                              (ex-message e)))]
    ;; One fresh `*ref-return-memo*` per pass: the registry is
    ;; append-only within a pass (topological order), so ref re-fires
    ;; are pure for the pass's duration — see the var's docstring.
    ;; Between passes the registry entries CHANGE (pass 3 re-records
    ;; under narrowings), so the cache must not survive the boundary.
    (binding [types-check/*ref-return-memo* (atom {})]
      (doseq [fd sorted]
        (try (types-check/check-fn-def! fd)
             (catch Exception e
               (collect! fd e)))))
    (let [narrowings (types-narrowing/build-caller-narrowings sorted)
          overrides  (types-narrowing/build-ref-return-overrides sorted)]
      (reset! failures {})
      (binding [types-check/*ref-return-memo* (atom {})]
        (doseq [fd sorted]
          (try (types-narrowing/check-fn-def-with-narrowings! fd narrowings overrides)
               (catch Exception e
                 (collect! fd e))))))
    ;; Record the final per-fn structured failures under the sync's
    ;; branch; clear entries for sweep-covered fns that now pass.
    ;; Only fns THIS sweep saw are touched — editor-created rows on
    ;; the same branch keep their CRUD-recorded entries.
    (let [failed @failures
          failed-ids (into #{} (keep :fn-id) (vals failed))
          sweep-ids (into #{} (keep fd-fn-id) sorted)]
      (doseq [{:keys [fn-id diagnostic]} (vals failed)
              :when fn-id]
        (diag/record! branch-id fn-id [diagnostic]))
      (doseq [fn-id (keys (diag/branch-errors branch-id))
              :when (and (contains? sweep-ids fn-id)
                         (not (contains? failed-ids fn-id)))]
        (diag/clear-fn! branch-id fn-id)))
    (when (pos? (count @failures))
      (log/warn "Type-check sweep: " (count @failures)
                "fn-defs failed (DEBUG-logged) — runtime unaffected,"
                " editor effect/return strips may be missing for those names —"
                " docs/TYPE_SYSTEM_DECISIONS.md"))
    (when-not skip-allowlist-gate?
      (types-check/assert-sweep-failures-match-allowlist!
        (set (keys @failures))
        (update-vals @failures :message)))))


(def ^:dynamic *reconcile-moved-override*
  "Parallel-test seam: when bound to a fn, `reconcile-moved-identities!`
   delegates to it (same args) instead of running the real
   identity-plane scan. nil (production) = the real pass runs. Tests
   `binding` this instead of `with-redefs`-ing the root var — a root
   rebind is process-global and forced a `^:serial` pin on
   `graphden.system.core-test` (serial-reduction batch 4). Cost on the
   real path: one nil check per package sync — a boot / bootstrap-time
   path, never per-execute."
  nil)


(declare reconcile-moved-identities*!)


(defn reconcile-moved-identities!
  "ROOT FIX for the ghost-identity class (audit-4): a package fn's
   deterministic id is `uuid-v5(ns-path, name)`, so moving it to
   another namespace mints a NEW id — and, before this pass, silently
   ABANDONED the old row with every pre-move ref still pointing at it
   (the live-demo outage; three downstream rescues patched its
   symptoms). Heal at the moment of the move instead:

   After a package sync, an identity-plane `:fn` row is a MOVE
   LEFTOVER when ALL hold:
   - it has a name and its id equals its OWN deterministic derivation
     (`records/fn-id(row-ns-path, name)`) — i.e. it is a
     package-world row, never an editor-created one (random ids are
     NEVER touched);
   - it is NOT in the just-synced record set;
   - its ns-path's root segment belongs to a package being synced
     (partial test bundles must not judge other packages' rows);
   - EXACTLY ONE just-synced fn carries the same bare name (the
     unambiguous move target). 0 candidates = genuine package REMOVAL;
     >1 = ambiguous move (left alone, warned).

   For a MOVE (1 candidate): repoint every ref (identity + all branch
   version rows, in place) at the new id, purge the ghost's own
   subgraph, and drop its registry entry.

   For a REMOVAL (0 candidates): purge the dead identity too — but ONLY
   when nothing else references it (`idrepair/inbound-refs` empty), so a
   still-used fn dropped from EDN stays LOUD instead of being deleted out
   from under its referrer. This stops removed package fns from lingering
   name/id-resolvable and being loaded into every compiled context (a
   stale-def compile-crash + a slow forever-growth that pushed operators
   toward DB resets). Deterministic ids make an over-eager purge
   self-healing on the next full sync.

   Runs BEFORE the compiled-registry build, so there is nothing stale to
   invalidate."
  ([storage packages synced-fn-rows]
   (reconcile-moved-identities! storage packages synced-fn-rows nil))
  ([storage packages synced-fn-rows opts]
   (if-let [f *reconcile-moved-override*]
     (f storage packages synced-fn-rows)
     (reconcile-moved-identities*! storage packages synced-fn-rows opts))))


(defn- reconcile-moved-identities*!
  "The real body of `reconcile-moved-identities!` (see its docstring) —
   split out so the seam check stays a one-liner. `opts` (nilable):
   `:preexisting-fn-ids` — the fn ids that existed BEFORE this sync's
   writes. When present, a 1-candidate \"move\" is accepted only if the
   candidate row was MINTED by this sync: a real namespace move always
   creates a new deterministic id, whereas a removal that merely shares
   its bare name with a long-standing fn in ANOTHER namespace must not
   repoint the world at that unrelated fn (ADR-identity stage 5 made
   same-bare-name-across-namespaces a supported state)."
  [storage packages synced-fn-rows opts]
  (let [base (idrepair/base-of storage)
        preexisting (:preexisting-fn-ids opts)
        ;; `write-records!` (the production caller) returns a
        ;; `{fn-name-keyword → fn-id}` MAP, not rows. The old body ran
        ;; `(group-by :name synced-fn-rows)` / `(keep :id …)` over it —
        ;; `(:name MapEntry)` is nil, so `synced-by-name` was ALWAYS `{}`
        ;; and EVERY moved identity fell into the removal branch with
        ;; zero candidates → `repoint-refs!` never ran (a cross-ns fn
        ;; move left dangling refs; the ROOT FIX was silently dead).
        ;; Normalise to `{:name :id}` rows so BOTH shapes work (tests
        ;; pass a vector of rows directly).
        synced-rows (if (map? synced-fn-rows)
                      (mapv (fn [[nm id]] {:name (name nm) :id id}) synced-fn-rows)
                      synced-fn-rows)
        package-roots (into #{} (map :name) (:packages packages))
        ;; The COMPLETE expected package-identity set — not just the
        ;; composed `synced-fn-rows`. `synced-fn-rows` is ONLY the
        ;; composed fn-defs + type-rows; base-fns sync via a SEPARATE
        ;; pass and are absent from it, so keying the removal test on
        ;; `synced-fn-rows` alone would flag EVERY base-fn (`add`, `eq`,
        ;; `map`, …) as a genuine removal and purge them. `compute-all-
        ;; fn-name-ids` derives the deterministic id of every named
        ;; def in the loaded packages (base + composed + type) under its
        ;; qualified key; its UUID values are exactly "the ids that
        ;; SHOULD exist after this sync". Union the actual synced rows in
        ;; too (post-move-reconcile ids). A deterministic-id row absent
        ;; from THIS set is a real removal.
        expected-ids (into (into #{} (keep :id) synced-rows)
                           (filter uuid?)
                           (vals (compute-all-fn-name-ids packages)))
        synced-by-name (group-by :name (filter :name synced-rows))
        ns-rows (sp/query-entities base :ns {})
        ns-by-id (into {} (map (juxt :id identity)) ns-rows)
        ns-path (fn ns-path
                  [nsid]
                  (when-let [r (ns-by-id nsid)]
                    (if-let [p (:parent-id r)]
                      (str (ns-path p) "." (:name r))
                      (:name r))))
        leftovers
        (for [row (sp/query-entities base :fn {})
              :when (and (:name row)
                         ;; Synthetic anon rows can never be an authored
                         ;; MOVE: their name embeds a shape+use-site hash,
                         ;; so a vanished shape has 0 same-name candidates
                         ;; by construction — scanning them only floods
                         ;; the leftover log (hundreds per reduced-set
                         ;; test bootstrap).
                         (nil? (:anonymous-hash row))
                         (not (str/starts-with? (:name row) "_anon-"))
                         (not (contains? expected-ids (:id row))))
              :let [path (some-> (:namespace-id row) ns-path)
                    root (some-> path (str/split #"\.") first)]
              :when (and path
                         (contains? package-roots root)
                         (= (:id row)
                            (records/fn-id path (keyword (:name row)))))]
          row)]
    (let [not-a-move? (fn [row]
                        (let [cs (get synced-by-name (:name row))]
                          (or (empty? cs)
                              (and (= 1 (count cs)) (some? preexisting)
                                   (contains? preexisting (:id (first cs)))))))
          removals (filterv not-a-move? leftovers)]
      ;; MOVES are batched: one repoint pass over the ref surface with
      ;; the whole old→new map + one table-scan purge cascade — a bulk
      ;; namespace relocation used to pay a full repoint scan PER moved
      ;; fn (the same N-scans shape as the 2026-08-31 removal blowup).
      (let [move-row? (fn [row]
                        (let [cs (get synced-by-name (:name row))]
                          (and (= 1 (count cs))
                               (or (nil? preexisting)
                                   (not (contains? preexisting
                                                   (:id (first cs))))))))
            moves (filterv move-row? leftovers)]
        (when (seq moves)
          (let [old->new (into {}
                               (map (fn [row]
                                      [(:id row)
                                       (:id (first (get synced-by-name (:name row))))]))
                               moves)]
            (log/info "reconciling moved package identities"
                      {:count (count moves)
                       :names (mapv :name moves)})
            (idrepair/repoint-refs! storage old->new)
            (idrepair/purge-fn-subgraphs-many! storage (keys old->new))
            (doseq [row moves]
              (registry-core/unregister-rich-type! (keyword (:name row))
                                                   (:id row)))))
        (doseq [row leftovers
                :when (not (move-row? row))]
          (let [candidates (get synced-by-name (:name row))]
            ;; >1 same-name candidates — a move we cannot resolve
            ;; safely. THE signal this reconciler exists for. A
            ;; 1-candidate row whose candidate PRE-DATES this sync is
            ;; not warned here — it falls into the removal set below.
            (when (> (count candidates) 1)
              (log/warn "package identity leftover NOT auto-reconciled"
                        {:name (:name row) :id (:id row)
                         :reason :ambiguous-move-target
                         :candidates (count candidates)})))))
      ;; 0-candidate leftovers are genuine REMOVALS (their package is
      ;; being synced — the `package-roots` guard above — but no fn of
      ;; that name remains). Left in the DB they stay name/id-resolvable
      ;; AND load into every compiled context, so a stale def whose deps
      ;; changed incompatibly can fail the whole-graph `compile-all` (a
      ;; boot crash) — and they accumulate forever, which is what pushed
      ;; operators toward a DB reset. Purge — but ONLY what nothing
      ;; OUTSIDE the removal set references; a removal still referenced
      ;; from live graph must stay LOUD, not be silently deleted out
      ;; from under its referrer. Refs BETWEEN removals (a retired
      ;; fn-def chain referencing itself) resolve by purging referrers
      ;; before referees — that is why this runs as a SET: the per-row
      ;; `inbound-refs` loop paid a full ref-surface scan per fn
      ;; (~1 s × N against a managed PG — the 2026-08-31 deploy-health
      ;; blowup) and still left whole retired chains behind as
      ;; "referenced" by their own siblings.
      ;; Safe against a false positive: package fns carry a
      ;; deterministic `uuid-v5(ns,name)` id, so the next full sync
      ;; re-creates an over-eagerly-purged leaf identically.
      (when (seq removals)
        (let [removal-ids (into #{} (map :id) removals)
              refs-map (idrepair/inbound-refs-many storage removal-ids)
              ;; A `:slot` ref has no owning fn (owner-fn-id nil) — but a
              ;; slot exposed ONLY by removal-set members (and bound by
              ;; no surviving fn) dies with the set, so its type-ref must
              ;; not pin a retired type-row. Classify those slots as
              ;; IN-SET so the kept computation below sees through them.
              in-set-slot-ids
              (let [slot-exposers (reduce (fn [m fs]
                                            (update m (:slot-id fs)
                                                    (fnil conj #{}) (:fn-id fs)))
                                          {}
                                          (sp/query-entities base :fn-slot {}))
                    slot-binders (into #{}
                                       (comp (remove #(contains? removal-ids (:fn-id %)))
                                             (map :slot-id))
                                       (concat (sp/query-entities base :binding {})
                                               (sp/query-entities base :binding-version {})))]
                (into #{}
                      (keep (fn [[slot-id exposers]]
                              (when (and (seq exposers)
                                         (every? removal-ids exposers)
                                         (not (contains? slot-binders slot-id)))
                                slot-id)))
                      slot-exposers))
              external-ref? (fn [ref]
                              (if (= :slot (:entity ref))
                                (not (contains? in-set-slot-ids (:id ref)))
                                (not (contains? removal-ids (:owner-fn-id ref)))))
              ;; An id is KEPT when any ref chain reaches it from
              ;; outside the removal set: seed with directly-externally-
              ;; referenced ids, then propagate through in-set refs
              ;; (a kept member's refs keep its targets too).
              kept (loop [kept (into #{}
                                     (keep (fn [[id refs]]
                                             (when (some external-ref? refs)
                                               id)))
                                     refs-map)]
                     (let [kept' (into kept
                                       (keep (fn [[id refs]]
                                               (when (some #(contains? kept (:owner-fn-id %))
                                                           refs)
                                                 id)))
                                       refs-map)]
                       (if (= kept' kept) kept (recur kept'))))
              purgeable (into [] (remove #(contains? kept (:id %))) removals)]
          ;; Every ref INTO a purgeable row is owned by another purgeable
          ;; row (what the `kept` propagation proved) and refs carry no
          ;; FK — one batched cascade (a scan per TABLE, not per fn)
          ;; removes the whole set, orphaned slots included.
          (when (seq purgeable)
            (log/info "purging removed package identities (unreferenced dead code)"
                      {:count (count purgeable)
                       :names (mapv :name purgeable)})
            (idrepair/purge-fn-subgraphs-many! storage (map :id purgeable))
            (doseq [row purgeable]
              (registry-core/unregister-rich-type! (keyword (:name row))
                                                   (:id row))))
          ;; ONE aggregated warn, not one per row — a large retirement
          ;; pinned by live version rows is a permanent state, and the
          ;; per-row form printed hundreds of lines on every boot.
          (let [left (filterv #(contains? kept (:id %)) removals)]
            (when (seq left)
              (log/warn "package identities removed from EDN but STILL REFERENCED — left in place"
                        {:reason :removed-but-referenced
                         :count (count left)
                         :names (mapv :name left)}))))))
    (count leftovers)))


(defn sync-fn-entities-from-packages!
  "Pure side-effects: sync composed fn-defs to storage, snapshot their
   rich-types, run a topological-order type-check sweep. Returns the
   created fn-rows. `base-fns-info` is the result of
   `register-base-fns-from-packages!` — its `:ns-id-map` and
   `:all-name->id` are forwarded to the compose layer so cross-set
   references (base-fn `:return-type` naming a fn-def-declared type-row)
   resolve.

   Shared by the production `:exec/fn-entities` integrant init-key and
   the out-of-band `bootstrap-from-packages!` test helper.

   `:skip-type-check?` — when truthy, runs only the storage-sync +
   seed-rich-types passes; skips the heavy topological type-check
   sweep at the end. Tests that don't exercise the type-API
   endpoints can opt in to save ~15 s of bootstrap per ns. The
   editor type strips / `/api/types/*` endpoints depend on the
   sweep, so production NEVER skips."
  ([storage packages base-fns-info]
   (sync-fn-entities-from-packages! storage packages base-fns-info nil))
  ([storage packages base-fns-info
    {:keys [skip-type-check? skip-allowlist-gate?]}]
   (let [fn-defs (:fn-defs packages)
         ns-id-map (or (:ns-id-map base-fns-info) {})
         extra-name->id (or (:all-name->id base-fns-info) {})
         ;; Hand the base-fn defs into the composed-fn sync so the slot
         ;; resolver sees their `:args` declarations — without these,
         ;; bindings on slots owned by base-fns wouldn't resolve.
         extra-defs (into {}
                          (keep (fn [[fn-name fn-def]]
                                  (when fn-name
                                    [fn-name (assoc fn-def :name fn-name)])))
                          (:base-fn-defs packages))
         ;; The bundled-package bulk sync writes every fn/slot/binding row
         ;; of the whole graph in single batches — 10485 fn rows as of
         ;; 2026-08-15, past the 10000 `*max-batch-size*` cap, which
         ;; guards USER-submitted writes, not this trusted bootstrap path
         ;; (its own docstring says so). Fresh-DB boots (a new deployment,
         ;; `bb deploy`, the golden test bootstrap) died on
         ;; :batch-error/batch-too-large; incremental re-syncs never hit
         ;; it, which is why long-lived deployments kept working. Bind a
         ;; generous ceiling for exactly this call instead of raising the
         ;; global cap again (5000→10000 was already this whack-a-mole).
         ;; NB: bind the CONFIG var — `sp/*max-batch-size*` is a load-time
         ;; VALUE alias of it, and `validate-batch-size!` reads the config
         ;; var; rebinding the sp copy would be a silent no-op.
         ;; Snapshot the fn ids that exist BEFORE this sync's writes —
         ;; the reconciler uses it to tell a real namespace MOVE (its
         ;; 1-candidate target is a row this sync just minted) from a
         ;; REMOVAL whose bare name merely collides with a long-standing
         ;; fn in another namespace (repointing at that one would rewire
         ;; the graph to an unrelated fn).
         ;; Skipped when the reconciler itself is seam-mocked (unit
         ;; tests hand a keyword mock-storage that can't answer reads).
         preexisting-fn-ids (when-not *reconcile-moved-override*
                              (into #{} (map :id)
                                    (sp/query-entities
                                      (idrepair/base-of storage) :fn {})))
         fns (binding [sp-config/*max-batch-size*
                       (max sp-config/*max-batch-size* 100000)]
               (fn-composition/sync-fns-to-storage! storage fn-defs ns-id-map
                                                    extra-name->id extra-defs))
         ;; ROOT FIX (audit-4): heal namespace-moved package
         ;; identities at the moment of the move — see the fn
         ;; docstring. Before the seed/sweep so registry + compile
         ;; only ever see the healed graph.
         _ (reconcile-moved-identities! storage packages fns
                                        {:preexisting-fn-ids preexisting-fn-ids})]
     ;; Snapshot composed fn-defs into the in-memory rich-type registry
     ;; so the editor's `:effects` strip and arg-type hints can resolve
     ;; their declared shape. Two passes:
     ;;
     ;; 1. Seed each fn-def's declared shape (return + args + declared
     ;;    `:effects`). Without this, `check-all-defs!` would fail to
     ;;    resolve refs to peer fn-defs whose entries don't exist yet.
     ;; 2. Run the full type-checker so `:effects` propagate transitively
     ;;    through every parent + ref edge — that's what powers the
     ;;    editor's effects-strip showing the union of every category
     ;;    a fn-def TRANSITIVELY pulls in. Wrap in try/catch: a single
     ;;    fn-def's type-mismatch shouldn't block server startup.
     ;; Refinement aliases are registered earlier in `:exec/base-fns` so
     ;; base-fn arg types (`:port`, `:user-port`, …) resolve to their
     ;; structural form during the base-fn rich-type pass.
     ;; record-rich-types! validates arg `:type` declarations — those
     ;; only exist on base-fn-style fn-defs (rare here; composed fn-defs
     ;; use `:args` for parent BINDINGS, not declarations). Try-each so a
     ;; few mis-shaped entries don't kill the seed pass; check-all-defs!
     ;; below recovers the proper computed types via type-inference anyway.
     (doseq [fd fn-defs]
       (when-let [fn-name (:name fd)]
         (try (registry-core/record-rich-types! fn-name fd)
              (catch Exception e
                ;; Mis-shaped entries are recoverable by the type-check
                ;; sweep below — don't block startup, but DEBUG-log so
                ;; the per-fn cause is available when chasing a
                ;; downstream sweep failure.
                (log/debug e "Seed-pass record-rich-types! failed for"
                           fn-name)))))
     ;; Type-check in dependency (topological) order: every fn-def is
     ;; checked AFTER the parents and refs it reads, so a SINGLE sweep
     ;; reaches the fixpoint — `check-fn-def!` always sees its
     ;; dependencies' final rich-types, never a stale seed. This is
     ;; what eliminates the order-dependent under-convergence a fixed
     ;; pass count over arbitrary order suffered (a deep chain it
     ;; couldn't propagate, leaving composed fn-defs absent or
     ;; mis-typed). A fn-def that throws here is genuinely absent from
     ;; the rich-type registry — the editor would miss its effect strip
     ;; / computed return — so the per-failure detail is logged at
     ;; DEBUG and a single summary WARN runs at the end. Per-fn-def
     ;; WARNs were too noisy in prod startup logs (~22 baseline
     ;; entries from `:router-result` / `:merge-in` / friends whose
     ;; producer-of-callable shape the typchecker can't unify yet —
     ;; runtime behaviour is correct, the editor just doesn't get a
     ;; computed return-type for those slots). DEBUG keeps the signal
     ;; available; the summary count makes regressions visible.
     ;; Inline `{:parent :X :args …}` anon fn-defs appear in arg-binding
     ;; position throughout `branches/`, `secrets/`, and `execution/`
     ;; packages. The parser's storage-sync pass lifts each into a
     ;; synthetic named `_anon-<hash>` fn-def — so storage / runtime
     ;; see the expanded form — but the type-check sweep reads the
     ;; ORIGINAL EDN, which still carries the literal map. Without
     ;; pre-expansion the sweep classifies each inline anon as a
     ;; record-type literal and fails 20+ bindings against slots that
     ;; expect the parent's structural return type (eg. `:ref` against
     ;; `[:union :null :text]`, `:string` against predicates).
     ;;
     ;; Run the same `expand-inline-anons-in-module` pass on the
     ;; type-check input so the sweep sees the synthetic refs.
     (let [expanded-fn-defs (records-parse/expand-inline-anons-in-module fn-defs)]
       ;; Re-seed rich-types so synthetic anons get a registry entry too.
       (doseq [fd expanded-fn-defs]
         (when-let [fn-name (:name fd)]
           (try (registry-core/record-rich-types! fn-name fd)
                (catch Exception e
                  (log/debug e "Re-seed record-rich-types! failed for" fn-name)))))
       (when-not skip-type-check?
         ;; The sweep records its final failure set into the per-branch
         ;; diagnostics store — key it by the branch this sync ran on
         ;; (nil for an unversioned/base storage = default branch).
         (run-type-check-sweep! expanded-fn-defs skip-allowlist-gate?
                                (vs/current-branch-id storage))
         ;; Port-collision scan — runs against the expanded fn-def
         ;; set so synthetic anons that bind `:port` get inspected
         ;; too. Logs a WARN per colliding port; doesn't fail
         ;; bootstrap because the OS still tells the truth at
         ;; reconcile time. Catches admin-misconfig (two web-server
         ;; fn-defs both bound to :port 8080) BEFORE any service is
         ;; even created — earlier than `:start-failed-at`.
         (port-check/warn-on-collisions! expanded-fn-defs)))
     fns)))


;; =============================================================================
;; Test-friendly bootstrap (out-of-band)
;; =============================================================================
;;
;; Replicates the `:exec/base-fns` + `:exec/fn-entities` init-key chain
;; without integrant. Tests that exercise graph-level handlers
;; (`/api/entities/*` via `:process-create-entity` &c.) call this once
;; from a `:once` fixture to populate storage + registry + type-aliases.
;;
;; Returns `{:ns-id-map :all-name->id :base-fns :fn-rows}` so callers
;; can resolve fn-ids by name without re-querying storage.

(defn bootstrap-from-packages!
  "Bootstrap `storage` from the named `package-names` (default
   [\"core\" \"web\"]). Calls the same `register-base-fns-from-packages!`
   + `sync-fn-entities-from-packages!` helpers the production
   `:exec/base-fns` + `:exec/fn-entities` init-keys use, so any drift
   between bootstrap and production stays localized to those two
   helpers. Safe to call once per test JVM lifetime against a clean
   storage. Idempotent against the global type-alias / base-fn-impl
   registries (re-registers them)."
  ([storage]
   (bootstrap-from-packages! storage ["core" "web"] nil))
  ([storage package-names]
   (bootstrap-from-packages! storage package-names nil))
  ([storage package-names opts]
   (let [packages (pkg/load-packages package-names)
         base-fns-info (register-base-fns-from-packages! storage packages)
         fns (sync-fn-entities-from-packages! storage packages base-fns-info opts)]
     (assoc base-fns-info :fn-rows fns))))
