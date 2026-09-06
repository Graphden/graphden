(ns graphden.schema.graph.schema
  "Graph data schema — fn/slot/fn-slot/binding model.

   Five entities form the base graph:
   - ns                — namespace (organization, name uniqueness scope).
   - fn                — a single entity for both functions AND types.
                         types are fn-rows without an impl, specialized via
                         base-fn-id (refinement), element-fn-id (list), or
                         simply carrying fn-slot rows (record / composite).
   - slot              — an atomic (name, type-fn-id) pair. Shared across
                         many fns through the fn-slot junction.
                         Immutable post-create.
   - fn-slot           — junction: fn ⊃ slots, with order.
                         Describes 'the parameters / fields of this fn'.
   - binding           — per-fn customization of a specific slot:
                         value/ref-binding, rename, type-override,
                         terminal seal, list-append/closed flags.
   - binding-list-item — items for list-typed slot bindings
                         (ordered, indexable).

   ## Role determined by field-presence (no `kind` discriminator)

   | parent-ids | return-type-fn-id | base-fn-id | element-fn-id | constraint | fn-slot rows | Role |
   |---|---|---|---|---|---|---|
   | empty | NOT NULL | NULL | NULL | NULL | * | base-fn (Clojure impl) |
   | empty | NULL | NULL | NULL | NULL | NOT empty | record-type (auto-builder) |
   | empty | NULL | NOT NULL | NULL | NOT NULL | empty | refinement-type |
   | empty | NULL | NULL | NOT NULL | NULL | empty | list-type |
   | empty | NULL | NULL | NULL | NULL | empty | primitive (boot data) |
   | NOT empty | * | * | * | * | * | composed fn-def |

   ## Inheritance is computed, not materialized

   A child fn does NOT carry per-slot rows for the slots it inherits —
   its free args are computed on demand by walking the `parent-ids`
   chain and subtracting the slots it binds. Sequences live as
   list-typed slots with `binding-list-item` rows."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Enums
;; =============================================================================

(def ^:private value-kind-enum-uuid
  #uuid "b79e6e8b-8aff-4188-862b-d8a85ef4fcdf")


;; array-map preserves insertion order — `value-kinds` below derives
;; the editor-facing ordered enum list from these keys, so this is the
;; single place the value-kind set AND its order are declared.
(def value-kind-values
  "Public — the storage codec derives its heuristic enum-detection set
   (`known-value-kind-values`) from these keys instead of keeping a
   hand-typed duplicate that silently drifts when a kind is added."
  (array-map
    :null        #uuid "c703ffd9-6401-4c49-9ca3-a280f6aac8ba"
    :uuid        #uuid "3a83af1b-f15c-421d-a5f1-f13db07deb72"
    :text        #uuid "cf26384f-d093-461d-9268-b42b8fd6eae6"
    :int         #uuid "154d3c4f-8d11-4592-9e24-5c40176cc5a7"
    :bool        #uuid "7497d750-67aa-4b55-8477-8323a9ab7761"
    :numeric     #uuid "f7a6728b-5ac6-4e1a-8bdb-ddc240cc059d"
    :timestamptz #uuid "e4476a32-3e93-4333-b0e5-964b9b19bea1"
    :jsonb       #uuid "b1b15bb9-a458-4337-9241-2a33e1ef25ea"
    :bytes       #uuid "2dcadfbd-800f-4b7b-bbcc-82b2afcf9f86"
    :any         #uuid "a3d7e8f1-9b2c-4d5e-8f6a-1c2d3e4f5a6b"
    :fn          #uuid "b4e8f9a2-0c3d-5e6f-9a7b-2d3e4f5a6b7c"
    :sequence    #uuid "9d1b3f8c-7a2e-4c5d-8e3f-1a2b4c6d8e0f"
    :keyword     #uuid "5e2a4b6c-8d1f-4e7a-9b3c-7f5d8e0a2c4d"))


(def value-kinds
  "Ordered `value_kind` enum values — the primitive type tags a binding
   `value` (or a slot) can carry. The single source of truth; served
   verbatim by `GET /api/value-kinds` so the editor's type-pickers read
   it instead of hard-coding the list."
  (vec (keys value-kind-values)))


;; Override-kind enum — policy for a value/ref binding:
;;   :fixed       — descendants cannot override this binding (default).
;;   :default     — this is a "default"; a descendant may fully replace it.
;;   :secret-path — binding.value is a vault PATH; the
;;                  executor auto-dereferences via clients/vault at
;;                  arg-resolution time. The actual secret value
;;                  never appears in graphden storage. Sync-time
;;                  validation refuses this kind on slots whose
;;                  effective type doesn't carry a `:secret` marker
;;                  (so it can't be used to launder a vault path
;;                  into a non-secret slot).
(def ^:private override-kind-enum-uuid
  #uuid "8199ca93-0a28-403a-8625-69c6a801d0c4")


(def ^:private override-kind-values
  {:fixed       #uuid "902c5068-8162-493d-b9d0-3590efb4d30c"
   :default     #uuid "e0f08934-335a-4724-9ac0-de7256c4a55d"
   :secret-path #uuid "9b3f2e84-7c4a-4dd1-b15e-3a82c9f8b7d6"})


;; =============================================================================
;; Entity UUIDs
;; =============================================================================

(def ^:private ns-entity-uuid
  #uuid "d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f80")


(def ^:private fn-entity-uuid
  #uuid "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private slot-entity-uuid
  #uuid "f2bc63ef-7661-4c53-98dc-ef06c004ad60")


(def ^:private fn-slot-entity-uuid
  #uuid "f52634f0-e203-49bf-b0e9-a762d7e924e7")


(def ^:private binding-entity-uuid
  #uuid "a855e0aa-0efa-4acb-804c-715be50de146")


(def ^:private binding-list-item-entity-uuid
  #uuid "05bbcb6c-da00-473a-b62a-217a7be2e38b")


;; =============================================================================
;; Field UUIDs — :ns
;; =============================================================================

(def ^:private ns-name-field-uuid
  #uuid "e5f6a7b8-1234-4c9d-0e1f-aabbccddeeff")


(def ^:private ns-parent-id-field-uuid
  #uuid "f6a7b8c9-2345-4d0e-1f2a-aabbccddeeff")


(def ^:private ns-description-field-uuid
  #uuid "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")


(def ^:private ns-org-id-field-uuid
  #uuid "7d369002-d60e-4d67-bd23-c6dabe28bfc8")


;; =============================================================================
;; Field UUIDs — :fn
;; =============================================================================

(def ^:private fn-name-field-uuid
  #uuid "c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private fn-namespace-id-field-uuid
  #uuid "a7b8c9d0-3456-4e1f-2a3b-aabbccddeeff")


(def ^:private fn-parent-ids-field-uuid
  #uuid "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private fn-impl-hash-field-uuid
  #uuid "f6a7b8c9-d0e1-4f2a-3b4c-5d6e7f8a9b0c")


(def ^:private fn-description-field-uuid
  #uuid "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e")


;; Refinement-type predicate. Set ⇒ this fn-row IS a refinement-type.
;; At runtime — implicit impl validates the single `:value` slot binding
;; against this constraint, throws `:refinement/violated` on miss.
;; Mutually exclusive with `parent-ids` (enforced loader/CRUD).
(def ^:private fn-constraint-field-uuid
  #uuid "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e90")


;; Refinement base — what type we're refining. Required when `constraint`
;; is set. Walks via this FK to compute the fully-resolved structural
;; rich-type `[:refine <base> <constraint>]`.
(def ^:private fn-base-fn-id-field-uuid
  #uuid "db40b8a7-6a3e-4c0f-8f3d-a1b1ea20c1f2")


;; List-element type. Set ⇒ this fn-row IS a list-type, with elements
;; of the referenced type. Slots referencing this fn carry `[:list T]`.
(def ^:private fn-element-fn-id-field-uuid
  #uuid "2b872fc4-951b-445e-ae82-571bf7057ad0")


;; Declared return type. FK → fn (which represents the type). NULL for
;; fn-defs whose return is purely computed from parent chain / impl.
(def ^:private fn-return-type-fn-id-field-uuid
  #uuid "a9fbce25-cde0-4f8f-855d-65799ca5a747")


;; For anonymous composite types (inline `:input {:foo :int}` with no name)
;; — a hash of the sorted (slot-id, position) pairs. A UNIQUE INDEX on this
;; field enforces dedup: the same shape → one type-row.
(def ^:private fn-anonymous-hash-field-uuid
  #uuid "a373c531-1ace-4b62-a9ed-789a83988a21")


;; Authored `:expects-effects` declaration — the set of effects the
;; fn-def's *author* claims this fn produces. The type-checker compares
;; this against `:effects` computed from the impl/body and flags drift
;; (effects appeared without being declared) or over-declaration
;; (declared but never produced). Stored as a JSONB array of keywords
;; (e.g. `["db" "io"]`) and surfaced both via /api/types and the editor.
;; Loader writes from `fns.edn` on sync; UI may overwrite per-row.
(def ^:private fn-expects-effects-field-uuid
  #uuid "d52a7c10-3b94-4f0d-9c1e-7a4d8b6f2e15")


;; Identity-level "do not propagate version-rows across branches" flag.
;; Mirrors the monotonic-ratchet of slot `:required`: nil ≡ false ≡
;; "inherit"; true on this fn OR any ancestor in `:parent-ids` closure
;; makes the effective set true; widening (descendant `:branch-local?
;; false` when ancestor is true) is rejected at sync-time. Checked at
;; resolve-time in `versioning/storage/resolution.clj`: merge-
;; candidates of foreign branches are filtered out for fn-ids whose
;; effective-branch-local? is true, so dev-only runtime config
;; (web-server port, vault path, etc.) never leaks to main on merge.
(def ^:private fn-branch-local-field-uuid
  #uuid "e8b3f7c2-9d4a-4f1e-b2c5-1a3b8d6e9f02")


;; =============================================================================
;; Field UUIDs — :org-id (tenancy — docs/TENANCY_SEAM.md § Storage & schema seams)
;; Identity-level column on every graph entity. NULL ≡ the shared "public"
;; org (core writes leave it NULL; the addon's OrgScopedStorage stamps the
;; current org). NOT versioned — tenant ownership doesn't vary per branch,
;; so it's excluded from `version-data-fields` and the version mirror, like
;; `:parent-ids`.
;; =============================================================================

(def ^:private fn-org-id-field-uuid
  #uuid "34ec3591-43fb-4248-aab9-2d0b58ab69d9")


(def ^:private slot-org-id-field-uuid
  #uuid "759d2294-4fe6-4339-b223-faa9079ccf99")


(def ^:private fn-slot-org-id-field-uuid
  #uuid "0db50482-5ca2-42bb-8598-dcb8b66f4664")


(def ^:private binding-org-id-field-uuid
  #uuid "049586d3-7aa3-4066-96b5-73565d07e87e")


(def ^:private binding-list-item-org-id-field-uuid
  #uuid "6b8713a2-5917-448f-a460-af64ee7340f1")


;; =============================================================================
;; Field UUIDs — :slot
;; =============================================================================

(def ^:private slot-name-field-uuid
  #uuid "45567a17-c458-440e-984d-5a0335e79e88")


(def ^:private slot-type-fn-id-field-uuid
  #uuid "81ec21e2-bec2-450c-a4cc-1e8e40944616")


(def ^:private slot-description-field-uuid
  #uuid "f47d60f1-c0d4-4b32-91a3-a8972cbc4e33")


(def ^:private slot-required-field-uuid
  #uuid "1ddc55ee-cb3d-4877-a07a-72ee47d24e51")


;; Phase 6 — link a renamed slot back to its source slot in the parent
;; closure. When set, the row is a "renamed view" of `source-slot-id`
;; under a new `:name`; descendants binding the new name target THIS
;; slot's id (via the standard `slot-id(owner-fn-id, slot-name)` UUIDv5
;; scheme), and the resolver walks the chain to find the original
;; type / inherited binding when needed.
;;
;; The corresponding `:rename-to` text on `binding` becomes redundant
;; once every renamed slot carries this FK — backfill + drop is the
;; final step of Phase 6.
(def ^:private slot-source-slot-id-field-uuid
  #uuid "0a4e0c11-3f4f-5a8e-9d1c-d8e3a7b2f5c1")


;; =============================================================================
;; Field UUIDs — :fn-slot (junction)
;; =============================================================================

(def ^:private fn-slot-fn-id-field-uuid
  #uuid "28f8574b-5fd2-44c9-9a0c-1a06ae85b21f")


(def ^:private fn-slot-slot-id-field-uuid
  #uuid "c06dfbba-4b2a-422b-a48f-5104ce31b718")


(def ^:private fn-slot-position-field-uuid
  #uuid "e67dc181-42f3-4d02-adfc-894cabe01a0f")


;; =============================================================================
;; Field UUIDs — :binding
;; =============================================================================

(def ^:private binding-fn-id-field-uuid
  #uuid "5541a2be-757f-4e53-ac89-27c6ca7e1b84")


(def ^:private binding-slot-id-field-uuid
  #uuid "7ee8a91d-6ec9-4e03-8ed7-700f0fb53aaa")


(def ^:private binding-value-field-uuid
  #uuid "6459f00a-76ef-4fc3-a331-d222dc130982")


(def ^:private binding-value-present-field-uuid
  #uuid "644dd3d8-96f9-4813-b9e9-6809603bf477")


(def ^:private binding-ref-fn-id-field-uuid
  #uuid "d16294e8-cc37-4a74-9a75-d8e865a9660a")


(def ^:private binding-override-kind-field-uuid
  #uuid "4c41a63b-c4cb-47e0-818a-bf7d129a5066")


(def ^:private binding-rename-to-field-uuid
  #uuid "db3f560b-4b5b-4486-bd72-3cb4626d73d7")


(def ^:private binding-type-override-fn-id-field-uuid
  #uuid "fb360739-96aa-46cf-878a-c53398460a72")


(def ^:private binding-description-field-uuid
  #uuid "0db6164f-137e-47bd-9be8-786a4d4ffe07")


(def ^:private binding-list-append-field-uuid
  #uuid "509e045b-4ac9-4a6b-ac53-20f02b2e1f8d")


(def ^:private binding-list-closed-field-uuid
  #uuid "9cff0b5c-36dc-4527-8ee2-2943466a2520")


(def ^:private binding-terminal-field-uuid
  ;; §4.3 explicit seal: `:terminal true` on a binding locks its (fn,slot)
  ;; against descendant overrides (enforced by `validation/terminal-rej`).
  ;; Generalizes the automatic value-seal to not-yet-valued template slots.
  #uuid "7e3a1c08-5d2f-4b96-9a40-8c1e6f7b0d35")


(def ^:private binding-resolver-fn-id-field-uuid
  ;; Generic value-resolver: when set, the executor treats the binding's
  ;; stored :value as the INPUT to this graph fn at arg-resolution time
  ;; ("stored value → runtime value"). `:override-kind :secret-path` is
  ;; the legacy special case (resolver ≡ :vault-get). SECRETS.md
  ;; § generalization.
  #uuid "3b7a9c15-4e2d-4f86-9a01-6c8d2e5b7f13")


(def ^:private fn-lambda-params-field-uuid
  ;; Authored HOF call-site parameter list (ordered array of arg-name
  ;; strings; `[]` = "everything captured"). The compile's wrap-arity
  ;; dispatch (`compile.renames/declared-lambda-params`) reads it off
  ;; the fn ROW — sweep-independent, editor-authorable, exported.
  #uuid "e0982385-4c17-46cc-8ca4-386a8738519f")


(def ^:private binding-required-field-uuid
  #uuid "54c53941-0b30-4020-b701-530d4d043d63")


;; =============================================================================
;; Field UUIDs — :binding-list-item
;; =============================================================================

(def ^:private binding-list-item-binding-id-field-uuid
  #uuid "b1ce8d68-08bf-4872-be1d-60dcb03137d6")


(def ^:private binding-list-item-position-field-uuid
  #uuid "151911f8-be74-4943-b7eb-6f453849c0bc")


(def ^:private binding-list-item-value-field-uuid
  #uuid "84dffedc-2d78-48a9-89d9-9f81c93e91f3")


(def ^:private binding-list-item-ref-fn-id-field-uuid
  #uuid "59ab3d7b-b6e8-4194-977b-cc5c199c6065")


(def ^:private binding-list-item-literal-field-uuid
  #uuid "0931c1b5-3c19-4323-927d-e180182e9e0a")


;; =============================================================================
;; Base field maps — the SINGLE source for each versioned entity's columns.
;;
;; Public: `schema.versioned.schema/derive-version-fields` derives the
;; `-version` mirror entity (refs demoted to :uuid + branch/created/deleted
;; framework columns) AND the versioned-storage `version-data-fields` set
;; from these maps — so adding a column here is the ONE edit; the mirror
;; either derives it automatically or fails the build loudly (never the
;; old silent-write-drop).
;; =============================================================================

(def fn-fields
  "Base `:fn` field specs. Identity-level (never versioned):
   `:namespace-id` / `:parent-ids` (structural identity), `:org-id`
   (tenant owner), `:branch-local?` (monotonic identity flag)."
  {:name {:uuid fn-name-field-uuid
          :type :text
          :nullable? true
          ;; Indexed: name lookups (`query-fn-by-name`, ref-by-name,
          ;; constraint-type resolution) hit the largest table; a
          ;; standalone index keeps those O(log n). The mirror keeps the
          ;; flag — check-fn-name-collision! finds its candidate set on
          ;; fn-version.name.
          :indexed? true}
   :namespace-id {:uuid fn-namespace-id-field-uuid
                  :type :ref
                  :ref-entity :ns
                  :nullable? true}
   :parent-ids {:uuid fn-parent-ids-field-uuid
                :type :ref-many
                :ref-entity :fn
                :nullable? true}
   :description {:uuid fn-description-field-uuid
                 :type :text
                 :nullable? true}
   ;; Refinement-type marker.
   :constraint {:uuid fn-constraint-field-uuid
                :type :jsonb
                :nullable? true}
   ;; Refinement base type (FK→fn).
   :base-fn-id {:uuid fn-base-fn-id-field-uuid
                :type :ref
                :ref-entity :fn
                :nullable? true}
   ;; List-type element (FK→fn).
   :element-fn-id {:uuid fn-element-fn-id-field-uuid
                   :type :ref
                   :ref-entity :fn
                   :nullable? true}
   ;; Declared return type (FK→fn). Replaces old `return-type` enum column.
   :return-type-fn-id {:uuid fn-return-type-fn-id-field-uuid
                       :type :ref
                       :ref-entity :fn
                       :nullable? true}
   ;; Hash for anonymous composite dedup.
   :anonymous-hash {:uuid fn-anonymous-hash-field-uuid
                    :type :text
                    :nullable? true}
   ;; Authored effect-set declaration (drift checker warns when computed
   ;; effects diverge from this).
   :expects-effects {:uuid fn-expects-effects-field-uuid
                     :type :jsonb
                     :nullable? true}
   ;; Authored HOF call-site parameter list (see field-uuid note).
   :lambda-params {:uuid fn-lambda-params-field-uuid
                   :type :jsonb
                   :nullable? true}
   ;; Identity-level monotonic flag (see ns-doc above).
   :branch-local? {:uuid fn-branch-local-field-uuid
                   :type :bool
                   :nullable? true}
   ;; Tenant owner (§3.0 B2). NULL ≡ public.
   :org-id {:uuid fn-org-id-field-uuid
            :type :text
            :nullable? true}})


(def fn-slot-fields
  "Base `:fn-slot` field specs. Identity-level: `:org-id`."
  {:fn-id {:uuid fn-slot-fn-id-field-uuid
           :type :ref
           :ref-entity :fn}
   :slot-id {:uuid fn-slot-slot-id-field-uuid
             :type :ref
             :ref-entity :slot}
   :position {:uuid fn-slot-position-field-uuid
              :type :int}
   ;; Tenant owner (§3.0 B2). NULL ≡ public.
   :org-id {:uuid fn-slot-org-id-field-uuid
            :type :text
            :nullable? true}})


(def binding-fields
  "Base `:binding` field specs. Identity-level: `:org-id`."
  {:fn-id {:uuid binding-fn-id-field-uuid
           :type :ref
           :ref-entity :fn}
   :slot-id {:uuid binding-slot-id-field-uuid
             :type :ref
             :ref-entity :slot}
   :value {:uuid binding-value-field-uuid
           :type :jsonb
           :nullable? true}
   ;; `:value-present` distinguishes "author wrote `{:value nil}`" from
   ;; "author wrote nothing for this slot". Both serialise `:value` to
   ;; SQL NULL via `encode-value`'s `(when (some? value) ...)` skip, so
   ;; without this flag they're indistinguishable at read time — and the
   ;; classifier falls through to `:free`, leaking the caller's
   ;; `fa[<slot-name>]` (e.g. Ring request) into the slot. See
   ;; `compile/bindings.clj/value-binding?`.
   :value-present {:uuid binding-value-present-field-uuid
                   :type :bool
                   :nullable? true}
   :ref-fn-id {:uuid binding-ref-fn-id-field-uuid
               :type :ref
               :ref-entity :fn
               :nullable? true}
   ;; `:rename-to` retired in Phase 6e — see retire-field call after the
   ;; entity declaration. The text was replaced by slot.source-slot-id (FK).
   :type-override-fn-id {:uuid binding-type-override-fn-id-field-uuid
                         :type :ref
                         :ref-entity :fn
                         :nullable? true}
   :description {:uuid binding-description-field-uuid
                 :type :text
                 :nullable? true}
   :list-append {:uuid binding-list-append-field-uuid
                 :type :bool
                 :nullable? true}
   :list-closed {:uuid binding-list-closed-field-uuid
                 :type :bool
                 :nullable? true}
   ;; §4.3 explicit seal — locks (fn,slot) vs descendants.
   :terminal {:uuid binding-terminal-field-uuid
              :type :bool
              :nullable? true}
   :required {:uuid binding-required-field-uuid
              :type :bool
              :nullable? true}
   ;; Generic resolver — see the uuid def above.
   :resolver-fn-id {:uuid binding-resolver-fn-id-field-uuid
                    :type :ref
                    :ref-entity :fn
                    :nullable? true}
   ;; Tenant owner (§3.0 B2). NULL ≡ public.
   :org-id {:uuid binding-org-id-field-uuid
            :type :text
            :nullable? true}})


;; =============================================================================
;; Field UUIDs — :resource-override
;; =============================================================================

(def ^:private resource-override-entity-uuid
  #uuid "1e77e7a2-cde4-489d-9f50-0098b5f8ff8e")


(def ^:private resource-override-path-field-uuid
  #uuid "20f148ed-3825-4521-9466-29c768786a8a")


(def ^:private resource-override-content-field-uuid
  #uuid "bb0ec1ef-df59-45de-9e6d-23448f61f1dd")


(def ^:private resource-override-org-id-field-uuid
  #uuid "a68cd771-e7d0-4693-9fe3-f712d9e2a35a")


(def resource-override-fields
  "Base `:resource-override` field specs — an in-DB override of a
   classpath frontend asset (`:read-resource-overridable` reads it
   first). Identity-level: `:org-id` (platform rows in practice — the
   entity is tenant-forbidden in the cloud)."
  {:path {:uuid resource-override-path-field-uuid
          :type :text
          ;; Indexed: `:_ro-row` resolves an asset by path on the hot
          ;; serve path (~once per bundle FILE per asset request), and
          ;; `check-resource-override-path-collision!` queries by path.
          ;; The mirror index (versioned schema) backs the collision
          ;; candidate set on the version table.
          :indexed? true}
   :content {:uuid resource-override-content-field-uuid
             :type :text
             :nullable? true}
   ;; Tenant owner (§3.0 B2). NULL ≡ public/platform.
   :org-id {:uuid resource-override-org-id-field-uuid
            :type :text
            :nullable? true}})


(def binding-list-item-fields
  "Base `:binding-list-item` field specs. Identity-level: `:org-id`."
  {:binding-id {:uuid binding-list-item-binding-id-field-uuid
                :type :ref
                :ref-entity :binding}
   :position {:uuid binding-list-item-position-field-uuid
              :type :int}
   :value {:uuid binding-list-item-value-field-uuid
           :type :jsonb
           :nullable? true}
   :ref-fn-id {:uuid binding-list-item-ref-fn-id-field-uuid
               :type :ref
               :ref-entity :fn
               :nullable? true}
   :literal {:uuid binding-list-item-literal-field-uuid
             :type :bool
             :nullable? true}
   ;; Tenant owner (§3.0 B2). NULL ≡ public.
   :org-id {:uuid binding-list-item-org-id-field-uuid
            :type :text
            :nullable? true}})


;; =============================================================================
;; Enum builders
;; =============================================================================

(defn- value-kind-enum-values
  "Derived from `value-kind-values` KEYS — the single declaration of
   the kind set — so a kind added there reaches the DB enum too.
   (Previously built from `[:null :any :fn] + ft/supported-types`: a
   key added only to `value-kind-values` would flow into
   `/api/value-kinds` and the codec heuristic but silently never
   reach the enum.)"
  []
  (mapv (fn [[k uuid]] {:uuid uuid :value k}) value-kind-values))


(defn- override-kind-enum-values
  []
  (mapv (fn [[k uuid]] {:uuid uuid :value k}) override-kind-values))


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extends a builder with graph schema entities."
  [builder]
  (-> builder
      ;; Enums
      ;; :value-kind — the PG enum TYPE is currently referenced by no
      ;; column (the columns that once used it are retired); the
      ;; declaration stays until a retire-enum mechanism exists — an
      ;; unused pg enum type is inert (same status as :override-kind
      ;; below). The `value-kind-values` MAP itself is load-bearing:
      ;; codec heuristic + /api/value-kinds.
      (ds/add-enum :value-kind value-kind-enum-uuid (value-kind-enum-values))
      (ds/add-enum :override-kind override-kind-enum-uuid (override-kind-enum-values))

      ;; -----------------------------------------------------------------
      ;; ns: namespace entity
      ;; -----------------------------------------------------------------
      (ds/add-entity :ns ns-entity-uuid
                     {:name {:uuid ns-name-field-uuid
                             :type :text}
                      :parent-id {:uuid ns-parent-id-field-uuid
                                  :type :ref
                                  :ref-entity :ns
                                  :nullable? true}
                      :description {:uuid ns-description-field-uuid
                                    :type :text
                                    :nullable? true}
                      ;; Tenant owner (§3.0 B2). NULL ≡ public — core /
                      ;; package namespaces are shared; a tenant's namespaces
                      ;; are stamped with their org and isolated (read-filter
                      ;; + own?-write-guard + RLS), same model as :fn.
                      :org-id {:uuid ns-org-id-field-uuid
                               :type :text
                               :nullable? true}})
      ;; Per ORG, like `:branch` — `(org-id, parent-id, name) NULLS NOT
      ;; DISTINCT`: two orgs may both create `packages/team` under the shared
      ;; public parent (the old `(parent-id, name)` key bounced org B off org
      ;; A's INVISIBLE row — a cross-org existence oracle), while a root
      ;; namespace (NULL parent) is finally unique too (NULLS DISTINCT never
      ;; fired on it). The old index is `retired-indexes` in
      ;; storage/postgres/migration.clj; the new one lands on a migrated DB
      ;; through `ensure-unique-indexes!`.
      (ds/add-constraint :ns {:type :unique :fields [:org-id :parent-id :name]
                              :nulls-not-distinct? true})

      ;; -----------------------------------------------------------------
      ;; fn: function entity (also represents types — see ns-doc).
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn fn-entity-uuid fn-fields)
      ;; NOTE — the `UNIQUE (namespace-id, name)` constraint was retired.
      ;; Like `binding-list-item (binding-id, position)` before it, name
      ;; uniqueness is a per-branch RESOLVED-VIEW property, not a base-table
      ;; one: soft-deleted identity rows persist by design and kept the
      ;; `(ns, name)` key occupied forever (delete a fn inside a namespace →
      ;; every future create/move of a same-named fn there bounced with a
      ;; unique-violation), while NULL `namespace-id` (root fns) was never
      ;; covered by the btree at all. VersionedStorage now enforces it
      ;; against the live branch view (`check-fn-name-collision!`), advisory-
      ;; lock-serialized; the raw index is dropped via `retired-indexes` in
      ;; storage/postgres/migration.clj.
      (ds/add-constraint :fn {:type :unique :fields [:anonymous-hash]})

      ;; -----------------------------------------------------------------
      ;; slot: an atomic (name, type) pair.
      ;; Immutable — changing the type = creating a new slot.
      ;; -----------------------------------------------------------------
      (ds/add-entity :slot slot-entity-uuid
                     {:name {:uuid slot-name-field-uuid
                             :type :text}
                      :type-fn-id {:uuid slot-type-fn-id-field-uuid
                                   :type :ref
                                   :ref-entity :fn}
                      :required {:uuid slot-required-field-uuid
                                 :type :bool
                                 :nullable? true}
                      :description {:uuid slot-description-field-uuid
                                    :type :text
                                    :nullable? true}
                      ;; Renamed-view marker — when set, this slot is a
                      ;; new identity for `source-slot-id` exposed under
                      ;; a different `:name`. Composed fn-defs use this
                      ;; to surface inherited slots under their own
                      ;; vocabulary; descendants then bind by the new
                      ;; name and the binding lands on this slot's id
                      ;; via the deterministic UUIDv5 scheme.
                      :source-slot-id {:uuid slot-source-slot-id-field-uuid
                                       :type :ref
                                       :ref-entity :slot
                                       :nullable? true}
                      ;; Tenant owner (§3.0 B2). NULL ≡ public.
                      :org-id {:uuid slot-org-id-field-uuid
                               :type :text
                               :nullable? true}})

      ;; -----------------------------------------------------------------
      ;; fn-slot: many-to-many junction. fn ⊃ slots, with order.
      ;; Describes 'the parameters / fields of this fn'.
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-slot fn-slot-entity-uuid fn-slot-fields)
      (ds/add-constraint :fn-slot {:type :unique :fields [:fn-id :slot-id]})

      ;; -----------------------------------------------------------------
      ;; binding: per-fn customization of a specific slot.
      ;; Mutually-exclusive groups:
      ;;   value/ref-binding (value xor ref-fn-id) + override-kind
      ;;   rename (rename-to)
      ;;   type-override (type-override-fn-id)
      ;;   per-level metadata (description, terminal)
      ;;   list-specific markers (list-append, list-closed)
      ;; Application-side constraint enforces "at most one
      ;; non-nil from {value, ref-fn-id}".
      ;; -----------------------------------------------------------------
      (ds/add-entity :binding binding-entity-uuid binding-fields)
      (ds/add-constraint :binding {:type :unique :fields [:fn-id :slot-id]})

      ;; -----------------------------------------------------------------
      ;; binding-list-item: ordered items for list-typed slot bindings.
      ;; -----------------------------------------------------------------
      (ds/add-entity :binding-list-item binding-list-item-entity-uuid
                     binding-list-item-fields)
      ;; NOTE — `(binding-id, position)` UNIQUE was retired in favour of
      ;; a per-branch resolved-view check in `VersionedStorage`. The base
      ;; identity row represents cross-branch identity; multiple branches
      ;; can legitimately hold different items at the same `(binding-id,
      ;; position)` (the resolver disambiguates by branch). Within a
      ;; single branch, position uniqueness is enforced in
      ;; `versioning.storage.core/check-list-item-position-collision!`.
      ;; Existing DBs get the legacy index dropped by
      ;; `storage.postgres.migration/drop-retired-indexes!`.

      ;; -----------------------------------------------------------------
      ;; resource-override: an in-DB override of a classpath frontend
      ;; asset (UI Step 1 — edit the editor's own JS/CSS from inside the
      ;; running editor). `path` uniqueness is a per-branch RESOLVED-VIEW
      ;; property (like fn names) enforced in VersionedStorage
      ;; (`check-resource-override-path-collision!`), not a base index.
      ;; -----------------------------------------------------------------
      (ds/add-entity :resource-override resource-override-entity-uuid
                     resource-override-fields)

      ;; -----------------------------------------------------------------
      ;; Retired fields (Phase 6e — drop binding.rename-to)
      ;; -----------------------------------------------------------------
      ;; The `slot.source-slot-id` FK is the canonical home for renames
      ;; now. The legacy text column has had no readers since Phase 6c
      ;; and no writers since Phase 6d; mark it retired so the migration
      ;; framework issues DROP COLUMN on next deploy.
      (ds/retire-field :binding :rename-to binding-rename-to-field-uuid)
      ;; `fn.impl-hash` retired — the base-fn discriminator is now the
      ;; presence of `return-type-fn-id` (a type-row never has one). The
      ;; column was always NULL in storage; mark it retired so the
      ;; migration framework issues DROP COLUMN on next deploy.
      (ds/retire-field :fn :impl-hash fn-impl-hash-field-uuid)
      ;; `binding.override-kind` retired (audit-2 stage 2b): :fixed was
      ;; superseded by :terminal, :default was write-only, :secret-path
      ;; became the :vault-get resolver form (stage 1 writers + stage 2a
      ;; boot migration converted every row before this drop). The
      ;; :override-kind ENUM declaration stays until a retire-enum
      ;; mechanism exists — an unused pg enum type is inert.
      (ds/retire-field :binding :override-kind binding-override-kind-field-uuid)))


(defn build-schema
  "Builds the graph data schema."
  [builder]
  (-> builder
      (extend-builder)
      (ds/build)))
