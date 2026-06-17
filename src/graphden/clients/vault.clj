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
   responses — the editor's error pane labels them uniformly."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http]))


;; Process-wide vault client — set by the `:vault/client` integrant
;; init-key at startup, cleared on halt. Read as a fallback when a
;; consumer doesn't have a ctx-attached client. Mirrors the
;; `system.branch-router/active-router` pattern: shared infrastructure
;; that doesn't fit per-branch ctx (where propagating it triggers an
;; unrelated compile-eager closure-leak on the secrets graph) but
;; logically belongs to the JVM lifecycle anyway — one Vault per JVM.
(defonce ^{:doc "JVM-wide active vault client `{:address … :token …}` or nil."}
  active-client
  (atom nil))


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
  [{:keys [address token]} path]
  (require-path! path "get-secret")
  (let [resp @(http/get (vault-url address "data" path) (request-opts token))
        _ (check-status! resp #{200} path "GET data")
        parsed (json/parse-string (:body resp) true)
        value (get-in parsed [:data :data :value])]
    (when-not (string? value)
      (throw (ex-info (str "Vault secret at " path
                           " is missing `data.value` (expected a string)")
                      {:type :vault/lookup-failed :path path :raw parsed})))
    value))


(defn put-secret
  "Write `secret/data/<path>` with `{value: <value>}`. Returns the
   new version number (KV v2 retains history)."
  [{:keys [address token]} path value]
  (require-path! path "put-secret")
  (let [resp @(http/post (vault-url address "data" path)
                         (json-body token {:data {:value value}}))
        _ (check-status! resp #{200} path "POST data")
        parsed (json/parse-string (:body resp) true)]
    (get-in parsed [:data :version])))


(defn delete-secret
  "Permanently delete every version + metadata. Uses
   `DELETE /v1/secret/metadata/<path>` because the data endpoint
   only soft-deletes the latest version."
  [{:keys [address token]} path]
  (require-path! path "delete-secret")
  (let [resp @(http/delete (vault-url address "metadata" path)
                           (request-opts token))]
    (check-status! resp #{204} path "DELETE metadata")
    nil))


(defn get-metadata
  "Read `created_time`, `current_version`, `custom_metadata`, version
   list, etc. Returns the inner `:data` map (JSON-decoded, keyword
   keys). Raises with `:vault/lookup-failed` if the path is missing."
  [{:keys [address token]} path]
  (require-path! path "get-metadata")
  (let [resp @(http/get (vault-url address "metadata" path)
                        (request-opts token))
        _ (check-status! resp #{200} path "GET metadata")
        parsed (json/parse-string (:body resp) true)]
    (:data parsed)))


(defn put-metadata
  "Replace `custom_metadata` (a map of string→string) wholesale.
   Vault rejects non-string values, so callers must stringify
   before calling."
  [{:keys [address token]} path metadata]
  (require-path! path "put-metadata")
  (let [resp @(http/post (vault-url address "metadata" path)
                         (json-body token {:custom_metadata metadata}))]
    (check-status! resp #{204} path "POST metadata")
    nil))
