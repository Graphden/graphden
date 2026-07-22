(ns graphden.packages.web.vault.impls
  "Base-fn shims for OpenBao / Vault KV v2.

   The HTTP client lives in `graphden.clients.vault` — that fn is
   reused by `graphden.crud.secrets` for the admin-side Secrets CRUD
   (so the secret-leaf executor-side deref and the admin
   `/api/secrets` pipeline share one HTTP code path)."
  (:require
    [graphden.clients.vault :as vault]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defn- require-client!
  "Read `(:vault ctx)`, falling back to the JVM-wide
   `vault/active-client` atom. Mirrors `crud.secrets/require-vault!` —
   per-branch ctx builds don't carry vault forward (propagating it
   exposes a separate compile-eager closure-leak), so the JVM-wide
   atom is the load-bearing path here."
  [ctx]
  (or (:vault ctx)
      @vault/active-client
      (throw (ex-info "Vault client not configured — set VAULT_ADDR / VAULT_TOKEN"
                      {:type :vault/not-configured}))))


(defbase secret-leaf
  [in]
  ;; The `:in` arg is auto-derefed by the executor at arg-resolution
  ;; time (see `compile/bindings.clj` `:secret-value` case +
  ;; `compile.clj` `:secret-value` build-args branch). Impl is a
  ;; pure passthrough; we still record `:network` because the
  ;; executor just made a vault HTTP call on our behalf and the
  ;; effect-trace needs to reflect that. Tagged `:network` (not
  ;; `:io`) for consistency with sibling HTTP wrappers
  ;; (`web/http-client`, `web/http`, `web/branch-router`) that all
  ;; tag outbound HTTP as `:network`.
  (cr/record-effect! :network)
  in)


(defbase vault-put
  [path value]
  (cr/record-effect! :network)
  (vault/put-secret (require-client! ctx) path value))


(defbase vault-delete
  [path]
  (cr/record-effect! :network)
  (vault/delete-secret (require-client! ctx) path))


(defbase vault-get
  [path]
  (cr/record-effect! :network)
  (vault/get-secret (require-client! ctx) path))


(defbase vault-metadata-get
  [path]
  (cr/record-effect! :network)
  (vault/get-metadata (require-client! ctx) path))


(defbase vault-metadata-put
  [path metadata]
  (cr/record-effect! :network)
  (vault/put-metadata (require-client! ctx) path metadata))


(def impls
  {:secret-leaf secret-leaf
   :vault-get vault-get
   :vault-put vault-put
   :vault-delete vault-delete
   :vault-metadata-get vault-metadata-get
   :vault-metadata-put vault-metadata-put})
