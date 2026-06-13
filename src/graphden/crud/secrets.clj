(ns graphden.crud.secrets
  "Admin-side CRUD for secrets — backed by OpenBao + the
   `:secret-leaf` fn-def shape.

   A secret is represented in graphden as a normal `fn` row with
   `parent-ids=[:secret-leaf]` plus a single `binding` row whose
   `:override-kind :secret-path` carries the KV path in
   `binding.value`. The actual secret VALUE never touches the
   graphden DB — it goes straight to OpenBao via the
   `graphden.clients.vault` client, and the executor auto-derefs
   the path at arg-resolution time (see `compile/bindings.clj`
   `:secret-value` case). This module orchestrates both sides:
   graphden-row + OpenBao-write happen together with compensation
   rollback if either side fails.

   Endpoints (see `app/secrets/fns.edn`):
   - `GET /api/secrets`          → `list-secrets`
   - `POST /api/secrets`         → `create-secret`
   - `DELETE /api/secrets/:id`   → `delete-secret`
   - `PUT /api/secrets/:id/value` → `rotate-secret`"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.clients.vault :as vault]
    [graphden.crud.entities :as crud-entities]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as shape]
    [graphden.crud.type-check :as tc]
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.util
      UUID)))


(defn- log-rollback-failure
  "Log (don't silence) a failed compensation step. Rollback steps must
   not throw — a re-throw inside the outer `catch` block would mask the
   original failure that triggered the rollback. But silently nil'ing
   the failure hides operational data the operator needs (vault left
   with an orphan secret? graphden left with an orphan binding?). Log
   at WARN so the original error stays primary while the compensation
   gap is visible in dashboards."
  [step e]
  (log/warn e (str "Secret-rollback step failed: " (name step)
                   " — manual cleanup may be required")))


(defn- require-vault!
  "The admin path treats a missing vault as a hard error — without
   OpenBao there's nowhere to store the value. (The executor's
   `:secret-value` auto-deref also fails with `:vault/not-configured`
   in this state.)"
  [ctx]
  (or (:vault ctx)
      (throw (ex-info "Vault client not configured — set VAULT_ADDR / VAULT_TOKEN"
                      {:type :vault/not-configured}))))


(defn- find-path-slot-id
  "The single arg slot is owned by `:secret-leaf` (its `:in` slot).
   Pick the first fn-slot junction off the owner."
  [storage owner-fn-id]
  (when-let [fs (first (sp/query-entities storage :fn-slot {:fn-id owner-fn-id}))]
    (:slot-id fs)))


(defn- secret-binding-path
  "Read the path string out of a secret-fn-def's binding. Returns
   nil when no binding exists (corrupted state)."
  [storage fn-id path-slot-id]
  (some-> (first (sp/query-entities storage :binding {:fn-id fn-id
                                                      :slot-id path-slot-id}))
          :value))


(defn find-usages
  "Find every fn that references `fn-id`:
   - as a parent (`parent-ids` :ref-many — reverse junction-index
     via `sp/query-ref-many-owners`, O(log n))
   - via a binding (`binding.ref-fn-id` :ref — equality filter)
   - via a sequence item (`binding-list-item.ref-fn-id` :ref)
   Returns a vec of `{:fn-id :name :reason}` maps, deduplicated.

   Parallels the graph fn-def `:find-fn-usages` in
   `app/secrets/fns.edn` — the production HTTP path goes through
   that graph composition (`:query-ref-many-owners` + 2
   `:list-entities` reverse-ref scans + `:merge`-precedence + name
   lookup). This Clojure helper exists for the test orchestrator
   `delete-secret` (mirror of the graph path for unit testing)."
  [storage fn-id]
  (let [parent-owner-ids (sp/query-ref-many-owners storage :fn :parent-ids fn-id)
        as-binding (sp/query-entities storage :binding {:ref-fn-id fn-id})
        as-list-item (sp/query-entities storage :binding-list-item {:ref-fn-id fn-id})
        ;; One IN-query to resolve every list-item's owning binding, then
        ;; pluck the :fn-id off each. Pre-fix this did
        ;; `(read-entity :binding bid)` inside `keep` — N round-trips for
        ;; N list-items.
        list-item-binding-ids (into [] (keep :binding-id) as-list-item)
        list-item-bindings (when (seq list-item-binding-ids)
                             (sp/query-entities storage :binding
                                                {:id list-item-binding-ids}))
        list-item-binding-fn-ids (into [] (keep :fn-id) list-item-bindings)
        ;; Collect every referencing fn-id with the most-specific reason
        ;; (parent > binding > list-item). One row per fn-id.
        reasons (merge
                  (into {} (map (fn [b] [(:fn-id b) :binding]) as-binding))
                  (into {} (map (fn [fid] [fid :list-item]) list-item-binding-fn-ids))
                  (into {} (map (fn [oid] [oid :parent]) parent-owner-ids)))
        ;; Resolve names with one targeted SQL fetch instead of a full :fn scan.
        ids (vec (keys reasons))
        fns (when (seq ids) (sp/query-entities storage :fn {:id ids}))
        name-by-id (into {} (map (juxt :id :name)) fns)]
    (mapv (fn [[fid reason]]
            {:fn-id fid :name (name-by-id fid) :reason reason})
          reasons)))


