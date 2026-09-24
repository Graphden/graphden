(ns graphden.packages.app.registry.impls
  "Implementations for app/registry base functions. Thin primitive over
   `graphden.packages.export` — the multi-step publish/extract flow is
   graph composition (fn-defs) over this + the CRUD base-fns."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.compat :as compat]
    [graphden.packages.export :as export]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.wire :as wire]
    [graphden.packages.registry-shared :as shared]
    [graphden.packages.semver :as semver]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.sql.pg :as pg]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.epoch :as br-epoch]
    [graphden.system.deploy-config :as deploy-config]
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


(defbase tenancy-active?
  "True when the tenancy addon is wired (its org-capability policy is
   installed). Single seam read (§3.1) — lets server-rendered copy
   (the governance who-may-publish note) branch on the SAME fact the
   editor derives from capability headers."
  []
  (tc/tenancy-addon-active?))


(defbase graph-rows
  []
  (cr/record-effect! :db)
  (export/read-graph (request/require-storage ctx)))


;; Atomic publish core: reject if `(pkg-name, pkg-version)` already
;; exists (nil) or another org lists the NAME publicly (`{:refused
;; "name-taken"}`), else hash + insert. The existence check stays ADJACENT to the
;; insert (one base-fn) to keep the check-then-insert race window
;; minimal on top of the DB-level UNIQUE(name, version). Returns the
;; CREATED ROW (nil = version already exists) — both result envelopes,
;; like the empty-bundle rejection, are graph composition (`:_pub-ok` /
;; `:_pub-err-exists` under `:publish-package`), so the response shape
;; is admin-visible. The row itself (content hash, `:public?` / `:status`
;; normalisation, listing, publisher) is `registry-shared/version-row` —
;; the same row the boot-time starter catalogue writes.
(defn- publish-under-lock!
  "The check-then-insert of a publish, run with the package's name lock
   held (`shared/with-package-name-lock`): the version pre-check, the
   public-name holder check, the insert. See `publish-package-apply`."
  [storage pkg-name pkg-version bundle pkg-public listing]
  (let [holder (shared/foreign-public-holder storage pkg-name)]
    (cond
      (seq (sp/query-entities storage :package-version
                              {:name pkg-name :version pkg-version}))
      nil

      ;; Names (docs/MARKETPLACE.md § 2): another org lists this name
      ;; publicly — neither a public nor a private version may join it.
      holder
      {:refused "name-taken" :holder holder}

      :else
      (shared/insert-or-exists!
        storage
        (shared/version-row pkg-name pkg-version bundle pkg-public listing)))))


