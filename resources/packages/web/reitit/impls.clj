(ns graphden.packages.web.reitit.impls
  "Implementations for web/reitit base functions.

   Wraps reitit's routing + middleware-chain composition. Middleware is
   expressed at graph level via `middleware` (factory producing a reitit
   spec map) and `proceed` (delegate-to-next in chain) — see below."
  (:require
    [clojure.walk :as walk]
    [graphden.executor.defbase :refer [defbase]]
    [reitit.ring :as ring]))


(defn- keywordize-map-keys
  "Adapts map keys to reitit keyword format (string keys → keywords, recursive)."
  [m]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v]) x))
        x))
    m))


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


;; === Router ====================================================================
;;
;; `reitit.ring/ring-handler` compiles routes (including middleware chains)
;; ONCE at startup and returns a Ring callable. We just hand it routes in
;; the shape the graph built — reitit owns all per-request dispatch,
;; method-matching, and middleware composition. No manual chain-building
;; at runtime.

(defbase router
  [routes not-found-response method-not-allowed-response error-response]
  (let [non-nil-routes (vec (remove nil? routes))
        normalized-routes (keywordize-map-keys non-nil-routes)]
    (ring/ring-handler
      (ring/router normalized-routes)
      (ring/create-default-handler
        {:not-found          (fn [_] not-found-response)
         :method-not-allowed (fn [_] method-not-allowed-response)
         :not-acceptable     (fn [_] error-response)}))))


;; === Registry ===

(def impls
  {:router router
   :middleware middleware
   :proceed proceed})