(defn- shape-secret
  "JSON-shape one secret for the wire. Pulls metadata from OpenBao
   so the editor can show created-at + description without a second
   round-trip per secret. Failures to read metadata downgrade
   gracefully — the row still appears, just without provenance.

   `shape` is `\"secret-leaf\"` — the only admin secret shape."
  [storage vault-client fn-row path-slot-id shape]
  (let [fn-id (:id fn-row)
        path (secret-binding-path storage fn-id path-slot-id)
        metadata (when path
                   (try (vault/get-metadata vault-client path)
                        (catch Exception _ nil)))]
    (cond-> {:id (str fn-id)
             :name (:name fn-row)
             :namespace-id (some-> (:namespace-id fn-row) str)
             :description (:description fn-row)
             :path path
             :shape shape}
      metadata (assoc :created-at (:created_time metadata)
                      :updated-at (:updated_time metadata)
                      :version (:current_version metadata)
                      :custom-metadata (:custom_metadata metadata)))))


(defn list-secrets
  "GET /api/secrets — every fn-def with parent=`[:secret-leaf]`,
   enriched with OpenBao metadata. Secret VALUES are never returned."
  [ctx]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)]
    (if-not secret-leaf-id
      {:ok false :error ":secret-leaf base-fn not found — package web.vault not loaded?"}
      (let [path-slot-id (find-path-slot-id storage secret-leaf-id)
            owner-ids (sp/query-ref-many-owners storage :fn :parent-ids secret-leaf-id)
            candidates (when (seq owner-ids)
                         (sp/query-entities storage :fn {:id (vec owner-ids)}))
            secrets (->> candidates
                         (filter #(shape/secret-fn? % secret-leaf-id))
                         (mapv (fn [fn-row]
                                 (shape-secret storage vault-client fn-row
                                               path-slot-id "secret-leaf"))))]
        {:ok true :secrets secrets}))))


;; Private `parse-uuid-loose` removed — the `(uuid? v)` passthrough
;; case was dead in practice. Every callsite read `body`-extracted
;; JSON fields or URL segments, both of which arrive as strings (or
;; nil). For those `request/parse-uuid-or-clear` already returns the
;; right value (UUID on parse success, nil on blank / malformed /
;; non-string). Callers below thread directly through that helper.


(defn parse-create-secret-request
  "Parse the JSON body of `POST /api/secrets` into the bundle the C7
   `:cond` graph fn-def consumes."
  [body]
  (let [description (some-> (:description body) str)]
    {:nm (some-> (:name body) str str/trim)
     :ns-id (request/parse-uuid-or-clear (:namespace-id body))
     :path (some-> (:path body) str str/trim)
     :value (:value body)
     :description description
     :custom-metadata (cond-> {}
                        (and description (seq description))
                        (assoc :description description))}))


(defn create-secret-leaf-id
  "Look up the `:secret-leaf` base-fn id for the C7 guard chain.
   Returns nil when package web.vault isn't loaded (or its base-fn
   row was somehow missing). Shared between the leaf-missing
   predicate and the apply branch."
  [ctx]
  (shape/find-secret-leaf-fn-id (request/require-storage ctx)))


