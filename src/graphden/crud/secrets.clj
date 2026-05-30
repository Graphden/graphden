(ns graphden.crud.secrets
  "Admin-side CRUD for secrets — backed by OpenBao + the user's
   `:vault-get` fn-def shape.

   A secret is represented in graphden as a normal `fn` row with
   `parent-ids=[:vault-get]` plus a single `binding` row carrying
   the KV path in `binding.value`. The actual secret VALUE never
   touches the graphden DB — it goes straight to OpenBao via the
   `graphden.clients.vault` client. This module orchestrates both
   sides: graphden-row + OpenBao-write happen together with
   compensation rollback if either side fails.

   Endpoints (see `app/secrets/fns.edn`):
   - `GET /api/secrets`          → `list-secrets`
   - `POST /api/secrets`         → `create-secret`
   - `DELETE /api/secrets/:id`   → `delete-secret`
   - `PUT /api/secrets/:id/value` → `rotate-secret`"
  (:require
    [clojure.string :as str]
    [graphden.clients.vault :as vault]
    [graphden.crud.entities :as crud-entities]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as shape]
    [graphden.crud.type-check :as tc]
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.util
      UUID)))


(defn- require-vault!
  "The admin path treats a missing vault as a hard error — without
   OpenBao there's nowhere to store the value. (User `:vault-get`
   calls also fail with `:vault/not-configured` in this state.)"
  [ctx]
  (or (:vault ctx)
      (throw (ex-info "Vault client not configured — set VAULT_ADDR / VAULT_TOKEN"
                      {:type :vault/not-configured}))))


(defn- find-path-slot-id
  "The single arg slot is owned by the secret base-fn (`:vault-get`
   in the legacy shape with slot `:path`; `:secret-leaf` in the
   Followup-4 shape with slot `:in`). Each base-fn has exactly one
   slot — pick the first fn-slot junction off the owner."
  [storage owner-fn-id]
  (when-let [fs (first (sp/query-entities storage :fn-slot {:fn-id owner-fn-id}))]
    (:slot-id fs)))


(defn- secret-binding-path
  "Read the path string out of a secret-fn-def's binding on the
   `:path` slot. Returns nil when no binding exists (corrupted state)."
  [storage fn-id path-slot-id]
  (some-> (first (sp/query-entities storage :binding {:fn-id fn-id
                                                      :slot-id path-slot-id}))
          :value))


