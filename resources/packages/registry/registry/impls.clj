(ns graphden.packages.app.registry.impls
  "Implementations for app/registry base functions. Thin primitive over
   `graphden.packages.export` — the multi-step publish/extract flow is
   graph composition (fn-defs) over this + the CRUD base-fns."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.interface :as composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.export :as export]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.wire :as wire]
    [graphden.packages.semver :as semver]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.storage.core :as vs]))


;; The export BUNDLES (`:export-namespace` / `:export-graph`) are graph
;; compositions in fns.edn — root-filter, secret-strip policy, and the
;; bundle-map assembly are all graph-visible. The primitives below are
;; what they compose: the records↔EDN codec read, the two pure
;; secret-path passes, the EDN-wire encoder, and the one cohesive
;; dependency-analysis pass.

(defbase graph-fn-defs
  "The whole stored graph as fn-def maps — `export/export-graph`, the
   records read + records→EDN codec (inverse-of-parser library
   boundary)."
  []
  (cr/record-effect! :db)
  (export/export-graph (request/require-storage ctx)))


(defbase secret-path-args-fn
  "Manifest of vault-path bindings across fn-defs — one
   `{:fn <name> :arg <arg>}` per `{:secret-path …}` arg-value. Pure
   scan (`export/secret-path-args`)."
  [fn-defs]
  (export/secret-path-args fn-defs))


(defbase strip-secret-paths-fn
  "fn-defs with vault paths removed — a stripped arg reverts to a FREE
   secret-typed slot at the importer. Pure pass
   (`export/strip-secret-paths`)."
  [fn-defs]
  (export/strip-secret-paths fn-defs))


(defbase encode-unreadable-kws-fn
  "EDN-wire boundary — refs whose qualification isn't spellable as a
   readable keyword (`@`-versioned ns, root ns) become `#graphden/ref`
   tagged literals (`records.wire/encode-unreadable-kws`). For
   EDN-TEXT artifacts (the whole-graph bundle); the JSONB publish path
   keeps raw keywords."
  [value]
  (wire/encode-unreadable-kws value))


(defbase namespace-external-deps
  "Dependency analysis for the subtree rooted at `root` —
   `{:dependencies [...] :package-dependencies [...]}` via
   `export/external-deps`: one records read shared by the structural
   ref closure and the constraint type-name scan (cohesive single-pass
   analysis, stays one primitive)."
  [root]
  (cr/record-effect! :db)
  (export/external-deps (request/require-storage ctx) root))


(defbase current-org-id
  "The org id in scope for this request (`tc/current-org`; the shared
   public org when unbound — single-tenant). Single context read (§3.1).
   Lets graph compositions (the governance catalog filter) compare rows
   against the caller's org without an org literal. Pure: a thread-local
   read, constant within one request execution."
  []
  (tc/current-org))


(defbase graph-rows
  []
  (cr/record-effect! :db)
  (export/read-graph (request/require-storage ctx)))


