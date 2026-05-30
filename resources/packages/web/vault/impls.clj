(ns graphden.packages.web.vault.impls
  "Base-fn shims for OpenBao / Vault KV v2.

   The HTTP client lives in `graphden.clients.vault` — that fn is
   reused by `graphden.crud.secrets` for the admin-side Secrets CRUD
   (so the user-facing `:vault-get` and the admin `/api/secrets`
   pipeline share one HTTP code path)."
  (:require
    [graphden.clients.vault :as vault]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defn- require-client!
  [ctx]
  (or (:vault ctx)
      (throw (ex-info "Vault client not configured — set VAULT_ADDR / VAULT_TOKEN"
                      {:type :vault/not-configured}))))


(defbase vault-get
  [path]
  (cr/record-effect! :io)
  (vault/get-secret (require-client! ctx) path))


(defbase secret-leaf
  [in]
  ;; The `:in` arg is auto-derefed by the executor at arg-resolution
  ;; time (see `compile/bindings.clj` `:secret-value` case +
  ;; `compile.clj` `:secret-value` build-args branch). Impl is a
  ;; pure passthrough; we still record `:io` because the executor
  ;; just made a vault call on our behalf and the effect-trace
  ;; needs to reflect that.
  (cr/record-effect! :io)
  in)


(defbase vault-put
  [path value]
  (cr/record-effect! :io)
  (vault/put-secret (require-client! ctx) path value))


(defbase vault-delete
  [path]
  (cr/record-effect! :io)
  (vault/delete-secret (require-client! ctx) path))


(defbase vault-metadata-get
  [path]
  (cr/record-effect! :io)
  (vault/get-metadata (require-client! ctx) path))


(defbase vault-metadata-put
  [path metadata]
  (cr/record-effect! :io)
  (vault/put-metadata (require-client! ctx) path metadata))


(def impls
  {:vault-get vault-get
   :secret-leaf secret-leaf
   :vault-put vault-put
   :vault-delete vault-delete
   :vault-metadata-get vault-metadata-get
   :vault-metadata-put vault-metadata-put})
