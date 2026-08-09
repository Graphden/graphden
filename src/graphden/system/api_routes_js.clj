(ns graphden.system.api-routes-js
  "Boot-time-cached `window.API = {…}` JS module that closes the
   backend↔frontend URL coupling.

   The string is computed once after `:_router` compiles, stored in
   a process-global atom, and read by the `:cached-api-routes-js`
   base-fn (declared `:effects #{}` so the type-checker treats it
   as pure — that's load-bearing: a graph chain that pulled in the
   router's handlers as a dependency would propagate every
   handler's effects into the editor JS asset, which the type-check
   sweep would reject).

   Producer side lives in this ns + the `:exec/api-routes-js-cache`
   integrant init-key. Reader side is the `cached-api-routes-js`
   defbase in `resources/packages/web/reitit/impls.clj` (a thin
   `@!cache` deref).

   The pure JS-templating helpers `emit-entry` + `routes->js-bundle`
   live here as the canonical implementation; the
   `:routes->js-bundle` base-fn in `web/reitit/impls.clj`
   delegates to them so the graph-side primitive and the boot-time
   cache stay byte-identical.

   See [[graphden.system.api-url-drift]] for the related sync-time
   validator that catches stale `/api/*` literals in editor JS."
  (:require
    [clojure.string :as str]
    [graphden.system.route-collection :as rc]
    [reitit.core :as r]
    [reitit.ring :as ring]))


;; =============================================================================
;; JS templating — emit `window.API = {…}` from a vector of paths
;; =============================================================================

(defn- coalesce-runs
  "Merge consecutive `[:lit a] [:lit b]` → `[:lit \"a/b\"]` so the
   emitted JS reads `\"/api/branches\"` rather than `\"/api\" +
   \"/branches\"`. Params stay as separate entries."
  [tagged]
  (reduce
    (fn [acc [k v]]
      (let [[lk lv] (last acc)]
        (if (and (= k :lit) (= lk :lit))
          (conj (vec (butlast acc)) [:lit (str lv "/" v)])
          (conj acc [k v]))))
    []
    tagged))


