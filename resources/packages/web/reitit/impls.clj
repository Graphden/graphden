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
    [reitit.ring :as ring]))


;; === Reitit library wrappers ====================================================

(defn- merge-routes-by-path
  "Pre-process routes: group entries that share `path` and merge their
   method-data maps into one. We declare each (path, method) pair as a
   separate fn-def, but reitit's router needs each path declared ONCE
   with ALL its methods — even with `:conflicts nil` it silently drops
   all but the first duplicate. This collapses the vector before
   handing it to reitit."
  [routes]
  (vec (for [[path entries] (group-by first routes)]
         [path (apply merge (map second entries))])))


(defbase ring-router-fn
  "Compile routes into a reitit router. Consumes reitit-shaped route
   data `[[path {:method {:handler …}} …]]` with keyword keys."
  [routes]
  (ring/router (merge-routes-by-path routes)))


(defbase ring-create-default-handler-fn
  "Build a Ring handler that reitit falls back to when no route matches.
   `handlers` is a map with `:not-found`, `:method-not-allowed`,
   `:not-acceptable` keys, each a Ring handler."
  [handlers]
  (ring/create-default-handler handlers))


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


;; === Registry ===

(def impls
  {:ring-router                 ring-router-fn
   :ring-create-default-handler ring-create-default-handler-fn
   :ring-handler                ring-handler-fn
   :middleware                  middleware})
