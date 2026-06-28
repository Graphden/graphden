(ns graphden.tenancy.auth
  "Multi-tenant AuthProvider for the tenancy addon (PLATFORM_PLAN §3.0).

   Resolves a request to `{:authenticated? :user :org}` so that — via the
   B4 request-scope seam — OrgScopedStorage scopes every row to the
   caller's org. The core single-token provider only ever returns
   `:authenticated?`; THIS provider adds the `:org` that turns the addon
   from a safe no-op into real multi-tenancy.

   Backed by a pluggable token lookup. `token-map-provider` ships a
   static-map backing — tokens are SHA-256-hashed at construction and a
   request's bearer is matched by hash, so the comparison is fixed-shape
   (not a per-byte string compare) and raw tokens aren't held in memory. A
   real deployment swaps the lookup for a storage- or secret-backed one
   without touching the provider."
  (:require
    [clojure.string :as str]
    [graphden.auth.provider :as auth]
    [graphden.storage.protocol.core :as sp]))


(defn- sha256-hex
  [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (java.security.MessageDigest/.digest digest (String/.getBytes s "UTF-8"))]
    (str/join (map #(format "%02x" (bit-and % 0xff)) bytes))))


(defrecord TokenAuthProvider
  [lookup]

  auth/AuthProvider

  (authenticate
    [_ request]
    ;; bearer → principal-or-nil via the injected lookup. A hit is marked
    ;; authenticated; anything else fails closed.
    (if-let [principal (some-> (auth/extract-bearer request) lookup)]
      (assoc principal :authenticated? true)
      {:authenticated? false})))


(defn token-map-provider
  "An `AuthProvider` over a static `{token → {:user … :org …}}` map. Tokens
   are hashed at construction; a request's bearer is matched by hash."
  [token->principal]
  (let [by-hash (into {} (map (fn [[t p]] [(sha256-hex t) p])) token->principal)]
    (->TokenAuthProvider
      (fn [token]
        (when-not (str/blank? token)
          (get by-hash (sha256-hex token)))))))


(defn token-hash
  "SHA-256 hex of a bearer token — the value stored in `:token.token-hash`.
   The single canonical hashing for storage-backed tokens; the `create-token`
   base-fn computes the same value at write time (the round-trip is tested)."
  [token]
  (sha256-hex token))


(defn- token-live?
  "A `:token` row is usable iff it has no expiry (operator API key) or its
   expiry (`:expires-at`, epoch millis) is still in the future. Expired session
   tokens fail closed exactly like an unknown token."
  [row]
  (let [exp (:expires-at row)]
    (or (nil? exp) (> exp (System/currentTimeMillis)))))


(defn storage-token-provider
  "An `AuthProvider` over the `:token` entity (PLATFORM_PLAN §3.4 #1). A
   request's bearer is hashed and matched against `:token` rows, so onboarding
   a user is creating a row (no redeploy). `storage` should be the BASE storage
   — auth runs before the request scope, in the platform context. Reads only
   the hash; a non-expired hit yields `{:user … :org …}` (§4.1 session TTL)."
  [storage]
  (->TokenAuthProvider
    (fn [token]
      (when-not (str/blank? token)
        (when-let [row (first (sp/query-entities storage :token {:token-hash (sha256-hex token)}))]
          (when (token-live? row)
            {:user (:user row) :org (:org row)}))))))