(defn create-secret-name-taken?
  "C7 guard — a fn with the same (`:name`, `:namespace-id`) already
   exists. Both the predicate and the dynamic-reason error builder
   look it up the same way, but here we only need the boolean."
  [parsed ctx]
  (seq (sp/query-entities (request/require-storage ctx) :fn
                          {:name (:nm parsed) :namespace-id (:ns-id parsed)})))


(defn replay-secret-rollback!
  "Shared rollback callable for the §3.3 secret-write `:try` carve-outs
   (create-secret + create-inline-binding). Walks the journal in
   reverse and undoes each entry by tag: `[:vault-delete <path>]`
   tries `vault/delete-secret`, `[:storage-delete <et> <id>]` tries
   `sp/delete-entity`. Each step is best-effort + logged — replay
   failures don't re-throw, matching the legacy behaviour (the
   important contract is that the response says `{:ok false}` and
   no orphan rows linger on the happy path)."
  [journal exception ctx]
  (let [storage (request/require-storage ctx)
        vault-client (try (require-vault! ctx)
                          (catch Exception _ nil))]
    (doseq [entry (reverse @journal)]
      (case (first entry)
        :vault-delete
        (when vault-client
          (try (vault/delete-secret vault-client (second entry))
               (catch Exception e (log-rollback-failure :vault-delete e))))

        :storage-delete
        (let [[_ et id] entry]
          (try (sp/delete-entity storage et id)
               (catch Exception e (log-rollback-failure et e))))))
    (if (instance? clojure.lang.ExceptionInfo exception)
      {:ok false
       :error (or (ex-message exception) (str exception))
       :data (ex-data exception)}
      {:ok false
       :error (or (ex-message exception) (str exception))})))


(defn apply-create-secret-body
  "Body of the create-secret `:try`: vault-put + vault-put-metadata
   (optional) + storage create-fn + storage create-binding +
   post-create whole-fn type-check. Records rollback entries on the
   shared `journal` atom (`[:vault-delete path]`, `[:storage-delete
   :fn fn-id]`, `[:storage-delete :binding binding-id]`). Throws on
   any failure (caught by `:try`)."
  [parsed leaf-id journal ctx]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        {:keys [nm ns-id path value description custom-metadata]} parsed
        path-slot-id (find-path-slot-id storage leaf-id)
        ;; OpenBao first — easy to roll back via vault-delete.
        _ (vault/put-secret vault-client path value)
        _ (swap! journal conj [:vault-delete path])
        metadata-ok? (try (vault/put-metadata vault-client path custom-metadata)
                          true
                          (catch Exception e
                            (log/warn e "Vault metadata stamp failed"
                                      {:path path})
                            false))
        fn-id (UUID/randomUUID)
        binding-id (UUID/randomUUID)]
    (crud-entities/create-entity
      :fn
      (cond-> {:id fn-id
               :name nm
               :parent-ids [leaf-id]
               :_admin-secret-create true}
        ns-id (assoc :namespace-id ns-id)
        (and description (seq description)) (assoc :description description))
      ctx)
    (swap! journal conj [:storage-delete :fn fn-id])
    (crud-entities/create-entity
      :binding
      {:id binding-id
       :fn-id fn-id
       :slot-id path-slot-id
       :value path
       :override-kind :secret-path}
      ctx)
    (swap! journal conj [:storage-delete :binding binding-id])
    (tc/type-check-fn-after-mutation! storage fn-id)
    {:ok true
     :secret {:id (str fn-id)
              :name nm
              :namespace-id (some-> ns-id str)
              :path path
              :description description
              :metadata-stamped? metadata-ok?}}))


(defn apply-create-secret
  "C7 success branch — wraps `apply-create-secret-body` with journal
   allocation + try/catch + replay. Survives for non-graph callers."
  [parsed leaf-id ctx]
  (let [journal (atom [])]
    (try (apply-create-secret-body parsed leaf-id journal ctx)
         (catch Exception e
           (replay-secret-rollback! journal e ctx)))))


