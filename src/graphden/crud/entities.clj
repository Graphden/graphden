(ns graphden.crud.entities
  "Generic entity CRUD for the web/crud base functions — the bodies
   behind `create/update/delete-entity` and the create / update apply
   handlers, plus the error envelope and rename-slot forwarding they
   share.

   The rest of the tree lives in `crud.entities.*`, one topic each, and
   is re-exported at the bottom of this file so the historical
   `entities/<sym>` surface keeps working:

     `invalidation`  delta seeds, local cache clear, cross-pod NOTIFY
     `list`          the /api/graph/entities read scopes
     `record-type`   journalled compound type-row create / update
     `seq`           binding-list-item append / remove / update / move
     `tighten`       fn-type + effects narrowing

   Top of the crud.* DAG: may require every other `graphden.crud.*`
   namespace. It does NOT — and must not — depend on any
   `graphden.packages.*` package: the rendering code that does stays
   in `web/crud/impls.clj`."
  (:require
    [clojure.set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.entities.invalidation :as inval]
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.entities.record-type :as record-type]
    [graphden.crud.entities.seq :as seq-ops]
    [graphden.crud.entities.tighten :as tighten]
    [graphden.crud.package-guard :as pkg-guard]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as secret-shape]
    [graphden.crud.type-check :as tc]
    [graphden.crud.validation :as validation]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.diagnostics :as diag]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.storage.core :as vcore]
    [graphden.web.errors :as web-errors]))


(defn html-error-response
  "Wrap `reason` in a Ring response with the canonical
   `<p class=\"error\">…</p>` body the editor's CSS expects. Centralises
   what was eight near-identical literal builders scattered across the
   create/update/delete + sequence apply branches. The
   `Content-Type` header is set explicitly so the response is correct
   regardless of whatever an upstream wrapper decides.

   Public so the `crud.entities.seq` / `crud.entities.tighten`
   sub-namespaces can build the same error envelope without
   duplicating the literal."
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


(def ^:dynamic *create-entity-override*
  "Parallel-test failure-injection seam: when bound, `create-entity`
   calls this fn `(f entity-type data ctx)` instead of the real body.
   An override that wants the real behaviour for a subset of calls
   re-binds this var to nil and re-enters `create-entity`. nil
   (production) = real body. Tests `binding` this instead of
   `with-redefs`-ing the root var — a root rebind is process-global
   and forced a `^:serial` pin on `crud.secrets-test`. Mirrors
   `advisory-lock/*impl-override*`. Cost on the real path: one nil
   check per CRUD create."
  nil)


(defn- create-entity-impl
  [entity-type data ctx]
  ;; Abort-shielded: the whole bump->write->invalidate->note pipeline
  ;; completes even if the client disconnects mid-request (see
  ;; util.abort-shield) - un-noted epochs made every abort cost a
  ;; background recompile via the graph-epoch heal.
  (shield/run!
    (fn []
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
          (inval/invalidate! ctx storage et result)
          (inval/notify-after-write! ctx storage et :write result)
          result)))))


(defn create-entity
  [entity-type data ctx]
  (if-let [f *create-entity-override*]
    (f entity-type data ctx)
    (create-entity-impl entity-type data ctx)))


(def ^:private owner-identity-fields
  "Per entity, the immutable fields that name the row's OWNER for the
   write-time guards — filled from the stored row on update so a
   partial payload is checked like a full one."
  {:binding [:fn-id :slot-id]
   :binding-list-item [:binding-id]})


