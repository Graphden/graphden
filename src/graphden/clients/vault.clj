(ns graphden.clients.vault
  "Pure Clojure client for OpenBao / Vault KV v2.

   Two consumers:
   - `graphden.packages.web.vault.impls` — wraps each fn in a `defbase`
     so the user fn-graph can read secrets via `:vault-get`.
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


(defn- data-url
  [address path]
  (str (str/replace address #"/+$" "")
       "/v1/secret/data/"
       (str/replace path #"^/+" "")))


(defn- metadata-url
  [address path]
  (str (str/replace address #"/+$" "")
       "/v1/secret/metadata/"
       (str/replace path #"^/+" "")))


(defn- check-status!
  "Raise `:vault/lookup-failed` unless the response status is in
   `expected`. http-kit packs network failures into `:error`, status
   mismatches into `:status` — surface both with the same canonical
   `:type` so callers can pattern-match on one thing."
  [{:keys [status body error]} expected path op]
  (cond
    error
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
  (let [resp @(http/get (data-url address path)
                        {:headers {"X-Vault-Token" token}
                         :timeout 5000
                         :as :text})
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
  (let [resp @(http/post (data-url address path)
                         {:headers {"X-Vault-Token" token
                                    "Content-Type" "application/json"}
                          :body (json/generate-string {:data {:value value}})
                          :timeout 5000
                          :as :text})
        _ (check-status! resp #{200} path "POST data")
        parsed (json/parse-string (:body resp) true)]
    (get-in parsed [:data :version])))


(defn delete-secret
  "Permanently delete every version + metadata. Uses
   `DELETE /v1/secret/metadata/<path>` because the data endpoint
   only soft-deletes the latest version."
  [{:keys [address token]} path]
  (let [resp @(http/delete (metadata-url address path)
                           {:headers {"X-Vault-Token" token}
                            :timeout 5000
                            :as :text})]
    (check-status! resp #{204} path "DELETE metadata")
    nil))


(defn get-metadata
  "Read `created_time`, `current_version`, `custom_metadata`, version
   list, etc. Returns the inner `:data` map (JSON-decoded, keyword
   keys). Raises with `:vault/lookup-failed` if the path is missing."
  [{:keys [address token]} path]
  (let [resp @(http/get (metadata-url address path)
                        {:headers {"X-Vault-Token" token}
                         :timeout 5000
                         :as :text})
        _ (check-status! resp #{200} path "GET metadata")
        parsed (json/parse-string (:body resp) true)]
    (:data parsed)))


(defn put-metadata
  "Replace `custom_metadata` (a map of string→string) wholesale.
   Vault rejects non-string values, so callers must stringify
   before calling."
  [{:keys [address token]} path metadata]
  (let [resp @(http/post (metadata-url address path)
                         {:headers {"X-Vault-Token" token
                                    "Content-Type" "application/json"}
                          :body (json/generate-string {:custom_metadata metadata})
                          :timeout 5000
                          :as :text})]
    (check-status! resp #{204} path "POST metadata")
    nil))
