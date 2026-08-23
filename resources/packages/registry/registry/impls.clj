(ns graphden.packages.app.registry.impls
  "Implementations for app/registry base functions. Thin primitive over
   `graphden.packages.export` — the multi-step publish/extract flow is
   graph composition (fn-defs) over this + the CRUD base-fns."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [graphden.clients.egress :as egress]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.export :as export]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.wire :as wire]
    [graphden.packages.semver :as semver]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.storage.core :as vs]
    [org.httpkit.client :as http-client]))


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
        forked-ids (pkg-sync/sync-bundle! storage fns)]
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
;; Remote-registry mirror — the client half of cross-install package pull
;; (PACKAGE_DISTRIBUTION § 13). ONE remote package per call: the install
;; worklist drives the dependency closure through its normal `:resolve`
;; ops, each missing dep mirroring on its own retry — so the recursion
;; stays graph-visible, and this base-fn stays a transport+store boundary
;; (fetch → decode → idempotent insert; same atomic check-then-insert
;; class as `publish-package-apply`).
;; ---------------------------------------------------------------------------

(defn- remote-registry-token
  []
  (System/getenv "GRAPHDEN_REGISTRY_TOKEN"))


(defn- remote-auth-headers
  []
  (let [token (remote-registry-token)]
    (cond-> {} (seq (str token)) (assoc "Authorization" (str "Bearer " token)))))


