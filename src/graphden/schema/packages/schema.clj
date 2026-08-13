(ns graphden.schema.packages.schema
  "Package-registry schema — the `:package-version` (published snapshot)
   and `:package-install` (per-branch version pin) entities.

   A `:package-version` is an IMMUTABLE, named, versioned snapshot of a
   namespace subtree's `fns.edn` (produced by
   `graphden.packages.export/export-namespace`) plus its declared
   dependencies. It is the registry artifact `POST /api/packages/publish`
   creates and `install` consumes.

   ## Why a new entity (principle #2)

   A published version is a distinct ARTIFACT — immutable, content-
   addressed (`:content-hash`), self-contained — not a node of the live,
   mutable graph. Same justification class as `:service` (a desired-state
   row, not graph semantics).

   ## Why NOT reuse graph branches

   Branches version the WHOLE graph per-entity, mutably, resolved along a
   `base_branch_id` chain — they have no bundle identity, no immutability,
   and `:fn-version` ≠ a behavioural snapshot (binding edits don't
   re-anchor it). The branch synergy (docs/VERSIONING.md) is for the
   INSTALL-STAGING step — install a version onto a branch,
   test, merge — NOT for STORING the registry. The two are orthogonal.

   NOT versioned: a published row is immutable by contract (the publish
   path rejects re-publishing an existing `(name, version)`), so it never
   needs per-branch overlay and stays out of
   `versioning.storage.resolution/entity-config` — same as `:fn-execution`
   / `:service`.

   ## Org-scoped registry (spec §5)

   `:package-version` carries TWO tenancy fields, both justified against
   the fn-metadata rule (a registry row is not a graph fn — its
   visibility cannot be a graph marker):

   - `:org-id` (nullable) — the publisher's org, stamped by the tenancy
     addon's OrgScopedStorage exactly like `:fn`/`:ns`. NULL ≡ the
     shared platform tier (pre-existing rows, single-tenant writes).
     RLS mirrors the own+public read / own-only write policy.
   - `:public?` (nullable bool) — the explicit publish-publicly opt-in.
     A tenant's row stays in ITS org (provenance, own-only writes keep
     holding at the RLS layer), while `:public? true` makes it
     platform-visible. The flag — rather than re-stamping `org-id` to
     public — keeps the RLS write policies own-only AND lets the
     publisher revoke public visibility later (its row stays its own).

   Known limitation (documented, not solved here): package NAMES are not
   org-scoped, so `(name, version)` can exist once per org — an org that
   sees both its own private row and a same-named public row gets
   whichever the query returns first. Per-org name scoping is a future
   design task.

   Future (NOT Phase 1):
   - DB-level `UNIQUE (name, version)` — currently enforced application-
     side in the publish flow.
   - `:yanked?` flag for soft-deprecating a bad version."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Entity UUID
;; =============================================================================

