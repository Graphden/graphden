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
    [graphden.auth.provider :as auth]))


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