(defn- find-usages
  "Find every fn that references `fn-id`:
   - as a parent (`parent-ids` :ref-many — reverse junction-index
     via `sp/query-ref-many-owners`, O(log n))
   - via a binding (`binding.ref-fn-id` :ref — equality filter)
   - via a sequence item (`binding-list-item.ref-fn-id` :ref)
   Returns a vec of `{:fn-id :name :reason}` maps, deduplicated."
  [storage fn-id]
  (let [parent-owner-ids (sp/query-ref-many-owners storage :fn :parent-ids fn-id)
        as-binding (sp/query-entities storage :binding {:ref-fn-id fn-id})
        as-list-item (sp/query-entities storage :binding-list-item {:ref-fn-id fn-id})
        list-item-binding-fn-ids (keep (fn [li]
                                         (some-> (sp/read-entity storage :binding (:binding-id li))
                                                 :fn-id))
                                       as-list-item)
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

   `shape` is `\"secret-leaf\"` (new model) or `\"vault-get\"` (legacy
   — needs migration via POST /api/secrets/:fn-id/migrate)."
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
  "GET /api/secrets — every fn-def with parent=`[:vault-get]` OR
   parent=`[:secret-leaf]`, enriched with OpenBao metadata. Secret
   VALUES are never returned."
  [ctx]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        vault-get-id (shape/find-vault-get-fn-id storage)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)]
    (if-not (or vault-get-id secret-leaf-id)
      {:ok false :error ":vault-get / :secret-leaf base-fn not found — package web.vault not loaded?"}
      (let [;; Each base-fn owns its single arg slot (`:path` for
            ;; vault-get, `:in` for secret-leaf). We need both to
            ;; read the path out of each candidate's binding row.
            vault-get-path-slot-id (when vault-get-id
                                     (find-path-slot-id storage vault-get-id))
            secret-leaf-path-slot-id (when secret-leaf-id
                                       (find-path-slot-id storage secret-leaf-id))
            ;; Reverse junction-index: owners of EITHER vault-get
            ;; OR secret-leaf as a parent. Cheap because the index
            ;; is per-target.
            owner-ids (distinct
                        (concat
                          (when vault-get-id
                            (sp/query-ref-many-owners storage :fn :parent-ids vault-get-id))
                          (when secret-leaf-id
                            (sp/query-ref-many-owners storage :fn :parent-ids secret-leaf-id))))
            candidates (when (seq owner-ids)
                         (sp/query-entities storage :fn {:id (vec owner-ids)}))
            secrets (->> candidates
                         (filter #(shape/secret-fn? % vault-get-id secret-leaf-id))
                         (mapv (fn [fn-row]
                                 (let [ps (vec (:parent-ids fn-row))
                                       [slot-id shape] (condp = ps
                                                         [secret-leaf-id] [secret-leaf-path-slot-id "secret-leaf"]
                                                         [vault-get-id]   [vault-get-path-slot-id "vault-get"]
                                                         [nil nil])]
                                   (shape-secret storage vault-client fn-row slot-id shape)))))]
        {:ok true :secrets secrets}))))


(defn- parse-uuid-loose
  "Accept either a UUID or its string form. Returns nil when the
   input is blank / malformed."
  [v]
  (cond
    (uuid? v) v
    (string? v) (request/parse-uuid-or-clear v)
    :else nil))


