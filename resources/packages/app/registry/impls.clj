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
    [graphden.packages.semver :as semver]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defbase export-namespace
  [root]
  (cr/record-effect! :db)
  (export/export-namespace (request/require-storage ctx) root))


;; Atomic publish: reject if `(pkg-name, pkg-version)` already exists,
;; else hash + insert. Invariant-bearing (immutability) algorithm — a
;; legitimate base-fn (packages-quality §3) and race-safer than spreading
;; check-then-insert across graph nodes. The app-level existence check is
;; the Phase-1 guard; a DB-level UNIQUE(name, version) is the hardening
;; follow-up noted in the schema. Returns a tagged `{:ok …}` result so the
;; route serialises success and conflict the same way (no throw/`:try`).
(defbase publish-package
  [pkg-name pkg-version bundle]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [storage (request/require-storage ctx)
        existing (sp/query-entities storage :package-version
                                    {:name pkg-name :version pkg-version})]
    (if (seq existing)
      {:ok false :reason "version-exists" :name pkg-name :version pkg-version}
      (let [fns (:fns bundle)
            content-hash (ids/digest-hex "SHA-256" (json/generate-string fns))
            row (sp/create-entity storage :package-version
                                  {:name pkg-name
                                   :version pkg-version
                                   :ns-root (:namespace bundle)
                                   :fns fns
                                   :dependencies (:dependencies bundle)
                                   :content-hash content-hash
                                   :published-at (java.time.Instant/now)})]
        {:ok true
         :id (str (:id row))
         :name pkg-name
         :version pkg-version
         :content-hash content-hash
         :fn-count (count fns)
         :dependencies (:dependencies bundle)}))))


;; Registry index — all published versions, metadata only (omits the
;; heavy `:fns` bundle). `sp/query-entities` decodes the jsonb columns,
;; so the result is plain Clojure data; `:id` / `:published-at` are
;; stringified for clean JSON.
(defbase list-package-versions
  []
  (cr/record-effect! :db)
  (->> (sp/query-entities (request/require-storage ctx) :package-version {})
       (mapv (fn [r]
               {:id (str (:id r))
                :name (:name r)
                :version (:version r)
                :ns-root (:ns-root r)
                :content-hash (:content-hash r)
                :published-at (str (:published-at r))
                :fn-count (count (:fns r))
                :dependencies (:dependencies r)}))))


;; The full published bundle for one (name, version), or nil if absent.
(defbase fetch-package-version
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (when-let [r (first (sp/query-entities (request/require-storage ctx) :package-version
                                         {:name pkg-name :version pkg-version}))]
    (assoc r :id (str (:id r)) :published-at (str (:published-at r)))))


;; ---------------------------------------------------------------------------
;; Shared Clojure helpers for install / fork / materialize / pin. These are
;; private `defn-`, NOT base-fns: a base-fn must never call another base-fn
;; (that would hide a graph edge — philosophy), but sharing implementation
;; detail across a few base-fns is ordinary Clojure reuse.
;; ---------------------------------------------------------------------------

