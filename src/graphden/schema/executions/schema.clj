(ns graphden.schema.executions.schema
  "Execution-history schema — fn-execution / fn-execution-arg /
   fn-execution-arg-item.

   An execution is an immutable, time-stamped event: \"user U at time T
   ran fn-version V with args A, got result/error R\". Unlike fn /
   binding / slot (which are structural and versioned — the editor
   mutates them and the version-history table records the trail), an
   execution row is write-once-then-status-update; no audit trail of
   how its status / result changed makes sense.

   Hence these entities are intentionally NOT registered in
   `versioning.storage.resolution/entity-config`. Mutations to
   `:status` / `:finished-at` / `:result` / `:error` /
   `:cancel-requested?` go in-place on the base table.

   The fn-id is captured via `:fn-version-id` (FK → :fn-version row,
   which itself carries `:fn-id` for base-id lookup). Storing a
   version-id instead of a logical fn-id makes the history
   audit-correct: editing a fn after running it doesn't rewrite what
   the execution actually did.

   Arg shape mirrors `:binding` + `:binding-list-item`: each slot
   binding becomes one `:fn-execution-arg` row carrying either a
   literal `:value` (jsonb) OR a `:ref-fn-version-id` (HOF ref,
   frozen). List-typed args spawn `:fn-execution-arg-item` rows
   ordered by `:position`. The XOR between `:value` and
   `:ref-fn-version-id` is enforced in the backend write-path (the
   declarative schema protocol exposes only `:type :unique`, not
   CHECK-constraints)."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Enum: :execution-status
;; =============================================================================

(def ^:private execution-status-enum-uuid
  #uuid "b7553da6-1f31-4e06-a706-89afea6504c8")


(def ^:private execution-status-values
  ;; insertion order = display order in editor history dropdowns
  (array-map
    :pending   #uuid "767f94eb-c075-4acd-97a1-e066c59c78b2"
    :succeeded #uuid "69f58f9f-392a-4558-bd29-f472c46b6a87"
    :failed    #uuid "3781aea2-c908-4421-b561-78827eb188b4"
    :cancelled #uuid "853377e4-c384-441c-b659-289dab19588a"))


(defn- execution-status-enum-values
  []
  (mapv (fn [[k uuid]] {:uuid uuid :value k}) execution-status-values))


;; =============================================================================
;; Entity UUIDs
;; =============================================================================

(def ^:private fn-execution-entity-uuid
  #uuid "467ce1e9-1f1b-4ff5-84e1-92ffc6023b80")


(def ^:private fn-execution-arg-entity-uuid
  #uuid "24caee70-5302-41b4-ac8f-071456553c95")


(def ^:private fn-execution-arg-item-entity-uuid
  #uuid "0accb5fb-512e-40e6-916f-1ee9e28c959e")


;; =============================================================================
;; Field UUIDs — :fn-execution
;; =============================================================================

(def ^:private fn-execution-fn-version-id-field-uuid
  #uuid "64970afb-f98f-47c7-916f-5cfecd732d77")


(def ^:private fn-execution-started-at-field-uuid
  #uuid "270a0b22-77eb-4c29-a1ee-6b50c75640a1")


;; Cross-service tracing (docs/EXECUTION.md § Tracing across services):
;; an outbound `:service-get` carries the caller's trace id + execution
;; id in a header; a listener that receives it persists the request it
;; handles as an execution row linked to the caller. A trace is the
;; top-level execution's id; every hop shares it.
(def ^:private fn-execution-trace-id-field-uuid
  #uuid "5e2a7c9d-1f4b-4d68-a3c7-8b0e6d2f4a91")


(def ^:private fn-execution-parent-execution-id-field-uuid
  #uuid "b7f1d3a5-9c2e-4b74-8e6a-2d5c0f8b1e63")


(def ^:private fn-execution-finished-at-field-uuid
  #uuid "56a12b66-a6cc-4502-8107-35015050938c")


(def ^:private fn-execution-status-field-uuid
  #uuid "eca54c51-6367-44b4-9793-c283807f71f7")


(def ^:private fn-execution-result-field-uuid
  #uuid "e9cd98a4-888a-426c-81b6-d15840ef2b82")


(def ^:private fn-execution-result-truncated-field-uuid
  #uuid "b7246db2-ba41-4edc-ba00-625ee1f600db")


(def ^:private fn-execution-error-field-uuid
  #uuid "21571edb-44f8-4915-a0cf-7a4f3973e72c")


(def ^:private fn-execution-error-data-field-uuid
  #uuid "33ed4c9f-7b26-40e1-960b-f738e8e60122")


(def ^:private fn-execution-declared-effects-field-uuid
  #uuid "8e226d5b-5607-4075-bbdd-c910f46965c6")


(def ^:private fn-execution-runtime-effects-field-uuid
  #uuid "0d5b1f9c-3a8e-4b27-9c0a-7e9f0e4d8f12")


(def ^:private fn-execution-user-id-field-uuid
  #uuid "3384fe98-b150-4d34-9a5c-530be4d07373")


(def ^:private fn-execution-cancel-requested-field-uuid
  #uuid "13aacbf1-e4b1-4411-a46a-202f88fc633c")


(def ^:private fn-execution-touched-secret-field-uuid
  ;; Audit trail: true when the executed fn-def either
  ;; declared a `:secret`-typed slot OR computed a `:secret`-returning
  ;; signature. Set on rows whose `runtime-effects` is also non-empty
  ;; — that's the combination that means a secret was both consumed
  ;; AND observably acted upon (network / io / db side effect).
  #uuid "5d9b6c0a-c3f4-49a8-9c01-0b1e8b5e4a2c")


(def ^:private fn-execution-path-trace-field-uuid
  ;; Debug/observability P1+P3 — execution-path capture. Filled on
  ;; terminal status when the submission opted in via `trace?` /
  ;; `capture-values?` (which scope capture to that execution's own
  ;; traversal — the `trace-all` sentinel), or when an ambient-sampling
  ;; draw won for a selectively-traced fn (P3; the runtime traced set
  ;; also gates programmatic captures that bind `*path-trace*`
  ;; directly).
  #uuid "c39fc6a2-73d6-45a7-b062-07c2de5a7b42")


(def ^:private fn-execution-org-id-field-uuid
  ;; Tenant owner (§3.0 B2 / §4 org-scoped executions). NULL ≡ public.
  ;; Stamped by OrgScopedStorage at create-time; the conveyed *current-org*
  ;; rides the completion future so the terminal UPDATE passes the own-guard.
  #uuid "7c2e9a41-5b83-4d06-8f1a-3e6b0d27c594")


(def ^:private fn-execution-branch-id-field-uuid
  ;; Which branch's ExecutionContext ran this. Drives the Errors panel's
  ;; branch-scoped visibility: a failure shows on the branch that ran it
  ;; and on its descendants (branch-chain), never on siblings/ancestors.
  ;; NULL ≡ pre-feature row — visible on every branch until TTL-swept.
  #uuid "aa7dc281-504e-40de-a771-41d4203c216d")


(def ^:private fn-execution-acknowledged-at-field-uuid
  ;; Explicit dismiss from the Errors panel — the row stays (audit
  ;; trail intact) but stops counting as an unresolved failure.
  #uuid "85ddf66c-15ea-4edb-a21b-7b840a0e0ac7")


;; =============================================================================
;; Field UUIDs — :fn-execution-arg
;; =============================================================================

(def ^:private fn-execution-arg-execution-id-field-uuid
  #uuid "42259589-a4a9-41e5-a9e3-113009d8cbb0")


(def ^:private fn-execution-arg-slot-id-field-uuid
  #uuid "0441e724-eed6-420c-88e1-d5d65d311822")


(def ^:private fn-execution-arg-value-field-uuid
  #uuid "4cef4a91-f42d-4c14-93d8-a3a6e67808a4")


(def ^:private fn-execution-arg-ref-fn-version-id-field-uuid
  #uuid "102b829f-db83-4b91-96d1-277783f98ac8")


;; =============================================================================
;; Field UUIDs — :fn-execution-arg-item
;; =============================================================================

(def ^:private fn-execution-arg-item-execution-arg-id-field-uuid
  #uuid "ee4bacc8-bf40-4fd4-9c55-fb21a7b31c1d")


(def ^:private fn-execution-arg-item-position-field-uuid
  #uuid "89950e9c-58cd-4c2c-8129-4d98d84b2bbe")


(def ^:private fn-execution-arg-item-value-field-uuid
  #uuid "7ac84d64-be76-4472-b668-995bd5648981")


(def ^:private fn-execution-arg-item-ref-fn-version-id-field-uuid
  #uuid "b808d532-df88-41fe-adeb-23f51c787a73")


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extends a builder with execution-history entities. Must be chained
   AFTER `versioned.schema/extend-builder` so the `:fn-version` entity
   is registered when this module's `:ref :ref-entity :fn-version`
   fields are validated."
  [builder]
  (-> builder
      ;; -----------------------------------------------------------------
      ;; :execution-status enum
      ;; -----------------------------------------------------------------
      (ds/add-enum :execution-status
                   execution-status-enum-uuid
                   (execution-status-enum-values))

      ;; -----------------------------------------------------------------
      ;; :fn-execution — one row per `(future-submitted, fn-version)`
      ;; event. Status flips :pending → :succeeded | :failed |
      ;; :cancelled; result/error/finished-at fill in once the future
      ;; resolves. Pure fast fns whose POST didn't ask for persistence
      ;; never get a row (inline result returned to caller); see
      ;; `crud/fn-execution.clj` for the auto-persist matrix.
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-execution fn-execution-entity-uuid
                     {;; Frozen snapshot of which fn-version ran. The
                      ;; logical base fn-id derives via JOIN through
                      ;; :fn-version (audit-correct: editing the fn
                      ;; later doesn't rewrite the historical event).
                      :fn-version-id {:uuid fn-execution-fn-version-id-field-uuid
                                      :type :ref
                                      :ref-entity :fn-version}
                      :started-at {:uuid fn-execution-started-at-field-uuid
                                   :type :timestamptz}
                      ;; Nullable until status ≠ :pending. duration =
                      ;; finished-at − started-at is computed at
                      ;; read-time (single source of truth in the
                      ;; timestamp pair).
                      :finished-at {:uuid fn-execution-finished-at-field-uuid
                                    :type :timestamptz
                                    :nullable? true}
                      :status {:uuid fn-execution-status-field-uuid
                               :type :enum
                               :enum-name :execution-status}
                      ;; Result body, jsonb. Cap (5 MB) enforced in
                      ;; backend write-path: oversize → store nil here
                      ;; and set :result-truncated? true.
                      :result {:uuid fn-execution-result-field-uuid
                               :type :jsonb
                               :nullable? true}
                      :result-truncated? {:uuid fn-execution-result-truncated-field-uuid
                                          :type :bool
                                          :nullable? true}
                      ;; Exception message + ex-data. Both truncated
                      ;; in backend write-path (4 KB / 64 KB).
                      :error {:uuid fn-execution-error-field-uuid
                              :type :text
                              :nullable? true}
                      :error-data {:uuid fn-execution-error-data-field-uuid
                                   :type :jsonb
                                   :nullable? true}
                      ;; Snapshot of `:effects` from rich-types
                      ;; registry at submit time — declared, not
                      ;; runtime-tracked. Stored as array on wire,
                      ;; set in Clojure.
                      :declared-effects {:uuid fn-execution-declared-effects-field-uuid
                                         :type :jsonb
                                         :nullable? true}
                      ;; Runtime-observed effect categories, captured
                      ;; by base-fn impls via `cr/record-effect!`.
                      ;; Set on terminal status alongside :result /
                      ;; :error. Comparing against :declared-effects
                      ;; surfaces drift (an impl that performs an
                      ;; un-declared effect, or vice versa).
                      :runtime-effects {:uuid fn-execution-runtime-effects-field-uuid
                                        :type :jsonb
                                        :nullable? true}
                      ;; Nullable in the current auth model (bearer
                      ;; token only, no user identity). Reserved for
                      ;; future sessions/users — no schema migration
                      ;; needed when we fill it.
                      :user-id {:uuid fn-execution-user-id-field-uuid
                                :type :uuid
                                :nullable? true}
                      ;; Soft cancel signal. The future checks
                      ;; this flag and throws InterruptedException
                      ;; on the next executor invocation step.
                      :cancel-requested? {:uuid fn-execution-cancel-requested-field-uuid
                                          :type :bool
                                          :nullable? true}
                      ;; Audit trail. True iff the
                      ;; executed fn-def's rich-type carries the
                      ;; `:secret` marker on its return OR on any
                      ;; arg slot — combined with a non-empty
                      ;; `:runtime-effects`, this row recorded a
                      ;; secret crossing into a side-effecting
                      ;; sink (the kind GitHub Actions audits via
                      ;; mask logs). Read-side filter for the
                      ;; future Secret-flows history tab.
                      :touched-secret? {:uuid fn-execution-touched-secret-field-uuid
                                        :type :bool
                                        :nullable? true}
                      ;; Debug P1+P3 execution-path capture:
                      ;; `{:entries [{:seq :parent-seq? :fn-id
                      ;;              :cache-hit? :duration-ms
                      ;;              (:value | :value-truncated? |
                      ;;               :value-hidden)?}|
                      ;;             {:seq :parent-seq? :fn-id :hidden}]
                      ;;   :path-truncated? :values-dropped?}`.
                      ;; `:seq` numbers frames in ENTRY order and
                      ;; `:parent-seq` links to the forcing frame, so
                      ;; the completion-ordered vector reassembles into
                      ;; the call tree. `:hidden` is `:secret` or the
                      ;; fail-closed `:unknown-type`; `:value-hidden
                      ;; :secret-derived` marks a consumer of a hidden
                      ;; frame's output. Snapshotted on terminal status
                      ;; from the `*path-trace*` atom (opt-in `trace?`
                      ;; submits only); byte-capped (256 KB) with
                      ;; oldest-first truncation — the marker lives
                      ;; INSIDE the json, no extra column; re-redacted
                      ;; through the CURRENT registry on every read
                      ;; (persist/re-redact-path-trace). `:fn-execution`
                      ;; is non-versioned, so this is a plain column.
                      :path-trace {:uuid fn-execution-path-trace-field-uuid
                                   :type :jsonb
                                   :nullable? true}
                      ;; Tenant owner (§4 org-scoped executions). NULL ≡ public.
                      :org-id {:uuid fn-execution-org-id-field-uuid
                               :type :text
                               :nullable? true}
                      ;; Branch whose ExecutionContext ran this (errors-panel
                      ;; visibility scope). Same shape as :service/:branch-id;
                      ;; :ref carries no DB-level FK, so a later branch delete
                      ;; leaves a harmless dangling id on an audit row.
                      :branch-id {:uuid fn-execution-branch-id-field-uuid
                                  :type :ref
                                  :ref-entity :branch
                                  :nullable? true}
                      ;; Dismissed from the Errors panel at this instant.
                      ;; NULL ≡ not acknowledged.
                      :acknowledged-at {:uuid fn-execution-acknowledged-at-field-uuid
                                        :type :timestamptz
                                        :nullable? true}
                      ;; The trace this run belongs to (= the top-level
                      ;; execution's id) and the execution that called INTO
                      ;; this one over the wire (a `:service-get` from
                      ;; another service). nil ≡ a top-level run / untraced.
                      :trace-id {:uuid fn-execution-trace-id-field-uuid
                                 :type :uuid
                                 :nullable? true
                                 :indexed? true}
                      :parent-execution-id {:uuid fn-execution-parent-execution-id-field-uuid
                                            :type :uuid
                                            :nullable? true
                                            :indexed? true}})

      ;; -----------------------------------------------------------------
      ;; :fn-execution-arg — one row per free-arg the executor was
      ;; given. Mirrors `:binding` shape (value xor ref-fn-version-id)
      ;; so executions and bindings parse-and-render through the same
      ;; helper code. The slot-id ties back to the fn-version's free
      ;; slot.
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-execution-arg fn-execution-arg-entity-uuid
                     {:execution-id {:uuid fn-execution-arg-execution-id-field-uuid
                                     :type :ref
                                     :ref-entity :fn-execution}
                      :slot-id {:uuid fn-execution-arg-slot-id-field-uuid
                                :type :ref
                                :ref-entity :slot}
                      ;; XOR with ref-fn-version-id, enforced in
                      ;; backend write-path (declarative schema
                      ;; protocol exposes :unique only, not CHECK).
                      :value {:uuid fn-execution-arg-value-field-uuid
                              :type :jsonb
                              :nullable? true}
                      :ref-fn-version-id {:uuid fn-execution-arg-ref-fn-version-id-field-uuid
                                          :type :ref
                                          :ref-entity :fn-version
                                          :nullable? true}})
      ;; A given execution can bind a slot only once — same uniqueness
      ;; the binding model enforces on `(fn-id, slot-id)`.
      (ds/add-constraint :fn-execution-arg
                         {:type :unique :fields [:execution-id :slot-id]})

      ;; -----------------------------------------------------------------
      ;; :fn-execution-arg-item — sequence content under a list-typed
      ;; arg. Mirrors `:binding-list-item`. Order by `:position`; same
      ;; XOR rule on (value, ref-fn-version-id).
      ;; -----------------------------------------------------------------
      (ds/add-entity :fn-execution-arg-item fn-execution-arg-item-entity-uuid
                     {:execution-arg-id {:uuid fn-execution-arg-item-execution-arg-id-field-uuid
                                         :type :ref
                                         :ref-entity :fn-execution-arg}
                      :position {:uuid fn-execution-arg-item-position-field-uuid
                                 :type :int}
                      :value {:uuid fn-execution-arg-item-value-field-uuid
                              :type :jsonb
                              :nullable? true}
                      :ref-fn-version-id {:uuid fn-execution-arg-item-ref-fn-version-id-field-uuid
                                          :type :ref
                                          :ref-entity :fn-version
                                          :nullable? true}})
      (ds/add-constraint :fn-execution-arg-item
                         {:type :unique :fields [:execution-arg-id :position]})))
