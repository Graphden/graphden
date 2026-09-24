(ns graphden.crud.secrets
  "Admin-side secret writes — backed by OpenBao + the `:secret-leaf`
   fn-def shape.

   A secret is represented in graphden as a normal `fn` row with
   `parent-ids=[:secret-leaf]` plus a single `binding` row whose
   `:vault-get` resolver binding carries the KV path in
   `binding.value`. The actual secret VALUE never touches the graphden
   DB — it goes straight to OpenBao via the `graphden.clients.vault`
   client, and the executor auto-derefs the path at arg-resolution time
   (see `compile/bindings.clj` `:secret-value` case).

   The endpoints themselves (list / create / delete / rotate, inline
   bind + rotate) are graph fn-defs in `app/secrets/fns.edn`. This
   namespace holds only what their base-fns (`app/secrets/impls.clj`)
   delegate to: the two journalled write bodies (graphden row +
   OpenBao write, each step recorded for compensation), the journal
   replay that undoes them, and the rotate ownership predicate."
  (:require
    [clojure.tools.logging :as log]
    [graphden.clients.vault :as vault]
    [graphden.crud.entities :as crud-entities]
    [graphden.crud.package-guard :as pkg-guard]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]
    [graphden.versioning.storage.core :as vcore])
  (:import
    (java.util
      UUID)))


(defn- vault-get-fn-id
  "Row id of the `:vault-get` base-fn — the generic resolver every
   secret binding references (`:override-kind :secret-path` retired,
   audit-2 stage 1). Resolved by NAME through the request's storage so
   editor DBs with index-reused ids stay correct; throws when the
   vault package isn't installed (a secret binding without its
   resolver would be an unexecutable row)."
  [ctx]
  (let [storage (request/require-storage ctx)
        ;; BASE-FN filter (`:return-type-fn-id` set is THE base-fn
        ;; marker): per-ns names legally allow a same-named COMPOSED fn
        ;; in any namespace, and `first` of an unordered name query
        ;; could pick it — stamping every new secret binding with a
        ;; wrong resolver. Base-fn bare names stay globally unique, so
        ;; the filtered pick is deterministic.
        row (first (filter :return-type-fn-id
                           (sp/query-entities storage :fn
                                              {:name "vault-get"})))]
    (or (:id row)
        (throw (ex-info ":vault-get base-fn not found — is the web/vault package installed?"
                        {:type :secrets/vault-get-missing})))))


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
   in this state.)

   Reads `(:vault ctx)` first, then falls back to the JVM-wide
   `vault/active-client` atom. The fallback covers per-branch ctx
   builds that don't carry vault forward (branch-router's
   build-branch-ctx). The atom is not a workaround: the client is a
   platform singleton (one Vault per JVM), so the JVM-lifecycle atom
   is its authoritative home — same design as
   `branch-router/active-router-global`. Threading it through every
   per-branch ctx was audited 2026-07 and found safe (compile-eager
   closures take ctx per-call, capture nothing) but redundant."
  [ctx]
  (or (:vault ctx)
      @vault/active-client
      (throw (ex-info "Vault client not configured — set VAULT_ADDR / VAULT_TOKEN"
                      {:type :vault/not-configured}))))


(defn- find-path-slot-id
  "The single arg slot is owned by `:secret-leaf` (its `:in` slot).
   Pick the first fn-slot junction off the owner."
  [storage owner-fn-id]
  (when-let [fs (first (sp/query-entities storage :fn-slot {:fn-id owner-fn-id}))]
    (:slot-id fs)))


(defn path-still-referenced?
  "Does any binding version row — on any branch, purged rows excluded —
   still resolve `path` through a resolver? Two fns may bind the same
   vault path; the value goes only when the last reference is gone."
  [base-storage path]
  (boolean (some :resolver-fn-id
                 (sp/query-entities base-storage :binding-version {:value path}))))


(defn- claim-path!
  "The path a new secret is stored at (`vault/scoped-path` — the tenant's
   org prefix applied, the syntax checked), refusing one another binding
   already references with a 409."
  [storage path]
  (let [p (vault/scoped-path path)]
    ;; Any binding version on any branch — a new secret must not claim a
    ;; path still referenced: its value write would silently replace the
    ;; other binding's secret.
    (when (path-still-referenced? (vcore/unwrap storage) p)
      (throw (ex-info (str "Vault path " p " is already bound by another secret — "
                           "choose a different path, or bind the existing secret by reference")
                      {:type :secrets/path-in-use :path p :http-status 409})))
    p))


