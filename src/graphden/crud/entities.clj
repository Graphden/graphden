(ns graphden.crud.entities
  "Heavy CRUD logic for the web/crud base functions — the bodies
   behind the generic `create/update/delete-entity`, the form
   parsers, the `process-*` request dispatchers, the sequence-arg
   operations, the fn-type / effects tightening flow, and the
   graph dump / single-entity query.

   Top of the crud.* DAG: may require every other `graphden.crud.*`
   namespace. It does NOT — and must not — depend on any
   `graphden.packages.*` package: the rendering code that does stays
   in `web/crud/impls.clj`."
  (:require
    [cheshire.core :as json]
    [clojure.set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as secret-shape]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.validation :as validation]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
    [graphden.versioning.branch-local :as branch-local]))


;; === Affected-fn-id derivation for delta invalidation =======================
;;
;; Every CRUD mutation either lands ON a fn-row (e.g. `:fn` create), TOUCHES a
;; specific fn-row's closure (e.g. binding / fn-slot / binding-list-item under
;; some owner fn), OR cuts across many fns (e.g. `:slot` rename — slots are
;; shared; `:ns` rename — namespaces don't affect closures at all). For the
;; first two cases we can name the affected seed and let
;; `compile-runtime/delta-recompile!` walk the reverse-deps index to recompile
;; just the blast radius. For the cross-cutting cases we hand back nil and the
;; 1-arity `invalidate-graph-cache!` falls through to a full rebuild.
;;
;; The seed is just the directly-mutated fn-id (or, for binding-list-item, the
;; fn that OWNS the parent binding). Descendants are picked up by the
;; reverse-deps walk in `delta-recompile!` — no need to expand them here.

(defn affected-fn-ids
  "Returns the seed set of fn-ids whose closure may be invalidated by a
   write to `entity-type` carrying `entity-data`. nil ⇒ caller must
   invoke 1-arity `invalidate-graph-cache!` (full clear)."
  [storage entity-type entity-data]
  (case entity-type
    :fn
    (when-let [id (:id entity-data)] #{id})

    (:fn-slot :binding)
    (when-let [fid (:fn-id entity-data)] #{fid})

    :binding-list-item
    ;; Items live under a binding; the binding's fn-id is the owner.
    ;; On delete we pre-read the item before the row is gone so
    ;; `entity-data` carries `:binding-id`; on create the caller
    ;; supplies it directly.
    (when-let [bid (:binding-id entity-data)]
      (some-> (sp/read-entity storage :binding bid) :fn-id hash-set))

    ;; :slot is shared across many fns; :ns doesn't touch closures —
    ;; both fall through to a full clear.
    nil))


(defn invalidate!
  "Convenience wrapper: derive the affected fn-id seeds and call
   `invalidate-graph-cache!` with the right arity. Pass `entity-data`
   that already includes `:id` (so :fn deletes pre-read the row,
   binding-list-item deletes pre-read the item).

   `:fn` writes also drop the per-storage branch-local cache (in
   `graphden.versioning.branch-local`) — `:parent-ids` and
   `:branch-local?` changes can both shift the effective set, and
   the cache key is the storage handle so it lives below the
   graph-cache layer.

   Also kicks `recon/restart-services-depending-on!` against the
   affected fn-id seeds so cron-loop services whose closure was
   captured before the edit get restarted. HTTP services re-read
   their compiled-registry lazily on the next request and don't
   need the hint; cron loops sit in closed-over fn-graphs and
   would otherwise fire the pre-edit code forever. Best-effort —
   the restart is observability-grade; a failure during it
   doesn't fail the user's CRUD call. No-op when the reconciler
   singleton isn't wired (tests, REPL eval)."
  [ctx storage entity-type entity-data]
  (when (= entity-type :fn)
    ;; Cache lives below the VersionedStorage wrapper and is keyed by
    ;; the BASE storage handle; unwrap before invalidating.
    (let [base (or (:base-storage storage) storage)]
      (branch-local/invalidate! base)))
  (let [seeds (affected-fn-ids storage entity-type entity-data)]
    (if seeds
      (exec-ctx/invalidate-graph-cache! ctx seeds)
      (exec-ctx/invalidate-graph-cache! ctx))
    (when (seq seeds)
      (try
        ;; `recon/running` is a process-wide defonce atom — the same
        ;; one the integrant init wired up.
        (recon/restart-services-depending-on! ctx recon/running seeds)
        (catch Exception e
          (log/warn e
                    "post-edit service restart hook failed"
                    {:entity-type entity-type :seeds seeds}))))))


(def ^:private fn-graph-entity-types
  "Entity types whose mutations invalidate compiled fn-graphs on
   every pod. `:ns` is OUT — namespace renames don't touch fn
   closures (the editor only displays them differently)."
  #{:fn :slot :fn-slot :binding :binding-list-item})


(defn notify-after-write!
  "Fire NOTIFY events on `graphden_events` so sibling pods react to
   the mutation:

   - `:service` writes → `service:<op>:<id>` — handled by the
     reconciler's listener callback in `system/core.clj`.
   - fn-graph writes (`:fn` / `:slot` / `:fn-slot` / `:binding` /
     `:binding-list-item`) → one `fn:invalidate:<seed-fn-id>` event
     per affected fn-id (delta invalidation), or
     `fn:invalidate:` with empty id when the change is cross-cutting
     (full clear) — handled by `:exec/compiled-registry`'s listener
     callback.

   Cheap on the write path: one `pg_notify` SQL against the main
   pool, per emitted event. Becomes a no-op when the ctx has no
   `:notify-emitter` (tests without PG)."
  [ctx storage entity-type op data]
  (when-let [emit (:notify-emitter ctx)]
    (cond
      (and (= entity-type :service) (:id data))
      (emit {:kind :service :op op :id (str (:id data))})

      (contains? fn-graph-entity-types entity-type)
      (let [seeds (affected-fn-ids storage entity-type data)]
        (if (seq seeds)
          (doseq [seed seeds]
            (emit {:kind :fn :op :invalidate :id (str seed)}))
          ;; nil seeds from `affected-fn-ids` ≡ "full clear" —
          ;; mirror the local fallback `(invalidate-graph-cache!
          ;; ctx)` (no seed set).
          (emit {:kind :fn :op :invalidate :id ""}))))))


(defn- html-error-response
  "Wrap `reason` in a Ring response with the canonical
   `<p class=\"error\">…</p>` body the editor's CSS expects. Centralises
   what was eight near-identical literal builders scattered across the
   create/update/delete + sequence apply branches. The
   `Content-Type` header is set explicitly so the response is correct
   regardless of whatever an upstream wrapper decides."
  [status reason]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<p class=\"error\">" reason "</p>")})


;; === Context-aware Query Functions ===

(defn list-entities
  [entity-type where ctx]
  (vec (sp/query-entities (request/require-storage ctx)
                          (keyword entity-type) (or where {}))))


(defn get-entity
  [entity-type id ctx]
  (sp/read-entity (request/require-storage ctx) (keyword entity-type) id))


(defn- secret-leaf-capability-rej
  "Refuse :fn creates whose parent-ids touches ANY admin-only vault
   base-fn (declared via `:tags #{:admin-only-vault}` in
   `web/vault/fns.edn`; see `secret-shape/find-admin-only-vault-base-fn-ids`),
   UNLESS the data carries `:_admin-secret-create true`. The admin
   path (`crud.secrets/create-secret`) sets the marker and strips
   it before calling the storage layer; any other path (the
   generic `/api/entities/fn` endpoint, ad-hoc API clients, etc.)
   reaches this gate WITHOUT the marker and gets bounced through
   `/api/secrets`.

   Covers `:secret-leaf`, `:vault-put`, `:vault-delete`,
   `:vault-metadata-put`. The three write-side bases are admin-side
   operations that mutate OpenBao state; user fn-defs that compose
   them would bypass the audited `/api/secrets` flow.
   `:vault-metadata-get` (read-only) is NOT gated — metadata isn't
   a secret value.

   Returns a rejection map or nil. Mirrors the shape `write-rej`
   returns so the existing error-throw branch handles both."
  [storage data]
  (let [gated-ids (secret-shape/find-admin-only-vault-base-fn-ids storage)
        parents-set (set (:parent-ids data))]
    (when (seq (clojure.set/intersection gated-ids parents-set))
      (when-not (:_admin-secret-create data)
        {:type :capability/secret-leaf-restricted
         :reason "fn-defs with parent on an admin-only vault base-fn (:secret-leaf / :vault-put / :vault-delete / :vault-metadata-put) can only be created via POST /api/secrets — the admin path that also writes the value to OpenBao. Use the Secrets sidebar panel in the editor, or call /api/secrets directly."}))))


(defn create-entity
  [entity-type data ctx]
  (let [storage (request/require-storage ctx)
        et (keyword entity-type)
        ;; For :fn create the row may not have an `:id` yet; the
        ;; cycle check still wants it (parent / FK targets need to
        ;; know who's "owner"). Synthesize one so the check sees a
        ;; stable owner — `sp/create-entity` honours a pre-supplied
        ;; `:id` so the synthesized value is what lands in storage.
        ;; `:binding` :value-present normalisation lives in
        ;; `storage/protocol/core/standard-crud-normalize-data`
        ;; (called from every postgres CRUD entry) so direct
        ;; `sp/create-entity` users (tests, sync) pick it up too.
        data' (cond-> data
                (and (= et :fn) (nil? (:id data))) (assoc :id (random-uuid)))]
    ;; Capability gate: secret-shaped fn-defs are admin-only — see
    ;; `secret-leaf-capability-rej` for the rationale. The marker is
    ;; an in-memory contract between `crud.secrets` and this fn; it
    ;; never reaches storage.
    (when (= et :fn)
      (when-let [rej (secret-leaf-capability-rej storage data')]
        (throw (ex-info (:reason rej)
                        {:type (:type rej)
                         :entity-type et
                         :data (dissoc data' :_admin-secret-create)}))))
    (when-let [rej (validation/write-rej storage et data')]
      (throw (ex-info (:reason rej)
                      {:type (:type rej)
                       :entity-type et :data data'})))
    (let [result (sp/create-entity storage et (dissoc data' :_admin-secret-create))]
      (invalidate! ctx storage et result)
      (notify-after-write! ctx storage et :write result)
      result)))


(defn update-entity
  [entity-type id data ctx]
  (let [storage (request/require-storage ctx)
        et (keyword entity-type)
        check-data (assoc data :id id)]
    (when-let [rej (validation/write-rej storage et check-data)]
      (throw (ex-info (:reason rej)
                      {:type (:type rej)
                       :entity-type et :id id :data data})))
    (let [result (sp/update-entity storage et id data)]
      (invalidate! ctx storage et result)
      (notify-after-write! ctx storage et :write (assoc result :id id))
      result)))


(defn delete-entity
  [entity-type id ctx]
  (let [storage (request/require-storage ctx)
        et (keyword entity-type)
        ;; Pre-read so we know the parent fn-id for binding /
        ;; fn-slot / binding-list-item before the row is gone.
        ;; For :fn the row's :id IS the seed; we synthesize one
        ;; rather than pay the read.
        snapshot (if (= et :fn)
                   {:id id}
                   (sp/read-entity storage et id))]
    (sp/delete-entity storage et id)
    (invalidate! ctx storage et snapshot)
    (notify-after-write! ctx storage et :delete {:id id})
    true))


(defn list-all-graph-entities
  [ctx]
  ;; Slot/fn-slot/binding model: dump every storage row the editor
  ;; needs to render the graph. Routes through the shared graph-cache
  ;; (populated by layout / compile-runtime) so editor refreshes after
  ;; mutations don't re-query the same five tables every time.
  ;;
  ;; Each fn-row is augmented with a `:role` field so the sidebar can
  ;; group entries into Types vs Functions sections without an extra
  ;; round-trip through `/api/types`.
  (let [storage (request/require-storage ctx)
        base (types-api/cached-or-load-graph ctx)
        fn-slots-by-fn (group-by :fn-id (:fn-slots base))
        rich-snapshot (registry/rich-types-snapshot)
        roled-fns (mapv (fn [f]
                          (assoc f :role
                                 (types-api/compute-fn-role
                                   f
                                   (boolean (seq (get fn-slots-by-fn (:id f))))
                                   rich-snapshot)))
                        (:fns base))]
    (-> base
        (assoc :fns roled-fns)
        (assoc :namespaces (vec (sp/query-entities storage :ns {}))))))


;; === Compound type-row create / update ======================================

(defn parse-create-record-type
  "Stage 1 of create-record-type — JSON body → `{:name :ns-id
   :description :fields}`."
  [request]
  (let [body (request/read-json-body request)
        ns-raw (:namespace-id body)]
    {:name (some-> (:name body) str)
     :ns-id (when-not (str/blank? (str ns-raw))
              (request/parse-uuid-or-clear (str ns-raw)))
     :description (:description body)
     :fields (vec (:fields body))}))


;; C19: stage 2 of create-record-type was here as
;; `validate-create-record-type`. Removed — replaced by the
;; `:_create-record-type-validation` `:cond` graph fn-def in
;; `web/crud/fns.edn` (predicates + error consts). Test-side
;; analogue lives in `entities_test/_validate-create-record-type-inline`.


(defn- create-record-type-fn-row!
  "Phase 1 of create-record-type — root `:fn` row + journal entry.
   Returns the new fn-id."
  [storage journal nm ns-id desc]
  (let [own-id (java.util.UUID/randomUUID)]
    (sp/create-entity storage :fn
                      (cond-> {:id own-id
                               :name nm
                               :namespace-id ns-id
                               :parent-ids []
                               :impl-hash nil
                               :base-fn-id nil
                               :element-fn-id nil
                               :return-type-fn-id nil
                               :anonymous-hash nil
                               :constraint nil}
                        (and desc (seq desc)) (assoc :description desc)))
    (swap! journal conj [:fn own-id])
    own-id))


(defn- create-record-type-fields!
  "Phase 2 of create-record-type — mint a `:slot` + `:fn-slot`
   junction per field, journalling each create for the rollback
   callable. Resolves each field's `:type` to a type-fn-id, throws
   `:type-row/field-missing-name` if any field lacks a name."
  [storage journal own-id fields]
  (doseq [[idx field] (map-indexed vector fields)]
    (let [field-name (some-> (:name field) str)
          type-id (tc/resolve-type-fn-id-or-throw storage (:type field))
          field-desc (:description field)
          required? (if (contains? field :required) (boolean (:required field)) true)
          slot-id (java.util.UUID/randomUUID)
          fn-slot-id (java.util.UUID/randomUUID)]
      (when (str/blank? field-name)
        (throw (ex-info "field name required"
                        {:type :type-row/field-missing-name})))
      (sp/create-entity storage :slot
                        (cond-> {:id slot-id
                                 :name field-name
                                 :type-fn-id type-id
                                 :required required?}
                          (and field-desc (seq field-desc))
                          (assoc :description field-desc)))
      (swap! journal conj [:slot slot-id])
      (sp/create-entity storage :fn-slot
                        {:id fn-slot-id
                         :fn-id own-id
                         :slot-id slot-id
                         :position idx})
      (swap! journal conj [:fn-slot fn-slot-id]))))


(defn apply-create-record-type-body
  "Phases 1-3 of create-record-type's atomic write — fn-row + N
   slot-rows + N fn-slot junctions + cache-invalidate. Mutates
   `journal` (shared atom-of-vector) on each successful storage
   write so the rollback callable can replay in reverse. Throws on
   any storage / type-resolve failure — caught by the surrounding
   `:try` graph node. Reached only after `:_create-record-type-
   validation` passed.

   Body is orchestration; each phase lives in a small private
   helper so the read flows top-to-bottom by phase name."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description fields :fields} parsed
        own-id (create-record-type-fn-row! storage journal nm ns-id desc)]
    (create-record-type-fields! storage journal own-id fields)
    (invalidate! ctx storage :fn {:id own-id})
    {:ok true :id (str own-id) :name nm}))


(defn apply-create-rollback
  "`:try`'s on-throw branch for create-record-type AND create-list-type.
   Derefs `journal` and replays entries in reverse, deleting each
   row best-effort (delete failures are logged, not re-thrown — the
   important contract is that user state stays consistent with what
   the response says). Returns the `{:ok false :error :data}` shape."
  [journal exception ctx]
  (let [storage (request/require-storage ctx)]
    (doseq [[et id] (reverse @journal)]
      (try (sp/delete-entity storage et id)
           (catch Exception e
             (log/warn e "Rollback delete-entity failed for"
                       et id "— manual cleanup may be required")))))
  ;; `Throwable/.getMessage` can return null (Java API contract); wrap
  ;; both branches in `str` so the `:error` field is always a string.
  ;; Earlier the ExceptionInfo arm returned raw `.getMessage` and the
  ;; fallback arm wrapped in `str` — a serialised response would
  ;; carry a JSON-`null` only on the ExceptionInfo arm.
  (let [msg (str (Throwable/.getMessage ^Throwable exception))]
    (cond-> {:ok false :error msg}
      (instance? clojure.lang.ExceptionInfo exception)
      (assoc :data (ex-data exception)))))


(defn apply-create-record-type
  "Stage 3 of create-record-type — wraps the `apply-create-record-type-body`
   (atomic write unit) in a try-catch + rollback that mirrors what the
   graph `:try` would do. Survives for non-graph callers; new paths
   go through the graph fn-def `:_create-record-type-apply`."
  [parsed ctx]
  (let [journal (atom [])]
    (try (apply-create-record-type-body parsed journal ctx)
         (catch Exception e
           (apply-create-rollback journal e ctx)))))


;; `parse-create-list-type` removed — the parse stage is now a graph
;; fn-def (`:_create-list-type-parsed`) composing `:parse-json-body`
;; + per-field getters. C20: `validate-create-list-type` similarly
;; replaced by the `:_create-list-type-validation` `:cond`. Only the
;; rollback-bearing apply stage remains in Clojure (`apply-create-list-type`).


(defn apply-create-list-type-body
  "Phases 1-3 of create-list-type's atomic write — fn-row with
   `:element-fn-id` + synthesised `items` slot + fn-slot junction +
   cache-invalidate. Mutates `journal` (shared atom) for rollback.
   Throws on storage / type-resolve failure — caught by the
   surrounding `:try` graph node."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description element-ref :element-ref} parsed
        own-id (java.util.UUID/randomUUID)
        elem-id (tc/resolve-type-fn-id-or-throw storage element-ref)
        seq-id (tc/resolve-type-fn-id-or-throw storage "sequence")
        slot-id (java.util.UUID/randomUUID)
        fn-slot-id (java.util.UUID/randomUUID)]
    (sp/create-entity storage :fn
                      (cond-> {:id own-id
                               :name nm
                               :namespace-id ns-id
                               :parent-ids []
                               :impl-hash nil
                               :base-fn-id nil
                               :element-fn-id elem-id
                               :return-type-fn-id nil
                               :anonymous-hash nil
                               :constraint nil}
                        (and desc (seq desc)) (assoc :description desc)))
    (swap! journal conj [:fn own-id])
    (sp/create-entity storage :slot
                      {:id slot-id
                       :name "items"
                       :type-fn-id seq-id
                       :required true})
    (swap! journal conj [:slot slot-id])
    (sp/create-entity storage :fn-slot
                      {:id fn-slot-id
                       :fn-id own-id
                       :slot-id slot-id
                       :position 0})
    (swap! journal conj [:fn-slot fn-slot-id])
    (invalidate! ctx storage :fn {:id own-id})
    {:ok true :id (str own-id) :name nm}))


(defn apply-create-list-type
  "Stage 3 of create-list-type — wraps `apply-create-list-type-body`
   in the same try/catch + journal-replay rollback the graph
   `:try` would do. Survives for non-graph callers; new paths go
   through `:_create-list-type-apply`."
  [parsed ctx]
  (let [journal (atom [])]
    (try (apply-create-list-type-body parsed journal ctx)
         (catch Exception e
           (apply-create-rollback journal e ctx)))))


;; `parse-update-record-type` removed — the parse stage is now a graph
;; fn-def composing `:parse-json-body` + per-field getters + `:contains?`
;; on `:description` for the `:has-description?` distinction.
;; C21: `validate-update-record-type` similarly replaced by the
;; `:_update-record-type-validation` `:cond`. Only the rollback-bearing
;; apply stage remains in Clojure (`apply-update-record-type`).


;; === Stage-3 update-record-type apply: journalled txn split for graph ===
;;
;; The 141-line monolith was decomposed so the journalled-write pattern
;; is visible at the graph level: `:_update-record-type-apply` is now a
;; `:try` (core.system) whose body runs phases 2-5 + invalidate + success
;; and whose `on-throw` reads the shared `:atom` journal and replays it
;; in reverse. The atom is a single `:_apply-update-record-type-journal`
;; fn-def referenced from both branches at the `:try`-call's cache
;; level, so body and rollback see the SAME instance.
;;
;; The phases themselves stay as ONE Clojure helper each — per-iteration
;; storage writes are still iteration (not composition), and the inner
;; per-field reuse/mint decision is tightly coupled to the journal.
;; Splitting further would scatter one conceptual operation into N
;; atom-threading hops with no semantic gain (skill graphden-fn-refactor
;; §3 §1). The win here is the OUTER shape — try / journal / rollback —
;; not lower-level per-iteration atomisation.

(defn- load-update-record-type-state
  "Pre-update snapshot the diff-and-apply needs: the current fn-row,
   its fn-slots, the underlying slot-rows, and the `[name type-fn-id]
   → fn-slot` index that drives the reuse-vs-mint decision."
  [storage fn-id]
  (let [existing-fn (first (sp/query-entities storage :fn {:id fn-id}))
        current-fss (sp/query-entities storage :fn-slot {:fn-id fn-id})
        current-slot-ids (mapv :slot-id current-fss)
        current-slots (when (seq current-slot-ids)
                        (sp/query-entities storage :slot
                                           {:id current-slot-ids}))
        slots-by-id (into {} (map (juxt :id identity)) (or current-slots []))
        ;; Match by (name, type-fn-id): retypes must yield a new
        ;; slot since slot rows are immutable.
        slots-by-name+type (into {}
                                 (map (fn [fs]
                                        (let [s (get slots-by-id (:slot-id fs))]
                                          [[(:name s) (:type-fn-id s)] fs])))
                                 current-fss)]
    {:existing-fn existing-fn
     :current-fss current-fss
     :slots-by-name+type slots-by-name+type}))


(defn- resolve-update-fields
  "Resolve every incoming field's type up front so a typo doesn't
   leave the row half-rewritten — all storage writes wait until we
   have the full resolved vector. Throws `:type-row/field-missing-
   name` on a blank name and propagates whatever
   `resolve-type-fn-id-or-throw` raises on a bad type ref."
  [storage fields]
  (mapv (fn [field]
          (let [field-name (some-> (:name field) str)
                _ (when (str/blank? field-name)
                    (throw (ex-info "field name required"
                                    {:type :type-row/field-missing-name})))
                type-id (tc/resolve-type-fn-id-or-throw storage (:type field))
                required? (if (contains? field :required)
                            (boolean (:required field)) true)]
            {:name field-name
             :type-fn-id type-id
             :description (:description field)
             :required required?}))
        fields))


(defn- compute-slot-assignments
  "Decide per-field whether to reuse a current slot (same name + type)
   or mint a fresh one. `:reuse?` flag drives every downstream phase;
   reused entries carry the existing slot-id / fn-slot-id, new ones
   carry pre-allocated UUIDs and the resolved `:spec`."
  [resolved slots-by-name+type]
  (mapv (fn [r]
          (if-let [fs (get slots-by-name+type
                           [(:name r) (:type-fn-id r)])]
            {:slot-id (:slot-id fs)
             :fn-slot-id (:id fs)
             :reuse? true}
            {:slot-id (java.util.UUID/randomUUID)
             :fn-slot-id (java.util.UUID/randomUUID)
             :reuse? false
             :spec r}))
        resolved))


(defn- create-new-slots!
  "Phase 2: insert slot rows for fields the diff classified as new.
   Records each insert in `journal` for the rollback path."
  [storage journal assignments]
  (doseq [a assignments
          :when (not (:reuse? a))]
    (let [{:keys [slot-id spec]} a]
      (sp/create-entity storage :slot
                        (cond-> {:id slot-id
                                 :name (:name spec)
                                 :type-fn-id (:type-fn-id spec)
                                 :required (:required spec)}
                          (and (:description spec) (seq (:description spec)))
                          (assoc :description (:description spec))))
      (swap! journal conj {:op :create :entity-type :slot :id slot-id}))))


(defn- delete-unused-fn-slots!
  "Phase 3: drop every current fn-slot whose id isn't in `kept-fs-ids`.
   Journals the full row so the rollback path can resurrect it."
  [storage journal current-fss kept-fs-ids]
  (doseq [fs current-fss
          :when (not (kept-fs-ids (:id fs)))]
    (sp/delete-entity storage :fn-slot (:id fs))
    (swap! journal conj {:op :delete :entity-type :fn-slot :row fs})))


(defn- rewire-fn-slot-positions!
  "Phase 4: walk `assignments` in target-position order — reused
   entries get a delete+re-create with the new position when they
   moved (UNIQUE constraint on `(fn-id, position)` means we can't
   bump in-place); new entries get a fresh fn-slot insert. Each leg
   is journalled so a downstream failure can fully rewind."
  [storage journal fn-id current-fss assignments]
  (doseq [[idx a] (map-indexed vector assignments)]
    (cond
      (:reuse? a)
      (let [old-fs (first (filter #(= (:id %) (:fn-slot-id a)) current-fss))]
        (when (and old-fs (not= (:position old-fs) idx))
          (sp/delete-entity storage :fn-slot (:id old-fs))
          (swap! journal conj {:op :delete :entity-type :fn-slot :row old-fs})
          (let [new-row (assoc old-fs :position idx)]
            (sp/create-entity storage :fn-slot new-row)
            (swap! journal conj {:op :create :entity-type :fn-slot
                                 :id (:id new-row)}))))

      :else
      (do
        (sp/create-entity storage :fn-slot
                          {:id (:fn-slot-id a)
                           :fn-id fn-id
                           :slot-id (:slot-id a)
                           :position idx})
        (swap! journal conj {:op :create :entity-type :fn-slot
                             :id (:fn-slot-id a)})))))


(defn- apply-fn-row-patch!
  "Phase 5: optional rename / re-description of the fn-row itself.
   No-op when neither field changes. The patch isn't journalled — a
   throw downstream would already have left earlier phases for the
   rollback path to reverse."
  [storage existing-fn fn-id nm has-description? desc]
  (when (or (and nm (not= nm (:name existing-fn)))
            has-description?)
    (let [patch (cond-> {}
                  (and nm (not= nm (:name existing-fn)))
                  (assoc :name nm)
                  has-description?
                  (assoc :description desc))]
      (sp/update-entity storage :fn fn-id patch))))


(defn apply-update-record-type-body
  "Body of `:_update-record-type-apply`'s `:try`. Performs phases 2-5
   of the diff-and-apply (create new slots / delete unused fn-slots /
   rewire positions / optional rename), appending rollback hints to
   `journal` along the way, then invalidates caches and returns the
   success response. Throws on any storage failure or bad-type-resolve
   — caught by `:try`, which hands control to `-rollback`.

   Body itself is the orchestration; each phase lives in a small
   `apply-update-record-type-*` private helper so the read flows
   top-to-bottom by phase name instead of by line range."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {fn-id :fn-id nm :name desc :description fields :fields
         has-description? :has-description?} parsed
        {:keys [existing-fn current-fss slots-by-name+type]}
        (load-update-record-type-state storage fn-id)
        resolved (resolve-update-fields storage fields)
        assignments (compute-slot-assignments resolved slots-by-name+type)
        kept-fs-ids (into #{} (comp (filter :reuse?) (map :fn-slot-id))
                          assignments)]
    (create-new-slots! storage journal assignments)
    (delete-unused-fn-slots! storage journal current-fss kept-fs-ids)
    (rewire-fn-slot-positions! storage journal fn-id current-fss assignments)
    (apply-fn-row-patch! storage existing-fn fn-id nm has-description? desc)
    ;; The compound write happened through `sp/*-entity` —
    ;; bypassing the defbase wrappers that normally call
    ;; `invalidate!`. Without this nudge the next read of
    ;; `/api/graph/entities` would return the cached pre-
    ;; update graph and the editor would see no change.
    (invalidate! ctx storage :fn-slot {:fn-id fn-id})
    {:ok true :id (str fn-id) :name (or nm (:name existing-fn))}))


(defn apply-update-record-type-rollback
  "Called by `:try`'s `:on-throw` when the body throws. Reads the
   journal atom + replays its entries in reverse: a recorded `:create`
   becomes a delete, a recorded `:delete` becomes a create. Each
   replay step is wrapped in its own try/swallow so one stuck reversal
   doesn't block the rest. Returns the partial Ring response carrying
   the original exception's message + ex-data."
  [journal exception ctx]
  (let [storage (request/require-storage ctx)]
    (doseq [entry (reverse (deref journal))]
      (try
        (case (:op entry)
          :create (sp/delete-entity storage (:entity-type entry) (:id entry))
          :delete (sp/create-entity storage (:entity-type entry) (:row entry))
          nil)
        (catch Exception e
          (log/warn e "Journalled-rollback step failed:" (:op entry)
                    (:entity-type entry) (or (:id entry) (:row entry))
                    "— manual cleanup may be required"))))
    (cond-> {:ok false :error (str (Throwable/.getMessage exception))}
      (instance? clojure.lang.ExceptionInfo exception)
      (assoc :data (ex-data exception)))))


(defn apply-update-record-type
  "Test / back-compat wrapper that re-assembles the `:try` + atom-journal
   shape in Clojure. The live runtime path runs through
   `:_update-record-type-apply` — a `:try` graph fn-def composing `-body`
   + `-rollback` over a shared `:_apply-update-record-type-journal`
   atom; this fn replays the same shape so direct Clojure callers
   (parse → validate → apply test harness in entities_test) keep working.
   Use the graph path for production; this exists for tests + Clojure-
   side composability."
  [parsed ctx]
  (let [journal (atom [])]
    (try
      (apply-update-record-type-body parsed journal ctx)
      (catch Exception e
        (apply-update-record-type-rollback journal e ctx)))))


;; === Form Parsing ===
;;
;; All parse-*-from-form impls are permissive — fields are only
;; assoc'd when the key is actually present in the form. That way
;; both create (full form) and update (partial form, e.g.
;; description-only) flow through the same code without partial
;; updates blanking the unsent fields. Empty strings are kept (so
;; a submitted-empty `description=` clears the field rather than
;; leaving the old value).

(defn ensure-rename-slot!
  "Phase 6b — keep UI rename atomically consistent with EDN parser
   output. When a binding write carries a non-blank `:rename-to=X`
   AND the binding's owner fn is composed (parent-ids non-empty),
   the EDN parser would have ALSO emitted an own-slot row + fn-slot
   junction so descendants binding `X` find a slot identity to
   target. UI today writes only the binding row; this helper fills
   in the missing pair.

   Args: `fn-id` (binding's owner fn), `source-slot-id` (the slot
   the binding targets — becomes the new slot's :source-slot-id
   FK), `rename-to` (new name).

   Idempotent: walks the deterministic UUIDv5 scheme for slot-id
   and fn-slot-id, no-ops when the rows already exist (e.g. on
   repeat PUT). Returns nil; throws on unexpected storage failures
   so the caller can surface to the user."
  [storage fn-id source-slot-id rename-to]
  (when (and fn-id source-slot-id rename-to (not (str/blank? rename-to)))
    (let [fn-row (sp/read-entity storage :fn fn-id)
          parent-ids (:parent-ids fn-row)
          source-slot (sp/read-entity storage :slot source-slot-id)]
      (when (and (seq parent-ids) source-slot)
        (let [new-slot-id (records/slot-id fn-id rename-to)
              new-fn-slot-id (records/fn-slot-id fn-id new-slot-id)
              ;; Reuse source slot's type-fn-id so the renamed view
              ;; has the same type — UI doesn't expose type-override
              ;; in the rename popover. Type narrowing remains a
              ;; separate edit (the type chip).
              slot-row {:id new-slot-id
                        :name rename-to
                        :type-fn-id (:type-fn-id source-slot)
                        :required (or (:required source-slot) false)
                        :description nil
                        :source-slot-id source-slot-id}]
          (when-not (sp/read-entity storage :slot new-slot-id)
            (sp/create-entity storage :slot slot-row))
          (when-not (sp/read-entity storage :fn-slot new-fn-slot-id)
            (sp/create-entity storage :fn-slot
                              {:id new-fn-slot-id
                               :fn-id fn-id
                               :slot-id new-slot-id
                               :position 0})))))))


;; === Action Handlers ===

(defn chain-has-process-effect?
  "Walks the parent-ids closure of `fn-id` looking for any ancestor that
   declares `:process` in its rich-types entry. Used by guard 6 —
   composed fn-defs whose own rich-type entry is missing (e.g. failed
   sync-time type-check) can still be service-eligible if an ancestor
   declares the effect.

   Also surfaced as the `:chain-has-process-effect?` base-fn in
   `web/crud/impls.clj` so the guard composes at the graph layer.

   BFS by frontier level: one batched `:fn {:id frontier}` query per
   level instead of per-node `read-entity`. Same shape as the
   inheritance walker in `crud.validation/flag-key-on-chain?`."
  [storage fn-id]
  (loop [frontier [fn-id]
         seen #{}]
    (if (empty? frontier)
      false
      (let [rows (sp/query-entities storage :fn {:id frontier})
            has-process? (some (fn [row]
                                 (let [nm (some-> row :name name)
                                       eff (some-> (registry/rich-type-of (keyword nm))
                                                   :effects)]
                                   (contains? (or eff #{}) :process)))
                               rows)]
        (if has-process?
          true
          (let [seen' (into seen frontier)
                next-frontier (->> rows
                                   (mapcat :parent-ids)
                                   (remove nil?)
                                   (remove seen')
                                   distinct
                                   vec)]
            (recur next-frontier seen')))))))


(defn- humanise-create-exception
  "Render the user-facing form of a create-entity failure — Postgres
   unique-violation messages read like internal log lines; rewrite the
   common shape and fall back to any `:reason` carried in `ex-data` or
   the original message."
  [^Exception e entity-type entity-data type-str]
  (let [msg (or (Throwable/.getMessage e) "")
        nm (some-> entity-data :name)]
    (cond
      (and (re-find #"(?i)duplicate key" msg) nm)
      (str (name entity-type) " " (pr-str nm)
           " already exists here — pick a different name")
      (re-find #"(?i)duplicate key" msg)
      (str (name entity-type) " already exists with these fields")
      :else (or (some-> (ex-data e) :reason) msg
                (str "Failed to create " type-str)))))


(defn- try-create-or-error
  "Run `sp/create-entity` with capability gating + humanised exception
   formatting. Returns `{:created <id>}` or `{:error <human-msg>}`.

   Capability gate: secret-shaped fn-defs (parent=[:vault-get]) can
   only be created via /api/secrets — the form-driven path never
   carries the in-memory `:_admin-secret-create` marker, so any
   attempt to sneak one through /api/entities/fn bounces with a 409.
   Closes the orthogonal hole to the delete-side guard at
   `process-delete-entity`.

   Projects to `:id` — leaving the whole record in `:created` makes
   the NOTIFY emitter stringify the map and the listener's
   `UUID/fromString` throws \"UUID string too large\"."
  [storage entity-type entity-data type-str]
  (let [cap-rej (when (= entity-type :fn)
                  (secret-leaf-capability-rej storage entity-data))]
    (cond
      cap-rej {:error (:reason cap-rej)}
      :else (try
              {:created (:id (sp/create-entity storage entity-type entity-data))}
              (catch Exception e
                (log/error e "create-entity failed for"
                           entity-type entity-data)
                {:error (humanise-create-exception e entity-type entity-data type-str)})))))


(defn- forward-rename-slot!
  "Phase 6c — forward a form `:rename-to` to the dedicated renamed-view
   slot. A failure here is logged, not fatal — the binding is still
   useful without the rename slot."
  [storage form-data entity-data]
  (try (ensure-rename-slot! storage
                            (:fn-id entity-data)
                            (:slot-id entity-data)
                            (when-not (str/blank? (:rename-to form-data))
                              (str (:rename-to form-data))))
       (catch Exception e
         (log/error e "ensure-rename-slot! failed"))))


(defn- post-create-type-check-fn-id
  "Resolve the OWNING fn-id for a binding-shaped mutation so the
   post-create type-check sees the aggregate of every sibling binding."
  [storage type-str entity-data]
  (cond
    (= type-str "binding")
    (:fn-id entity-data)
    (= type-str "binding-list-item")
    (some-> (:binding-id entity-data)
            (#(sp/read-entity storage :binding %))
            :fn-id)))


(defn- verify-post-create-or-rollback!
  "Post-create whole-fn type-check for binding mutations. A binding can
   be individually valid yet break the OWNING fn-def's aggregate
   check; on failure delete the just-created row so DB state stays
   consistent. Returns the rejection map (with `:reason`) when the
   check rejects, nil when the row stays."
  [storage create-result type-str entity-data entity-type]
  (when (and (:created create-result)
             (#{"binding" "binding-list-item"} type-str))
    (when-let [fn-id (post-create-type-check-fn-id storage type-str entity-data)]
      (when-let [rej (tc/type-check-fn-after-mutation! storage fn-id)]
        ;; Roll back the just-created row when the post-write check
        ;; rejects. Silently nil'ing the rollback failure would mask
        ;; orphan rows surviving the rejection — log so dashboards see it.
        (try (sp/delete-entity storage entity-type (:created create-result))
             (catch Exception e
               (log/warn e "Rollback delete-entity failed after type-check rejection"
                         {:entity-type entity-type
                          :id (:created create-result)})))
        rej))))


(defn apply-create-core
  "§3.3 atomic core of the create-apply flow: capability gate +
   `sp/create-entity` (with unique-violation humanisation) + Phase-6c
   rename-slot side-effect + post-create whole-fn type-check +
   on-failure rollback. Returns a uniform shape:
     `{:created <id>}` on success
     `{:error <human-msg>}` on any failure path
   so the outer graph can dispatch on the shape and run invalidate /
   notify / response without re-deriving the rollback semantics.

   The §3.3 invariant — type-check + rollback see the SAME just-
   created row id — lives entirely inside this fn. Phase 4.4 is the
   place where a `:atom` + `:try` graph composition expresses the
   same invariant; here we keep the carve-out because the rollback
   is conditional on the post-check result, not on an exception."
  [{:keys [entity-type type-str form-data entity-data]} ctx]
  (let [storage (request/require-storage ctx)
        create-result (try-create-or-error storage entity-type entity-data type-str)]
    (when (and (:created create-result)
               (= type-str "binding")
               (contains? form-data :rename-to))
      (forward-rename-slot! storage form-data entity-data))
    (if-let [post-rej (verify-post-create-or-rollback!
                        storage create-result type-str entity-data entity-type)]
      {:error (:reason post-rej)}
      (if (:created create-result)
        create-result
        {:error (or (:error create-result)
                    (str "Failed to create " type-str))}))))


(defn apply-create
  "Stage 3 of create — wraps `apply-create-core` (the §3.3 transactional
   unit) with the response envelope. Returns the same partial Ring
   response shape the legacy single-fn implementation did:
     `{:status 200 :body \"<p>Entity created successfully</p>\"}` on success
     `{:status 400 :body \"<p class=\"error\">…</p>\"}` on any error.

   This wrapper survives so non-graph callers (legacy tests, internal
   helpers) keep working. New paths go through the graph fn-def
   `:_create-apply` in fns.edn which calls `:try-apply-create` then
   builds the response from the returned shape."
  [parsed ctx]
  (let [{:keys [entity-type entity-data]} parsed
        result (apply-create-core parsed ctx)
        storage (request/require-storage ctx)]
    (cond
      (:created result)
      (do (invalidate! ctx storage entity-type
                       (assoc entity-data :id (:created result)))
          (notify-after-write! ctx storage entity-type :write
                               (assoc entity-data :id (:created result)))
          {:status 200
           :body "<p>Entity created successfully</p>"})
      :else (html-error-response 400 (:error result)))))


(defn apply-update-core
  "§3.1 atomic core of the update-apply flow: `sp/update-entity` +
   Phase-6c rename-slot side-effect (binding writes only). Returns
   a uniform shape:
     `{:updated <id>}` on success
     `{:error <msg>}` on write failure
   The rename-slot failure is logged but never escalated — the
   binding row is still useful without the rename slot, matching the
   legacy behaviour."
  [{:keys [entity-type type-str id-uuid form-data entity-data]} ctx]
  (let [storage (request/require-storage ctx)
        updated (try (sp/update-entity storage entity-type id-uuid entity-data)
                     (catch Exception e
                       (log/error e "update-entity failed for"
                                  entity-type id-uuid entity-data)
                       nil))]
    (when (and updated (= type-str "binding") id-uuid
               (contains? form-data :rename-to))
      (try
        (when-let [existing (sp/read-entity storage :binding id-uuid)]
          (ensure-rename-slot! storage
                               (:fn-id existing)
                               (:slot-id existing)
                               (when-not (str/blank? (:rename-to form-data))
                                 (str (:rename-to form-data)))))
        (catch Exception e
          (log/error e "ensure-rename-slot! failed"))))
    (if updated
      {:updated id-uuid}
      {:error "Failed to update entity"})))


(defn apply-update
  "Stage 3 of update — wraps `apply-update-core` (the atomic write
   unit) with the response envelope. Returns the same partial Ring
   response shape the legacy single-fn implementation did. The graph
   path goes through `:try-apply-update` + `:_update-apply` in
   `fns.edn`; this wrapper survives for non-graph callers."
  [parsed ctx]
  (let [{:keys [entity-type id-uuid entity-data pre-existing]} parsed
        result (apply-update-core parsed ctx)
        storage (request/require-storage ctx)]
    (if (:updated result)
      (do (invalidate! ctx storage entity-type
                       (merge pre-existing entity-data {:id id-uuid}))
          (notify-after-write! ctx storage entity-type :write
                               (merge pre-existing entity-data {:id id-uuid}))
          {:status 200
           :body "<p>Entity updated successfully</p>"})
      (html-error-response 400 (:error result)))))


(defn ns-non-empty-reason
  "Returns a human-readable reason if `ns-id` still has nested
   namespaces or fns living under it; nil if empty (and therefore
   safe to delete)."
  [storage ns-id]
  (let [child-ns (count (sp/query-entities storage :ns {:parent-id ns-id}))
        child-fns (count (sp/query-entities storage :fn {:namespace-id ns-id}))]
    (when (or (pos? child-ns) (pos? child-fns))
      (str "Namespace contains "
           (when (pos? child-ns) (str child-ns " sub-namespace" (when (> child-ns 1) "s")))
           (when (and (pos? child-ns) (pos? child-fns)) " and ")
           (when (pos? child-fns) (str child-fns " graph" (when (> child-fns 1) "s")))
           " — remove the contents first."))))


(defn fn-in-use-reason
  "Returns a human-readable reason if `fn-id` is referenced by another
   fn (as a parent, via a binding's `ref-fn-id`, or via a list-item's
   `ref-fn-id`); nil if unreferenced. Slot/binding model: bindings
   replace arg-rows for ref tracking, with list-items handling
   sequence-element refs."
  [storage fn-id]
  (let [;; Reverse junction lookup — `idx_fn__parent_ids_target` hits
        ;; the indexed column. Pre-fix this loaded EVERY fn row and
        ;; filtered in-memory (O(N) on the entity table).
        parent-owners (remove #(= % fn-id)
                              (sp/query-ref-many-owners storage :fn :parent-ids fn-id))
        used-as-parent (count parent-owners)
        ref-bindings (count (sp/query-entities storage :binding {:ref-fn-id fn-id}))
        ref-items (count (sp/query-entities storage :binding-list-item {:ref-fn-id fn-id}))
        refs (+ ref-bindings ref-items)]
    (when (or (pos? used-as-parent) (pos? refs))
      (str "Graph is "
           (when (pos? used-as-parent) (str "a parent of " used-as-parent " other graph"
                                            (when (> used-as-parent 1) "s")))
           (when (and (pos? used-as-parent) (pos? refs)) " and ")
           (when (pos? refs) (str "referenced by " refs " binding" (when (> refs 1) "s")))
           " — remove the dependents first."))))


(defn delete-ns-non-empty-reason
  "C5 guard support — returns the human-readable reason string ONLY
   when `(:entity-type parsed)` is `:ns` AND that namespace still
   contains content; nil otherwise (so the predicate
   `:_delete-ns-non-empty?` can simply check `some?`, and the
   `:_delete-err-ns-non-empty` dynamic error builder can re-use the
   computed reason without re-querying)."
  [parsed ctx]
  (when (and (= :ns (:entity-type parsed)) (:id parsed))
    (ns-non-empty-reason (request/require-storage ctx) (:id parsed))))


(defn delete-fn-secret?
  "C5 guard — true when the parsed delete targets a fn-def whose
   shape is a secret (`:vault-get` legacy or `:secret-leaf`
   followup-4). The admin path
   (`DELETE /api/secrets/:fn-id`) cleans up the OpenBao value
   alongside the graphden row; deleting through this generic
   endpoint would orphan the secret in vault."
  [parsed ctx]
  (and (= :fn (:entity-type parsed)) (:id parsed)
       (let [storage (request/require-storage ctx)
             secret-leaf-id (secret-shape/find-secret-leaf-fn-id storage)]
         (secret-shape/secret-fn? (sp/read-entity storage :fn (:id parsed))
                                  secret-leaf-id))))


(defn delete-fn-in-use-reason
  "C5 guard support — returns the in-use reason string only when the
   parsed delete targets a referenced fn-def; nil otherwise. NB:
   the secret-shape guard `:_delete-fn-is-secret?` runs FIRST so a
   secret-fn that's also in use surfaces as the secret-shape 409,
   not the in-use 409."
  [parsed ctx]
  (when (and (= :fn (:entity-type parsed)) (:id parsed))
    (fn-in-use-reason (request/require-storage ctx) (:id parsed))))


(defn apply-delete-entity
  "Success branch of delete-entity — reached only after the `:cond`
   validation clauses pass. Pre-reads the row before the delete:
   - if absent → 404 (silently returning 200+`entityDeleted` on a
     nonexistent row deceived UI clients into thinking the delete
     succeeded; the row was never there);
   - if present → use the snapshot as the `invalidate-seed` (matters
     for `:binding-list-item` and similar entities whose seed needs
     the row's foreign keys); for `:ns` / `:fn` the id alone is
     sufficient.

   The pre-read replaces what was a type-gated snapshot — the
   absent-row check is unconditional now, and the cost is one extra
   round-trip for `:ns` / `:fn` deletes."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        et (:entity-type parsed)
        id (:id parsed)
        existing (try (sp/read-entity storage et id)
                      (catch Exception _ nil))]
    (if (nil? existing)
      (html-error-response 404 (str "Entity not found: " (name et) " " id))
      (do
        (sp/delete-entity storage et id)
        (invalidate! ctx storage et (or existing {:id id}))
        (notify-after-write! ctx storage et :delete {:id id})
        {:status 200 :body ""}))))


;; === Sequence operations =====================================================
;;
;; Slot/binding model: a sequence slot's items live in
;; `binding_list_item` rows ordered by `:position`. The binding row
;; for `(fn, sequence-slot)` carries `:list-append true` when items
;; extend a parent's items rather than replace them. Append/remove
;; operate on item rows directly — no linked-list pointers, just
;; positional indices.

(defn find-sequence-binding
  "Find the binding row that owns the sequence items for `fn-id`. A fn
   that has at least one sequence-typed slot may have an own binding
   on it (with or without `:list-append`); when it doesn't yet, the
   first append creates one. Returns either the existing binding row
   or a synthetic `{:fn-id … :slot-id …}` placeholder pinning where
   the binding will be created.

   Resolves entirely against the in-memory graph cache — five-table
   reads collapse to one cache hit per editor sequence-edit click."
  [ctx fn-id]
  (let [graph (types-api/cached-or-load-graph ctx)
        fns-by-id (into {} (map (juxt :id identity)) (:fns graph))
        slots-by-id (into {} (map (juxt :id identity)) (:slots graph))
        fn-slots-by-fn (group-by :fn-id (:fn-slots graph))
        bindings-by-fn-slot (into {}
                                  (map (fn [b] [[(:fn-id b) (:slot-id b)] b]))
                                  (:bindings graph))
        sequence?
        (fn [slot]
          (= "sequence" (:name (get fns-by-id (:type-fn-id slot)))))
        ;; Walk parent chain in memory.
        chain (loop [acc [], seen #{}, queue [fn-id]]
                (if (empty? queue)
                  acc
                  (let [fid (first queue)
                        rest-q (vec (rest queue))]
                    (if (or (nil? fid) (contains? seen fid))
                      (recur acc seen rest-q)
                      (let [f (get fns-by-id fid)
                            pids (->> (:parent-ids f) (remove nil?) (remove seen))]
                        (recur (conj acc fid) (conj seen fid)
                               (into rest-q pids)))))))
        sequence-slot
        (some (fn [fid]
                (some (fn [fs]
                        (let [s (get slots-by-id (:slot-id fs))]
                          (when (sequence? s) s)))
                      (get fn-slots-by-fn fid [])))
              chain)]
    (when sequence-slot
      (or (get bindings-by-fn-slot [fn-id (:id sequence-slot)])
          {:fn-id fn-id :slot-id (:id sequence-slot) :synthetic true}))))


(defn resolve-sequence-payload
  "Parses a sequence-op JSON body into the `binding-list-item` shape.
   Body shapes:
     {\"ref\":  \"fn-uuid-string\"}
     {\"ref-name\": \"my-fn\"}
     {\"value\": <any JSON>}

   A `\":foo\"`-shaped value string is the wire form of a keyword
   literal (JSON has no keyword type) — restore the keyword and set
   `:literal true`, matching how `records.clj` stores a fn-def's
   `{:value :kw}` item. Without the flag a read would re-emit the
   keyword colon-stripped and the editor would mis-type it as plain
   text. (The legacy `:literal? true` EDN flag was retired; the
   storage `:literal` column is still used to disambiguate keyword
   literals from string text on read-back.)"
  [storage body]
  (cond
    (contains? body :ref)
    {:ref-fn-id (java.util.UUID/fromString (:ref body))}

    (contains? body :ref-name)
    (if-let [target (first (sp/query-entities storage :fn {:name (:ref-name body)}))]
      {:ref-fn-id (:id target)}
      (throw (ex-info (str "Fn not found by name: " (:ref-name body))
                      {:type :sequence-op/fn-not-found :ref-name (:ref-name body)})))

    (contains? body :value)
    (let [v (:value body)]
      (if (and (string? v) (> (count v) 1) (str/starts-with? v ":"))
        {:value (keyword (subs v 1)) :literal true}
        {:value v}))

    :else
    (throw (ex-info "Sequence op body requires :ref, :ref-name, or :value"
                    {:type :sequence-op/invalid-body :body body}))))


(defn find-seq-append-binding
  "Read-only sequence-binding resolution for the C3 graph. Returns
   `find-sequence-binding`'s result (existing binding | synthetic
   placeholder | nil) — does NOT materialize a synthetic binding;
   `apply-seq-append` runs that write only after every guard passes."
  [parsed ctx]
  (when-let [fn-id (:fn-id parsed)]
    (find-sequence-binding ctx fn-id)))


(defn apply-seq-append-core
  "§3.3 atomic core of sequence-append: materialise synthetic binding
   if needed, compute next position, run pre-write validation, write
   the binding-list-item row. Returns `{:created <item-id> :position
   <int> :fn-id <fn-id>}` on success or `{:error <reason>}` on
   pre-write validation rejection. The graph composition around this
   primitive dispatches on the returned shape and runs invalidate +
   response.

   The synthetic-binding materialise + position-compute + pre-rej
   triplet share a binding-id that can't be split across graph nodes
   without race risk — hence §3.3."
  [parsed seq-binding ctx]
  (let [storage (request/require-storage ctx)
        fn-id (:fn-id parsed)
        body  (:body parsed)
        seq-binding (if (:synthetic seq-binding)
                      (sp/create-entity storage :binding
                                        {:fn-id (:fn-id seq-binding)
                                         :slot-id (:slot-id seq-binding)
                                         :list-append true})
                      seq-binding)
        binding-id (:id seq-binding)
        used-pos (map :position
                      (sp/query-entities storage :binding-list-item
                                         {:binding-id binding-id}))
        new-pos (inc (apply max -1 used-pos))
        payload (resolve-sequence-payload storage body)
        new-item (merge {:id (random-uuid)
                         :binding-id binding-id
                         :position new-pos}
                        payload)
        pre-rej (validation/write-rej storage :binding-list-item new-item)]
    (if pre-rej
      {:error (:reason pre-rej)}
      (do (sp/create-entity storage :binding-list-item new-item)
          {:created (:id new-item)
           :position new-pos
           :fn-id fn-id}))))


(defn apply-seq-append
  "Wrapper for non-graph callers — builds the Ring envelope around
   `apply-seq-append-core`. New paths go through `:_seq-append-apply`."
  [parsed seq-binding ctx]
  (let [result (apply-seq-append-core parsed seq-binding ctx)]
    (if (:created result)
      (do (exec-ctx/invalidate-graph-cache! ctx #{(:fn-id result)})
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:item-id (:created result)
                                        :position (:position result)})})
      (html-error-response 400 (:error result)))))


(defn load-seq-remove-item
  "Resolve the binding-list-item row for a parsed sequence-remove
   request. Returns nil when the item-id is invalid OR when no row
   matches — the `:cond` graph fn-def's not-found guard rejects in
   both cases (the invalid-item-id guard runs first, so by the time
   this fires the id is well-formed)."
  [parsed ctx]
  (when-let [item-id (:item-id parsed)]
    (sp/read-entity (request/require-storage ctx) :binding-list-item item-id)))


(defn apply-seq-remove
  "Success branch of sequence-remove — reached only after the `:cond`
   validation clauses pass. Deletes the item row, invalidates caches
   via the snapshot (so `affected-fn-ids` walks `:binding-id` → `:fn-id`),
   returns the 200 partial Ring response."
  [parsed item ctx]
  (let [storage (request/require-storage ctx)]
    (sp/delete-entity storage :binding-list-item (:item-id parsed))
    (invalidate! ctx storage :binding-list-item item)
    {:status 200 :body ""}))


(defn load-seq-update-item
  "Read the binding-list-item row for a parsed sequence-update
   request. Returns nil when the id is invalid (guard #1 catches it
   first) OR when no row matches (`:_seq-update-item-not-found?`
   catches that)."
  [parsed ctx]
  (when-let [item-id (:item-id parsed)]
    (sp/read-entity (request/require-storage ctx) :binding-list-item item-id)))


(defn apply-seq-update-core
  "§3.3 atomic core of sequence-update: resolve body payload, run
   pre-write validation, write the binding-list-item row. Returns
   `{:updated <item-id>}` on success or `{:error <reason>}` on
   pre-write rejection."
  [parsed item ctx]
  (let [storage (request/require-storage ctx)
        item-id (:item-id parsed)
        payload (resolve-sequence-payload storage (:body parsed))
        changes (merge {:value nil :ref-fn-id nil :literal nil} payload)
        pre-rej (validation/write-rej storage :binding-list-item
                                      (merge item changes {:id item-id}))]
    (if pre-rej
      {:error (:reason pre-rej)}
      (do (sp/update-entity storage :binding-list-item item-id changes)
          {:updated item-id}))))


(defn apply-seq-update
  "Wrapper for non-graph callers — builds the Ring envelope around
   `apply-seq-update-core`. New paths go through `:_seq-update-apply`."
  [parsed item ctx]
  (let [storage (request/require-storage ctx)
        result (apply-seq-update-core parsed item ctx)]
    (if (:updated result)
      (do (invalidate! ctx storage :binding-list-item item)
          {:status 200 :body ""})
      (html-error-response 400 (:error result)))))


;; === Tighten fn-typed binding effects =====================================
;;
;; Phase 8 carved out a 4-arity `[:fn args ret #{eff-set}]` form so a
;; slot whose callable should stay pure (or only do certain effects)
;; can REJECT impure callbacks at sync time. There was no UI to set
;; the constraint, so it lived only in EDN-side declarations. This
;; endpoint exposes it: the editor sends `{effects: ["io" "db"]}`,
;; the server constructs the 4-arity constraint, dedupes via
;; deterministic `anonymous-fn-id` (same shape collapses to one row),
;; and writes the binding's `:type-override-fn-id`.
;;
;; Subtype safety: the new constraint must be a SUBTYPE of the
;; current effective fn-type. Tightening from a 3-arity (no eff
;; constraint = any effects allowed) to a 4-arity is always a
;; narrowing; tightening across two 4-arities requires the new
;; eff-set ⊆ old. `subtype?` enforces this and we surface the
;; rejection as a 400.

(defn commit-tighten!
  "Helper for `tighten-effects-impl!` — performs the actual write
   (anon fn-row create + binding update) once the safety checks have
   passed. Pulled out so the impl's let-and-cond chain stays
   readable."
  [storage binding-id b new-c _effects-vec]
  (let [hash-hex (records/digest-hex "SHA-1" (pr-str new-c))
        new-id (records/anonymous-fn-id hash-hex)
        pre-override (:type-override-fn-id b)]
    ;; Find or create. Storage upsert is the natural fit — same
    ;; id ⇒ same row, no orphan duplicates.
    (when-not (sp/read-entity storage :fn new-id)
      (sp/create-entity storage :fn
                        {:id new-id
                         :name nil
                         :namespace-id nil
                         :parent-ids []
                         :impl-hash nil
                         :base-fn-id nil
                         :element-fn-id nil
                         :return-type-fn-id nil
                         :anonymous-hash hash-hex
                         :constraint new-c}))
    (sp/update-entity storage :binding binding-id
                      {:type-override-fn-id new-id})
    ;; Aggregate type-check on the owning fn. The bound-callable
    ;; effect check above is the primary guard; this catches
    ;; whatever else `check-fn-def!` evaluates (return-type
    ;; subtype, deeper structural unification, etc.). Roll back
    ;; on rejection so the binding doesn't end up in a broken
    ;; state the user has to debug.
    (if-let [post-rej (tc/type-check-fn-after-mutation! storage (:fn-id b))]
      (do (sp/update-entity storage :binding binding-id
                            {:type-override-fn-id pre-override})
          {:status 400
           :reason (str "Tightening rejected by post-write "
                        "type-check: " (:reason post-rej))})
      {:status 200
       :result {:type-override-fn-id new-id
                :constraint new-c
                :fn-id (:fn-id b)}})))


(defn tighten-fn-type-impl!
  "Compute a narrower fn-type constraint by selectively replacing
   `args`, `ret`, or `effects` from the current effective type.
   `delta` is `{:args {…} :ret T :effects [\"io\" …]}` — any subset.
   Defaults preserve the current value: 3-arity gets a 4th element
   only when `:effects` is supplied, and `:args` / `:ret` keep
   whatever the current shape carries when omitted.

   Subtype-checks the new constraint against the current; rejects
   widenings. Then runs the bound-callable safety check (effects
   only — narrower args / ret don't introduce new escape paths the
   way effects do, and the post-write `check-fn-def!` catches deeper
   structural mismatches)."
  [storage binding-id delta]
  (let [b (sp/read-entity storage :binding binding-id)]
    (cond
      (nil? b)
      {:status 404 :reason "Binding not found"}

      :else
      (let [slot (sp/read-entity storage :slot (:slot-id b))
            cur-tfn-id (or (:type-override-fn-id b) (:type-fn-id slot))
            cur-tfn (when cur-tfn-id (sp/read-entity storage :fn cur-tfn-id))
            cur-c (:constraint cur-tfn)]
        (cond
          (or (not (vector? cur-c)) (not= :fn (first cur-c)))
          {:status 400
           :reason (str "Slot's effective type is not an fn-type ("
                        (pr-str cur-c) "); can't tighten.")}

          :else
          (let [cur-args (or (nth cur-c 1) {})
                cur-ret (nth cur-c 2)
                cur-eff (when (= 4 (count cur-c)) (nth cur-c 3))
                {:keys [args ret effects]} delta
                ;; Args delta is a per-name override map. Merge so
                ;; unmentioned arg names keep their current type.
                new-args (if (map? args)
                           (merge cur-args (types-api/json->type args))
                           cur-args)
                new-ret (if (some? ret)
                          (types-api/json->type ret)
                          cur-ret)
                new-eff (cond
                          (some? effects) (into #{} (map keyword) effects)
                          cur-eff         cur-eff
                          :else           nil)
                new-c (cond-> [:fn new-args new-ret] new-eff (conj new-eff))
                ok? (types/subtype? new-c cur-c)]
            (if-not ok?
              {:status 400
               :reason (str "Proposed type " (pr-str new-c)
                            " is not a narrowing of " (pr-str cur-c)
                            " — every component (args / ret / effects)"
                            " must be a subtype of the current value.")}
              ;; Bound-callable effect check — same as the
              ;; effect-only path. Args / ret narrowings don't
              ;; introduce new escape paths beyond what
              ;; `check-fn-def!` covers.
              (let [eff-set (or new-eff #{})
                    ref-fn-id (:ref-fn-id b)
                    ref-row (when ref-fn-id (sp/read-entity storage :fn ref-fn-id))
                    ref-info (when-let [n (:name ref-row)]
                               (registry/rich-type-of (keyword n)))
                    ref-effects (or (:effects ref-info) #{})
                    escapes (when (and (some? new-eff) (seq ref-effects))
                              (clojure.set/difference (set ref-effects) eff-set))]
                (if (seq escapes)
                  {:status 400
                   :reason (str "Bound fn `" (:name ref-row) "`"
                                " produces effects " (vec (sort escapes))
                                " that the requested constraint "
                                (vec (sort eff-set))
                                " forbids. Either widen the effect set"
                                " or rebind to a fn with effects ⊆ "
                                (vec (sort eff-set)) ".")}
                  (commit-tighten! storage binding-id b new-c nil))))))))))


(defn tighten-effects-impl!
  "Backwards-compatible thin wrapper — `tighten-fn-type-impl!` with
   only the `:effects` delta filled in. Tests load this symbol
   directly; production callers go through the form-driven defbase."
  [storage binding-id effects-vec]
  (tighten-fn-type-impl! storage binding-id {:effects effects-vec}))


(defn apply-tighten-core
  "§3.3 atomic core of tighten-fn-effects: narrows the fn-typed
   binding's effective type. Returns `{:status :reason :result}` from
   `tighten-fn-type-impl!` unchanged — the outer graph dispatches on
   `:status` and runs invalidate + response."
  [parsed ctx]
  (tighten-fn-type-impl! (request/require-storage ctx)
                         (:binding-id parsed) (:delta parsed)))


(defn apply-tighten
  "Wrapper for non-graph callers — builds the Ring envelope around
   `apply-tighten-core`. New paths go through `:_tighten-apply`."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {:keys [status reason result]} (apply-tighten-core parsed ctx)]
    (if (= 200 status)
      (do (invalidate! ctx storage :binding {:fn-id (:fn-id result)})
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string result)})
      (html-error-response status reason))))
