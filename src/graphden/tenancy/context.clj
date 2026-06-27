(ns graphden.tenancy.context
  "Current-organization binding for tenant scoping (PLATFORM_PLAN §3.0).

   This is the core-side SEAM the tenancy addon's `OrgScopedStorage` reads
   to scope every row. It mirrors the `*allowed-effects*` pattern exactly:
   a thread-local the request path binds from the authenticated principal,
   consumed lower down — here, by the storage decorator instead of the
   effect gate.

   The core default is the shared `\"public\"` org. A single-tenant
   deployment (no addon wired, no `:org` in the principal) therefore
   behaves exactly as before: every row is public, every read sees public.
   Only when the addon's provider resolves a real `:org` AND its storage
   decorator is wired does scoping take effect — making tenancy opt-in by
   construction (ADR §3.0).")


(def public-org
  "The shared, always-present organization. Core writes/reads here; it is
   the default tenant so single-tenant mode needs no org concept at all."
  "public")


(def ^:dynamic *current-org*
  "Org id in scope for the current execution. The addon's request path
   rebinds this from the authenticated principal (via `with-org`); the
   addon's `OrgScopedStorage` reads it through `current-org`. Unbound =
   the shared public org."
  public-org)


(defn current-org
  "The org id in scope right now (`public-org` when unbound)."
  []
  *current-org*)


(defn org-from-principal
  "The org id carried by an auth principal — the addon's provider sets
   `:org` (see `auth/AuthProvider`). Falls back to the shared public org
   when absent (single-token mode never sets `:org`)."
  [principal]
  (or (:org principal) public-org))


(defmacro with-org
  "Run `body` with `*current-org*` bound to `org` (nil → public-org)."
  [org & body]
  `(binding [*current-org* (or ~org ~public-org)]
     ~@body))
