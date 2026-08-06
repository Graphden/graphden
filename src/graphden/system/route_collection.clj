(ns graphden.system.route-collection
  "Process-wide ORDERED COLLECTION of fall-through routers — the runtime
   half of the route-collection seam (docs/TENANCY_SEAM.md
   § Route-collection seam).

   The core handler chain consults this collection before its own
   branch-resolution chain (`graphden.system.branch-router/dispatch`).
   Each entry is a compiled Ring callable built from a `:router-or-nil`
   fn-def — it returns a response on a matched path and `nil` otherwise.
   `dispatch-first` tries every installed router in turn and returns the
   first non-nil response; when none match (or the collection is empty)
   it returns `nil` and the caller falls through to the main app router.
   With NO routers installed the collection is `{}`, `dispatch-first`
   short-circuits to `nil`, and the seam is a transparent pass-through —
   single-tenant behaviour is byte-for-byte unchanged.

   Optional first-party subsystems (the package `registry`, the `mcp`
   endpoint) and the tenancy addon each install THEIR OWN router here at
   boot under a distinct key, so a deployment that omits a package simply
   never installs its router and its paths 404. The routers claim
   DISJOINT path prefixes (`/api/packages` + `/api/export` +
   `/partials/packages-panel`; `/mcp`; the tenancy `/admin` + `/api/auth`
   control plane), so `dispatch-first`'s order is not load-bearing.

   Like `graphden.system.branch-router/active-router`, the installed
   routers are compiled Ring callables that closed over a fixed ctx, so
   they must live in a JVM-wide atom — the request path runs inside
   compiled closures. Reached only through `collection-atom` so the
   kaocha parallel plugin can isolate the collection per NS-thread via
   `*active-collection-override*` (integration tests like
   `grants-admin-test` install a router mid-run).")


(defonce ^{:doc "Active router collection for this JVM — a map
                 `{key → compiled-Ring-callable}`. Empty outside any
                 installed subsystem: `dispatch-first` short-circuits so
                 the seam falls through to the main app router
                 (single-tenant default). Populated by the
                 `:tenancy/router-install` / `:registry/router-install` /
                 `:mcp/router-install` init-keys on startup, drained on
                 halt.

                 Reached only through `collection-atom` so the kaocha
                 parallel plugin can isolate it per NS-thread via
                 `*active-collection-override*`."}
  active-collection-global
  (atom {}))


(def ^:dynamic *active-collection-override*
  "Per-NS-thread override atom for parallel-test isolation. `nil` = use
   the process-global `active-collection-global`. Bound to a fresh
   `(atom {})` per NS-thread by `kaocha.plugin.parallel`, mirroring
   branch-router."
  nil)


(defn- collection-atom
  []
  (or *active-collection-override* active-collection-global))


(defn active-collection-isolation-seed
  "Parallel-plugin seeder: each isolated NS-thread starts with an EMPTY
   collection — no routers installed — so a sibling thread's installed
   router can't leak in."
  []
  {})


(defn install-router!
  "Install (or replace) the router under `key`. Idempotent per key."
  [key router]
  (swap! (collection-atom) assoc key router))


(defn remove-router!
  "Drop the router installed under `key` (halt path). No-op if absent."
  [key]
  (swap! (collection-atom) dissoc key))


(defn current-collection
  "The `{key → router}` map currently installed (this thread's view)."
  []
  @(collection-atom))


(defn dispatch-first
  "Invoke each installed router on `request` in turn, returning the first
   non-nil response, or `nil` when none match (or the collection is
   empty). Nil-safe: an empty collection returns `nil` directly, so the
   caller falls through without touching any subsystem."
  [request]
  (some (fn [router] (router request)) (vals (current-collection))))


(defn dispatch
  "Invoke a SINGLE `router` on `request`, nil-safe (returns `nil` when
   `router` is nil). A utility for tests that build a `:router-or-nil`
   directly and assert its match / no-match / nil-safety behaviour; the
   live request path uses `dispatch-first` over the installed collection."
  [router request]
  (when router (router request)))