;; The declared dependency names (from a :package-version row's :dependencies)
;; NOT present as fns in `storage` — the shared install/fork/materialize
;; precondition. Returns a vector of keyword names (empty = all satisfied).
;; One storage read + a set diff; no graph composition.
(defn- missing-dependencies
  [storage dependencies]
  (let [dep-names (mapv name dependencies)
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
  [ctx storage ns-root version fns]
  (let [materialized (mapv (fn [fd]
                             (update fd :namespace #(version-qualified-ns ns-root version %)))
                           fns)
        ns-id-map (loader/sync-namespaces! storage (into #{} (keep :namespace) materialized))]
    (composition/sync-fns-to-storage! storage materialized ns-id-map)
    (exec-ctx/invalidate-graph-cache! ctx)
    (count materialized)))


;; True if the version's first fn already exists under its version-qualified
;; namespace — idempotency guard + cloud public-org skip: OrgScoped read
;; returns own+public, so a tenant sees a platform-materialized version and
;; does NOT re-materialize it into its own org.
(defn- already-materialized?
  [storage ns-root version fns]
  (boolean
    (when-let [f (first fns)]
      (sp/read-entity storage :fn
                      (ids/fn-id (version-qualified-ns ns-root version (:namespace f))
                                 (:name f))))))


;; Upsert the single pin for `(current-branch, pkg-name)` → version. One pin
;; per (branch, package). Returns the branch-id. Branch comes from the
;; request-scoped VersionedStorage, so it records on the request's branch
;; (staging). Shared by :set-package-pin and reference :install-package.
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


;; Pick the highest published version of `pkg-name` satisfying `spec` — a
;; semver constraint (exact `"1.2.0"`, `">=1.1"`, `"~>1.2"`, …); nil / "" /
;; "latest" mean "any" (the newest). Returns the concrete version string, or
;; nil when nothing matches. Lets install / fork / materialize accept a
;; constraint or "latest", not just an exact pin. Versions sort by parsed
;; `[major minor patch]`.
(defn- resolve-version
  [storage pkg-name spec]
  (let [constraint (if (contains? #{nil "" "latest"} spec) "*" spec)
        matching (into []
                       (comp (map :version)
                             (filter #(semver/satisfies-constraint? % constraint)))
                       (sp/query-entities storage :package-version {:name pkg-name}))]
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
;; Returns the count rewritten.
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
                    (and ref (contains? remap ref) (not (contains? old-fids owner))))]
    (+ (reduce (fn [n b]
                 (if (user-ref? (:ref-fn-id b) (:fn-id b))
                   (do (sp/update-entity storage :binding (:id b)
                                         {:ref-fn-id (remap (:ref-fn-id b))})
                       (inc n))
                   n))
               0 bindings)
       (reduce (fn [n it]
                 (if (user-ref? (:ref-fn-id it) (binding-owner (:binding-id it)))
                   (do (sp/update-entity storage :binding-list-item (:id it)
                                         {:ref-fn-id (remap (:ref-fn-id it))})
                       (inc n))
                   n))
               0 items))))


;; ---------------------------------------------------------------------------
;; Install (reference), fork (copy-on-write), materialize.
;; ---------------------------------------------------------------------------

;; Fork a published version: sync its fns into the graph AT THEIR ORIGINAL
;; namespace, DUPLICATING the rows into the caller's project so they can be
;; modified (a deliberate fork — PACKAGE_DISTRIBUTION §4.5). This is the
;; copy-on-write path; reference :install (below) does NOT copy. Rejects on
;; absent deps / unknown version. Branch-staged via the request storage.
(defbase fork-package
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        resolved (resolve-version storage pkg-name pkg-version)
        row (when resolved
              (first (sp/query-entities storage :package-version
                                        {:name pkg-name :version resolved})))]
    (if (nil? row)
      {:ok false :reason "not-found" :name pkg-name :requested pkg-version}
      (let [fns (:fns row)
            missing (missing-dependencies storage (:dependencies row))]
        (if (seq missing)
          {:ok false :reason "missing-dependencies" :missing missing}
          (let [ns-id-map (loader/sync-namespaces! storage (into #{} (keep :namespace) fns))]
            (composition/sync-fns-to-storage! storage fns ns-id-map)
            (exec-ctx/invalidate-graph-cache! ctx)
            {:ok true :name pkg-name :version resolved :forked (count fns)}))))))


;; Materialize a published version's fns ONCE under a version-qualified
;; namespace so multiple versions coexist and callers REFERENCE them rather
;; than copy rows (PACKAGE_DISTRIBUTION §4.2). Org-neutral: syncs into ctx's
;; current scope (self-hosted → the one graph; cloud → public-org when a
;; platform admin runs it in public scope). Rejects on absent deps.
(defbase materialize-package-version
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        resolved (resolve-version storage pkg-name pkg-version)
        row (when resolved
              (first (sp/query-entities storage :package-version
                                        {:name pkg-name :version resolved})))]
    (if (nil? row)
      {:ok false :reason "not-found" :name pkg-name :requested pkg-version}
      (let [ns-root (:ns-root row)
            missing (missing-dependencies storage (:dependencies row))]
        (if (seq missing)
          {:ok false :reason "missing-dependencies" :missing missing}
          {:ok true
           :name pkg-name
           :version resolved
           :namespace (version-qualified-ns ns-root resolved ns-root)
           :materialized (materialize-fns! ctx storage ns-root resolved (:fns row))})))))


;; Install a published version by REFERENCE: ensure it is materialized under
;; `<ns-root>@<version>` (idempotent; skipped when already visible — incl. a
;; platform-materialized public-org version), then pin (current-branch,
;; package) → version. Does NOT copy rows into the caller's project — the pin
;; plus the visible materialized rows ARE the install; the caller references
;; them. Update = install a newer version (repins). Rejects on absent deps /
;; unknown version. Workspace-visibility grant is a tenancy-layer concern,
;; added by the org-admin package when the addon is present.
(defbase install-package
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [storage (request/require-storage ctx)
        resolved (resolve-version storage pkg-name pkg-version)
        row (when resolved
              (first (sp/query-entities storage :package-version
                                        {:name pkg-name :version resolved})))]
    (if (nil? row)
      {:ok false :reason "not-found" :name pkg-name :requested pkg-version}
      (let [ns-root (:ns-root row)
            fns (:fns row)
            missing (missing-dependencies storage (:dependencies row))]
        (if (seq missing)
          {:ok false :reason "missing-dependencies" :missing missing}
          (do
            (when-not (already-materialized? storage ns-root resolved fns)
              (materialize-fns! ctx storage ns-root resolved fns))
            (upsert-pin! storage pkg-name resolved)
            {:ok true
             :name pkg-name
             :version resolved
             :namespace (version-qualified-ns ns-root resolved ns-root)}))))))


;; Update (or roll back) an installed package to a different version:
;; materialize the target version (if needed), REWRITE the project's own refs
;; from the currently-pinned version to the target (variant B — package-
;; internal refs untouched), then repoint the pin. Symmetric — passing an
;; OLDER version is a rollback. Rejects if the package isn't installed, the
;; target version is unknown, or its deps are absent. Same-version is a no-op.
(defbase update-package-version
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [storage (request/require-storage ctx)
        branch-id (vs/current-branch-id storage)
        pin (first (sp/query-entities storage :package-install
                                      {:branch-id branch-id :package-name pkg-name}))]
    (if (nil? pin)
      {:ok false :reason "not-installed" :name pkg-name}
      (let [old-version (:version pin)
            resolved (resolve-version storage pkg-name pkg-version)
            row (when resolved
                  (first (sp/query-entities storage :package-version
                                            {:name pkg-name :version resolved})))]
        (cond
          (nil? row)
          {:ok false :reason "not-found" :name pkg-name :requested pkg-version}

          (= resolved old-version)
          {:ok true :name pkg-name :from old-version :to resolved :rewritten-refs 0}

          :else
          (let [ns-root (:ns-root row)
                fns (:fns row)
                missing (missing-dependencies storage (:dependencies row))]
            (if (seq missing)
              {:ok false :reason "missing-dependencies" :missing missing}
              (do
                (when-not (already-materialized? storage ns-root resolved fns)
                  (materialize-fns! ctx storage ns-root resolved fns))
                (let [rewritten (rewrite-refs-to-version! storage ns-root old-version resolved fns)]
                  (upsert-pin! storage pkg-name resolved)
                  (exec-ctx/invalidate-graph-cache! ctx)
                  {:ok true :name pkg-name :from old-version :to resolved
                   :rewritten-refs rewritten})))))))))


;; ---------------------------------------------------------------------------
;; Package pins — per-branch desired-state "this branch uses package P at V".
;; The pin drives update/rollback (repoint the row) and the editor's installed
;; list. Reference-install writes a pin instead of copying rows.
;; ---------------------------------------------------------------------------

;; Directly set a pin (current-branch, pkg-name) → version, without touching
;; materialization — the manual counterpart to :install-package.
(defbase set-package-pin
  [pkg-name pkg-version]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [branch-id (upsert-pin! (request/require-storage ctx) pkg-name pkg-version)]
    {:ok true
     :package-name pkg-name
     :version pkg-version
     :branch-id (str branch-id)}))


;; The current branch's pins — what's installed here. `sp/query-entities`
;; decodes the row; ids/timestamps stringified for clean JSON.
(defbase list-installed-packages
  []
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        branch-id (vs/current-branch-id storage)]
    (->> (sp/query-entities storage :package-install {:branch-id branch-id})
         (mapv (fn [r]
                 {:package-name (:package-name r)
                  :version (:version r)
                  :branch-id (str (:branch-id r))
                  :installed-at (str (:installed-at r))})))))


;; Drop the pin for `(current-branch, pkg-name)` — uninstall. Returns a tagged
;; result; `:removed false` when nothing was pinned (idempotent).
(defbase remove-package-pin
  [pkg-name]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        branch-id (vs/current-branch-id storage)
        existing (first (sp/query-entities storage :package-install
                                           {:branch-id branch-id :package-name pkg-name}))]
    (when existing
      (sp/delete-entity storage :package-install (:id existing)))
    {:ok true :package-name pkg-name :removed (some? existing)}))


(def impls
  {:export-namespace export-namespace
   :publish-package publish-package
   :list-package-versions list-package-versions
   :fetch-package-version fetch-package-version
   :fork-package fork-package
   :materialize-package-version materialize-package-version
   :install-package install-package
   :update-package-version update-package-version
   :set-package-pin set-package-pin
   :list-installed-packages list-installed-packages
   :remove-package-pin remove-package-pin})