(defn create-secret
  "POST /api/secrets — atomically create the OpenBao value, the
   OpenBao custom-metadata, and the graphden fn-row + path-binding.
   On any failure, rolls back partial writes (vault-delete if the
   graphden side fails).

   Thin orchestration over the per-stage helpers
   (`parse-create-secret-request` / `create-secret-leaf-id` /
   `create-secret-name-taken?` / `apply-create-secret`) — the same
   helpers the C7 graph `:cond` in `app/secrets/fns.edn` consumes.
   Keeping this public defn lets direct Clojure callers (notably
   the secrets test suite) keep their `(create-secret ctx body)`
   call shape, while ensuring the validation and apply logic stay
   single-sourced between the test path and the graph path."
  [ctx body]
  (let [parsed (parse-create-secret-request body)
        leaf-id (create-secret-leaf-id ctx)]
    (cond
      (str/blank? (:nm parsed))
      {:ok false :error "Required field ':name' is missing"}

      (str/blank? (:path parsed))
      {:ok false :error "Required field ':path' is missing"}

      (not (string? (:value parsed)))
      {:ok false :error "Required field ':value' (string) is missing"}

      (nil? leaf-id)
      {:ok false
       :error ":secret-leaf base-fn missing — package web.vault not loaded"}

      (create-secret-name-taken? parsed ctx)
      {:ok false
       :error (str "fn already exists with name: " (:nm parsed))
       :reason :name-taken}

      :else
      (apply-create-secret parsed leaf-id ctx))))