(defn- emit-entry
  "One JS object line per route. Static paths land as string
   constants; parametric paths land as functions that
   `encodeURIComponent` each param and concatenate. Each segment
   carries its own leading `/`, then adjacent literals are
   coalesced — so `/api/branches/:ref/conflicts` emits
   `\"/api/branches\" + \"/\" + encodeURIComponent(ref) +
   \"/conflicts\"` (no spurious trailing slash from the loop's
   buffer)."
  [path]
  (let [segs (rest (str/split path #"/"))
        params (vec (for [s segs :when (str/starts-with? s ":")]
                      (-> s (subs 1) (str/replace #"-" "_"))))
        key (-> path
                (str/replace #"^/" "")
                (str/replace #":" "")
                (str/replace #"[/-]" "_")
                (#(if (str/blank? %) "root" %)))]
    (if (empty? params)
      (str "  " key ": " (pr-str path) ",")
      (let [tagged (mapv (fn [s]
                           (if (str/starts-with? s ":")
                             [:slash-param (-> s (subs 1) (str/replace #"-" "_"))]
                             [:lit s]))
                         segs)
            ;; Coalesce only consecutive [:lit …] entries — params
            ;; stay separate. Then prepend the leading `/` to each
            ;; lit (so a single-segment lit "/foo" still renders).
            runs (->> tagged
                      coalesce-runs
                      (mapv (fn [[k v]]
                              (if (= k :lit) [:lit (str "/" v)] [:param v]))))
            ;; Trailing slash from the original path needs to be
            ;; preserved — append it to the final `:lit` run, or
            ;; emit it as a standalone literal when the path ends
            ;; on a `:param`.
            runs (cond
                   (not (str/ends-with? path "/")) runs
                   (= :lit (first (last runs)))    (conj (vec (butlast runs))
                                                         [:lit (str (second (last runs)) "/")])
                   :else                           (conj runs [:lit "/"]))
            body (->> runs
                      (map (fn [[k v]]
                             (if (= k :lit)
                               (pr-str v)
                               (str "\"/\" + encodeURIComponent(" v ")"))))
                      (str/join " + "))]
        (str "  " key
             ": function(" (str/join ", " params)
             ") { return " body "; },")))))


(defn routes->js-bundle
  "Emit a `window.API = {…}` IIFE from a vector of full route path
   patterns. Public so both the `:routes->js-bundle` defbase
   (request- or sync-time graph codegen) and the boot-time
   `:exec/api-routes-js-cache` init-key can call it without
   duplication."
  [paths]
  (str "// AUTO-GENERATED — do not edit. Source of truth: route\n"
       "// fn-defs in `app/routes/*`. Regenerated at every system\n"
       "// boot (via graphden.system.api-routes-js) and validated\n"
       "// against editor JS by graphden.system.api-url-drift.\n"
       "(function () {\n"
       "  window.API = {\n"
       (str/join "\n" (mapv emit-entry (sort (distinct paths)))) "\n"
       "  };\n"
       "})();\n"))


;; =============================================================================
;; Router path extraction
;; =============================================================================

(defn router-paths
  "All path patterns the compiled router serves, in route-table
   order. Accepts either a bare `reitit.core/Router` or a
   `reitit.ring` handler. A route-collection router that is a PLAIN
   Clojure fn (the accounts `/auth/*` router — the seam explicitly
   allows any `(fn [req] resp-or-nil)`) has no reitit route table, so
   it contributes no window.API entries: return `[]` rather than throw
   on the `reitit.core/routes` protocol. Mirrors
   [[graphden.system.api-url-drift/router-paths]] — kept as a separate
   fn so `bb test` doesn't force a require-cycle."
  [router]
  (if-let [rr (or (ring/get-router router) (when (satisfies? r/Router router) router))]
    (mapv first (r/routes rr))
    []))


;; =============================================================================
;; Process-global cache
;; =============================================================================

(def ^:private !cache
  "Atom holding the computed JS string. nil until
   `:exec/api-routes-js-cache` init-key fires."
  (atom nil))


(def ^:private !base-routers
  "The FIRST-PARTY routers `window.API` is built from — the main `:_router`
   plus the optional `registry-router` / `mcp-router` (route PATHS are static,
   even though those packages' routes are SERVED per-branch). Remembered by
   `install-base-routers!` so a later addon rebuild (`rebuild-window-api!`)
   can re-union them with the route-collection instead of dropping them."
  (atom []))


(defn install-from-routers!
  "Compute the JS bundle from the UNION of several compiled routers'
   `/api/*` paths and store it. Lets an addon contribute its OWN
   router's routes to `window.API` — so editor JS addresses them via
   `window.API.<key>` (no hardcoded literals) and the frontend
   auto-adapts to whatever routes the addon's routing graph serves.
   `routes->js-bundle` sorts + dedupes, so passing the core `:_router`
   plus the tenancy router is safe even if they overlap."
  [routers]
  (let [paths (->> routers
                   (mapcat router-paths)
                   (filter #(str/starts-with? % "/api/")))]
    (reset! !cache (routes->js-bundle paths))))


(defn install-from-router!
  "Compute the JS bundle from `router` (filtering to `/api/*` paths)
   and store it in the cache. Idempotent."
  [router]
  (install-from-routers! [router]))


(defn install-base-routers!
  "Store the first-party `routers` as the window.API base set AND build the
   cache from them. Called once by `:exec/api-routes-js-cache`."
  [routers]
  (reset! !base-routers routers)
  (install-from-routers! routers))


(defn rebuild-window-api!
  "Rebuild `window.API` from the remembered first-party base routers
   (`:_router` + optional `registry-router` / `mcp-router`) UNIONED with
   every router currently installed in the route-collection (the tenancy
   addon). Called by an addon after `route-collection/install-router!` so the
   cache reflects the FULL set — `install-from-routers!` resets (not appends),
   so a rebuild that forgot the base routers would drop their `/api/*` keys."
  []
  (install-from-routers! (concat @!base-routers (vals (rc/current-collection)))))


(defn install!
  "Lower-level variant — store an already-computed string."
  [js-str]
  (reset! !cache js-str))


(defn read-cache
  "Return the cached JS string. Returns `\"\"` (empty payload) when
   not yet installed — keeps the editor bundle valid in test-
   bootstrap paths where the cache init-key never ran."
  []
  (or @!cache ""))


(defn clear-cache!
  "Reset the cache to nil. Intended for halt-key / test teardown."
  []
  (reset! !cache nil))
