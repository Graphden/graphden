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

   Marketplace listing fields (2026-09, docs/MARKETPLACE.md): one artifact
   entity serves every marketplace KIND — `:kind` (nil / `fns` = a fn-def
   package, `theme` = an editor theme, `keymap` = a keyboard layout);
   the listing metadata `:description` / `:category` / `:tags`; and
   `:payload` (the theme's token map / the keymap's binding overrides —
   nil for fns packages, whose content is `:fns`). Reusing the artifact
   row rather than minting a `:theme` / `:keymap` entity keeps ONE
   publish / visibility / withdraw / version-resolution path
   (principle #2). Three small companions carry what the artifact can't:
   - `:package-review` — one (rating, body) per (package-name, author);
     org-scoped with `:public? true` so a review is visible wherever the
     package is (tenancy RLS mirrors `:package-version`).
   - `:package-stat` — a GLOBAL (deliberately un-scoped) cumulative
     install counter per package-name: an installer in org B cannot
     write org A's artifact row, and per-org pins are invisible across
     orgs, so the count lives on its own row every tenant may bump.
   - `:ui-pref` — the CURRENT user's editor preferences (active theme /
     keymap), one row per (owner-id, key); owner is stamped + filtered
     by the `:ui-pref-*` base-fns (app/prefs), never by the caller.

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


