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
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.web.route-shape :as route-shape]))


;; The public package superset. `tenancy-admin` moved to the private
;; graphden-tenancy repo (open-core split) and guards its own route shapes there.
(def ^:private package-set
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


;; The template set + allowed shapes live in `graphden.web.route-shape`,
;; shared with the sync-time validator (`packages.sync`) and the editor
;; write-time guard (`crud.validation/route-handler-shape-rej`) so the
;; three enforcement points can't drift.

(deftest bare-route-handlers-declare-request-or-empty-params
  (let [fn-defs (:fn-defs (loader/load-packages package-set))
        by-name (into {} (map (juxt :name identity)) fn-defs)
        bare-routes (filter #(route-shape/bare-route-parents (:parent %)) fn-defs)
        offenders (for [r bare-routes
                        :let [h (get-in r [:args :handler])
                              handler (when (keyword? h) (by-name h))
                              lp (:lambda-params handler)]
                        ;; nil lambda-params = derived; the compile pipeline
                        ;; validates those separately. Only DECLARED shapes
                        ;; outside the allowed set break the wire path.
                        :when (and handler
                                   (not (route-shape/valid-handler-lambda-params? lp)))]
                    {:route (:name r) :handler h :lambda-params (vec lp)})]
    (is (seq bare-routes) "sanity: the loader surfaced bare routes to check")
    (is (empty? offenders)
        (str "bare (middleware-less) route handlers MUST declare :lambda-params"
             " [] or [:request] — the raw-ring positional call breaks any other"
             " shape (blank form fields / request-rendered-as-markup): "
             (pr-str offenders)))))


;; The sync-time mirror of the same contract — what an EXTERNAL package
;; hits at `register-base-fns-from-packages!` before any DB write.

(deftest sync-validator-rejects-bad-bare-route-handler
  (let [validate! #'sync/validate-route-handler-shapes!]
    (testing "a bare route whose handler declares a wire-breaking shape throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lambda-params"
            (validate! {:fn-defs [{:name :probe-route
                                   :parent :get-route
                                   :args {:path {:value "/probe"}
                                          :handler :probe-handler}}
                                  {:name :probe-handler
                                   :parent :const
                                   :lambda-params [:request :limit]
                                   :args {}}]}))))

    (testing "valid + derived shapes pass"
      (is (nil? (validate! {:fn-defs [{:name :probe-route
                                       :parent :get-route
                                       :args {:handler :probe-handler}}
                                      {:name :probe-handler
                                       :lambda-params [:request]}]})))
      (is (nil? (validate! {:fn-defs [{:name :probe-route
                                       :parent :post-route
                                       :args {:handler :probe-handler}}
                                      {:name :probe-handler}]}))
          "nil lambda-params = derived, out of scope"))

    (testing "a foreign namespace's same-named template is not the bare template"
      (is (nil? (validate! {:fn-defs [{:name :probe-route
                                       :parent :other.ns/get-route
                                       :args {:handler :probe-handler}}
                                      {:name :probe-handler
                                       :lambda-params [:x :y]}]}))))))
