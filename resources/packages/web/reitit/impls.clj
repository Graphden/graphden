(ns graphden.packages.web.reitit.impls
  "Implementations for web/reitit base functions.

   Wraps reitit's routing + middleware-chain composition. Middleware is
   expressed at graph level via `middleware` (factory producing a reitit
   spec map) and `proceed` (delegate-to-next in chain) — see below."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.executor.defbase :refer [defbase]]
    [reitit.core :as r]))


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

(defn- compile-middleware-chain
  "Reduce a route's middleware specs into a single handler wrapper —
   applied right-to-left so the FIRST entry runs outermost (first to
   see the request, last to see the response). Matches reitit's
   convention."
  [handler middleware]
  (reduce
    (fn [h mw]
      (let [wrap (:wrap mw)]
        (wrap h)))
    handler
    (reverse middleware)))


(defbase router
  [routes not-found-response method-not-allowed-response error-response]
  (let [non-nil-routes (vec (remove nil? routes))
        normalized-routes (keywordize-map-keys non-nil-routes)
        compiled-router (r/router normalized-routes)
        resp-404 not-found-response
        resp-405 method-not-allowed-response
        resp-500 error-response]
    (fn [request]
      (let [uri (:uri request)]
        (if-let [match (r/match-by-path compiled-router uri)]
          (let [method (if (keyword? (:method request))
                         (:method request)
                         (keyword (str/lower-case (str (:method request)))))
                route-data (:data match)
                method-data (get route-data method)]
            (if method-data
              (if-let [handler-fn (:handler method-data)]
                (let [;; Reitit convention: middleware can live at the
                      ;; route-data level (applies to all methods) and/or
                      ;; at the method-data level (this method only). We
                      ;; concat route-level first, then method-level, so
                      ;; route-wide middleware wraps outermost.
                      middleware (concat (:middleware route-data)
                                         (:middleware method-data))
                      composed (if (seq middleware)
                                 (compile-middleware-chain handler-fn middleware)
                                 handler-fn)]
                  (composed (assoc request :path-params (:path-params match))))
                resp-500)
              resp-405))
          resp-404)))))


;; === Registry ===

(def impls
  {:router router
   :middleware middleware
   :proceed proceed})
