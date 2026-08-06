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
   per-branch ctx builds don't carry vault forward, and the atom is
   the authoritative home for a platform-singleton client (one Vault
   per JVM), so the JVM-wide read is the load-bearing path here."
  [ctx]
  (or (:vault ctx)
      @vault/active-client
      (throw (ex-info "Vault client not configured — set VAULT_ADDR / VAULT_TOKEN"
                      {:type :vault/not-configured}))))


(defn- operator-only!
  "The raw vault ops take an ARBITRARY path against the JVM-wide platform
   token, so they bypass per-org secret isolation (the KV namespace is flat) —
   they are PLATFORM-ONLY. Refuse a restricted (tenant) graph execution:
   `cr/*allowed-effects*` is non-nil ONLY inside the cloud sandbox, nil for the
   unrestricted platform ctx. (The operator's `/api/secrets` CRUD calls the
   vault client DIRECTLY, not these base-fns, so it is unaffected.) Without
   this, a paid-tier tenant — which carries `:network` — could compose
   `{:parent :vault-get :args {:path \"other-org/secret\"}}` and read (or
   `:vault-put` overwrite) another org's secret. `:secret-leaf` is EXEMPT: its
   path is bound at COMPILE time from an operator-authored `:secret`, never
   tenant-arbitrary."
  [op]
  (when (some? cr/*allowed-effects*)
    (throw (ex-info (str "Vault " op " is operator-only — a tenant graph cannot "
                         "read or write raw secret paths (per-org isolation)")
                    {:type :vault/operator-only :op op}))))


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
  (operator-only! "put")
  (cr/record-effect! :network)
  (vault/put-secret (require-client! ctx) path value))


(defbase vault-delete
  [path]
  (operator-only! "delete")
  (cr/record-effect! :network)
  (vault/delete-secret (require-client! ctx) path))


(defbase vault-get
  [path]
  (operator-only! "get")
  (cr/record-effect! :network)
  (vault/get-secret (require-client! ctx) path))


(defbase vault-metadata-get
  [path]
  (operator-only! "metadata-get")
  (cr/record-effect! :network)
  (vault/get-metadata (require-client! ctx) path))


(defbase vault-metadata-put
  [path metadata]
  (operator-only! "metadata-put")
  (cr/record-effect! :network)
  (vault/put-metadata (require-client! ctx) path metadata))


(def impls
  {:secret-leaf secret-leaf
   :vault-get vault-get
   :vault-put vault-put
   :vault-delete vault-delete
   :vault-metadata-get vault-metadata-get
   :vault-metadata-put vault-metadata-put})