;; Atomic publish core: reject if `(pkg-name, pkg-version)` already
;; exists, else hash + insert. The existence check stays ADJACENT to the
;; insert (one base-fn) to keep the check-then-insert race window
;; minimal until the DB-level UNIQUE(name, version) hardening noted in
;; the schema ships. Returns the CREATED ROW (nil = version already
;; exists) — both result envelopes, like the empty-bundle rejection,
;; are graph composition (`:_pub-ok` / `:_pub-err-exists` under
;; `:publish-package`), so the response shape is admin-visible. The
;; content-hash + `:public?` normalisation stay here: both are STORED
;; on the row at write time (readers never re-derive them).
(defbase publish-package-apply
  [pkg-name pkg-version bundle pkg-public]
  ;; Authz chokepoint: publishing to an ORG's registry requires the
  ;; `:publish-packages` org capability. Guard the deepest effectful core so
  ;; NO route (JSON or panel) can bypass it. Single-tenant-safe via the
  ;; platform-tier short-circuit — mirrors the `:view-all-stats` precedent in
  ;; app/execution/impls.clj; the org-cap seam is default-deny without the
  ;; tenancy addon, so the short-circuit keeps self-hosted/operator publishing
  ;; open. `:authz/forbidden` → 403 in the tenancy request-scope wrapper.
  (when-not (or (tc/current-platform-tier?)
                (tc/current-has-org-cap? :publish-packages))
    (throw (ex-info "Publishing requires the publish-packages capability."
                    {:type :authz/forbidden :capability :publish-packages})))
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [storage (request/require-storage ctx)
        fns (:fns bundle)]
    (when-not (seq (sp/query-entities storage :package-version
                                      {:name pkg-name :version pkg-version}))
      ;; Public = the explicit opt-in OR a platform-tier publish
      ;; (single-tenant / operator — the shared registry). Normalised
      ;; AT WRITE time so readers never re-derive tier from org-id:
      ;; a row is platform-visible iff `:public?` is true. A tenant
      ;; publish without the opt-in stays private to its org
      ;; (`:org-id` stamped by the tenancy decorator, spec §5).
      (sp/create-entity storage :package-version
                        {:name pkg-name
                         :version pkg-version
                         :ns-root (:namespace bundle)
                         :fns fns
                         :dependencies (:dependencies bundle)
                         :package-dependencies (:package-dependencies bundle)
                         :secrets (vec (:secrets bundle))
                         :content-hash (ids/digest-hex "SHA-256" (json/generate-string fns))
                         ;; Same value the tenancy decorator stamps when
                         ;; scoped (it overwrites with `(tc/current-org)`
                         ;; too) — set here as well so SINGLE-TENANT rows
                         ;; carry the public org instead of NULL and the
                         ;; governance catalog's org-equality filter works
                         ;; identically with and without the addon.
                         :org-id (tc/current-org)
                         :public? (boolean (or pkg-public (tc/current-platform-tier?)))
                         :published-at (java.time.Instant/now)}))))


;; `:list-package-versions` / `:fetch-package-version` are pure graph
;; compositions in fns.edn over `:query-entities` — the per-row JSON
;; reshape (stringified ids/timestamps, `:fn-count`) is graph-visible.


;; ---------------------------------------------------------------------------
;; Shared Clojure helpers for install / fork / materialize / pin. These are
;; private `defn-`, NOT base-fns: a base-fn must never call another base-fn
;; (that would hide a graph edge — philosophy), but sharing implementation
;; detail across a few base-fns is ordinary Clojure reuse.
;; ---------------------------------------------------------------------------

(defbase missing-package-dependencies
  "The declared dependency names (a :package-version row's
   `:dependencies`) NOT present as fns in storage — the shared
   install / fork / materialize precondition. ONE batched IN-query +
   set diff; the batch shape (no N+1) is the point of keeping it a
   single storage predicate. Returns a vector of keyword names
   (empty = all satisfied)."
  [dependencies]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        dep-names (mapv name dependencies)
        present (into #{}
                      (map :name)
                      (when (seq dep-names)
                        (sp/query-entities storage :fn {:name (vec (distinct dep-names))})))]
    (mapv keyword (distinct (remove present dep-names)))))


;; Rewrite one bundle fn's namespace so version V of the package rooted at
;; NS-ROOT lives at `<ns-root>@<sanitized-version>`. The version's dots are
;; sanitized to dashes (a dot is the ns-path separator), so "1.3.0" → "1-3-0"
;; and `web.components.foo` → `web.components@1-3-0.foo`. Pure boundary
;; string-shaping. A ns not under NS-ROOT is left as-is.
(defn- version-qualified-ns
  [ns-root version fn-ns]
  (if (and fn-ns (str/starts-with? fn-ns ns-root))
    (str ns-root "@" (str/replace (str version) "." "-") (subs fn-ns (count ns-root)))
    fn-ns))


