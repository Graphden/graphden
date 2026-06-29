(ns graphden.system.tenancy-router
  "Process-wide registry for the tenancy addon's control-plane router —
   the runtime half of the route-collection seam.

   The core handler chain always carries an inert `:tenancy-routing-wrap`
   (web/tenancy-router). On each request it consults the router installed
   here: when an addon has installed one, matching control-plane paths
   (the org-admin / grants panels, auth routes, …) are served by it;
   anything else returns `nil` and the wrap falls through to the main
   app router. With no addon active the atom stays `nil`, the dispatch
   short-circuits to `nil`, and the wrap is a transparent pass-through —
   single-tenant behaviour is byte-for-byte unchanged.

   Mirrors `graphden.system.branch-router/active-router`: the installed
   router is a compiled Ring callable (built from the addon's
   `:tenancy-router` fn-def, a `:router-or-nil` over its `:tenancy-routes`
   list), so it must live in a JVM-wide atom — the wrap's base-fn impls
   run inside compiled closures that already closed over a fixed ctx.")


(defonce ^{:doc "Active tenancy control-plane router for this JVM — a
                 compiled Ring callable that returns a response on a
                 matched control-plane path and `nil` otherwise. Set by
                 the addon's `:tenancy/router-install` init-key on
                 startup, cleared on halt. `nil` outside a running
                 addon: dispatch short-circuits to `nil` so the
                 `:tenancy-routing-wrap` falls through to the main app
                 router (single-tenant default)."}
  active-router
  (atom nil))


(defn set-active-router!
  [router]
  (reset! active-router router))


(defn clear-active-router!
  []
  (reset! active-router nil))


(defn current-router
  []
  @active-router)


(defn dispatch
  "Invoke the installed tenancy `router` on `request`, returning its
   response or `nil` when no control-plane route matched. Nil-safe in
   `router`: with no addon active (`router` nil) returns `nil` directly,
   so the wrap falls through without touching the addon."
  [router request]
  (when router
    (router request)))
