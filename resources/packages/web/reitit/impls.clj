(ns graphden.packages.web.reitit.impls
  "Implementations for web/reitit base functions.

   Thin `defbase` wrappers around `reitit.ring` primitives —
   `ring-router`, `ring-create-default-handler`, `ring-handler` —
   plus the middleware factory.

   Router assembly (filter nils, build defaults-map, call
   reitit.ring/ring-handler) and `:proceed` (delegate-to-next) are
   expressed at graph level as fn-def compositions in `fns.edn`; this
   namespace only carries library call-sites and the middleware
   factory."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.system.api-routes-js :as api-js]
    [reitit.core :as r]
    [reitit.ring :as ring]))


;; === Reitit library wrappers ====================================================

(defbase ring-router-fn
  "Bare `(reitit.ring/router routes)`. The caller is expected to hand
   in reitit-shaped data (vectors + keyword keys). Graph-side coercion
   (vec'ing lazy `:seq` bindings, keywordizing string map-keys) is now
   a separate fn-def `:_router-coerced-routes` in fns.edn — sites that
   compose routes via graph primitives route their data through that
   coercer before binding it here."
  [routes]
  (ring/router routes))


(defbase ring-create-default-handler-fn
  "Build a Ring handler that reitit falls back to when no route matches.
   Takes the three slot-specific Ring RESPONSE maps directly — wraps
   each in `constantly` to satisfy reitit's `(handler request)`
   contract here at the adapter, so the fn-graph composes responses
   (pure data) without having to thread `:make-handler` per slot.
   Previously each handler was built as a separate `:make-handler`
   fn-graph and stuffed into a defaults map via `pairs->map`; that
   chain didn't propagate `:not-found-response` etc. through the ref
   boundary, so the values landed as nil. Single base-fn taking three
   response slots eliminates the boundary."
  [not-found-response method-not-allowed-response not-acceptable-response]
  (ring/create-default-handler
    {:not-found          (constantly not-found-response)
     :method-not-allowed (constantly method-not-allowed-response)
     :not-acceptable     (constantly not-acceptable-response)}))


(defbase ring-handler-fn
  "Compose a compiled reitit router and a default handler into the
   final Ring-handler callable that http-kit invokes per request."
  [router default-handler]
  (ring/ring-handler router default-handler))


;; === Middleware factory ========================================================
;;
;; Reitit middleware is a spec `{:name … :wrap (fn [handler] (fn [req] …))}`.
;; At route-compile time reitit folds `(:wrap mw)` around the route handler
;; producing a composed Ring callable per route.
;;
;; The graph-level `body` is a fn-graph with one leftover free arg `:ctx`
;; — a context map. We populate it with `{:request <ring-request>,
;; :next-handler <next-link>}` on each invocation. `:proceed` (a fn-def,
;; not an impl) pulls both pieces out of `:ctx` via `:get`. No dynvar.

(defbase middleware
  "Produces a reitit-compatible middleware spec. `body` is a fn-graph
   with TWO leftover free args (`:request` and `:next-handler`) — the
   compiler builds a map-callable for it. We populate both keys per
   request: `:request` is reitit's request, `:next-handler` is the
   next link in the chain. `body` is responsible for routing them via
   `:proceed` (a fn-def, not an impl)."
  [name body]
  {:name name
   :wrap (fn [handler]
           (fn [request]
             (body {:request request, :next-handler handler})))})


;; === Route enumeration ===========================================================

(defbase ring-route-paths
  "Return the full path patterns the compiled reitit router serves,
   in route-table order. Accepts either a bare `reitit.core/Router`
   (from `:ring-router`) or a `reitit.ring` handler — `get-router`
   pulls the inner router out of the latter, returns nil for the
   former, hence the `or` coercion. Output drives
   `:_editor-api-routes-js` (codegen of the JS constants module
   bundled into editor.js)."
  [router]
  (mapv first (r/routes (or (ring/get-router router) router))))


;; === JS code generation =========================================================
;;
;; The two JS-codegen defbases below delegate to pure helpers in
;; `graphden.system.api-routes-js`. Same code path used by the
;; boot-time `:exec/api-routes-js-cache` init-key, so the graph-
;; computed bundle and the cached bundle stay byte-identical.

(defbase routes->js-bundle
  "Emit a `window.API = {…}` JS module from a vector of full route
   path patterns. Static paths → string constants
   (`API.api_branches = '/api/branches'`); paths containing `:param`
   segments → functions that encodeURIComponent + concatenate
   (`API.api_branches_ref = function(ref){return '/api/branches/' +
   encodeURIComponent(ref);}`). IIFE wraps the assignment to keep
   helper locals out of the global scope."
  [paths]
  (api-js/routes->js-bundle paths))


(defbase cached-api-routes-js
  "Return the pre-computed `window.API = {…}` JS module — built
   once at boot by `:exec/api-routes-js-cache` from the live
   compiled router, kept in a process-global atom. Declared
   `:effects #{}` in fns.edn — the type-checker treats the atom
   read as pure so the editor JS bundle doesn't inherit any
   handler effects through this chain."
  []
  (api-js/read-cache))


;; === Registry ===

(def impls
  {:ring-router                 ring-router-fn
   :ring-create-default-handler ring-create-default-handler-fn
   :ring-handler                ring-handler-fn
   :middleware                  middleware
   :ring-route-paths            ring-route-paths
   :routes->js-bundle           routes->js-bundle
   :cached-api-routes-js        cached-api-routes-js})