(defbase publish-package-apply
  [pkg-name pkg-version bundle pkg-public listing]
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
  (let [storage (request/require-storage ctx)]
    ;; Serialised per package name across executors (docs/MARKETPLACE.md
    ;; § 2, Names): without the lock two orgs racing for the same public
    ;; name both pass the holder check and both land.
    (shared/with-package-name-lock
      storage pkg-name
      #(publish-under-lock! storage pkg-name pkg-version bundle pkg-public listing))))


(defbase breaking-changes-between
  "Consumer-visible incompatibilities from `old-fns` to `new-fns` (two
   bundles' fn-def lists) — `compat/breaking-changes`, pure."
  [old-fns new-fns]
  (compat/breaking-changes old-fns new-fns))


(defbase incompatible-dependency-bumps
  "Package dependencies whose version left the old one's caret range —
   `compat/incompatible-dependency-bumps`, pure."
  [old-deps new-deps]
  (compat/incompatible-dependency-bumps old-deps new-deps))


(defbase semver-compatible?
  "Is `to` inside `from`'s caret range (`^from`: same major, or same
   minor below 1.0) — the range a consumer pinned with a constraint
   would auto-advance into?"
  [from to]
  (boolean (semver/satisfies-constraint? to (str "^" from))))


(defbase withdraw-package-apply
  "Delete a published `:package-version` row, gated on the `:publish-packages`
   org capability — the DESTRUCTIVE counterpart of `publish-package-apply`, and
   guarded at the same deepest effectful core so NO route (JSON or panel) can
   bypass it. Before this, `withdraw` was auth-only, so an org member explicitly
   DENIED publish rights could still permanently erase the org's published
   versions. Single-tenant-safe via the platform-tier short-circuit (org-cap
   seam is default-deny without the tenancy addon). Own-org only — the row is
   org-scoped, so RLS/decorator confine the delete. Returns the deleted id."
  [id]
  (when-not (or (tc/current-platform-tier?)
                (tc/current-has-org-cap? :publish-packages))
    (throw (ex-info "Withdrawing a package requires the publish-packages capability."
                    {:type :authz/forbidden :capability :publish-packages})))
  (cr/record-effect! :db)
  (sp/delete-entity (request/require-storage ctx) :package-version id)
  id)


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
  ;; Dot-boundary guard (mirrors `export/under-ns?`): only the root ns
  ;; itself or a true descendant (`<ns-root>.`) is rewritten. A sibling
  ;; sharing a non-dotted prefix (`app.foobar` under root `app.foo`) is
  ;; left as-is — a bare `starts-with?` would mangle it into
  ;; `app.foo@1-0-0bar`. Latent for the pre-filtered callers, real for
  ;; the graph-exposed `version-qualified-ns-fn`.
  (if (and fn-ns (or (= fn-ns ns-root) (str/starts-with? fn-ns (str ns-root "."))))
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
                           fns)]
    (pkg-sync/sync-bundle! storage materialized)))