(defn create-secret
  "POST /api/secrets — atomically create the OpenBao value, the
   OpenBao custom-metadata, and the graphden fn-row + path-binding.
   On any failure, rolls back partial writes (vault-delete if the
   graphden side fails)."
  [ctx body]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        nm (some-> (:name body) str str/trim)
        ns-id (parse-uuid-loose (:namespace-id body))
        path (some-> (:path body) str str/trim)
        value (:value body)
        description (some-> (:description body) str)
        custom-metadata (cond-> {}
                          (and description (seq description)) (assoc :description description))]
    (cond
      (str/blank? nm)
      {:ok false :error "Required field ':name' is missing"}

      (str/blank? path)
      {:ok false :error "Required field ':path' is missing"}

      (not (string? value))
      {:ok false :error "Required field ':value' (string) is missing"}

      :else
      (let [secret-leaf-id (shape/find-secret-leaf-fn-id storage)
            path-slot-id (when secret-leaf-id (find-path-slot-id storage secret-leaf-id))]
        (cond
          (or (nil? secret-leaf-id) (nil? path-slot-id))
          {:ok false :error ":secret-leaf base-fn missing — package web.vault not loaded"}

          (seq (sp/query-entities storage :fn {:name nm :namespace-id ns-id}))
          {:ok false :error (str "fn already exists with name: " nm)
           :reason :name-taken}

          :else
          (let [;; OpenBao first — easy to roll back via vault-delete.
                _ (vault/put-secret vault-client path value)
                metadata-ok? (try (vault/put-metadata vault-client path custom-metadata)
                                  true
                                  (catch Exception _
                                    ;; Metadata is best-effort; the value
                                    ;; is in. Don't fail the create.
                                    false))
                fn-id (UUID/randomUUID)
                binding-id (UUID/randomUUID)]
            ;; Go through `crud.entities/create-entity` so write-rej
            ;; validation runs AND the compiled-registry / graph cache
            ;; gets invalidated — without that, /api/graph/layout for
            ;; this new fn 500s with "Root function not found".
            (try
              (crud-entities/create-entity
                :fn
                (cond-> {:id fn-id
                         :name nm
                         :parent-ids [secret-leaf-id]
                         ;; In-memory marker so the capability gate
                         ;; in `crud.entities/create-entity` lets the
                         ;; admin path through. Stripped before the
                         ;; row reaches storage — never persisted.
                         :_admin-secret-create true}
                  ns-id (assoc :namespace-id ns-id)
                  (and description (seq description)) (assoc :description description))
                ctx)
              ;; Binding-IS-secret model (F-4): :override-kind
              ;; :secret-path tells the executor to dereference via
              ;; vault at arg-resolution time. The binding's :value
              ;; is the path (visible in graphden); the actual secret
              ;; value never persists here.
              (crud-entities/create-entity
                :binding
                {:id binding-id
                 :fn-id fn-id
                 :slot-id path-slot-id
                 :value path
                 :override-kind :secret-path}
                ctx)
              ;; Type-check the new fn-def so its rich-type (in
              ;; particular `[:secret :text]` inherited from
              ;; `:secret-leaf`'s return) lands in the registry.
              ;; Without this, `/api/execute`'s `tainted-fn?` check
              ;; doesn't see the marker and the result-hide layer
              ;; (T4) doesn't fire — secret leaks into the response.
              ;; `apply-create` (the form-driven path) already does
              ;; an equivalent post-mutation check; this is the
              ;; admin-CRUD parallel.
              (tc/type-check-fn-after-mutation! storage fn-id)
              {:ok true
               :secret {:id (str fn-id)
                        :name nm
                        :namespace-id (some-> ns-id str)
                        :path path
                        :description description
                        :metadata-stamped? metadata-ok?}}
              (catch Exception t
                ;; Graphden write failed — vault-delete to keep stores in sync.
                (try (vault/delete-secret vault-client path)
                     (catch Exception _ nil))
                (try (sp/delete-entity storage :binding binding-id)
                     (catch Exception _ nil))
                (try (sp/delete-entity storage :fn fn-id)
                     (catch Exception _ nil))
                {:ok false
                 :error (or (ex-message t) (str t))
                 :data (ex-data t)}))))))))


(defn migrate-to-secret-leaf
  "POST /api/secrets/:fn-id/migrate — convert a legacy `:vault-get`-
   shaped secret fn-def to the new `:secret-leaf` shape:

   1. Legacy:   parent-ids=[:vault-get], binding on `:path` slot, no override.
   2. Migrated: parent-ids=[:secret-leaf], binding on `:in` slot, `:override-kind :secret-path`.

   Vault contents are NOT touched — the path stays the same.

   Order matters: delete old binding → switch parent-ids → create new
   binding. Doing it in a different order leaves the binding's
   `:slot-id` referencing a slot the new parent doesn't inherit
   (`write-rej` would reject the parent-ids update). Failures
   mid-sequence leave the row in an inconsistent state — caller must
   re-attempt or restore from backup. We do NOT attempt to roll back
   automatically because either (a) the storage operation succeeded
   (rollback would itself fail symmetrically) or (b) the failure is
   the validation gate, in which case the previous state is still
   intact and no rollback is needed."
  [ctx fn-id-ref]
  (let [storage (request/require-storage ctx)
        fn-id (parse-uuid-loose fn-id-ref)
        fn-row (when fn-id (sp/read-entity storage :fn fn-id))
        vault-get-id (shape/find-vault-get-fn-id storage)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)]
    (cond
      (nil? fn-row)
      {:ok false :error (str "Secret not found: " fn-id-ref) :reason :not-found}

      (or (nil? vault-get-id) (nil? secret-leaf-id))
      {:ok false :error ":vault-get / :secret-leaf base-fn not found — package web.vault not loaded"}

      (not= [vault-get-id] (vec (:parent-ids fn-row)))
      {:ok false :error (str "fn is not legacy :vault-get-shaped (parent-ids != [:vault-get]): " fn-id-ref)
       :reason :not-legacy-shape}

      :else
      (let [legacy-path-slot-id (find-path-slot-id storage vault-get-id)
            new-in-slot-id (find-path-slot-id storage secret-leaf-id)
            legacy-binding (first (sp/query-entities storage :binding {:fn-id fn-id
                                                                       :slot-id legacy-path-slot-id}))]
        (cond
          (nil? legacy-binding)
          {:ok false :error "Legacy secret has no :path binding (corrupted state?)"
           :reason :missing-binding}

          (nil? new-in-slot-id)
          {:ok false :error ":secret-leaf has no inherited slot — package web.vault corrupt?"
           :reason :missing-slot}

          :else
          (let [path (:value legacy-binding)]
            (crud-entities/delete-entity :binding (:id legacy-binding) ctx)
            (crud-entities/update-entity :fn fn-id {:parent-ids [secret-leaf-id]} ctx)
            (crud-entities/create-entity
              :binding
              {:id (UUID/randomUUID)
               :fn-id fn-id
               :slot-id new-in-slot-id
               :value path
               :override-kind :secret-path}
              ctx)
            (tc/type-check-fn-after-mutation! storage fn-id)
            {:ok true :id (str fn-id) :name (:name fn-row) :path path}))))))