(defn update-entity
  [entity-type id data ctx]
  ;; Abort-shielded: the whole bump->write->invalidate->note pipeline
  ;; completes even if the client disconnects mid-request (see
  ;; util.abort-shield) - un-noted epochs made every abort cost a
  ;; background recompile via the graph-epoch heal.
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            et (keyword entity-type)
            ;; A PUT carries only the changed fields — a bare `ref-fn-id`
            ;; re-point used to reach the cycle check with no owner (a
            ;; binding's `fn-id`, an item's `binding-id`) and pass
            ;; unchecked. Those identity fields are immutable, so fill
            ;; them from the stored row.
            check-data (if-let [ks (owner-identity-fields et)]
                         (merge (select-keys (sp/read-entity storage et id) ks)
                                (assoc data :id id))
                         (assoc data :id id))]
        ;; Capability gate on the UPDATE path too (F2): the create path
        ;; already runs secret-leaf-capability-rej, but a tenant could
        ;; create a plain fn then PUT :parent-ids pointing at an
        ;; admin-only vault base-fn, landing a secret-shaped fn outside
        ;; the audited /api/secrets flow. Only checked when the payload
        ;; actually re-parents (:parent-ids present replaces the value).
        (when (and (= et :fn) (contains? data :parent-ids))
          (when-let [rej (secret-leaf-capability-rej storage check-data)]
            (throw (ex-info (:reason rej)
                            {:type (:type rej)
                             :entity-type et :id id :data data}))))
        (when-let [rej (validation/write-rej storage et check-data)]
          (throw (ex-info (:reason rej)
                          {:type (:type rej)
                           :entity-type et :id id :data data})))
        (let [result (sp/update-entity storage et id data)]
          (inval/invalidate! ctx storage et result)
          (inval/notify-after-write! ctx storage et :write (assoc result :id id))
          result)))))