;; True if `version` is COMPLETELY materialized under its version-qualified
;; namespace — idempotency guard + cloud public-org skip: OrgScoped read
;; returns own+public, so a tenant sees a platform-materialized version and
;; does NOT re-materialize it into its own org. Shared by the
;; `:package-version-materialized?` base-fn and the update core.
;;
;; Completeness, not mere existence: `write-records!` commits the `:fn`
;; identity batch BEFORE the `:binding` batch, non-transactionally, so a
;; materialize that died mid-way leaves orphaned identity rows with no
;; bodies. Probing only `(first fns)`'s identity would then report TRUE and
;; make install SKIP the (idempotent) re-materialize, freezing the
;; half-written version. So verify BOTH: every bundle identity present AND
;; at least one materialized binding for the fns that carry one (non-empty
;; `:args`). A false negative only costs a redundant, safe re-sync.
(defn- already-materialized?
  [storage ns-root version fns]
  (let [fid (fn [f]
              (ids/fn-id (version-qualified-ns ns-root version (:namespace f))
                         (:name f)))
        expected-ids (mapv fid fns)
        ;; fns that customize a slot (non-empty :args) MUST have ≥1 binding —
        ;; exactly the body the mid-way write drops after committing identities.
        body-fn-ids (into [] (comp (filter #(seq (:args %))) (map fid)) fns)]
    (boolean
      (and (seq expected-ids)
           (= (count expected-ids)
              (count (sp/query-entities storage :fn {:id expected-ids})))
           (or (empty? body-fn-ids)
               (seq (sp/query-entities storage :binding {:fn-id body-fn-ids})))))))


(defbase package-version-materialized?
  "True iff `version` of the package rooted at `ns-root` is already
   visible under its version-qualified namespace — the idempotency
   guard of reference-install, exposed so the graph install flow can
   skip the materialize step (and its invalidation) when the version
   is already there. Under a cloud OrgScoped read a platform-
   materialized version is visible to the tenant, so the tenant does
   NOT re-materialize it into its own org. Probes COMPLETENESS
   (every bundle identity + a representative body row), not the
   mere existence of one identity — a half-written version reports
   false so install re-materializes it (re-sync is idempotent)."
  [ns-root version fns]
  (cr/record-effect! :db)
  (already-materialized? (request/require-storage ctx) ns-root version fns))


(defn- bump-install-stat!
  "Count one install of `pkg-name` on the GLOBAL `:package-stat` row —
   an atomic `INSERT … ON CONFLICT DO UPDATE` over the pool, the
   `usage-stat` bump's shape: the row is deliberately un-scoped (an
   installer in org B cannot write org A's artifact, and per-org pins
   are invisible across orgs) and platform-write-only under tenancy, so
   the generic entity route cannot forge a count. Best-effort: a failed
   bump is logged and never fails the install."
  [ctx pkg-name]
  (try
    ;; `java.sql.Timestamp`, not `Instant` — pgjdbc cannot infer a bind
    ;; type for a bare Instant (the usage-stat bump's `hour-bucket` does
    ;; the same conversion).
    (let [now (java.sql.Timestamp/from (java.time.Instant/now))]
      (pg/pg-execute ctx {:insert-into :package_stat
                          :values [{:id (random-uuid)
                                    :package_name pkg-name
                                    :installs 1
                                    :updated_at now}]
                          :on-conflict [:package_name]
                          :do-update-set {:installs [:+ :package_stat.installs 1]
                                          :updated_at now}}))
    (catch Exception e
      (log/warn e "package-stat bump failed (install unaffected)" {:package pkg-name}))))


(defn- upsert-pin!
  "Upsert the pin `(current-branch, pkg-name)` → version and return the
   branch id. The branch is the request's VersionedStorage branch, so the
   pin records on the request's branch (staging). One pin per (branch,
   package). A NEW pin is an install and bumps the
   package's global install counter; moving an existing pin (update /
   rollback) is not — the counter is installs, not pin writes. The bump is
   part of the pin write unit (like the cache invalidation an entity write
   owes), not a separate graph step: a graph-side \"was it pinned?\" probe
   is an effectful read that a later ref would re-run AFTER the pin."
  [ctx storage pkg-name version]
  (let [branch-id (vs/current-branch-id storage)
        existing (first (sp/query-entities storage :package-install
                                           {:branch-id branch-id :package-name pkg-name}))
        installed-at (java.time.Instant/now)]
    (if existing
      (sp/update-entity storage :package-install (:id existing)
                        {:version version :installed-at installed-at})
      (do
        (sp/create-entity storage :package-install
                          {:branch-id branch-id
                           :package-name pkg-name
                           :version version
                           :installed-at installed-at})
        (bump-install-stat! ctx pkg-name)))
    branch-id))


(defbase semver-pick
  "The highest of `versions` satisfying `spec` — a semver constraint
   (exact `\"1.2.0\"`, `\">=1.1\"`, `\"~>1.2\"`, …); nil / \"\" / \"latest\"
   mean \"any\" (the newest). Versions order by parsed `[major minor
   patch]`; nil when nothing matches. Pure — the ONE pick both the local
   (`:resolve-package-version`) and the remote (`:resolve-remote-version`)
   resolution compose, over whichever version list they read. Self-
   contained constraint-resolution algorithm (same carve-out class as
   `:pick-encoding`'s RFC negotiation)."
  [versions spec]
  (let [constraint (if (contains? #{nil "" "latest"} spec) "*" (str spec))]
    (->> versions
         (filter #(and (some? %) (semver/satisfies-constraint? (str %) constraint)))
         (sort-by #(semver/parse-version (str %)))
         last)))


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
        forked-ids (pkg-sync/sync-bundle! storage fns)]
    ;; Delta-invalidate: the forked fns (+ dependents) recompile, not the
    ;; whole registry — a full clear here froze constrained instances.
    (exec-ctx/invalidate-graph-cache! ctx forked-ids)
    (br-epoch/note-graph-epoch-validated! (request/require-storage ctx))
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
    (br-epoch/note-graph-epoch-validated! (request/require-storage ctx))
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
;; primitives are the base-fns `:missing-package-dependencies`,
;; `:package-version-materialized?`, `:materialize-package-fns` and
;; `:package-upsert-pin` from this file, and the `:resolve-package-version`
;; fn-def (`:semver-pick` over the name's `:package-version` rows). The former Clojure `install-recursive!` /
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
    (br-epoch/note-graph-epoch-validated! storage)
    rewritten))


;; ---------------------------------------------------------------------------
;; Remote-registry mirror — the client half of cross-install package pull
;; (PACKAGE_DISTRIBUTION § 13). ONE remote package per call: the install
;; worklist drives the dependency closure through its normal `:resolve`
;; ops, each missing dep mirroring on its own retry — so the recursion
;; stays graph-visible. The dial (`:http-request`, so a tenant's pull is
;; egress-guarded, rate-capped and byte-capped like any outbound call), the
;; decode and every refusal are fn-defs (`:mirror-remote-package!`); this
;; base-fn is only the idempotent insert (same atomic check-then-insert
;; class as `publish-package-apply`).
;; ---------------------------------------------------------------------------

(defn- mirror-under-lock!
  "The check-then-insert of a mirror, run with the package's name lock held
   — the same three steps as `publish-under-lock!`: an existing visible row
   wins, another org's public name refuses, else insert under the
   REQUESTED `(pkg-name, version)`."
  [storage pkg-name version row origin-url origin]
  (let [answer {:name pkg-name :version version}
        holder (delay (shared/foreign-public-holder storage pkg-name))]
    (cond
      (seq (sp/query-entities storage :package-version {:name pkg-name :version version}))
      answer

      @holder
      (assoc answer :error "name-taken" :holder (str @holder))

      (shared/insert-or-exists!
        storage
        (-> row
            (select-keys [:ns-root :fns :dependencies :package-dependencies
                          :secrets :content-hash
                          :kind :description :category :tags :payload])
            (assoc :name pkg-name
                   :version version
                   :org-id (tc/current-org)
                   :public? false
                   :origin (merge {:url origin-url} origin)
                   :published-at (java.time.Instant/now))))
      answer

      :else
      (assoc answer :error "version-exists"))))


(defbase mirror-store-package-version!
  "Store a fetched remote `row` as the LOCAL `:package-version`
   `(pkg-name, version)` — the REQUESTED key, never the row's own `:name` /
   `:version` (a remote the caller controls must not choose which release
   line its copy lands in: install needs no `:publish-packages`, and the
   key is registry-wide UNIQUE) — idempotent (an existing row wins, nothing
   is written) — and return `{:name :version}`, plus `:error` when refused:
   `name-taken` (another org lists the name publicly, docs/MARKETPLACE.md
   § 2 — a mirror may not join it any more than a publish may) or
   `version-exists` (the key is held by a row this org cannot see). Run
   under the package-name lock, like a publish. The only effect of the
   mirror: the fetch, the decode and every other refusal are the
   `:mirror-remote-package!` fn-def. The copy keeps the bundle and its
   marketplace listing, is stamped with this org, is NEVER public (a
   mirrored copy is not re-published from here), and remembers where it
   came from: `:origin` = `{:url origin-url}` merged with the origin's
   read-only signals (`:remote-package-card`; nil = a remote without them —
   the url alone still marks the row as a mirror)."
  [pkg-name version row origin-url origin]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        nm (str pkg-name)]
    (shared/with-package-name-lock
      storage nm
      #(mirror-under-lock! storage nm (str version) row origin-url origin))))


;; ---------------------------------------------------------------------------
;; Remote bearers — the ONE place a deployment token meets an outbound URL.
;; The dials themselves are `:http-request` fn-defs (fns.edn); this one
;; decides whether the URL about to be called earns a token: only a
;; URL whose ORIGIN is the configured one (`:registry-url` / `:hub-url`
;; deploy settings). A caller-chosen `source` elsewhere dials without it.
;; ---------------------------------------------------------------------------

(def ^:private endpoint-credentials
  "Per endpoint: the deploy setting naming its URL and the reader of its
   token."
  {"registry" [:registry-url #'shared/registry-token]
   "hub" [:hub-url #'shared/hub-token]})


(defbase remote-auth-value
  "The `Authorization` value for a dial to `url` on behalf of `endpoint`
   (`\"registry\"` / `\"hub\"`) — `Bearer <token>` when `url`'s origin is
   that endpoint's configured URL (`GRAPHDEN_REGISTRY_URL` /
   `GRAPHDEN_HUB_URL`), else nil. The install body's `source` is caller-
   chosen: without the origin check the registry token went to any host
   named there."
  [url endpoint]
  (cr/record-effect! :env)
  (when-let [[setting token] (endpoint-credentials (str endpoint))]
    (shared/bearer-for-origin url (deploy-config/read-setting setting) (token))))


