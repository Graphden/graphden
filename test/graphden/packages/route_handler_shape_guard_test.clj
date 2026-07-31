(ns graphden.packages.route-handler-shape-guard-test
  "Anti-drift guard for the bare-route handler calling convention.

   Routes WITHOUT middleware (`:get-route` / `:post-route` parents) hand the
   compiled handler callable the RAW ring request, positionally (reitit →
   shape-callable). That only threads correctly when the handler's
   `:lambda-params` is exactly `[]` (static response) or `[:request]`:

   - 2+ params → the map-callable treats the ring request AS the lambda-value
     map, so `:request` resolves to nil — every form field parses blank. The
     live cloud hit this: POST /api/signup 401'd \"already taken\" for every
     input (the handler declared `[:request :limit]`).
   - 1 param under another name → the whole request lands in THAT slot. The
     auth popover hit this: `[:children]` rendered the ring request map inside
     the <input> elements as garbage markup.

   Middlewared routes (`:post` / `:put` / `:delete` / `:get-auth-required`)
   thread through the middleware chain and tolerate wider shapes — they are
   out of scope here. Loads packages as pure data (no DB) so it runs in the
   unit suite."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.packages.loader :as loader]))


(def ^:private package-set
  ["core" "storage" "web" "app-base" "app" "registry" "mcp" "tenancy-admin"])


(def ^:private bare-route-parents
  #{:get-route :post-route})


(def ^:private allowed-shapes
  #{[] [:request]})


(deftest bare-route-handlers-declare-request-or-empty-params
  (let [fn-defs (:fn-defs (loader/load-packages package-set))
        by-name (into {} (map (juxt :name identity)) fn-defs)
        bare-routes (filter #(bare-route-parents (:parent %)) fn-defs)
        offenders (for [r bare-routes
                        :let [h (get-in r [:args :handler])
                              handler (when (keyword? h) (by-name h))
                              lp (:lambda-params handler)]
                        ;; nil lambda-params = derived; the compile pipeline
                        ;; validates those separately. Only DECLARED shapes
                        ;; outside the allowed set break the wire path.
                        :when (and handler (some? lp)
                                   (not (allowed-shapes (vec lp))))]
                    {:route (:name r) :handler h :lambda-params (vec lp)})]
    (is (seq bare-routes) "sanity: the loader surfaced bare routes to check")
    (is (empty? offenders)
        (str "bare (middleware-less) route handlers MUST declare :lambda-params"
             " [] or [:request] — the raw-ring positional call breaks any other"
             " shape (blank form fields / request-rendered-as-markup): "
             (pr-str offenders)))))