;; --- Marketplace listing fields (nullable — pre-marketplace rows have
;; none; a nil `:kind` reads as "fns"). See ns-doc § Marketplace. ---
(def ^:private pv-kind-field-uuid
  #uuid "3c1e7a52-8d4b-4f6e-9a21-6b0d5e8f1c73")


(def ^:private pv-description-field-uuid
  #uuid "5a9d2e17-1c3f-4b8a-8e64-2d7f9b0c4a15")


(def ^:private pv-category-field-uuid
  #uuid "7e4b8c30-2a6d-4e1f-b9c5-8f3a1d6e2b47")


(def ^:private pv-tags-field-uuid
  #uuid "9b2f6d48-4e7a-4c3b-a1d8-0c5e7f9a3b61")


(def ^:private pv-payload-field-uuid
  #uuid "1d7c3e59-6f8b-4a2d-b3e9-4a6c8d0f5e72")


;; =============================================================================
;; Entity UUID — :package-review (one rating + body per package × author)
;; =============================================================================

(def ^:private package-review-entity-uuid
  #uuid "8f3a5c71-2b9e-4d6f-a0c4-7e1b9d3f5a28")


(def ^:private pr-package-name-field-uuid
  #uuid "2a6e9c14-7d3b-4f8e-9b5a-1c4d7e0f2b36")


(def ^:private pr-rating-field-uuid
  #uuid "4c8b1e27-9f5d-4a3c-8d7e-3b6f9a2c4d58")


(def ^:private pr-body-field-uuid
  #uuid "6e0d3a39-1b7f-4c5e-a9f1-5d8b0c4e6f7a")


(def ^:private pr-author-id-field-uuid
  #uuid "8a2f5c4b-3d9e-4e7a-b1c3-7f0d2e6a8b9c")


(def ^:private pr-author-label-field-uuid
  #uuid "0c4a7e5d-5f1b-4a9c-9d5e-9b2f4c8e0d1a")


(def ^:private pr-org-id-field-uuid
  #uuid "2e6c9a7f-7b3d-4c1e-8f7a-1d4b6e0a2f3c")


(def ^:private pr-public-field-uuid
  #uuid "4a8e1c9b-9d5f-4e3a-a1b9-3f6d8a2c4e5b")


(def ^:private pr-created-at-field-uuid
  #uuid "6c0a3e1d-1f7b-4a5c-b3d1-5b8f0c4e6a7d")


(def ^:private pr-updated-at-field-uuid
  #uuid "8e2c5a3f-3b9d-4c7e-9f3b-7d0a2e6c8b9f")


;; =============================================================================
;; Entity UUID — :package-stat (global cumulative install counter)
;; =============================================================================

(def ^:private package-stat-entity-uuid
  #uuid "a04e7c5b-5d1f-4e9a-8b5d-9f2c4a6e0d1b")


(def ^:private ps-package-name-field-uuid
  #uuid "c26a9e7d-7f3b-4a1c-9d7f-1b4e6c8a2f3d")


(def ^:private ps-installs-field-uuid
  #uuid "e48c1a9f-9b5d-4c3e-a1f9-3d6a8e0c4b5f")


(def ^:private ps-updated-at-field-uuid
  #uuid "06a3c2b1-1d7f-4e5a-b3a1-5f8c0e2a6d7b")


;; =============================================================================
;; Entity UUID — :ui-pref (per-user editor preference)
;; =============================================================================

(def ^:private ui-pref-entity-uuid
  #uuid "28c5e4d3-3f9b-4a7c-9d3c-7b0e2a4c8f9d")


(def ^:private up-owner-id-field-uuid
  #uuid "4ae7a6f5-5b1d-4c9e-a5e5-9d2a4c6e0b1f")


(def ^:private up-key-field-uuid
  #uuid "6c09c8a7-7d3f-4e1a-b7a7-1f4c6e8a2d3b")


(def ^:private up-value-field-uuid
  #uuid "8e2be0c9-9f5b-4a3c-8d9c-3b6e8a0c4f5d")


(def ^:private up-org-id-field-uuid
  #uuid "a04d02eb-1b7d-4c5e-9fbe-5d8a0c2e6b7f")


(def ^:private up-updated-at-field-uuid
  #uuid "c26f24fd-3d9f-4e7a-a1d0-7f0c2e4a8d9b")


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

   - `:package-review` / `:package-stat` / `:ui-pref` — the marketplace
     companions (ns-doc § Marketplace): reviews, the global install
     counter, and per-user editor preferences.

   All are non-versioned (a published snapshot is immutable by contract; a
   pin, a review, a counter and a preference are runtime state, same class
   as `:service`), so the versioned-storage decorator passes writes
   straight through."
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
                                     :nullable? true}
                      ;; marketplace listing (ns-doc § Marketplace)
                      :kind {:uuid pv-kind-field-uuid
                             :type :text
                             :nullable? true}
                      :description {:uuid pv-description-field-uuid
                                    :type :text
                                    :nullable? true}
                      :category {:uuid pv-category-field-uuid
                                 :type :text
                                 :nullable? true}
                      :tags {:uuid pv-tags-field-uuid
                             :type :jsonb
                             :nullable? true}
                      :payload {:uuid pv-payload-field-uuid
                                :type :jsonb
                                :nullable? true}})
      ;; One review per (package-name, author-id) — app-side uniqueness
      ;; (the review upsert reads-then-writes under the caller's org, and a
      ;; review is small + low-stakes, so no advisory lock). `:public?` is
      ;; always written true: a review is as visible as the package it is
      ;; on, and the tenancy RLS arm mirrors `:package-version`'s.
      (ds/add-entity :package-review package-review-entity-uuid
                     {:package-name {:uuid pr-package-name-field-uuid
                                     :type :text
                                     :indexed? true}
                      :rating {:uuid pr-rating-field-uuid
                               :type :int}
                      :body {:uuid pr-body-field-uuid
                             :type :text
                             :nullable? true}
                      :author-id {:uuid pr-author-id-field-uuid
                                  :type :text
                                  :nullable? true}
                      :author-label {:uuid pr-author-label-field-uuid
                                     :type :text
                                     :nullable? true}
                      :org-id {:uuid pr-org-id-field-uuid
                               :type :text
                               :nullable? true}
                      :public? {:uuid pr-public-field-uuid
                                :type :bool
                                :nullable? true}
                      :created-at {:uuid pr-created-at-field-uuid
                                   :type :timestamptz
                                   :nullable? true}
                      :updated-at {:uuid pr-updated-at-field-uuid
                                   :type :timestamptz
                                   :nullable? true}})
      ;; GLOBAL by design (no :org-id): the cumulative install counter every
      ;; tenant may bump. Deliberately absent from the tenancy scoped set —
      ;; see the ns-doc; the addon's classification guard allowlists it.
      (ds/add-entity :package-stat package-stat-entity-uuid
                     {:package-name {:uuid ps-package-name-field-uuid
                                     :type :text
                                     :indexed? true}
                      :installs {:uuid ps-installs-field-uuid
                                 :type :int}
                      :updated-at {:uuid ps-updated-at-field-uuid
                                   :type :timestamptz
                                   :nullable? true}})
      (ds/add-constraint :package-stat {:type :unique :fields [:package-name]})
      ;; One row per (owner-id, key). `:value` is the pref's JSON document
      ;; (a theme selection, a keymap selection, …) — the app/prefs base-fns
      ;; own the owner stamp + filter.
      (ds/add-entity :ui-pref ui-pref-entity-uuid
                     {:owner-id {:uuid up-owner-id-field-uuid
                                 :type :text
                                 :indexed? true}
                      :key {:uuid up-key-field-uuid
                            :type :text}
                      :value {:uuid up-value-field-uuid
                              :type :jsonb
                              :nullable? true}
                      :org-id {:uuid up-org-id-field-uuid
                               :type :text
                               :nullable? true}
                      :updated-at {:uuid up-updated-at-field-uuid
                                   :type :timestamptz
                                   :nullable? true}})
      (ds/add-constraint :ui-pref {:type :unique :fields [:owner-id :key]})
      ;; :branch-id is a bare :uuid, NOT {:type :ref :ref-entity :branch}
      ;; like every other branch pointer — no FK, so an install row
      ;; survives deletion of the branch it was made on (installs are
      ;; per-branch state the user may re-point; a cascade/reject on
      ;; branch delete would be wrong either way). If you add the ref,
      ;; you take on that lifecycle question.
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