(defn parse-create-inline-binding-request
  "Parse the JSON body of `POST /api/secret-bindings` into `{:fn-id
   :slot-id :path :value}`."
  [body]
  {:fn-id (request/parse-uuid-or-clear (:fn-id body))
   :slot-id (request/parse-uuid-or-clear (:slot-id body))
   :path (some-> (:path body) str str/trim)
   :value (:value body)})


(defn inline-binding-target-fn-row
  "C11 sub-result — read the target fn row by parsed fn-id. nil when
   id is invalid OR the row doesn't exist."
  [parsed ctx]
  (when-let [fn-id (:fn-id parsed)]
    (sp/read-entity (request/require-storage ctx) :fn fn-id)))


(defn inline-binding-existing
  "C11 sub-result — look for an existing binding on (fn-id, slot-id).
   Returns the row or nil. Shared between the binding-exists guard
   and apply (which only fires when nil)."
  [parsed ctx]
  (let [{:keys [fn-id slot-id]} parsed]
    (when (and fn-id slot-id)
      (first (sp/query-entities (request/require-storage ctx) :binding
                                {:fn-id fn-id :slot-id slot-id})))))


(defn apply-create-inline-binding-body
  "Body of the inline-bind `:try`: vault-put then storage create.
   Records rollback entries on the shared `journal` atom. Throws on
   storage / vault failure (caught by `:try`)."
  [parsed journal ctx]
  (let [vault-client (require-vault! ctx)
        {:keys [fn-id slot-id path value]} parsed
        binding-id (UUID/randomUUID)]
    (vault/put-secret vault-client path value)
    (swap! journal conj [:vault-delete path])
    (crud-entities/create-entity
      :binding
      {:id binding-id
       :fn-id fn-id
       :slot-id slot-id
       :value path
       :override-kind :secret-path}
      ctx)
    (swap! journal conj [:storage-delete :binding binding-id])
    {:ok true
     :binding {:id (str binding-id)
               :fn-id (str fn-id)
               :slot-id (str slot-id)
               :path path}}))


(defn apply-create-inline-binding
  "C11 success branch — wraps `apply-create-inline-binding-body` with
   journal allocation + try/catch + replay. Survives for non-graph
   callers; new paths go through `:_inline-bind-apply`'s `:try` node."
  [parsed ctx]
  (let [journal (atom [])]
    (try (apply-create-inline-binding-body parsed journal ctx)
         (catch Exception e
           (replay-secret-rollback! journal e ctx)))))


(defn create-inline-binding
  "POST /api/secrets/binding — write VALUE to vault at PATH, then create
   a `:secret-path`-kinded binding on (`fn-id`, `slot-id`) so the user's
   fn picks the secret up at exec time. Skips the wrapper-fn-def step
   (`create-secret` above) — the binding IS the secret-fetch.

   `crud-entities/create-entity` runs `secret-path-rej`, which rejects
   when the slot's declared type doesn't contain `:secret`. On graphden
   failure (gate reject OR uniqueness collision) we vault-delete the
   path so the stores stay in sync.

   Thin orchestration over the C11 helpers (the same ones
   `:_create-inline-binding-data`'s graph cond consumes) so the test
   path and the graph path share one source of truth."
  [ctx body]
  (let [parsed (parse-create-inline-binding-request body)
        target-fn-row (inline-binding-target-fn-row parsed ctx)
        existing (inline-binding-existing parsed ctx)]
    (cond
      (nil? (:fn-id parsed))
      {:ok false :error "Required field ':fn-id' is missing or malformed"}

      (nil? (:slot-id parsed))
      {:ok false :error "Required field ':slot-id' is missing or malformed"}

      (str/blank? (:path parsed))
      {:ok false :error "Required field ':path' is missing"}

      (not (string? (:value parsed)))
      {:ok false :error "Required field ':value' (string) is missing"}

      (nil? target-fn-row)
      {:ok false :error (str "fn not found: " (:fn-id parsed))}

      (some? existing)
      {:ok false
       :error "A binding already exists for this fn+slot — delete it first"
       :reason :binding-exists}

      :else
      (apply-create-inline-binding parsed ctx))))


(defn parse-delete-secret-request
  "Parse `DELETE /api/secrets/:fn-id` URL into `{:fn-id <uuid|nil>
   :fn-id-ref <raw>}`. Raw form is preserved for the dynamic error
   messages — they cite back whatever the caller passed."
  [fn-id-ref]
  {:fn-id (request/parse-uuid-or-clear fn-id-ref)
   :fn-id-ref fn-id-ref})


(defn delete-secret-fn-row
  "C8 sub-result — read the target fn row by id, nil when id is
   invalid OR row doesn't exist. Shared by the not-found guard,
   the secret-shape predicate, and apply."
  [parsed ctx]
  (when-let [fn-id (:fn-id parsed)]
    (sp/read-entity (request/require-storage ctx) :fn fn-id)))


(defn delete-secret-find-usages
  "C8 sub-result — every fn that references this secret. Empty when
   apply can proceed. Computed independently of the not-found /
   not-a-secret guards (which fire earlier)."
  [parsed ctx]
  (when-let [fn-id (:fn-id parsed)]
    (find-usages (request/require-storage ctx) fn-id)))


(defn delete-secret-vault-cleanup!
  "Best-effort vault delete during secret removal. Vault may not have
   the path (already manually purged) or be unreachable — neither is
   a fatal condition for the graphden-side cleanup. Logged so
   operators see vault drift."
  [path ctx]
  (let [vault-client (require-vault! ctx)]
    (try (vault/delete-secret vault-client path)
         (catch Exception e
           (log/warn e "Vault delete failed during secret removal"
                     {:path path}))))
  nil)


(defn delete-secret-storage-cleanup!
  "Storage-side cleanup of a secret removal: delete the path-binding
   (if present) and the fn-row through `crud-entities/delete-entity`
   so the graph cache invalidates on each. Returns `nil`. Reads the
   binding row off `parsed` + `fn-row` so the graph caller doesn't
   have to thread it."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        fn-id (:fn-id parsed)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)
        path-slot-id (find-path-slot-id storage secret-leaf-id)
        binding-row (first (sp/query-entities storage :binding
                                              {:fn-id fn-id :slot-id path-slot-id}))]
    (when binding-row (crud-entities/delete-entity :binding (:id binding-row) ctx))
    (crud-entities/delete-entity :fn fn-id ctx)
    nil))


(defn apply-delete-secret
  "C8 success branch — wrapper kept for non-graph callers. New paths go
   through `:_delete-secret-apply`'s `:do` over `:_delete-secret-do-
   vault-cleanup` + `:_delete-secret-do-storage-cleanup` + the
   response builder."
  [parsed fn-row ctx]
  (let [storage (request/require-storage ctx)
        fn-id (:fn-id parsed)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)
        path-slot-id (find-path-slot-id storage secret-leaf-id)
        path (secret-binding-path storage fn-id path-slot-id)]
    (delete-secret-vault-cleanup! path ctx)
    (delete-secret-storage-cleanup! parsed ctx)
    {:ok true :id (str fn-id) :name (:name fn-row) :path path}))


(defn delete-secret
  "DELETE /api/secrets/:fn-id — hard delete (graphden row + every
   OpenBao version + metadata). Rejected if ANY fn references this
   secret (`:reason :secret-in-use`). Caller must re-target or
   remove dependents first.

   Thin orchestration over the C8 helpers (parse / fn-row lookup /
   usages / apply) so the test path and the `:_delete-secret-data`
   graph cond stay observationally equivalent."
  [ctx fn-id-ref]
  (let [parsed (parse-delete-secret-request fn-id-ref)
        fn-row (delete-secret-fn-row parsed ctx)
        storage (request/require-storage ctx)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)
        usages (delete-secret-find-usages parsed ctx)]
    (cond
      (nil? fn-row)
      {:ok false :error (str "Secret not found: " fn-id-ref)
       :reason :not-found}

      (not (shape/secret-fn? fn-row secret-leaf-id))
      {:ok false
       :error (str "fn is not a secret (parent != [:secret-leaf]): " fn-id-ref)
       :reason :not-a-secret}

      (seq usages)
      {:ok false
       :error (str "Secret is referenced by " (count usages)
                   " fn(s) — remove or re-target dependents first")
       :reason :secret-in-use
       :usages (mapv (fn [u]
                       {:fn-id (str (:fn-id u))
                        :name (:name u)
                        :reason (:reason u)})
                     usages)}

      :else
      (apply-delete-secret parsed fn-row ctx))))


(defn parse-rotate-secret-request
  "Parse the URL + body for `PUT /api/secrets/:fn-id/value` into
   `{:fn-id :fn-id-ref :value}`."
  [fn-id-ref body]
  {:fn-id (request/parse-uuid-or-clear fn-id-ref)
   :fn-id-ref fn-id-ref
   :value (:value body)})


(defn rotate-secret-path
  "C9 sub-result — resolve the vault path bound on the secret fn.
   Nil when the binding row is missing (corrupted state). Shared
   by the missing-binding guard + apply."
  [parsed fn-row ctx]
  (let [storage (request/require-storage ctx)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)]
    (when (and fn-row secret-leaf-id)
      (let [path-slot-id (find-path-slot-id storage secret-leaf-id)]
        (secret-binding-path storage (:fn-id parsed) path-slot-id)))))


(defn apply-rotate-secret
  "C9 success branch — vault-put writes a new value at the existing
   path. graphden state is unchanged."
  [parsed fn-row path ctx]
  (let [vault-client (require-vault! ctx)
        version (vault/put-secret vault-client path (:value parsed))]
    {:ok true
     :id (str (:fn-id parsed))
     :name (:name fn-row)
     :path path
     :version version}))


(defn rotate-secret
  "PUT /api/secrets/:fn-id/value — writes a new value to OpenBao at
   the same path (KV v2 retains the previous version under
   `?version=N`). graphden state is unchanged.

   Thin orchestration over the C9 helpers (parse / fn-row lookup /
   path lookup / apply). The fn-row + not-a-secret guards reuse the
   C8 delete helpers — same `parsed` shape (both carry `:fn-id`),
   same `:_delete-secret-fn-row` / `:_delete-secret-not-found?` /
   `:_delete-secret-not-a-secret?` graph defbases."
  [ctx fn-id-ref body]
  (let [parsed (parse-rotate-secret-request fn-id-ref body)
        fn-row (delete-secret-fn-row parsed ctx)
        storage (request/require-storage ctx)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)
        path (rotate-secret-path parsed fn-row ctx)]
    (cond
      (nil? fn-row)
      {:ok false :error (str "Secret not found: " fn-id-ref)
       :reason :not-found}

      (not (shape/secret-fn? fn-row secret-leaf-id))
      {:ok false :error (str "fn is not a secret: " fn-id-ref)
       :reason :not-a-secret}

      (not (string? (:value parsed)))
      {:ok false :error "Required field ':value' (string) is missing"}

      (nil? path)
      {:ok false :error "Secret has no :path binding (corrupted state?)"
       :reason :missing-binding}

      :else
      (apply-rotate-secret parsed fn-row path ctx))))
