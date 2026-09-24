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
   (pure data) without having to thread `:make-handler` per slot."
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
;; The graph-level `body` is a fn-graph with two leftover free args,
;; `:request` and `:next-handler`; the compiler hands it over as a
;; map-callable and we call it with `{:request <ring-request>,
;; :next-handler <next-link>}` on each invocation. `:proceed` (a fn-def,
;; not an impl) calls the next link with the request. No dynvar.

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


;; === JS code generation =========================================================
;;
;; The `window.API` module is built ONCE at boot by `:exec/api-routes-js-cache`
;; (`graphden.system.api-routes-js`) from the live compiled routers; the graph
;; reads it through the atom below.

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
   :cached-api-routes-js        cached-api-routes-js})