;; ---------------------------------------------------------------------------
;; Bundle import — POST /api/import/graph. The steps are graph composition
;; (`:import-bundle!` in fns.edn: find/create the branch → partition out the
;; platform-owned defs → drop orphaned anons → adopt editor-born identities
;; → the shared `:sync-fn-defs-branch!` → optional prune); these are the
;; effectful primitives it composes, each one storage call (+ its
;; invalidation, where it writes graph rows).
;; ---------------------------------------------------------------------------

(defn- branch-storage
  "The request's storage switched onto branch `branch-id`."
  [ctx branch-id]
  (vs/switch-branch (request/require-storage ctx) branch-id))


(defbase branch-by-name
  "The branch row named `branch-name`, or nil — a read of the branch table
   (beneath the versioned view: branches are not branch-versioned)."
  [branch-name]
  (cr/record-effect! :db)
  (first (sp/query-entities (:base-storage (request/require-storage ctx))
                            :branch {:name branch-name})))


(defbase create-owned-branch!
  "Create branch `branch-name` off the request's branch, stamping the
   caller as owner with the `owner` write-policy (the push-branch
   convention) when a user is bound. Returns the row."
  [branch-name]
  (cr/record-effect! :db)
  (let [uid (str (:user-id tc/*current-principal*))]
    (vs/create-branch! (request/require-storage ctx) branch-name
                       (cond-> {}
                         (seq uid) (assoc :owner-id uid :write-policy "owner")))))


(defbase drop-orphan-anon-defs
  "`fn-defs` without the exporter-lifted `_anon-*` defs nothing kept in
   the bundle still reaches (`packages.sync/drop-orphan-anon-defs`) — the
   hygiene step after the import dropped the platform-owned owners, so
   their anons don't flood the target with duplicate anonymous
   identities (they poisoned compiled routers). Pure."
  [fn-defs]
  (pkg-sync/drop-orphan-anon-defs (vec fn-defs)))


(defbase adopt-bundle-identities!
  "On branch `branch-id`: repoint + purge each editor-born RANDOM-id row
   whose `(namespace, name)` a def of `fn-defs` is about to sync under its
   deterministic id — so the sync re-creates it canonically instead of
   landing a same-name twin (`packages.sync/adopt-bundle-identities!`, the
   pull-after-push dedup). Returns the adopted names."
  [branch-id fn-defs]
  (cr/record-effect! :db)
  (pkg-sync/adopt-bundle-identities! (branch-storage ctx branch-id) (vec fn-defs)))


(defbase prune-bundle-scope!
  "Snapshot semantics on branch `branch-id`: tombstone the deterministic-id
   fns of the bundle's namespaces that `fn-defs` no longer contains, keep
   (and report) the ones still referenced
   (`packages.sync/reconcile-bundle-scope!`), and invalidate what went
   away on THAT branch's registry. Returns `{:pruned :kept-referenced}`."
  [branch-id fn-defs]
  (cr/record-effect! :db)
  (let [{:keys [pruned-ids] :as result}
        (pkg-sync/reconcile-bundle-scope! (branch-storage ctx branch-id) (vec fn-defs))
        target-ctx (when-let [router (br/current-router)] (br/ctx-for router branch-id))]
    ;; The tombstoned fns had no inbound refs (a referenced one is kept),
    ;; so the delta is exactly those fns; `#{}` when nothing went away.
    (exec-ctx/invalidate-graph-cache! (or target-ctx ctx) pruned-ids)
    (dissoc result :pruned-ids)))


;; ---------------------------------------------------------------------------
;; Package pins — per-branch desired-state "this branch uses package P at V".
;; The pin drives update/rollback (repoint the row) and the editor's installed
;; list. Reference-install writes a pin instead of copying rows.
;; ---------------------------------------------------------------------------

;; Single-row pin upsert (current-branch, pkg-name) → version — a
;; check-then-write pair on one desired-state row (`upsert-pin!`), which
;; the graph install and update flows bind. Returns the branch-id as
;; text; the `{:ok …}` envelope is graph composition
;; (`:set-package-pin` in fns.edn).
(defbase package-upsert-pin
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (str (upsert-pin! ctx (request/require-storage ctx) pkg-name pkg-version)))


;; `:list-installed-packages` / `:remove-package-pin` are pure graph
;; compositions in fns.edn over `:query-entities` / `:delete-entity` +
;; `:current-branch-id`.


(defbase parse-graph-edn
  "Read one EDN value from `string` with the graph WIRE readers, so a
   bundle that the CLI / export re-encoded through `wire/encode-unreadable-kws`
   (emitting `#graphden/ref` tagged literals for version-qualified `@` and
   root-ns refs that aren't spellable as readable keywords) round-trips.
   The generic `:parse-edn` uses default readers and throws → nil on such a
   tag, silently breaking the import of any graph with unspellable refs.
   nil when it doesn't parse."
  [string]
  (try (edn/read-string {:readers wire/wire-readers} string)
       (catch Exception _ nil)))


(def impls
  {:graph-fn-defs graph-fn-defs
   ;; taint-propagate: returns the parsed caller bundle (content passthrough),
   ;; same as core :parse-edn.
   :parse-graph-edn {:impl parse-graph-edn :taint-propagate? true}
   :secret-path-args secret-path-args-fn
   :strip-secret-paths strip-secret-paths-fn
   :encode-unreadable-kws encode-unreadable-kws-fn
   :namespace-external-deps namespace-external-deps
   :current-org-id current-org-id
   :tenancy-active? tenancy-active?
   :graph-rows graph-rows
   :publish-package-apply publish-package-apply
   :breaking-changes-between breaking-changes-between
   :incompatible-dependency-bumps incompatible-dependency-bumps
   :semver-compatible? semver-compatible?
   :withdraw-package-apply withdraw-package-apply
   ;; taint-propagate: answers one of the caller's own version strings.
   :semver-pick {:impl semver-pick :taint-propagate? true}
   :missing-package-dependencies missing-package-dependencies
   :package-version-materialized? package-version-materialized?
   :version-qualified-ns version-qualified-ns-fn
   :fork-package-fns fork-package-fns
   :materialize-package-fns materialize-package-fns
   :rewrite-refs-to-version rewrite-refs-to-version
   :package-upsert-pin package-upsert-pin
   ;; taint-propagate: echoes the caller's pkg-name / version.
   :mirror-store-package-version! {:impl mirror-store-package-version! :taint-propagate? true}
   :remote-auth-value remote-auth-value
   ;; taint-propagate: a filtered sub-vector of the caller's own bundle.
   :drop-orphan-anon-defs {:impl drop-orphan-anon-defs :taint-propagate? true}
   :branch-by-name branch-by-name
   :create-owned-branch! create-owned-branch!
   ;; taint-propagate: the adopted names are the caller bundle's own :name
   ;; fields — content passthrough (SECRETS.md § T3), like the pruned names.
   :adopt-bundle-identities! {:impl adopt-bundle-identities! :taint-propagate? true}
   :prune-bundle-scope! {:impl prune-bundle-scope! :taint-propagate? true}})
