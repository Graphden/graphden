(ns graphden.clients.vault
  "Pure Clojure client for OpenBao / Vault KV v2.

   Two consumers:
   - `graphden.packages.web.vault.impls` — wraps each fn in a `defbase`
     so the user fn-graph reads secrets via `:secret-leaf` (which
     the executor auto-derefs through this client).
   - `graphden.crud.secrets` — uses the same fns to manage the
     admin-side Secrets CRUD (create / list / delete / rotate) over
     `/api/secrets/*`.

   All fns take a `client` map `{:address \"http://...\" :token \"...\"}`
   (the same shape produced by the `:vault/client` integrant key) and
   raise `ex-info {:type :vault/lookup-failed}` on non-success
   responses — the editor's error pane labels them uniformly.

   TOKEN LIFECYCLE (assumptions — read before adding a secret path).
   This client is a STATELESS request wrapper: it sends the `:token`
   from its `client` map on every call and NEVER renews, re-auths, or
   inspects the token's TTL. The token is set ONCE from config at
   `:vault/client` init (`system.init.exec`) and held in `active-client`
   for the JVM's life. That is deliberate — token renewal is an ambient,
   time-driven concern that does not belong inside a per-request codec —
   and it makes the deployment contract explicit:

   - The supplied token MUST stay valid for the process lifetime. Use a
     PERIODIC or long-TTL token, OR (recommended for prod) point
     `:address` at a Vault Agent / OpenBao Agent sidecar that injects and
     transparently renews the token, so this client always sees a fresh
     one without ever handling renewal itself.
   - A token that EXPIRES mid-run is a configuration error, not a bug
     this client masks: Vault then answers 403, which surfaces as the
     normal `:vault/lookup-failed` (via `check-status!`) — a loud,
     labelled failure, not silent bad data. Operators rotate by restarting
     with a fresh token (or via the Agent sidecar above); there is no
     in-process re-auth to get subtly wrong.

   If auto-renew is ever wanted in-process, it belongs in a SEPARATE
   lifecycle component (a scheduled `auth/token/renew-self` against
   `active-client`), NOT threaded through these read/write fns."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http]))


;; Process-wide vault client — set by the `:vault/client` integrant
;; init-key at startup, cleared on halt. Read as a fallback when a
;; consumer doesn't have a ctx-attached client. Mirrors the
;; `system.branch-router/active-router` pattern: shared infrastructure
;; that logically belongs to the JVM lifecycle — one Vault per JVM, a
;; platform singleton, so the atom is its authoritative home rather
;; than a per-branch ctx copy. (An older comment blamed a
;; "compile-eager closure-leak" for the ctx drop; audited 2026-07 —
;; closures take ctx per-call and capture nothing, no such leak.)
(defonce ^{:doc "JVM-wide active vault client `{:address … :token …}` or nil."}
  active-client
  (atom nil))


;; =============================================================================
;; Parallel-test seam
;; =============================================================================

(def ^:dynamic *impl-override*
  "Parallel-test seam: a map of `{op-keyword → fn}` shadowing the real
   HTTP implementation of the correspondingly-named vault operation
   (`:get-secret` `:put-secret` `:delete-secret` `:get-metadata`
   `:put-metadata`). Each override fn receives the same args as the
   public fn (client map first). nil (production) = every call performs
   the real OpenBao request. Tests `binding` this instead of
   `with-redefs`-ing the root vars — a root rebind is process-global
   and forced a `^:serial` pin on `crud.secrets-test` (a sibling NS
   exercising real vault calls during the window would have seen the
   fake). Mirrors `advisory-lock/*impl-override*`. Cost on the real
   path: one nil-map lookup per vault call."
  nil)


(defn- impl
  [op]
  (get *impl-override* op))


(defn- require-path!
  "Reject nil / non-string / blank paths upfront with a clean
   `:vault/lookup-failed :reason :missing-path` ex-info. The string-
   ops in `data-url` / `metadata-url` would otherwise NPE with no
   `:type` tag — masking the real cause (binding misconfiguration
   upstream) as a generic NullPointerException."
  [path op]
  (when (or (nil? path) (not (string? path)) (str/blank? path))
    (throw (ex-info (str "Vault " op ": path is required and must be a non-blank string")
                    {:type :vault/lookup-failed
                     :reason :missing-path
                     :op op
                     :path path}))))


(defn- vault-url
  "Build a Vault KV v2 path-rooted URL. `kind` is `\"data\"`
   (per-version value rows) or `\"metadata\"` (version index +
   `custom_metadata`); same address/path normalisation applies to
   both."
  [address kind path]
  (str (str/replace address #"/+$" "")
       "/v1/secret/" kind "/"
       (str/replace path #"^/+" "")))


(defn- request-opts
  "Shared http-kit request options — token header, 5 s timeout, text
   body. `extra` lets a writer add the JSON `Content-Type` + body."
  [token & {:as extra}]
  (merge {:headers {"X-Vault-Token" token}
          :timeout 5000
          :as :text}
         extra))


(defn- json-body
  [token body]
  {:headers {"X-Vault-Token" token
             "Content-Type" "application/json"}
   :timeout 5000
   :as :text
   :body (json/generate-string body)})


(defn- check-status!
  "Raise `:vault/lookup-failed` unless the response status is in
   `expected`. http-kit packs network failures into `:error`, status
   mismatches into `:status` — surface both with the same canonical
   `:type` so callers can pattern-match on one thing."
  [{:keys [status body error]} expected path op]
  (cond
    error
    ;; `Throwable/.getMessage` can return nil (`new IOException()` for
    ;; example) — the outer `str` already coerces nil to "", so the
    ;; panic surface stays a non-null sentence without a redundant
    ;; nested wrap.
    (throw (ex-info (str "Vault request failed: " (Throwable/.getMessage error))
                    {:type :vault/lookup-failed :path path :op op}))

    (not (contains? expected status))
    (throw (ex-info (str "Vault returned " status " for " op " " path)
                    {:type :vault/lookup-failed :path path :op op :status status :body body}))))


(defn get-secret
  "Read `secret/data/<path>` and return the inner `data.data.value`
   string. Raises if missing or shape doesn't match the single-value
   convention."
  [{:keys [address token] :as client} path]
  (if-let [f (impl :get-secret)]
    (f client path)
    (do
      (require-path! path "get-secret")
      (let [resp @(http/get (vault-url address "data" path) (request-opts token))
            _ (check-status! resp #{200} path "GET data")
            parsed (json/parse-string (:body resp) true)
            value (get-in parsed [:data :data :value])]
        (when-not (string? value)
          ;; Do NOT put `parsed` in ex-data — for a KV v2 read it embeds the
          ;; secret material itself, and this ex-data is persisted verbatim into
          ;; a fn-execution's API-readable `:error-data` (redaction only fires
          ;; for `:secret`-typed RETURNS, so a fn that merely reads a secret
          ;; would leak it). The path + a class hint are enough to debug.
          (throw (ex-info (str "Vault secret at " path
                               " is missing `data.value` (expected a string)")
                          {:type :vault/lookup-failed :path path
                           :value-class (some-> value class .getName)})))
        value))))


(defn put-secret
  "Write `secret/data/<path>` with `{value: <value>}`. Returns the
   new version number (KV v2 retains history)."
  [{:keys [address token] :as client} path value]
  (if-let [f (impl :put-secret)]
    (f client path value)
    (do
      (require-path! path "put-secret")
      (let [resp @(http/post (vault-url address "data" path)
                             (json-body token {:data {:value value}}))
            _ (check-status! resp #{200} path "POST data")
            parsed (json/parse-string (:body resp) true)]
        (get-in parsed [:data :version])))))


(defn delete-secret
  "Permanently delete every version + metadata. Uses
   `DELETE /v1/secret/metadata/<path>` because the data endpoint
   only soft-deletes the latest version."
  [{:keys [address token] :as client} path]
  (if-let [f (impl :delete-secret)]
    (f client path)
    (do
      (require-path! path "delete-secret")
      (let [resp @(http/delete (vault-url address "metadata" path)
                               (request-opts token))]
        (check-status! resp #{204} path "DELETE metadata")
        nil))))


(defn get-metadata
  "Read `created_time`, `current_version`, `custom_metadata`, version
   list, etc. Returns the inner `:data` map (JSON-decoded, keyword
   keys). Raises with `:vault/lookup-failed` if the path is missing."
  [{:keys [address token] :as client} path]
  (if-let [f (impl :get-metadata)]
    (f client path)
    (do
      (require-path! path "get-metadata")
      (let [resp @(http/get (vault-url address "metadata" path)
                            (request-opts token))
            _ (check-status! resp #{200} path "GET metadata")
            parsed (json/parse-string (:body resp) true)]
        (:data parsed)))))


(defn put-metadata
  "Replace `custom_metadata` (a map of string→string) wholesale.
   Vault rejects non-string values, so callers must stringify
   before calling."
  [{:keys [address token] :as client} path metadata]
  (if-let [f (impl :put-metadata)]
    (f client path metadata)
    (do
      (require-path! path "put-metadata")
      (let [resp @(http/post (vault-url address "metadata" path)
                             (json-body token {:custom_metadata metadata}))]
        (check-status! resp #{204} path "POST metadata")
        nil))))