(defn create-inline-binding
  "POST /api/secrets/binding — write VALUE to vault at PATH, then create
   a `:secret-path`-kinded binding on (`fn-id`, `slot-id`) so the user's
   fn picks the secret up at exec time. Skips the wrapper-fn-def step
   (`create-secret` above) — the binding IS the secret-fetch.

   `crud-entities/create-entity` runs `secret-path-rej`, which rejects
   when the slot's declared type doesn't contain `:secret`. On graphden
   failure (gate reject OR uniqueness collision) we vault-delete the
   path so the stores stay in sync."
  [ctx body]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        fn-id (parse-uuid-loose (:fn-id body))
        slot-id (parse-uuid-loose (:slot-id body))
        path (some-> (:path body) str str/trim)
        value (:value body)]
    (cond
      (nil? fn-id) {:ok false :error "Required field ':fn-id' is missing or malformed"}
      (nil? slot-id) {:ok false :error "Required field ':slot-id' is missing or malformed"}
      (str/blank? path) {:ok false :error "Required field ':path' is missing"}
      (not (string? value)) {:ok false :error "Required field ':value' (string) is missing"}

      (nil? (sp/read-entity storage :fn fn-id))
      {:ok false :error (str "fn not found: " fn-id)}

      (seq (sp/query-entities storage :binding {:fn-id fn-id :slot-id slot-id}))
      {:ok false :error "A binding already exists for this fn+slot — delete it first"
       :reason :binding-exists}

      :else
      (let [_ (vault/put-secret vault-client path value)
            binding-id (UUID/randomUUID)]
        (try
          (crud-entities/create-entity
            :binding
            {:id binding-id
             :fn-id fn-id
             :slot-id slot-id
             :value path
             :override-kind :secret-path}
            ctx)
          {:ok true
           :binding {:id (str binding-id)
                     :fn-id (str fn-id)
                     :slot-id (str slot-id)
                     :path path}}
          (catch Exception t
            (try (vault/delete-secret vault-client path) (catch Exception _ nil))
            {:ok false
             :error (or (ex-message t) (str t))
             :data (ex-data t)}))))))


(defn- path-slot-for-fn-row
  "Pick the correct path-slot id for `fn-row` based on which secret
   base-fn it inherits from (legacy `:vault-get` or Followup-4
   `:secret-leaf`). Both base-fns own a single arg slot — the
   `find-path-slot-id` helper handles that uniformly; we just need
   to feed it the right owner id."
  [storage fn-row vault-get-id secret-leaf-id]
  (let [ps (vec (:parent-ids fn-row))
        owner-id (condp = ps
                   [secret-leaf-id] secret-leaf-id
                   [vault-get-id]   vault-get-id
                   nil)]
    (when owner-id (find-path-slot-id storage owner-id))))


