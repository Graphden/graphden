(ns graphden.packages.web.reitit.impls
  "Implementations for web/reitit base functions.

   Thin `defbase` wrappers around `reitit.ring` primitives —
   `ring-router`, `ring-create-default-handler`, `ring-handler` —
   plus the middleware primitives (`middleware`, `proceed`).

   Router assembly (filter nils, build defaults-map, call
   reitit.ring/ring-handler) is expressed at graph level as a fn-def
   composition in `fns.edn`; this namespace only carries the three
   reitit-library call-sites."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [reitit.ring :as ring]))


;; === Reitit library wrappers ====================================================

(defbase ring-router-fn
  "Compile routes into a reitit router. Consumes reitit-shaped route
   data `[[path {:method {:handler …}} …]]` with keyword keys."
  [routes]
  (ring/router routes))


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


;; === Middleware chain ==========================================================
;;
;; Reitit middleware is a spec `{:name … :wrap (fn [handler] (fn [req] …))}`.
;; At route-compile time reitit folds `(:wrap mw)` around the route handler
;; producing a composed Ring callable per route.
;;
;; In graph terms a middleware has a BODY (a fn-graph taking free `:request`).
;; The body is a normal Ring-shaped handler that either returns its own
;; response (early-return) or delegates to the next link in the chain via
;; `proceed`.  `proceed` reads the current next-handler off a dynvar that
;; `middleware`'s impl installs for the duration of each body call.

(def ^:dynamic ^:private *next-handler* nil)


(defbase middleware
  "Produces a reitit-compatible middleware spec. `body` is a fn-graph
   (callable taking one request argument) that runs PER REQUEST with
   `*next-handler*` bound to the next link in the chain — invoke it via
   `proceed` from inside `body` to continue."
  [name body]
  {:name name
   :wrap (fn [handler]
           (fn [request]
             (binding [*next-handler* handler]
               (body request))))})


(defbase proceed
  "Delegate to the next handler in the middleware chain. Must be called
   from inside a middleware body (`*next-handler*` is bound there)."
  [request]
  (when (nil? *next-handler*)
    (throw (ex-info "`proceed` called outside a middleware chain"
                    {:type :runtime-error/proceed-without-middleware})))
  (*next-handler* request))


;; === Registry ===

(def impls
  {:ring-router                 ring-router-fn
   :ring-create-default-handler ring-create-default-handler-fn
   :ring-handler                ring-handler-fn
   :middleware                  middleware
   :proceed                     proceed})
