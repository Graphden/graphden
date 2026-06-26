(ns graphden.schema.packages.schema
  "Package-registry schema — the `:package-version` entity.

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
   re-anchor it). The branch synergy (docs/VERSIONING.md, PLATFORM_PLAN
   §2.4) is for the INSTALL-STAGING step — install a version onto a branch,
   test, merge — NOT for STORING the registry. The two are orthogonal.

   NOT versioned: a published row is immutable by contract (the publish
   path rejects re-publishing an existing `(name, version)`), so it never
   needs per-branch overlay and stays out of
   `versioning.storage.resolution/entity-config` — same as `:fn-execution`
   / `:service`.

   Future (NOT Phase 1):
   - DB-level `UNIQUE (name, version)` — currently enforced application-
     side in the publish flow.
   - org-scoping for private registries (Phase 2).
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


(def ^:private pv-content-hash-field-uuid
  #uuid "c559dbeb-742a-444b-8c26-848ae3c462c8")


(def ^:private pv-published-at-field-uuid
  #uuid "d9527115-fdff-4333-a027-5c2459d2eda9")


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extend a schema builder with the `:package-version` entity. Chain
   after `services.schema/extend-builder`. Non-versioned (immutable
   snapshot) so the versioned-storage decorator passes writes straight
   through, same as `:service` / `:fn-execution`."
  [builder]
  (ds/add-entity builder :package-version package-version-entity-uuid
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
                  :content-hash {:uuid pv-content-hash-field-uuid
                                 :type :text}
                  :published-at {:uuid pv-published-at-field-uuid
                                 :type :timestamptz
                                 :nullable? true}}))