(defn delete-secret
  "DELETE /api/secrets/:fn-id — hard delete (graphden row + every
   OpenBao version + metadata). Rejected if ANY fn references this
   secret (`:reason :secret-in-use`). Caller must re-target or
   remove dependents first."
  [ctx fn-id-ref]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        fn-id (parse-uuid-loose fn-id-ref)
        fn-row (when fn-id (sp/read-entity storage :fn fn-id))
        vault-get-id (shape/find-vault-get-fn-id storage)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)
        path-slot-id (when fn-row
                       (path-slot-for-fn-row storage fn-row vault-get-id secret-leaf-id))]
    (cond
      (nil? fn-row)
      {:ok false :error (str "Secret not found: " fn-id-ref)
       :reason :not-found}

      (not (shape/secret-fn? fn-row vault-get-id secret-leaf-id))
      {:ok false :error (str "fn is not a secret (parent != [:vault-get|:secret-leaf]): " fn-id-ref)
       :reason :not-a-secret}

      :else
      (let [usages (find-usages storage fn-id)]
        (cond
          (seq usages)
          {:ok false :error (str "Secret is referenced by " (count usages) " fn(s) — remove or re-target dependents first")
           :reason :secret-in-use
           :usages (mapv (fn [u]
                           {:fn-id (str (:fn-id u))
                            :name (:name u)
                            :reason (:reason u)})
                         usages)}

          :else
          (let [path (secret-binding-path storage fn-id path-slot-id)
                binding-row (first (sp/query-entities storage :binding {:fn-id fn-id
                                                                        :slot-id path-slot-id}))]
            ;; Vault first; if graphden delete fails after, the row
            ;; is harmless (path-binding still resolves at runtime
            ;; will 404), and re-issuing DELETE will clean up.
            (try (vault/delete-secret vault-client path)
                 (catch Exception _ nil))
            ;; Both deletes go through crud.entities/delete-entity so
            ;; the graph cache + compiled-registry invalidate alongside
            ;; the row removal.
            (when binding-row (crud-entities/delete-entity :binding (:id binding-row) ctx))
            (crud-entities/delete-entity :fn fn-id ctx)
            {:ok true :id (str fn-id) :name (:name fn-row) :path path}))))))


(defn rotate-secret
  "PUT /api/secrets/:fn-id/value — writes a new value to OpenBao at
   the same path (KV v2 retains the previous version under
   `?version=N`). graphden state is unchanged."
  [ctx fn-id-ref body]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        fn-id (parse-uuid-loose fn-id-ref)
        fn-row (when fn-id (sp/read-entity storage :fn fn-id))
        vault-get-id (shape/find-vault-get-fn-id storage)
        secret-leaf-id (shape/find-secret-leaf-fn-id storage)
        path-slot-id (when fn-row
                       (path-slot-for-fn-row storage fn-row vault-get-id secret-leaf-id))
        value (:value body)]
    (cond
      (nil? fn-row)
      {:ok false :error (str "Secret not found: " fn-id-ref)
       :reason :not-found}

      (not (shape/secret-fn? fn-row vault-get-id secret-leaf-id))
      {:ok false :error (str "fn is not a secret: " fn-id-ref)
       :reason :not-a-secret}

      (not (string? value))
      {:ok false :error "Required field ':value' (string) is missing"}

      :else
      (let [path (secret-binding-path storage fn-id path-slot-id)]
        (if-not path
          {:ok false :error "Secret has no :path binding (corrupted state?)"
           :reason :missing-binding}
          (let [version (vault/put-secret vault-client path value)]
            {:ok true :id (str fn-id) :name (:name fn-row) :path path :version version}))))))
