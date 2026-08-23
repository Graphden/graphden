(ns graphden.schema.versioned.schema
  "Versioned schema — extends graph-schema with branch / version
   entities for Git-like versioning of mutable entities.

   Versioned (mutable):
   - fn               → fn-version
   - fn-slot          → fn-slot-version
   - binding          → binding-version
   - binding-list-item → binding-list-item-version

   Not versioned (immutable post-create):
   - slot — `(name, type-fn-id)` pair never changes; create new slot
            instead of mutating.
   - ns   — namespace structure rarely changes; not versioned (matches
            existing behavior).

   The old `arg-version` entity is **removed** — `arg` itself is gone
   from graph schema (replaced by slot/fn-slot/binding/binding-list-item)."
  (:require
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Entity UUIDs — branches + version entities
;; =============================================================================

(def ^:private branch-entity-uuid
  #uuid "a1b2c3d4-e5f6-4789-8c9d-0e1f2a3b4c5d")


(def ^:private branch-merge-entity-uuid
  #uuid "b2c3d4e5-f6a7-4890-9d0e-1f2a3b4c5d6e")


(def ^:private branch-approval-entity-uuid
  ;; Change proposals (review policy) — one row per approval a reviewer
  ;; grants a proposal branch. Mirrors :branch-merge (a plain, non-
  ;; version-intercepted record). Counted at merge time against the
  ;; target's `:required-approvals`.
  #uuid "d249d189-4ce0-4445-86e4-52bd3681159b")


(def ^:private fn-version-entity-uuid
  #uuid "c3d4e5f6-a7b8-4901-0e1f-2a3b4c5d6e7f")


(def ^:private fn-slot-version-entity-uuid
  #uuid "0efeec22-0ef9-4f88-b3eb-11ce0205376e")


(def ^:private binding-version-entity-uuid
  #uuid "8adfb5a2-c194-4def-8fe3-2ddc5a06409d")


(def ^:private binding-list-item-version-entity-uuid
  #uuid "58d29309-4843-4156-bd2a-9669c715a6b4")


;; =============================================================================
;; Field UUIDs — :branch
;; =============================================================================

(def ^:private branch-name-field-uuid
  #uuid "01020304-0506-4123-8c9d-0e1f2a3b4c5d")


(def ^:private branch-base-branch-id-field-uuid
  #uuid "02030405-0607-4234-9d0e-1f2a3b4c5d6e")


(def ^:private branch-created-at-field-uuid
  #uuid "03040506-0708-4345-0e1f-2a3b4c5d6e7f")


(def ^:private branch-org-id-field-uuid
  ;; Tenant owner (§4 org-scoped branches). NULL ≡ public (`main`). Stamped by
  ;; OrgScopedStorage; the read-filter isolates a tenant's branches. Names are
  ;; unique PER ORG (`UNIQUE (org-id, name) NULLS NOT DISTINCT`) — distinct orgs
  ;; may reuse a name, with no cross-org collision / existence leak.
  #uuid "04050607-0809-4456-9f0a-3b4c5d6e7f80")


(def ^:private branch-forbid-invalid-field-uuid
  ;; Error-tolerance Phase 5 — branch merge policy. When true, merging
  ;; INTO this branch is refused while either side carries recorded
  ;; type diagnostics (`versioning.merge.core/validate-branch-policy!`).
  ;; Plain nullable boolean on a NON-versioned entity: no version
  ;; mirror, no codec known-values (those are enum-only concerns).
  #uuid "c7251e69-b9eb-469e-b936-0cca96cc874f")


(def ^:private branch-require-merge-field-uuid
  ;; Protected branches (Stage 2, 2026-08-23) — GitHub-style "push only
  ;; via merge request". When true, DIRECT writes to this branch's graph
  ;; (editor CRUD + bundle import) are refused (`:branch/merge-required`,
  ;; 409); the only way a change lands is a MERGE from another branch.
  ;; Unlike `:write-policy` (WHO, tenancy-enforced), this is HOW — a
  ;; structural property enforced in OPEN CORE (no principals needed), so
  ;; a solo self-hoster can protect main too. Plain nullable boolean on a
  ;; NON-versioned entity, mirroring `:forbid-invalid?`.
  #uuid "805548b9-d190-4622-92f8-f68f89254a7f")


(def ^:private branch-review-state-field-uuid
  ;; Change proposals (2026-08-23) — the async review handoff. nil ≡ an
  ;; ordinary working branch; "proposed" ≡ its owner asked for this
  ;; branch to be reviewed and merged into its base. The reviewer list
  ;; is just the branches carrying "proposed". OPEN CORE (no principals
  ;; needed); the WHO-may-approve gate rides the target's `:write-policy`
  ;; + per-branch review policy. Plain nullable text on a NON-versioned
  ;; entity, mirroring `:write-policy`.
  #uuid "bc75cdfe-0fa5-437d-90ea-a471cd5011ed")


(def ^:private branch-required-approvals-field-uuid
  ;; Review policy — how many valid approvals a proposal needs before a
  ;; merge INTO this branch is allowed. nil/0 ≡ off. Enforced open-core
  ;; by the merge gate (`versioning.merge.core/validate-approval-policy!`).
  #uuid "f581b52e-6096-4ad2-b745-c4557f50d042")


(def ^:private branch-allow-self-approval-field-uuid
  ;; Review policy — when NOT true, the proposal author's own approval
  ;; does not count toward `:required-approvals` (GitHub "require review
  ;; from someone other than the author"). nil ≡ off (self-approval NOT
  ;; counted once approvals are required).
  #uuid "c521de6f-595a-4612-9622-bfcc78c5e71e")


(def ^:private branch-approver-ids-field-uuid
  ;; Review policy — an explicit allow-list of reviewer `:user-id`s who
  ;; may approve merges into this branch, ADDITIVE to whoever the
  ;; `:write-policy` role already admits. nil ≡ role-based only. JSONB
  ;; list of strings.
  #uuid "da35ac02-e3a5-41d8-8030-3c5a66db189e")


(def ^:private branch-owner-id-field-uuid
  ;; Protected branches (Stage 1, 2026-08-15) — the creating principal's
  ;; STABLE user id (`:user-id`, not the mutable username), stamped at
  ;; create when a principal is bound. nil in single-tenant / system
  ;; writes. Same nullable-text shape as `:org-id`.
  #uuid "8777efc7-d205-45c1-a001-b5b190ee35d2")


(def ^:private branch-write-policy-field-uuid
  ;; Protected branches — who may WRITE (version-plane rows + merges
  ;; into this branch). nil ≡ open (anyone the ordinary grants admit);
  ;; "owner" ≡ the branch owner (+ the org's :manage-grants holders as
  ;; the unlock escalation); "admins" ≡ :manage-grants holders only.
  ;; Enforced by the tenancy addon's authorize-writer; without the
  ;; addon there are no principals to tell apart and the field is
  ;; inert. Free-form nullable text like `:org-id` — the value set is
  ;; validated at the API boundary (set-branch-policy!).
  #uuid "a242bbb7-ba6c-4275-a4b4-fdd4b8dfb68a")


;; =============================================================================
;; Field UUIDs — :branch-merge
;; =============================================================================

(def ^:private branch-merge-source-branch-id-field-uuid
  #uuid "11121314-1516-4456-8c9d-0e1f2a3b4c5d")


(def ^:private branch-merge-source-timestamp-field-uuid
  #uuid "12131415-1617-4567-9d0e-1f2a3b4c5d6e")


(def ^:private branch-merge-target-branch-id-field-uuid
  #uuid "13141516-1718-4678-0e1f-2a3b4c5d6e7f")


(def ^:private branch-merge-target-timestamp-field-uuid
  #uuid "14151617-1819-4789-1f2a-3b4c5d6e7f8a")


(def ^:private branch-merge-created-at-field-uuid
  #uuid "15161718-1920-4890-2a3b-4c5d6e7f8a9b")


;; =============================================================================
;; Field UUIDs — :branch-approval
;; =============================================================================

(def ^:private branch-approval-source-branch-id-field-uuid
  #uuid "6796332c-aa37-4c42-8370-30cfa565ae10")


(def ^:private branch-approval-approver-id-field-uuid
  #uuid "a5bbd60d-d73f-474f-b404-fe94f6328b53")


(def ^:private branch-approval-content-stamp-field-uuid
  ;; The source branch's content fingerprint (count + max version
  ;; created-at) at approval time. A later edit on the source advances
  ;; the stamp, so the merge gate treats this approval as STALE and
  ;; drops it (GitHub "dismiss stale approvals").
  #uuid "78f50d44-a3a3-40cd-be29-dc7881a38127")


(def ^:private branch-approval-created-at-field-uuid
  #uuid "9de343a8-7a6b-4041-9599-6acfedac1253")


(def ^:private branch-approval-org-id-field-uuid
  ;; Tenant owner — lets the tenancy addon's OrgScopedStorage stamp +
  ;; org-filter approval rows (defence-in-depth: the merge gate + the
  ;; /approvals read are ALREADY safe because they key on the org-scoped
  ;; `:source-branch-id`, but scoping the row makes even a bare query
  ;; org-isolated). NULL ≡ public / single-tenant. Same nullable-text
  ;; shape as `:branch.org-id`.
  #uuid "6a616a15-4783-4e6d-8801-730ccbded6b9")


;; =============================================================================
;; Field UUIDs — :fn-version
;; =============================================================================

(def ^:private fn-version-fn-id-field-uuid
  #uuid "21222324-2526-4901-8c9d-0e1f2a3b4c5d")


(def ^:private fn-version-branch-id-field-uuid
  #uuid "22232425-2627-4012-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-name-field-uuid
  #uuid "23242526-2728-4123-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-impl-hash-field-uuid
  #uuid "26272829-3031-4456-3b4c-5d6e7f8a9b0c")


(def ^:private fn-version-created-at-field-uuid
  #uuid "27282930-3132-4567-4c5d-6e7f8a9b0c1d")


(def ^:private fn-version-deleted-at-field-uuid
  #uuid "28293031-3233-4678-5d6e-7f8a9b0c1d2e")


(def ^:private fn-version-description-field-uuid
  #uuid "3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-constraint-field-uuid
  #uuid "31323334-3536-4789-8c9d-0e1f2a3b4c5d")


(def ^:private fn-version-base-fn-id-field-uuid
  #uuid "32333435-3637-4890-9d0e-1f2a3b4c5d6e")


(def ^:private fn-version-element-fn-id-field-uuid
  #uuid "33343536-3738-4901-0e1f-2a3b4c5d6e7f")


(def ^:private fn-version-return-type-fn-id-field-uuid
  #uuid "34353637-3839-4012-1f2a-3b4c5d6e7f8a")


(def ^:private fn-version-anonymous-hash-field-uuid
  #uuid "35363738-3940-4123-2a3b-4c5d6e7f8a9b")


;; Mirrors fn.expects-effects (sym `fn-expects-effects-field-uuid` in
;; the graph-data-schema). Must exist on the version mirror or the
;; versioned-storage decorator strips writes silently and reads come
;; back without the column.
(def ^:private fn-version-lambda-params-field-uuid
  ;; Mirror of fn.lambda-params — pinned like every mirror column.
  #uuid "e8dcc3db-996a-45d2-a6c6-d788e44b255d")


(def ^:private fn-version-expects-effects-field-uuid
  #uuid "44a5c620-9e31-4d28-8b3a-6c1f5e9d2a47")


;; =============================================================================
;; Field UUIDs — :fn-slot-version
;; =============================================================================

(def ^:private fn-slot-version-fn-slot-id-field-uuid
  #uuid "36373839-4041-4234-3b4c-5d6e7f8a9b0c")


(def ^:private fn-slot-version-branch-id-field-uuid
  #uuid "37383940-4142-4345-4c5d-6e7f8a9b0c1d")


(def ^:private fn-slot-version-fn-id-field-uuid
  #uuid "38394041-4243-4456-5d6e-7f8a9b0c1d2e")


(def ^:private fn-slot-version-slot-id-field-uuid
  #uuid "39404142-4344-4567-6e7f-8a9b0c1d2e3f")


(def ^:private fn-slot-version-position-field-uuid
  #uuid "40414243-4445-4678-7f8a-9b0c1d2e3f4a")


(def ^:private fn-slot-version-created-at-field-uuid
  #uuid "42434445-4647-4890-9b0c-1d2e3f4a5b6c")


(def ^:private fn-slot-version-deleted-at-field-uuid
  #uuid "43444546-4748-4901-0c1d-2e3f4a5b6c7d")


;; =============================================================================
;; Field UUIDs — :binding-version
;; =============================================================================

(def ^:private binding-version-binding-id-field-uuid
  #uuid "44454647-4849-4a0b-1c2d-3e4f5a6b7c8d")


(def ^:private binding-version-branch-id-field-uuid
  #uuid "45464748-494a-4b1c-2d3e-4f5a6b7c8d9e")


(def ^:private binding-version-fn-id-field-uuid
  #uuid "4d5e6f7a-8b9c-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private binding-version-slot-id-field-uuid
  #uuid "e5607d87-1875-4cc6-a55d-bccee21807b5")


(def ^:private binding-version-value-field-uuid
  #uuid "51afa4af-fb8f-400b-8d26-6781fed40721")


(def ^:private binding-version-value-present-field-uuid
  #uuid "9f8c6e8d-56ee-4b82-a11b-15bc1bb491be")


(def ^:private binding-version-ref-fn-id-field-uuid
  #uuid "276ddd7a-4caa-4280-98b0-8fca584c3367")


(def ^:private binding-version-override-kind-field-uuid
  #uuid "3680c037-849c-46cb-a20c-d14680ea2435")


(def ^:private binding-version-rename-to-field-uuid
  #uuid "ca54868a-50f6-4aec-9d7b-518cb604c812")


(def ^:private binding-version-type-override-fn-id-field-uuid
  #uuid "063f3d28-2ecd-4b8a-a944-c005b72be75b")


(def ^:private binding-version-description-field-uuid
  #uuid "fda4de1c-2d46-4e6a-a233-bd9060af2b69")


(def ^:private binding-version-list-append-field-uuid
  #uuid "fb84cff4-6f91-4396-b607-1b34cff9a40e")


(def ^:private binding-version-list-closed-field-uuid
  #uuid "5bab550c-a484-4bd9-9ce5-b1df7549bc0e")


(def ^:private binding-version-terminal-field-uuid
  ;; Mirror of binding.:terminal (§4.3 seal) — must exist or VersionedStorage
  ;; strips the flag on write + reads come back without it.
  #uuid "8f4b2d19-6e3a-4c07-ab51-9d2f7a8c1e46")


(def ^:private binding-version-resolver-fn-id-field-uuid
  ;; Mirror of binding.resolver-fn-id — pinned like every mirror column.
  #uuid "7d2e5a83-1f96-4b04-8c37-9a5e1d4b6f28")


(def ^:private binding-version-required-field-uuid
  ;; Mirror of binding.:required (per-binding optional→required narrowing).
  ;; Without it VersionedStorage would leave `:required` on the shared
  ;; identity row, so a branch-only narrowing would leak across branches.
  #uuid "f5fac73f-48b8-4671-b07c-e2e2080659e7")


(def ^:private binding-version-created-at-field-uuid
  #uuid "f4b5fd01-2cac-4db5-ba0b-b7c229b0b2a5")


(def ^:private binding-version-deleted-at-field-uuid
  #uuid "24b920a2-f4eb-4e7d-a7b8-12b634cbe372")


;; =============================================================================
;; Field UUIDs — :binding-list-item-version
;; =============================================================================

(def ^:private binding-list-item-version-item-id-field-uuid
  #uuid "8e44ded4-b721-4620-b3c2-54f76aaac6b9")


(def ^:private binding-list-item-version-branch-id-field-uuid
  #uuid "d5940cd0-6d02-4fee-a996-d65aea7cd198")


(def ^:private binding-list-item-version-binding-id-field-uuid
  #uuid "ed238e51-f6c6-45d5-8fd6-ae9554bfffe9")


(def ^:private binding-list-item-version-position-field-uuid
  #uuid "02c57f0c-5c93-4303-96a4-9ff7878c8be4")


(def ^:private binding-list-item-version-value-field-uuid
  #uuid "53b45480-882f-4ef1-81f7-6993d8c49d12")


(def ^:private binding-list-item-version-ref-fn-id-field-uuid
  #uuid "f544e4e7-bc24-4859-a99f-0b542e8311fd")


(def ^:private resource-override-version-entity-uuid
  #uuid "561fafda-d391-4c1b-bab4-09a14a443946")


(def ^:private resource-override-version-override-id-field-uuid
  #uuid "0b467e5d-4480-4ed4-a962-f017390ea133")


(def ^:private resource-override-version-branch-id-field-uuid
  #uuid "0cecfb2b-dc9b-4d08-b4dc-944c6339e7c1")


(def ^:private resource-override-version-created-at-field-uuid
  #uuid "b9c8e2a6-4060-42a4-9cd6-ee1243f0dd68")


(def ^:private resource-override-version-deleted-at-field-uuid
  #uuid "14c74217-6137-4f21-8210-963afe7b908c")


(def ^:private resource-override-version-path-field-uuid
  #uuid "f65b08fe-3704-48d0-8712-5e0e410ab635")


(def ^:private resource-override-version-content-field-uuid
  #uuid "01925e50-4a98-47e7-9f13-ce435ee09865")


(def ^:private binding-list-item-version-literal-field-uuid
  #uuid "18875e04-1876-45f8-aae3-1dd8548458ec")


(def ^:private binding-list-item-version-created-at-field-uuid
  #uuid "6be2d727-9f48-4861-a50b-7aa040230880")


(def ^:private binding-list-item-version-deleted-at-field-uuid
  #uuid "e67deb49-ba44-4e3a-870f-f7eddaaffbbe")


;; =============================================================================
;; Version-mirror derivation — ONE base declaration, no hand-kept copy
;; =============================================================================
;;
;; Historically every `-version` entity was a hand-written parallel copy of
;; its base entity, and the versioned-storage decorator's
;; `version-data-fields` was a THIRD hand-kept list — so adding a column
;; meant 3 coordinated edits, and forgetting the mirror or the list made
;; the decorator strip the new column's writes SILENTLY. Now the mirror
;; field map and the version-data-fields set both derive from the base
;; field map (`gds/fn-fields` & co); the only per-field inputs kept here
;; are the PINNED column uuids (they must never change — the migration
;; framework matches columns by uuid, a changed uuid reads as drop+add)
;; and rare mirror-only tweaks (an explicit `:indexed?`).
;;
;; Adding a base column now fails the build LOUDLY unless you either pin
;; a mirror uuid for it (→ versioned) or list it as identity-level
;; (→ never versioned). The silent-drop footgun is structurally gone.

(defn- mirror-field-spec
  "Base field spec → its version-mirror spec: `:ref` demotes to `:uuid`
   (no FK from a version row — the target identity may be soft-deleted /
   cross-branch while the version row lingers); everything else copies
   verbatim, nullability included."
  [spec]
  (if (= :ref (:type spec))
    (-> spec (assoc :type :uuid) (dissoc :ref-entity))
    spec))


(defn derive-version-fields
  "Derive a `-version` mirror entity's field map from the BASE entity's
   field map.

   `framework` — the four version-framework columns
   (`{<id-field> {...ref base} :branch-id {...} :created-at :deleted-at}`),
   spliced in verbatim.
   `identity-fields` — base fields that live on the IDENTITY row and never
   version (structural identity, tenant owner). `:ref-many` fields are
   junction tables and skip automatically.
   `uuids` — `{field-name → pinned mirror-column uuid}`. A versioned base
   field with NO pinned uuid throws at build time: that is the point —
   the old failure mode was the decorator silently dropping the writes.
   `tweaks` — `{field-name → spec-merge}` for mirror-only flags
   (e.g. the explicit `:indexed?` on ref-fn-id — the base column is a
   real FK and gets its index from the constraint; the mirror's is a
   bare uuid)."
  [entity base-fields {:keys [identity-fields uuids tweaks]} framework]
  (let [data-fields
        (into {}
              (keep (fn [[fname spec]]
                      (when-not (or (contains? identity-fields fname)
                                    (= :ref-many (:type spec)))
                        (let [u (get uuids fname)]
                          (when-not u
                            (throw (ex-info
                                     (str "No version-mirror column uuid for "
                                          entity "/" fname
                                          " — a new base field must either be "
                                          "declared identity-level or given a "
                                          "pinned mirror uuid here. (Without "
                                          "this check the versioned decorator "
                                          "silently dropped its writes.)")
                                     {:entity entity :field fname})))
                          [fname (-> (mirror-field-spec spec)
                                     (assoc :uuid u)
                                     (merge (get tweaks fname)))]))))
              base-fields)
        ;; The reverse direction must fail loudly too: a pinned uuid
        ;; whose base field no longer exists is a leftover from a
        ;; field removal — silently ignoring it would let the pin map
        ;; rot indefinitely.
        orphan-pins (remove #(contains? data-fields %) (keys uuids))]
    (when (seq orphan-pins)
      (throw (ex-info (str "Orphan version-mirror uuid pin(s) for " entity
                           ": " (pr-str (vec orphan-pins))
                           " — the base field(s) no longer exist (or went "
                           "identity-level); drop the stale pins.")
                      {:entity entity :orphans (vec orphan-pins)})))
    (merge framework data-fields)))


(def ^:private mirror-config
  "Per-versioned-entity derivation inputs: which base fields stay on the
   identity row, the pinned mirror-column uuids, mirror-only tweaks."
  {:fn {:identity-fields #{:namespace-id :parent-ids :org-id :branch-local?}
        :uuids {:name fn-version-name-field-uuid
                :description fn-version-description-field-uuid
                :constraint fn-version-constraint-field-uuid
                :base-fn-id fn-version-base-fn-id-field-uuid
                :element-fn-id fn-version-element-fn-id-field-uuid
                :return-type-fn-id fn-version-return-type-fn-id-field-uuid
                :anonymous-hash fn-version-anonymous-hash-field-uuid
                :expects-effects fn-version-expects-effects-field-uuid
                :lambda-params fn-version-lambda-params-field-uuid}}
   :fn-slot {:identity-fields #{:org-id}
             :uuids {:fn-id fn-slot-version-fn-id-field-uuid
                     :slot-id fn-slot-version-slot-id-field-uuid
                     :position fn-slot-version-position-field-uuid}}
   :binding {:identity-fields #{:org-id}
             :uuids {:fn-id binding-version-fn-id-field-uuid
                     :slot-id binding-version-slot-id-field-uuid
                     :value binding-version-value-field-uuid
                     :value-present binding-version-value-present-field-uuid
                     :ref-fn-id binding-version-ref-fn-id-field-uuid
                     :type-override-fn-id binding-version-type-override-fn-id-field-uuid
                     :description binding-version-description-field-uuid
                     :list-append binding-version-list-append-field-uuid
                     :list-closed binding-version-list-closed-field-uuid
                     :terminal binding-version-terminal-field-uuid
                     :required binding-version-required-field-uuid
                     :resolver-fn-id binding-version-resolver-fn-id-field-uuid}
             ;; :indexed? — drives the version-side of the reverse-ref
             ;; lookup in `:ref-owner-bindings` (find-fn-usages / delete
             ;; ref-check). Not a `:ref` (no FK — the target fn may be
             ;; deleted while a version row lingers), so it needs the
             ;; explicit index flag.
             :tweaks {:ref-fn-id {:indexed? true}}}
   :resource-override {:identity-fields #{:org-id}
                       :uuids {:path resource-override-version-path-field-uuid
                               :content resource-override-version-content-field-uuid}
                       ;; :indexed? — check-resource-override-path-collision!
                       ;; finds its candidate set on the version table by
                       ;; :path (mirror of the base index).
                       :tweaks {:path {:indexed? true}}}
   :binding-list-item {:identity-fields #{:org-id}
                       :uuids {:binding-id binding-list-item-version-binding-id-field-uuid
                               :position binding-list-item-version-position-field-uuid
                               :value binding-list-item-version-value-field-uuid
                               :ref-fn-id binding-list-item-version-ref-fn-id-field-uuid
                               :literal binding-list-item-version-literal-field-uuid}
                       ;; Same version-side reverse-ref lookup as
                       ;; binding-version's :ref-fn-id above.
                       :tweaks {:ref-fn-id {:indexed? true}}}})


(def ^:private mirror-fields
  "Derived field maps for the four `-version` entities — built once at
   load from the base maps + `mirror-config`."
  {:fn-version
   (derive-version-fields
     :fn gds/fn-fields (mirror-config :fn)
     {:fn-id {:uuid fn-version-fn-id-field-uuid
              :type :ref :ref-entity :fn}
      :branch-id {:uuid fn-version-branch-id-field-uuid
                  :type :ref :ref-entity :branch}
      :created-at {:uuid fn-version-created-at-field-uuid
                   :type :timestamptz}
      :deleted-at {:uuid fn-version-deleted-at-field-uuid
                   :type :timestamptz :nullable? true}})
   :fn-slot-version
   (derive-version-fields
     :fn-slot gds/fn-slot-fields (mirror-config :fn-slot)
     {:fn-slot-id {:uuid fn-slot-version-fn-slot-id-field-uuid
                   :type :ref :ref-entity :fn-slot}
      :branch-id {:uuid fn-slot-version-branch-id-field-uuid
                  :type :ref :ref-entity :branch}
      :created-at {:uuid fn-slot-version-created-at-field-uuid
                   :type :timestamptz}
      :deleted-at {:uuid fn-slot-version-deleted-at-field-uuid
                   :type :timestamptz :nullable? true}})
   :binding-version
   (derive-version-fields
     :binding gds/binding-fields (mirror-config :binding)
     {:binding-id {:uuid binding-version-binding-id-field-uuid
                   :type :ref :ref-entity :binding}
      :branch-id {:uuid binding-version-branch-id-field-uuid
                  :type :ref :ref-entity :branch}
      :created-at {:uuid binding-version-created-at-field-uuid
                   :type :timestamptz}
      :deleted-at {:uuid binding-version-deleted-at-field-uuid
                   :type :timestamptz :nullable? true}})
   :binding-list-item-version
   (derive-version-fields
     :binding-list-item gds/binding-list-item-fields
     (mirror-config :binding-list-item)
     {:item-id {:uuid binding-list-item-version-item-id-field-uuid
                :type :ref :ref-entity :binding-list-item}
      :branch-id {:uuid binding-list-item-version-branch-id-field-uuid
                  :type :ref :ref-entity :branch}
      :created-at {:uuid binding-list-item-version-created-at-field-uuid
                   :type :timestamptz}
      :deleted-at {:uuid binding-list-item-version-deleted-at-field-uuid
                   :type :timestamptz :nullable? true}})
   :resource-override-version
   (derive-version-fields
     :resource-override gds/resource-override-fields
     (mirror-config :resource-override)
     {:override-id {:uuid resource-override-version-override-id-field-uuid
                    :type :ref :ref-entity :resource-override}
      :branch-id {:uuid resource-override-version-branch-id-field-uuid
                  :type :ref :ref-entity :branch}
      :created-at {:uuid resource-override-version-created-at-field-uuid
                   :type :timestamptz}
      :deleted-at {:uuid resource-override-version-deleted-at-field-uuid
                   :type :timestamptz :nullable? true}})})


(def ^:private framework-fields
  "The version-framework columns present on every mirror — excluded from
   `version-data-fields` (they are versioning plumbing, not entity data)."
  #{:branch-id :created-at :deleted-at})


;; =============================================================================
;; Versioned-entity registry
;; =============================================================================

(def versioned-entities
  #{:branch :branch-merge :branch-approval :fn-version :fn-slot-version
    :binding-version :binding-list-item-version})


(def version-entity-for
  {:fn               :fn-version
   :fn-slot          :fn-slot-version
   :binding          :binding-version
   :binding-list-item :binding-list-item-version
   :resource-override :resource-override-version})


(def version-id-field-for
  {:fn               :fn-id
   :fn-slot          :fn-slot-id
   :binding          :binding-id
   :binding-list-item :item-id
   :resource-override :override-id})


(defn version-data-fields
  "The versioned DATA fields of base entity `base` — derived from the
   same source as its mirror entity, so the versioned-storage decorator
   and the mirror can never disagree. This is the set
   `prepare-version-record` select-keys on: a field missing here had its
   writes silently dropped under the old hand-kept triple."
  [base]
  (let [mirror (get version-entity-for base)
        id-field (get version-id-field-for base)]
    (into #{}
          (remove (conj framework-fields id-field))
          (keys (get mirror-fields mirror)))))


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extends a builder with versioning entities."
  [builder]
  (-> builder
      ;; -----------------------------------------------------------------
      ;; branch
      ;; -----------------------------------------------------------------
      (ds/add-entity :branch branch-entity-uuid
                     {:name {:uuid branch-name-field-uuid
                             :type :text}
                      :base-branch-id {:uuid branch-base-branch-id-field-uuid
                                       :type :ref :ref-entity :branch
                                       :nullable? true}
                      :created-at {:uuid branch-created-at-field-uuid
                                   :type :timestamptz}
                      ;; Tenant owner (§4). NULL ≡ public (main).
                      :org-id {:uuid branch-org-id-field-uuid
                               :type :text
                               :nullable? true}
                      ;; Merge policy (error-tolerance Phase 5): truthy →
                      ;; merges INTO this branch are refused while recorded
                      ;; type diagnostics exist on either side. NULL ≡ off.
                      :forbid-invalid? {:uuid branch-forbid-invalid-field-uuid
                                        :type :bool
                                        :nullable? true}
                      ;; Protected branches (Stage 2): "push only via merge".
                      :require-merge? {:uuid branch-require-merge-field-uuid
                                       :type :bool
                                       :nullable? true}
                      ;; Change proposals: "proposed" marks a branch as
                      ;; submitted for review into its base. NULL ≡ ordinary.
                      :review-state {:uuid branch-review-state-field-uuid
                                     :type :text
                                     :nullable? true}
                      ;; Review policy (on the merge TARGET): how many
                      ;; approvals, whether self-approval counts, and an
                      ;; explicit reviewer allow-list. All NULL ≡ off.
                      :required-approvals {:uuid branch-required-approvals-field-uuid
                                           :type :int
                                           :nullable? true}
                      :allow-self-approval? {:uuid branch-allow-self-approval-field-uuid
                                             :type :bool
                                             :nullable? true}
                      :approver-ids {:uuid branch-approver-ids-field-uuid
                                     :type :jsonb
                                     :nullable? true}
                      ;; Protected branches (Stage 1): creator + write policy.
                      :owner-id {:uuid branch-owner-id-field-uuid
                                 :type :text
                                 :nullable? true}
                      :write-policy {:uuid branch-write-policy-field-uuid
                                     :type :text
                                     :nullable? true}})
      (ds/add-constraint :branch {:type :unique :fields [:org-id :name]
                                  :nulls-not-distinct? true})

      ;; -----------------------------------------------------------------
      ;; branch-merge
      ;; -----------------------------------------------------------------
      (ds/add-entity :branch-merge branch-merge-entity-uuid
                     {:source-branch-id {:uuid branch-merge-source-branch-id-field-uuid
                                         :type :ref :ref-entity :branch}
                      :source-timestamp {:uuid branch-merge-source-timestamp-field-uuid
                                         :type :timestamptz}
                      :target-branch-id {:uuid branch-merge-target-branch-id-field-uuid
                                         :type :ref :ref-entity :branch}
                      :target-timestamp {:uuid branch-merge-target-timestamp-field-uuid
                                         :type :timestamptz}
                      :created-at {:uuid branch-merge-created-at-field-uuid
                                   :type :timestamptz}})

      ;; -----------------------------------------------------------------
      ;; branch-approval — one row per reviewer approval of a proposal.
      ;; Plain, non-version-intercepted (like branch-merge). Carries
      ;; `:org-id` so the tenancy addon scopes it directly (in addition
      ;; to the transitive scoping via `source-branch-id` → branch.org-id).
      ;; -----------------------------------------------------------------
      (ds/add-entity :branch-approval branch-approval-entity-uuid
                     {:source-branch-id {:uuid branch-approval-source-branch-id-field-uuid
                                         :type :ref :ref-entity :branch}
                      :approver-id {:uuid branch-approval-approver-id-field-uuid
                                    :type :text}
                      :content-stamp {:uuid branch-approval-content-stamp-field-uuid
                                      :type :text}
                      :created-at {:uuid branch-approval-created-at-field-uuid
                                   :type :timestamptz}
                      :org-id {:uuid branch-approval-org-id-field-uuid
                               :type :text
                               :nullable? true}})

      ;; -----------------------------------------------------------------
      ;; fn-version — derived from gds/fn-fields (see § derivation above).
      ;; The base's `:indexed?` on :name rides along: check-fn-name-
      ;; collision! finds its candidate set on fn-version.name.
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-version fn-version-entity-uuid
                     (mirror-fields :fn-version))
      (ds/add-constraint :fn-version
                         {:type :unique :fields [:fn-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; fn-slot-version
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-slot-version fn-slot-version-entity-uuid
                     (mirror-fields :fn-slot-version))
      (ds/add-constraint :fn-slot-version
                         {:type :unique :fields [:fn-slot-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; binding-version
      ;; -----------------------------------------------------------------
      ;; `:rename-to` retired in Phase 6e (mirror of the main binding
      ;; entity) — see retire-field call below.
      (ds/add-entity :binding-version binding-version-entity-uuid
                     (mirror-fields :binding-version))
      (ds/add-constraint :binding-version
                         {:type :unique :fields [:binding-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; binding-list-item-version
      ;; -----------------------------------------------------------------
      (ds/add-entity :binding-list-item-version binding-list-item-version-entity-uuid
                     (mirror-fields :binding-list-item-version))
      (ds/add-constraint :binding-list-item-version
                         {:type :unique :fields [:item-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; resource-override-version (UI Step 1)
      ;; -----------------------------------------------------------------
      (ds/add-entity :resource-override-version resource-override-version-entity-uuid
                     (mirror-fields :resource-override-version))
      (ds/add-constraint :resource-override-version
                         {:type :unique :fields [:override-id :branch-id :created-at]})

      ;; -----------------------------------------------------------------
      ;; Retired fields (Phase 6e)
      ;; -----------------------------------------------------------------
      (ds/retire-field :binding-version :rename-to binding-version-rename-to-field-uuid)
      ;; `fn-version.impl-hash` retired — mirror of the main `:fn`
      ;; entity. The base-fn discriminator is now `return-type-fn-id`
      ;; presence; the column was always NULL. See retire-field on `:fn`.
      (ds/retire-field :fn-version :impl-hash fn-version-impl-hash-field-uuid)
      ;; Mirror of the retired binding.override-kind (audit-2 2b).
      (ds/retire-field :binding-version :override-kind
                       binding-version-override-kind-field-uuid)))


(defn build-schema
  "Builds the complete schema with graph and versioning entities."
  [builder]
  (-> builder
      (gds/extend-builder)
      (extend-builder)
      (ds/build)))