(defn- refuse-package-owner!
  "403 when `fn-id` is package-synced — an inline secret binding on it
   would change every descendant and be reverted by the next sync, and
   its value would be written to the vault before that became visible."
  [storage fn-id]
  (when-let [reason (pkg-guard/write-rejection storage :binding {:fn-id fn-id})]
    (throw (ex-info reason {:type :package/owned :http-status 403}))))


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
      (let [data (ex-data exception)]
        (cond-> {:ok false
                 :error (or (ex-message exception) (str exception))
                 ;; Drop `:body` — a vault error's ex-data carries the raw
                 ;; OpenBao HTTP response text, which is internal noise for
                 ;; the API caller and a theoretical secret-echo vector if a
                 ;; proxy mangles it.
                 :data (dissoc data :body :http-status)}
          ;; A guard that declared its status (403 package-owned, 409 path
          ;; in use) — read by `:json-envelope-response`.
          (:http-status data) (assoc :http-status (:http-status data))))
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
        {:keys [nm ns-id value description custom-metadata]} parsed
        path (claim-path! storage (:path parsed))
        path-slot-id (find-path-slot-id storage leaf-id)
        fn-id (UUID/randomUUID)
        binding-id (UUID/randomUUID)]
    ;; Storage FIRST, vault AFTER (mirrors the delete path). The `:fn` create
    ;; carries the `UNIQUE(name, namespace-id)` constraint, so a concurrent
    ;; duplicate-name create loses HERE — before touching vault. Were vault
    ;; put first, the loser's rollback would `vault-delete` the shared path
    ;; that the WINNER's row points at, silently breaking the winner's secret.
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
       :resolver-fn-id (vault-get-fn-id ctx)}
      ctx)
    (swap! journal conj [:storage-delete :binding binding-id])
    ;; Vault only after the row exists (loser never reaches here).
    (vault/put-secret vault-client path value)
    (swap! journal conj [:vault-delete path])
    (let [metadata-ok? (try (vault/put-metadata vault-client path custom-metadata)
                            true
                            (catch Exception e
                              (log/warn e "Vault metadata stamp failed"
                                        {:path path})
                              false))]
      (tc/type-check-fn-after-mutation! storage fn-id)
      {:ok true
       :secret {:id (str fn-id)
                :name nm
                :namespace-id (some-> ns-id str)
                :path path
                :description description
                :metadata-stamped? metadata-ok?}})))


(defn apply-create-inline-binding-body
  "Body of the inline-bind `:try`: storage create, THEN vault-put — the
   same order as `apply-create-secret-body`, so a refused binding (the
   resolver gate, a type rejection, a concurrent duplicate) never touches
   the vault. Refuses a package-synced owner (403) and a path another
   binding references (409) before any write. Records rollback entries on
   the shared `journal` atom; throws on storage / vault failure (caught by
   `:try`)."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        vault-client (require-vault! ctx)
        {:keys [fn-id slot-id value]} parsed
        _ (refuse-package-owner! storage fn-id)
        path (claim-path! storage (:path parsed))
        binding-id (UUID/randomUUID)]
    (crud-entities/create-entity
      :binding
      {:id binding-id
       :fn-id fn-id
       :slot-id slot-id
       :value path
       :resolver-fn-id (vault-get-fn-id ctx)}
      ctx)
    (swap! journal conj [:storage-delete :binding binding-id])
    ;; Vault only after the row exists — the rollback's `:vault-delete`
    ;; then only ever removes a path THIS request claimed.
    (vault/put-secret vault-client path value)
    (swap! journal conj [:vault-delete path])
    {:ok true
     :binding {:id (str binding-id)
               :fn-id (str fn-id)
               :slot-id (str slot-id)
               :path path}}))


(defn rotate-secret-not-owned?
  "C9 guard — a tenant may only rotate a secret its OWN org owns. The
   fn-row is read through the org-scoped storage, so another org's
   secret is already invisible (→ not-found); but a PUBLIC / shared
   secret is read-visible to every tenant, and rotate mutates vault
   directly — skipping the storage write-guard + RLS that `:delete`
   goes through. Without this guard a tenant could rewrite a shared
   secret's value. Platform ctx (`public-org`, unbound) is unrestricted,
   mirroring `tenancy.storage/own?` + `guard-write!`."
  [fn-row]
  (boolean
    (and fn-row
         (not= (tctx/current-org) tctx/public-org)
         (not= (tctx/current-org) (or (:org-id fn-row) tctx/public-org)))))


;; =============================================================================
;; Reclaiming vault values whose bindings are gone
;; =============================================================================
;;
;; The vault is outside graphden's transactions: whoever removes secret
;; bindings for good (the tombstone GC, `vs/delete-branch!`) collects their
;; paths first and hands them here after the commit.

(defn secret-paths
  "The vault paths `binding-versions` point at — an inline secret binding
   (a `:resolver-fn-id` resolver, the `:vault-get` secret) stores its KV
   path as `:value`."
  [binding-versions]
  (into #{} (comp (filter :resolver-fn-id) (map :value) (filter string?))
        binding-versions))


(defn secret-paths-of
  "Vault paths the entity about to be purged points at: a `:binding`
   through its version rows, or a `:fn` through the same rows of the
   bindings it owns. Read BEFORE the purge (the GC's `:before-purge`
   seam), while the rows exist. Other entity kinds hold no secrets."
  [base-storage entity-name id]
  (secret-paths (case entity-name
                  :binding (sp/query-entities base-storage :binding-version {:binding-id id})
                  :fn      (sp/query-entities base-storage :binding-version {:fn-id id})
                  [])))


(defn sweep-orphan-secrets!
  "After storage reclamation (the tombstone GC, a branch delete): delete
   from the vault every collected `path` no binding references any more. A missing vault client (self-host
   without OpenBao) or a failing delete is logged, never thrown — the
   storage reclamation already happened and must not be reported as
   failed."
  [base-storage paths]
  (when (seq paths)
    (if-let [client @vault/active-client]
      (doseq [path paths
              :when (not (path-still-referenced? base-storage path))]
        (try (vault/delete-secret client path)
             (log/info "vault secret reclaimed" {:path path})
             (catch Exception e
               (log/warn e "vault delete failed — manual cleanup" {:path path}))))
      (log/warn "secret bindings removed but no vault client — paths left in the vault"
                {:paths paths}))))