(defn- resolve-remote-version
  "Resolve `spec` (nil/\"latest\"/\"*\" or a semver constraint) to a CONCRETE
   published version of `pkg-name` on the remote registry `base`, by fetching
   its version list and picking the highest that satisfies the constraint.
   Returns the concrete version string, or nil when nothing matches / the
   list is unreachable. Concrete specs pass straight through without a list
   fetch."
  [base pkg-name spec]
  (if (and spec (not (contains? #{"" "latest" "*"} (str spec)))
           (not (re-find #"[*><~^ ]" (str spec))))
    spec
    (let [constraint (if (contains? #{nil "" "latest"} (some-> spec str)) "*" (str spec))
          list-url (str base "/api/packages")
          ;; SSRF guard: `base` is caller-supplied (POST /api/packages/install
          ;; body). In a RESTRICTED (tenant/cloud) execution — `*allowed-effects*`
          ;; bound — block internal / rebinding targets BEFORE dialing, so a
          ;; tenant can't probe cloud-internal services or exfiltrate the
          ;; registry bearer (throws :egress/blocked). The unrestricted
          ;; platform / self-host ctx skips it, so an offline localhost hub
          ;; still resolves — mirrors `web/http-client` http-request.
          _ (when (some? cr/*allowed-effects*) (egress/check-target! list-url))
          resp @(http-client/get list-url
                                 {:headers (remote-auth-headers) :as :text :timeout 60000})]
      (when (and (nil? (:error resp)) (= 200 (:status resp)))
        (let [rows (try (json/parse-string (:body resp) true) (catch Exception _ nil))
              versions (into []
                             (comp (filter #(= (str pkg-name) (str (:name %))))
                                   (map :version)
                                   (filter #(semver/satisfies-constraint? % constraint)))
                             (if (map? rows) (:packages rows (:versions rows)) rows))]
          (last (sort-by semver/parse-version versions)))))))


(defbase mirror-remote-package!
  "Fetch `(pkg-name, spec)` from the REMOTE registry `source` and store it
   as a local `:package-version` row. Idempotent: an existing
   `(name, version)` row wins. Transport is the fetch route's EDN face
   (`?format=edn` — the JSON face stringifies fn-def keywords). `spec` may
   be a CONCRETE version OR a constraint (`nil` / `\"latest\"` / a semver
   range): a non-concrete spec is resolved against the remote's version
   list first (`resolve-remote-version`). The bearer comes from
   `GRAPHDEN_REGISTRY_TOKEN` (the caller's account token on the remote —
   a public package needs any valid account there). Errors ride as data
   (`{:error …}`) so the install worklist can wrap them."
  [source pkg-name spec]
  (cr/record-effect! :network)
  (cr/record-effect! :db)
  (cr/record-effect! :env)
  (let [base (str/replace (str source) #"/+$" "")
        concrete (resolve-remote-version base pkg-name spec)]
    (if (nil? concrete)
      {:error "remote-version-not-found" :name pkg-name}
      (let [url (str base "/api/packages/" pkg-name "/" concrete "?format=edn")
            ;; SSRF guard on the concrete-spec path too (resolve-remote-version
            ;; passes concrete specs straight through without a list fetch, so
            ;; this is the only check for a pinned `?format=edn` fetch). Only in
            ;; a RESTRICTED execution (see resolve-remote-version) so a self-host
            ;; localhost hub still works.
            _ (when (some? cr/*allowed-effects*) (egress/check-target! url))
            resp @(http-client/get url {:headers (remote-auth-headers)
                                        :as :text :timeout 60000})]
        (cond
          (:error resp)
          {:error "remote-unreachable" :source base :detail (str (:error resp))}

          (not= 200 (:status resp))
          {:error "remote-fetch-failed" :source base :status (:status resp)}

          :else
          (let [row (try (edn/read-string {:readers wire/wire-readers} (:body resp))
                         (catch Exception _ ::unreadable))]
            (cond
              (= ::unreadable row) {:error "remote-bundle-unreadable" :source base}
              (nil? row) {:error "remote-not-found" :name pkg-name :version concrete}
              :else
              (let [storage (request/require-storage ctx)]
                (when-not (seq (sp/query-entities storage :package-version
                                                  {:name pkg-name :version concrete}))
                  (sp/create-entity storage :package-version
                                    (-> row
                                        (select-keys [:name :version :ns-root :fns
                                                      :dependencies :package-dependencies
                                                      :secrets :content-hash])
                                        (assoc :org-id (tc/current-org)
                                               ;; a mirrored copy is LOCAL — never
                                               ;; re-published as public here
                                               :public? false
                                               :published-at (java.time.Instant/now)))))
                {:mirrored (str pkg-name) :version (str concrete) :from base}))))))))


;; ---------------------------------------------------------------------------
;; Bundle import — POST /api/import/graph. The §3.3 atomic write core:
;; branch resolve/create, the branch-switched sync, the optional prune and
;; the TARGET branch's invalidation are one effect-ordered sequence (same
;; carve-out as the MCP `sync-fn-defs-branch!` and fork/materialize cores);
;; the HTTP guards + envelopes around it are graph composition in fns.edn.
;; ---------------------------------------------------------------------------

(defbase import-bundle!
  "Apply an exported bundle's `fn-defs` to the branch named `branch-name` —
   never the request's own branch, never main implicitly.

   Steps: resolve the branch by name (create it off the request's branch
   when `create?`, stamping the caller as owner with the `owner`
   write-policy — the push-branch convention); split out defs whose
   deterministic id is PACKAGE-OWNED (skipped + reported — the boot sync
   would restore them anyway, and silently repointing platform fns is the
   2026-08-20 incident class); sync the rest through the SAME
   `sync-bundle!` path the package loader uses (name collisions, cycles,
   type-check all apply — a rejection surfaces as an error the caller can
   act on); optionally prune (`reconcile-bundle-scope!` — snapshot
   semantics, branch tombstones only); delta-invalidate THAT branch's
   compiled registry.

   Returns `{:fn-ids [...] :skipped-owned [...] :adopted [...] :pruned {...}}`, or
   `{:error \"branch-not-found\"}` when the branch doesn't resolve and
   `create?` is false — errors ride as data so the graph maps them to
   response envelopes."
  [branch-name create? prune? fn-defs]
  (cr/record-effect! :db)
  (let [request-storage (request/require-storage ctx)
        find-branch #(first (sp/query-entities (:base-storage request-storage)
                                               :branch {:name branch-name}))
        branch (or (find-branch)
                   (when create?
                     (let [principal tc/*current-principal*]
                       (vs/create-branch! request-storage branch-name
                                          (cond-> {}
                                            (seq (str (:user-id principal)))
                                            (assoc :owner-id (str (:user-id principal))
                                                   :write-policy "owner"))))))]
    (if-not branch
      {:error "branch-not-found"}
      (let [storage (vs/switch-branch request-storage (:id branch))
            {owned-defs true wanted-raw false}
            (group-by #(owned/owned-fn-id? (ids/fn-id (:namespace %) (:name %)))
                      (vec fn-defs))
            ;; Dropping the owned defs orphans their exporter-lifted
            ;; `_anon-*` entries — syncing those floods the branch with
            ;; duplicate anon identities (they poisoned compiled routers).
            wanted (pkg-sync/drop-orphan-anon-defs (vec wanted-raw))
            ;; Canonicalise BEFORE the sync: an editor-born fn has a random
            ;; id here while the bundle's sync mints uuid-v5(ns,name) — see
            ;; adopt-bundle-identities!. Without it the first pull after a
            ;; push lands a duplicate name next to the original.
            adopted (pkg-sync/adopt-bundle-identities! storage (vec wanted))
            fn-ids (when (seq wanted) (pkg-sync/sync-bundle! storage (vec wanted)))
            pruned (when prune? (pkg-sync/reconcile-bundle-scope! storage (vec wanted)))]
        (exec-ctx/invalidate-graph-cache!
          (if-let [router (br/current-router)] (br/ctx-for router (:id branch)) ctx)
          fn-ids)
        (cond-> {:fn-ids (mapv str fn-ids)
                 :skipped-owned (mapv #(some-> (:name %) name) owned-defs)
                 :adopted adopted}
          pruned (assoc :pruned pruned))))))


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
   :resolve-package-version resolve-package-version
   :missing-package-dependencies missing-package-dependencies
   :package-version-materialized? package-version-materialized?
   :version-qualified-ns version-qualified-ns-fn
   :fork-package-fns fork-package-fns
   :materialize-package-fns materialize-package-fns
   :rewrite-refs-to-version rewrite-refs-to-version
   :package-upsert-pin package-upsert-pin
   :mirror-remote-package! mirror-remote-package!
   ;; taint-propagate: :skipped-owned returns the caller bundle's own
   ;; :name fields — content passthrough (SECRETS.md § T3).
   :import-bundle! {:impl import-bundle! :taint-propagate? true}})