(def ^:private package-version-entity-uuid
  #uuid "d4dc0bfb-aca9-4950-b524-7d9a13224688")


;; =============================================================================
;; Field UUIDs — :package-version
;; =============================================================================

(def ^:private pv-name-field-uuid
  #uuid "24db0dce-bda0-46c2-80ca-ba3de38c8235")


(def ^:private pv-version-field-uuid
  #uuid "6e11cce1-bee7-4cff-a50b-00999e546265")


(def ^:private pv-ns-root-field-uuid
  #uuid "6ddce5b9-2e32-4753-a237-7b1e86441b95")


(def ^:private pv-fns-field-uuid
  #uuid "2f2c0a14-1348-4e9a-bb89-7dd73e7b0dc7")


(def ^:private pv-dependencies-field-uuid
  #uuid "95a28858-0a9a-408c-a244-c6462167901b")


;; Package-level deps: which OTHER published packages this version's fns
;; reference (each `{:name :version}`), so install can pull them recursively.
;; Distinct from `:dependencies` (external fn NAMES). Nullable — pre-existing
;; rows + platform-only packages have none.
(def ^:private pv-package-dependencies-field-uuid
  #uuid "7c9e2a41-3b6d-4f80-9a1c-5e8b0d2f4a63")


;; Secret-args manifest: which `{:fn … :arg …}` slots had their vault
;; paths stripped at publish time (see `packages/export.clj` § Secret-path
;; policy). Surfaced at install as `:needs-definition` so the installer
;; learns what secrets to define. Nullable — pre-existing rows have none.
(def ^:private pv-secrets-field-uuid
  #uuid "6e1f4b7a-9c2d-4d3e-8a5f-1b0c7d9e2f45")


(def ^:private pv-content-hash-field-uuid
  #uuid "c559dbeb-742a-444b-8c26-848ae3c462c8")


;; Publisher's org (nullable — NULL ≡ shared platform tier; see ns-doc
;; § Org-scoped registry). Stamped by the tenancy addon's decorator.
(def ^:private pv-org-id-field-uuid
  #uuid "b1f6c2d8-4a7e-4b53-9e0d-2c8f5a1d7e94")


;; Explicit publish-publicly opt-in (see ns-doc § Org-scoped registry).
(def ^:private pv-public-field-uuid
  #uuid "e7a3d9f1-5c28-4e6b-8d40-9b2f6c4a8e17")


(def ^:private pv-published-at-field-uuid
  #uuid "d9527115-fdff-4333-a027-5c2459d2eda9")


;; =============================================================================
;; Entity UUID — :package-install (per-branch version pin)
;; =============================================================================

(def ^:private package-install-entity-uuid
  #uuid "3ba7efc6-3758-47fc-88db-2461ebd546b0")


(def ^:private pi-branch-id-field-uuid
  #uuid "320de48d-745e-4eb9-a7dd-53c8de5905d0")


(def ^:private pi-package-name-field-uuid
  #uuid "e312c026-1b07-4a37-82ae-8bbe5e837f83")


(def ^:private pi-version-field-uuid
  #uuid "015b9b12-e737-4ccf-999c-3a526f8a2d9f")


(def ^:private pi-org-id-field-uuid
  #uuid "a8a280ac-a37b-4421-b01f-e7c6cc7302f1")


(def ^:private pi-installed-at-field-uuid
  #uuid "16d256b7-d711-4888-ac48-38dfc8739dd4")


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extend a schema builder with the package-registry entities. Chain
   after `services.schema/extend-builder`.

   - `:package-version` — immutable published snapshot (content-addressed).
     Org-scoped (`:org-id` + `:public?`, see ns-doc § Org-scoped
     registry): a tenant's publish is private to its org unless the
     explicit public opt-in is set; NULL-org rows are the shared
     platform registry.
   - `:package-install` — a per-branch version PIN (desired-state: \"branch B
     uses package P at version V\"). Carries `:org-id` because pins ARE
     tenant-owned — each org installs/updates packages in its own project.
     One pin per `(branch-id, package-name)`, enforced app-side (mirrors the
     app-side uniqueness of `:package-version`).

   Both are non-versioned (a published snapshot is immutable by contract; a
   pin is runtime desired-state, same class as `:service`), so the
   versioned-storage decorator passes writes straight through."
  [builder]
  (-> builder
      (ds/add-entity :package-version package-version-entity-uuid
                     {:name {:uuid pv-name-field-uuid
                             :type :text}
                      :version {:uuid pv-version-field-uuid
                                :type :text}
                      :ns-root {:uuid pv-ns-root-field-uuid
                                :type :text}
                      :fns {:uuid pv-fns-field-uuid
                            :type :jsonb}
                      :dependencies {:uuid pv-dependencies-field-uuid
                                     :type :jsonb}
                      :package-dependencies {:uuid pv-package-dependencies-field-uuid
                                             :type :jsonb
                                             :nullable? true}
                      :secrets {:uuid pv-secrets-field-uuid
                                :type :jsonb
                                :nullable? true}
                      :content-hash {:uuid pv-content-hash-field-uuid
                                     :type :text}
                      :org-id {:uuid pv-org-id-field-uuid
                               :type :text
                               :nullable? true}
                      :public? {:uuid pv-public-field-uuid
                                :type :bool
                                :nullable? true}
                      :published-at {:uuid pv-published-at-field-uuid
                                     :type :timestamptz
                                     :nullable? true}})
      (ds/add-entity :package-install package-install-entity-uuid
                     {:branch-id {:uuid pi-branch-id-field-uuid
                                  :type :uuid}
                      :package-name {:uuid pi-package-name-field-uuid
                                     :type :text}
                      :version {:uuid pi-version-field-uuid
                                :type :text}
                      :org-id {:uuid pi-org-id-field-uuid
                               :type :text
                               :nullable? true}
                      :installed-at {:uuid pi-installed-at-field-uuid
                                     :type :timestamptz
                                     :nullable? true}})))