(defn delete-entity
  [entity-type id ctx]
  ;; Abort-shielded: the whole bump->write->invalidate->note pipeline
  ;; completes even if the client disconnects mid-request (see
  ;; util.abort-shield) - un-noted epochs made every abort cost a
  ;; background recompile via the graph-epoch heal.
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            et (keyword entity-type)
            ;; Pre-read so we know the parent fn-id for binding /
            ;; fn-slot / binding-list-item before the row is gone.
            ;; For :fn we need the row anyway to drop its
            ;; rich-types-registry entry by NAME (the registry is keyed
            ;; on fn-name, not fn-id), so the read pays for itself.
            snapshot (if (= et :fn)
                       (or (sp/read-entity storage et id) {:id id})
                       (sp/read-entity storage et id))
            ;; Owner fn of a binding-family row, resolved BEFORE the
            ;; delete (the binding row itself may be the row going
            ;; away). Error-tolerance Phase 3 (Gap B): a binding /
            ;; list-item delete can fix OR break the owning fn's
            ;; aggregate type-check, so the stored diagnostic must be
            ;; re-derived after the row is gone.
            owning-fn-id (case et
                           :binding (:fn-id snapshot)
                           :binding-list-item (some->> (:binding-id snapshot)
                                                       (sp/read-entity storage :binding)
                                                       :fn-id)
                           nil)]
        ;; User-facing delete → tombstone (so deleting an inherited entity on a
        ;; branch actually hides it, not a silent no-op). Sync / rollback deletes
        ;; keep the default hard-delete.
        (binding [vcore/*tombstone-delete?* true]
          (sp/delete-entity storage et id))
        ;; rich-types-registry entry survives the storage delete unless
        ;; we explicitly drop it. Without this the registry grows
        ;; monotonically as fn-defs are created and deleted across an
        ;; executor's lifetime — small per-entry but on a long-running
        ;; prod instance it adds up to a real GC-pressure source.
        (when (and (= et :fn) (:name snapshot))
          ;; Row id threaded so the drop is keyed by THIS identity — a
          ;; same-named duplicate (stale-identity class) keeps its entry.
          (registry/unregister-rich-type! (keyword (:name snapshot)) id))
        ;; Diagnostics stay fresh across deletes (Phase 3, Gap B):
        ;; a deleted fn takes its stored entry with it; a deleted
        ;; binding / list-item re-runs the owner's aggregate check,
        ;; which records anew or clears as appropriate.
        (if (= et :fn)
          (diag/clear-fn! (vcore/current-branch-id storage) id)
          (when owning-fn-id
            (tc/type-check-fn-after-mutation! storage owning-fn-id)))
        (inval/invalidate! ctx storage et snapshot)
        ;; NOTIFY the full pre-read `snapshot` (not a bare `{:id id}`): sibling
        ;; pods' `affected-fn-ids` needs the row's FKs (`:binding-id` / `:fn-id`)
        ;; to derive the delta seed. A bare id fell through to the empty-seed
        ;; (full-clear) NOTIFY — and since a pod receives its OWN NOTIFY, every
        ;; fn-graph delete (incl. a single sequence-item remove) then forced a
        ;; full compiled-registry rebuild (tens of seconds) on the emitting pod.
        (inval/notify-after-write! ctx storage et :delete (assoc (or snapshot {}) :id id))
        true))))


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
                                 (let [eff (some-> (registry/rich-type-of-id (:id row))
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
      ;; Prefer a carried :reason (already user-facing). A generic SQL
      ;; message is NOT user-facing — raw JDBC text leaked FK names,
      ;; casts and internal ids into 400 bodies (audit-7). Classify it
      ;; via the storage error registry and return the category's safe
      ;; sentence; the raw message goes to the log ref.
      :else (or (some-> (ex-data e) :reason)
                (let [category (some-> (ex-data e) :type namespace)
                      ref (str (random-uuid))]
                  (log/warn e "create-entity storage error withheld from client"
                            {:ref ref :entity-type entity-type})
                  (case category
                    "validation-error" msg
                    "constraint-violation" msg
                    (str "Storage rejected the write (ref " ref
                         ") — see server log")))
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
      cap-rej {:error (:reason cap-rej) :http-status 403}
      :else (try
              {:created (:id (sp/create-entity storage entity-type entity-data))}
              (catch Exception e
                (log/error e "create-entity failed for"
                           entity-type entity-data)
                ;; The error's HTTP status comes from the central map —
                ;; a name/position collision is a 409 CONFLICT, not a
                ;; malformed 400 (audit-7 error honesty).
                ;;
                ;; A raw Postgres unique-violation carries NO ex-data, so it
                ;; used to fall through to 500 — an editor double-click on a
                ;; not-yet-repainted placeholder (two binds racing the same
                ;; `(fn-id, slot-id)`) then read as an internal error and
                ;; paged as one. It is a conflict: the row is already there.
                {:error (humanise-create-exception e entity-type entity-data type-str)
                 :http-status (if (re-find #"(?i)duplicate key"
                                           (or (Throwable/.getMessage e) ""))
                                409
                                (web-errors/status-for-ex-data (ex-data e)))})))))


(defn- rename-root-slot-id
  "Follow `slot.source-slot-id` to the slot that DECLARED the arg.

   A `{:as :new-name}` rename mints a VIEW slot whose `:source-slot-id`
   points at the declared one, so the new name resolves for descendants.
   Bindings, though, always target the DECLARED slot — that is what the
   package parser writes (`packages.records.slot-resolution` walks the
   rename chain to the declaring ancestor) and what the executor reads.
   A binding written on the view slot lands, shows on the card, and is
   then invisible at run time: the value silently never arrives."
  [storage slot-id]
  (loop [id slot-id
         seen #{}]
    (if (or (nil? id) (contains? seen id))
      id
      (if-let [src (:source-slot-id (sp/read-entity storage :slot id))]
        (recur src (conj seen id))
        id))))


(defn- normalize-binding-slot
  "Rewrite a binding write's `:slot-id` to its rename root, so every
   client — editor, MCP, raw API — writes the slot the executor reads."
  [storage entity-type entity-data]
  (if (and (= entity-type :binding) (:slot-id entity-data))
    (update entity-data :slot-id #(rename-root-slot-id storage %))
    entity-data))


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


(defn- post-write-type-check-fn-id
  "Resolve the OWNING fn-id for a binding-shaped mutation so the
   post-write type-check sees the aggregate of every sibling binding.
   On UPDATE the entity-data is the partial form payload and may omit
   the FK fields, so fall back to reading the just-written row by
   `id-uuid`."
  [storage type-str entity-data id-uuid]
  (cond
    ;; A bare `:fn` write IS the owning fn — without this arm a
    ;; freshly-extended child (no bindings yet) had NO rich-types
    ;; entry until its first binding write: `fn-return-type` nil,
    ;; effects unknown, no typed repr / return chip. The entry only
    ;; appeared after the first binding mutation re-ran the check.
    (= type-str "fn")
    (or id-uuid (:id entity-data))
    (= type-str "binding")
    (or (:fn-id entity-data)
        (when id-uuid
          (:fn-id (sp/read-entity storage :binding id-uuid))))
    (= type-str "binding-list-item")
    (when-let [binding-id (or (:binding-id entity-data)
                              (when id-uuid
                                (:binding-id (sp/read-entity
                                               storage :binding-list-item
                                               id-uuid))))]
      (:fn-id (sp/read-entity storage :binding binding-id)))))


(defn- post-write-type-rej
  "Post-write whole-fn type-check for binding mutations. A binding can
   be individually valid yet break the OWNING fn-def's aggregate
   check. Error-tolerance Phase 2: the just-written row is KEPT even
   when the check fails — `type-check-fn-after-mutation!` records the
   failure in the per-branch diagnostics store (and clears it again
   once a later write fixes the fn) — EXCEPT for the security
   carve-out: a SECRET-involving diagnostic (`:secret? true` on the
   returned rej — a `[:secret …]` flow laundered into a plain slot)
   is not recorded, and the caller must roll the write back and
   return the pre-Phase-2 hard `{:error …}` envelope. Non-secret
   failures come back as `{:diagnostic …}` the caller surfaces
   additively as `:type-warnings` on the success envelope. nil when
   the check passes or doesn't apply to this entity type."
  [storage type-str entity-data id-uuid]
  (when (#{"fn" "binding" "binding-list-item"} type-str)
    (when-let [fn-id (post-write-type-check-fn-id
                       storage type-str entity-data id-uuid)]
      (tc/type-check-fn-after-mutation! storage fn-id
                                        {:reject-secret? true}))))


(defn apply-create-core
  "§3.3 atomic core of the create-apply flow: capability gate +
   `sp/create-entity` (with unique-violation humanisation) + Phase-6c
   rename-slot side-effect + post-create whole-fn type-check.
   Returns a uniform shape:
     `{:created <id>}` on success
     `{:created <id> :type-warnings [<diagnostic> …]}` when the write
       landed but the owning fn now fails the aggregate type-check
       (error-tolerance Phase 2 — the row is KEPT, the failure is
       recorded in the per-branch diagnostics store and surfaced
       additively; clients that ignore the key keep working)
     `{:error <human-msg>}` on a write failure (capability rejection,
       storage constraint violation, …)
   so the outer graph can dispatch on the shape and run invalidate /
   notify / response uniformly. Structural gates (cycles, name
   collisions, terminal / list-closed, MI) still reject BEFORE this
   fn runs — only the TYPE check became non-blocking. SECURITY
   CARVE-OUT: a SECRET-flow type failure (laundering a `[:secret …]`
   value into a plain slot) keeps the pre-Phase-2 behaviour — the
   just-created row is deleted and the diagnostic message comes back
   as `{:error …}` (a 400 on the wire); the guarantee must not rest
   on the derived diagnostics store (docs/SECRETS.md)."
  [{:keys [entity-type type-str form-data entity-data]} ctx]
  (let [storage (request/require-storage ctx)
        entity-data (normalize-binding-slot storage entity-type entity-data)
        pkg-reason (pkg-guard/write-rejection storage entity-type entity-data)
        create-result (if pkg-reason
                        {:error pkg-reason :http-status 403}
                        (try-create-or-error storage entity-type entity-data type-str))]
    (if (:created create-result)
      (let [rej (post-write-type-rej storage type-str entity-data
                                     (:created create-result))]
        (if (:secret? rej)
          ;; Hard reject: roll back the just-created row (logged, not
          ;; swallowed — an orphan surviving the rejection must be
          ;; visible) and surface the diagnostic message as the error.
          ;; The rename-slot side-effect deliberately hasn't run yet,
          ;; so no orphan renamed-view slot is left behind either.
          (do (try (sp/delete-entity storage entity-type (:created create-result))
                   (catch Exception e
                     (log/warn e "Rollback delete-entity failed after secret-flow type-check rejection"
                               {:entity-type entity-type
                                :id (:created create-result)})))
              {:error (:reason rej)})
          (do (when (and (= type-str "binding")
                         (contains? form-data :rename-to))
                (forward-rename-slot! storage form-data entity-data))
              (cond-> create-result
                rej (assoc :type-warnings [(:diagnostic rej)])))))
      ;; Preserve the error's :http-status (409 collisions, 403
      ;; capability — the central web.errors mapping) alongside the
      ;; human message.
      (cond-> {:error (or (:error create-result)
                          (str "Failed to create " type-str))}
        (:http-status create-result)
        (assoc :http-status (:http-status create-result))))))


(defn apply-update-core
  "§3.1 atomic core of the update-apply flow: `sp/update-entity` +
   Phase-6c rename-slot side-effect (binding writes only) + post-write
   whole-fn type-check for binding-shaped updates. Returns a uniform
   shape:
     `{:updated <id>}` on success
     `{:updated <id> :type-warnings [<diagnostic> …]}` when the write
       landed but the owning fn now fails the aggregate type-check
       (error-tolerance Phase 2 — recorded in the per-branch
       diagnostics store, surfaced additively; a later fixing write
       clears the stored entry)
     `{:error <msg>}` on write failure
   The rename-slot failure is logged but never escalated — the
   binding row is still useful without the rename slot, matching the
   legacy behaviour. SECURITY CARVE-OUT: a SECRET-flow type failure
   restores the touched fields from the pre-update row and returns
   the diagnostic message as `{:error …}` (a 400 on the wire) —
   secret laundering never persists, warn-and-persist is for
   ordinary type errors only (docs/SECRETS.md)."
  [{:keys [entity-type type-str id-uuid form-data entity-data]} ctx]
  (let [storage (request/require-storage ctx)
        ;; An update rarely carries `:slot-id`, but when it does the same
        ;; rename-root rule applies as on create.
        entity-data (normalize-binding-slot storage entity-type entity-data)
        error-msg (volatile! nil)
        ;; Pre-image for the secret carve-out rollback (binding family)
        ;; and for the package-owner write guard (adds fn-slot).
        pre-row (when (and id-uuid (#{"binding" "binding-list-item" "fn-slot"} type-str))
                  (sp/read-entity storage entity-type id-uuid))
        ;; A `:fn` update (rename / description / ns-move) targets the row
        ;; identified by `id-uuid` itself — no pre-image read needed, and
        ;; the guard refuses it on a package-synced fn (the next boot's
        ;; sync would revert it, and a rename breaks every bare ref).
        pkg-reason (if (and id-uuid (= "fn" type-str))
                     (pkg-guard/write-rejection storage entity-type {:id id-uuid})
                     (when pre-row
                       (pkg-guard/write-rejection storage entity-type pre-row)))
        updated (when-not pkg-reason
                  (try (sp/update-entity storage entity-type id-uuid entity-data)
                       (catch Exception e
                         (log/error e "update-entity failed for"
                                    entity-type id-uuid entity-data)
                         ;; Surface a write-rejection reason when the storage
                         ;; layer provides one (e.g. the fn-name collision
                         ;; check) — a bare "Failed to update entity" hides
                         ;; exactly the message the user can act on.
                         (vreset! error-msg (some-> (ex-data e) :reason))
                         nil)))]
    (if-not updated
      (cond-> {:error (or pkg-reason @error-msg "Failed to update entity")}
        pkg-reason (assoc :http-status 403))
      (let [rej (post-write-type-rej storage type-str entity-data id-uuid)]
        (if (:secret? rej)
          ;; Hard reject: restore every field the update touched from
          ;; the pre-image, then surface the diagnostic message. The
          ;; rename-slot side-effect below deliberately hasn't run yet.
          (do (if pre-row
                (try (sp/update-entity
                       storage entity-type id-uuid
                       (into {} (map (fn [[k _]] [k (get pre-row k)]))
                             entity-data))
                     (catch Exception e
                       (log/warn e "Rollback restore failed after secret-flow type-check rejection"
                                 {:entity-type entity-type :id id-uuid})))
                (log/warn "No pre-image to restore after secret-flow type-check rejection"
                          {:entity-type entity-type :id id-uuid}))
              {:error (:reason rej)})
          (do (when (and (= type-str "binding") id-uuid
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
              (cond-> {:updated id-uuid}
                rej (assoc :type-warnings [(:diagnostic rej)]))))))))


;; === Re-exports from sub-namespaces ==========================================
;;
;; The sequence-ops and tighten domains live in
;; `crud.entities.seq` / `crud.entities.tighten` to keep this file
;; focused on the generic CRUD + record/list-type + delete chains.
;; External callers (notably `web/crud/impls.clj` and
;; `crud/entities_test.clj`) reach them via the historical
;; `entities/<sym>` surface, so each public symbol is re-exported here
;; as a Var — the same facade pattern `types.core` uses for
;; `types.core.shapes`. New code can require the sub-ns directly.
;;
;; These were 12 `requiring-resolve` wrappers when the sub-nses still
;; required this one back; they don't any more, so the lazy resolve
;; (and its per-call Var lookup on the write path) is gone.

(def affected-fn-ids           inval/affected-fn-ids)
(def invalidate!               inval/invalidate!)
(def notify-after-write!       inval/notify-after-write!)

(def strip-impl-of             entity-list/strip-impl-of)


;; The atom itself, not a copy — the tenancy addon `reset!`s this seam by
;; the historical `entities/` name at boot (`authz/install-view-impl-filter!`)
;; and clears it on halt.
(def view-impl-filter          entity-list/view-impl-filter)
(def apply-view-impl-filter    entity-list/apply-view-impl-filter)
(def list-all-graph-entities   entity-list/list-all-graph-entities)

(def parse-create-record-type        record-type/parse-create-record-type)
(def apply-create-record-type-body   record-type/apply-create-record-type-body)
(def apply-create-rollback           record-type/apply-create-rollback)
(def apply-create-list-type-body     record-type/apply-create-list-type-body)
(def apply-update-record-type-body   record-type/apply-update-record-type-body)
(def apply-update-record-type-rollback record-type/apply-update-record-type-rollback)

(def find-sequence-binding     seq-ops/find-sequence-binding)
(def resolve-sequence-payload  seq-ops/resolve-sequence-payload)
(def find-seq-append-binding   seq-ops/find-seq-append-binding)
(def apply-seq-append-core     seq-ops/apply-seq-append-core)
(def load-seq-remove-item      seq-ops/load-seq-remove-item)
(def load-seq-update-item      seq-ops/load-seq-update-item)
(def apply-seq-update-core     seq-ops/apply-seq-update-core)
(def apply-seq-move-core       seq-ops/apply-seq-move-core)

(def commit-tighten!           tighten/commit-tighten!)
(def tighten-fn-type-impl!     tighten/tighten-fn-type-impl!)
(def tighten-effects-impl!     tighten/tighten-effects-impl!)
(def apply-tighten-core        tighten/apply-tighten-core)
