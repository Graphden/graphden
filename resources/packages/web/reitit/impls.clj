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
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]]
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
;; Templater for `window.API = {…}` from a vector of route path
;; patterns. Pure data → string transformation; no orchestration,
;; no closures, no state. The user-facing lever is the ROUTE TABLE
;; (graph data); the JS shape is implementation detail of the editor
;; bundle. Per packages-quality §3.3 this counts as library-adapter
;; boilerplate (formatting output for a specific consumer = JS), not
;; hidden composition — there are no graph-composable steps a user
;; would reasonably want to vary.

(defn- emit-entry
  "One JS object line per route. Static paths land as string
   constants; paths containing `:param` segments land as functions
   that `encodeURIComponent` each param and concatenate. Adjacent
   static segments are collapsed into one string literal so the
   emitted JS reads `\"/api/fns/\" + encodeURIComponent(id)` rather
   than segment-by-segment."
  [path]
  (let [segs (str/split path #"/")
        params (vec (for [s segs :when (str/starts-with? s ":")]
                      (-> s (subs 1) (str/replace #"-" "_"))))
        key (-> path
                (str/replace #"^/" "")
                (str/replace #":" "")
                (str/replace #"[/-]" "_")
                (#(if (str/blank? %) "root" %)))
        ;; Walk segments, emit a tag per slot: `[:lit "/api/fns/"]` or
        ;; `[:param "id"]`. Trailing slash on a static run is what
        ;; separates `…/api/fns/` from the upcoming param.
        parts (loop [acc [] buf "/" remaining (rest segs)]
                (if (empty? remaining)
                  (cond-> acc
                    (not= "" buf) (conj [:lit buf]))
                  (let [s (first remaining)]
                    (if (str/starts-with? s ":")
                      (recur (-> acc
                                 (cond-> (not= "" buf) (conj [:lit buf]))
                                 (conj [:param (-> s (subs 1) (str/replace #"-" "_"))]))
                             "/"
                             (rest remaining))
                      (recur acc (str buf s "/") (rest remaining))))))
        ;; Drop a trailing `/` that follows the LAST param when the
        ;; source path didn't end with one (loop's `buf "/"` reset
        ;; would otherwise leak a phantom slash).
        parts (if (and (= [:lit "/"] (last parts))
                       (not (str/ends-with? path "/")))
                (vec (butlast parts))
                parts)]
    (if (empty? params)
      (str "  " key ": " (pr-str path) ",")
      (let [body (->> parts
                      (map (fn [[kind v]]
                             (if (= kind :lit)
                               (pr-str v)
                               (str "encodeURIComponent(" v ")"))))
                      (str/join " + "))]
        (str "  " key
             ": function(" (str/join ", " params)
             ") { return " body "; },")))))


(defbase routes->js-bundle
  "Emit a `window.API = {…}` JS module from a vector of full route
   path patterns. Static paths → string constants
   (`API.api_branches = '/api/branches'`); paths containing `:param`
   segments → functions that encodeURIComponent + concatenate
   (`API.api_branches_ref = function(ref){return '/api/branches/' +
   encodeURIComponent(ref);}`). Paired with `:ring-route-paths` to
   close the backend↔frontend coupling loop — front-end never
   hardcodes a URL again. IIFE wraps the assignment to keep helper
   locals out of the global scope."
  [paths]
  (str "// AUTO-GENERATED by web/reitit `routes->js-bundle` — do not edit.\n"
       "// Edit the route fn-defs in `app/routes/` instead. Regenerated\n"
       "// on every system boot from the live reitit router.\n"
       "(function () {\n"
       "  window.API = {\n"
       (str/join "\n" (mapv emit-entry (sort (distinct paths)))) "\n"
       "  };\n"
       "})();\n"))


;; === Registry ===

(def impls
  {:ring-router                 ring-router-fn
   :ring-create-default-handler ring-create-default-handler-fn
   :ring-handler                ring-handler-fn
   :middleware                  middleware
   :ring-route-paths            ring-route-paths
   :routes->js-bundle           routes->js-bundle})
