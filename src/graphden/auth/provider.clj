(ns graphden.auth.provider
  "Pluggable authentication seam (docs/TENANCY_SEAM.md § Auth seam).

   The authentication DECISION — Ring request → principal — is an
   injectable component. Core ships the default single-token provider;
   the tenancy addon swaps the `:auth/provider` Integrant key for a
   session/JWT provider that also resolves the user + org for downstream
   tenant scoping. Everything below the seam — the `:authenticate-request`
   base-fn, the `:request-authenticated?` predicate, the auth middleware —
   is identical regardless of which provider is wired.

   This is prerequisite #1 of the tenancy addon: without an injectable
   auth point, the addon can't replace the hardcoded single-token check."
  (:require
    [clojure.string :as str]))


(defprotocol AuthProvider

  (authenticate
    [this request]
    "Given a Ring request, return a principal map. The only REQUIRED key
     is `:authenticated?` (bool). A real (addon) provider also returns
     `:user` / `:org` for tenant scoping. MUST NOT throw — an
     unauthenticated request returns `{:authenticated? false}`."))


(defn extract-bearer
  "The `Bearer <token>` value from a request's `Authorization` header, or
   nil when the header is absent or uses another scheme."
  [request]
  (let [h (get-in request [:headers "authorization"])]
    (when (and (string? h) (str/starts-with? h "Bearer "))
      (subs h 7))))


(defn constant-time-equal?
  "Timing-safe string compare — mirrors core.logic's
   `:constant-time-equal?`. `MessageDigest/isEqual` XOR-accumulates every
   byte instead of short-circuiting on first mismatch, so it doesn't leak
   a per-byte timing channel. nil / non-string → false."
  [a b]
  (let [^bytes ab (when (string? a) (String/.getBytes ^String a "UTF-8"))
        ^bytes bb (when (string? b) (String/.getBytes ^String b "UTF-8"))]
    (boolean (and ab bb (java.security.MessageDigest/isEqual ab bb)))))


(defrecord SingleTokenAuthProvider
  [token]

  AuthProvider

  (authenticate
    [_ request]
    ;; Authenticated iff a token is configured AND the request's bearer
    ;; equals it (constant-time). A blank/unset token never validates —
    ;; closes the `(= nil nil)` bypass where an unset AUTH_TOKEN would let
    ;; a header-less request through.
    {:authenticated? (and (not (str/blank? token))
                          (constant-time-equal? (extract-bearer request) token))}))


(defn single-token-provider
  "The default `AuthProvider` — validates `Bearer <token>` against the one
   configured `token` (AUTH_TOKEN). The token is captured at construction,
   so per-request auth performs no env read (an improvement over the old
   graph path that re-read `AUTH_TOKEN` on every request)."
  [token]
  (->SingleTokenAuthProvider token))