;; Sync a bundle's fns ONCE under `<ns-root>@<version>` (idempotent —
;; deterministic ids + upsert). Returns the count synced. The sync resolves
;; the bundle's external references (parents, HOF refs, renamed/free-arg slots)
;; through the same faithful fn-def path the boot sync uses. Shared by
;; :materialize-package-version and reference :install-package.
(defn- materialize-fns!
  "Sync a bundle's fns under `<ns-root>@<version>` (idempotent). Returns the
   materialized fn-ids (deterministic `fn-id` over each version-qualified ns +
   name) so the CALLER can delta-invalidate — recompile only the new fns + their
   dependents rather than clear + rebuild the whole registry. Does NOT
   invalidate itself (update combines these ids with rewritten-ref owners)."
  [storage ns-root version fns]
  (let [materialized (mapv (fn [fd]
                             (update fd :namespace #(version-qualified-ns ns-root version %)))
                           fns)
        ns-id-map (loader/sync-namespaces! storage (into #{} (keep :namespace) materialized))]
    (composition/sync-fns-to-storage! storage materialized ns-id-map)
    (mapv #(ids/fn-id (:namespace %) (:name %)) materialized)))


;; True if the version's first fn already exists under its version-qualified
;; namespace — idempotency guard + cloud public-org skip: OrgScoped read
;; returns own+public, so a tenant sees a platform-materialized version and
;; does NOT re-materialize it into its own org. Shared by the
;; `:package-version-materialized?` base-fn and the update core.
(defn- already-materialized?
  [storage ns-root version fns]
  (boolean
    (when-let [f (first fns)]
      (sp/read-entity storage :fn
                      (ids/fn-id (version-qualified-ns ns-root version (:namespace f))
                                 (:name f))))))


(defbase package-version-materialized?
  "True iff `version` of the package rooted at `ns-root` is already
   visible under its version-qualified namespace — the idempotency
   guard of reference-install, exposed so the graph install flow can
   skip the materialize step (and its invalidation) when the version
   is already there. Under a cloud OrgScoped read a platform-
   materialized version is visible to the tenant, so the tenant does
   NOT re-materialize it into its own org. §3.1 single storage read
   (probes the first fn's deterministic id)."
  [ns-root version fns]
  (cr/record-effect! :db)
  (already-materialized? (request/require-storage ctx) ns-root version fns))


;; Upsert the single pin for `(current-branch, pkg-name)` → version. One pin
;; per (branch, package). Returns the branch-id. Branch comes from the
;; request-scoped VersionedStorage, so it records on the request's branch
;; (staging). Shared by the `:package-upsert-pin` base-fn (which the
;; graph install flow + :set-package-pin bind) and the update core.
(defn- upsert-pin!
  [storage pkg-name version]
  (let [branch-id (vs/current-branch-id storage)
        existing (first (sp/query-entities storage :package-install
                                           {:branch-id branch-id :package-name pkg-name}))
        installed-at (java.time.Instant/now)]
    (if existing
      (sp/update-entity storage :package-install (:id existing)
                        {:version version :installed-at installed-at})
      (sp/create-entity storage :package-install
                        {:branch-id branch-id
                         :package-name pkg-name
                         :version version
                         :installed-at installed-at}))
    branch-id))


(defbase resolve-package-version
  "Pick the highest published version of `pkg-name` satisfying `spec` —
   a semver constraint (exact `\"1.2.0\"`, `\">=1.1\"`, `\"~>1.2\"`, …);
   nil / \"\" / \"latest\" mean \"any\" (the newest). Returns the concrete
   version string, or nil when nothing matches. Versions sort by parsed
   `[major minor patch]`. Self-contained constraint-resolution
   algorithm over one query — same carve-out class as
   `:pick-encoding`'s RFC negotiation; the DECISIONS around it
   (guards, envelopes, apply) live in the graph."
  [pkg-name spec]
  (cr/record-effect! :db)
  (let [constraint (if (contains? #{nil "" "latest"} spec) "*" spec)
        matching (into []
                       (comp (map :version)
                             (filter #(semver/satisfies-constraint? % constraint)))
                       (sp/query-entities (request/require-storage ctx)
                                          :package-version {:name pkg-name}))]
    (last (sort-by semver/parse-version matching))))


;; Repoint a project's OWN references from version OLD to version NEW of the
;; package rooted at NS-ROOT (variant B — update/rollback rewrites the caller's
;; refs rather than late-binding through the pin). Deterministic remap: for
;; each fn in the NEW bundle, its old-version fn-id → new-version fn-id (both
;; via `fn-id` over the version-qualified ns). A binding / list-item is
;; rewritten IFF its `:ref-fn-id` is an OLD-version fn AND its OWNER fn is NOT —
;; so package-INTERNAL refs (owner inside the package) and new-version refs
;; (don't point at old fns) are left untouched, mixing versions is impossible.
;; Writes create branch-version rows, so the rewrite is staging-safe/revertable.
;; Returns `{:count n :owners #{fn-ids}}` — the number of refs rewritten plus
;; the owner fns whose compiled form changed, so the caller can delta-invalidate.
(defn- rewrite-refs-to-version!
  [storage ns-root old-version new-version new-fns]
  (let [remap (into {}
                    (map (fn [fd]
                           (let [ns (:namespace fd) nm (:name fd)]
                             [(ids/fn-id (version-qualified-ns ns-root old-version ns) nm)
                              (ids/fn-id (version-qualified-ns ns-root new-version ns) nm)])))
                    new-fns)
        old-fids (set (keys remap))
        bindings (sp/query-entities storage :binding {})
        items (sp/query-entities storage :binding-list-item {})
        binding-owner (into {} (map (juxt :id :fn-id)) bindings)
        user-ref? (fn [ref owner]
                    (and ref (contains? remap ref) (not (contains? old-fids owner))))
        ;; Rewrite one row IFF it's a user-ref, tracking BOTH the count and the
        ;; owner fn-id — the owners are exactly the fns whose compiled form
        ;; changed, so the caller delta-invalidates just those (+ dependents)
        ;; instead of a full graph recompile.
        rewrite (fn [acc entity-type ent owner]
                  (if (user-ref? (:ref-fn-id ent) owner)
                    (do (sp/update-entity storage entity-type (:id ent)
                                          {:ref-fn-id (remap (:ref-fn-id ent))})
                        (-> acc (update :count inc) (update :owners conj owner)))
                    acc))
        acc0 {:count 0 :owners #{}}
        after-bindings (reduce (fn [acc b] (rewrite acc :binding b (:fn-id b))) acc0 bindings)]
    (reduce (fn [acc it] (rewrite acc :binding-list-item it (binding-owner (:binding-id it))))
            after-bindings items)))


;; ---------------------------------------------------------------------------
;; Install (reference), fork (copy-on-write), materialize.
;; ---------------------------------------------------------------------------

;; Fork apply-core (PACKAGE_DISTRIBUTION §4.5): sync a bundle's fns into
;; the graph AT THEIR ORIGINAL namespace (copy-on-write duplicate into the
;; caller's project) + delta-invalidate — the write and its invalidation
;; are a coupled pair (§3.3), everything around them (resolve, guards,
;; envelopes) is graph composition in fns.edn (`:fork-package`).
(defbase fork-package-fns
  [fns]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        ns-id-map (loader/sync-namespaces! storage (into #{} (keep :namespace) fns))
        forked-ids (mapv #(ids/fn-id (:namespace %) (:name %)) fns)]
    (composition/sync-fns-to-storage! storage fns ns-id-map)
    ;; Delta-invalidate: the forked fns (+ dependents) recompile, not the
    ;; whole registry — a full clear here froze constrained instances.
    (exec-ctx/invalidate-graph-cache! ctx forked-ids)
    (br/note-graph-epoch-validated! (request/require-storage ctx))
    (count fns)))


;; Materialize apply-core (PACKAGE_DISTRIBUTION §4.2): sync a bundle's
;; fns ONCE under `<ns-root>@<version>` + delta-invalidate — coupled
;; write+invalidation pair (§3.3). Resolve / guards / envelopes are graph
;; composition in fns.edn (`:materialize-package-version`).
(defbase materialize-package-fns
  [ns-root version fns]
  (cr/record-effect! :db)
  (let [mat-ids (materialize-fns! (request/require-storage ctx) ns-root version fns)]
    (exec-ctx/invalidate-graph-cache! ctx mat-ids)
    (br/note-graph-epoch-validated! (request/require-storage ctx))
    (count mat-ids)))


(defbase version-qualified-ns-fn
  "Pure boundary string-shaping: `web.components.foo` @ `1.3.0` under
   ns-root `web.components` → `web.components@1-3-0.foo`. Exposed as a
   base-fn (delegating to the same helper the §3.3 cores use) so graph
   envelopes can cite the version-qualified namespace without
   duplicating the naming contract."
  [ns-root version fn-ns]
  (version-qualified-ns ns-root version fn-ns))


;; `:install-package` is now a GRAPH fn-def — a `:fix` worklist loop
;; over resolve/install ops (see the `:_inst-*` chain in fns.edn). Its
;; primitives are the base-fns this file already exposes:
;; `:resolve-package-version`, `:missing-package-dependencies`,
;; `:package-version-materialized?`, `:materialize-package-fns`,
;; `:package-upsert-pin`. The former Clojure `install-recursive!` /
;; `install-one!` orchestration (guards, depth-first dep order,
;; short-circuit) lives in the graph where it is visible and per-step
;; composable.


;; Rewrite apply-core: repoint the project's OWN refs OLD→NEW (variant B —
;; package-internal refs untouched; `rewrite-refs-to-version!`'s shared
;; remap/accumulator is the §3.3 invariant) + delta-invalidate the owner
;; fns whose compiled form changed — a coupled write+invalidation pair,
;; same class as fork / materialize. An empty owner set invalidates
;; nothing (`#{}` = "the write reached no compiled closure"). The rest of
;; the former update pipeline — materialize-if-needed, pin repoint — is
;; GRAPH composition now (the `:_upd-rewritten` `:do` in fns.edn), so
;; update/rollback reads as steps in the graph instead of one opaque core.
(defbase rewrite-refs-to-version
  [ns-root old-version new-version fns]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        {rewritten :count :keys [owners]}
        (rewrite-refs-to-version! storage ns-root old-version new-version fns)]
    ;; Delta — a full clear here recompiled ~3600 fns and froze the server.
    (exec-ctx/invalidate-graph-cache! ctx owners)
    (br/note-graph-epoch-validated! storage)
    rewritten))


;; ---------------------------------------------------------------------------
;; Package pins — per-branch desired-state "this branch uses package P at V".
;; The pin drives update/rollback (repoint the row) and the editor's installed
;; list. Reference-install writes a pin instead of copying rows.
;; ---------------------------------------------------------------------------

;; Single-row pin upsert (current-branch, pkg-name) → version — a
;; check-then-write pair on one desired-state row, shared with the
;; install / update cores via `upsert-pin!`. Returns the branch-id as
;; text; the `{:ok …}` envelope is graph composition
;; (`:set-package-pin` in fns.edn).
(defbase package-upsert-pin
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (str (upsert-pin! (request/require-storage ctx) pkg-name pkg-version)))


;; `:list-installed-packages` / `:remove-package-pin` are pure graph
;; compositions in fns.edn over `:query-entities` / `:delete-entity` +
;; `:current-branch-id`.


(def impls
  {:graph-fn-defs graph-fn-defs
   :secret-path-args secret-path-args-fn
   :strip-secret-paths strip-secret-paths-fn
   :encode-unreadable-kws encode-unreadable-kws-fn
   :namespace-external-deps namespace-external-deps
   :current-org-id current-org-id
   :graph-rows graph-rows
   :publish-package-apply publish-package-apply
   :resolve-package-version resolve-package-version
   :missing-package-dependencies missing-package-dependencies
   :package-version-materialized? package-version-materialized?
   :version-qualified-ns version-qualified-ns-fn
   :fork-package-fns fork-package-fns
   :materialize-package-fns materialize-package-fns
   :rewrite-refs-to-version rewrite-refs-to-version
   :package-upsert-pin package-upsert-pin})
