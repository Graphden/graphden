(ns graphden.packages.web.reitit-test
  "Unit tests for the `:middleware` factory impl. `:proceed` is now a
   pure fn-def composition (no impl) — its behavior is exercised by
   the auth integration tests via the actual fn-graph executor.

   Loads impls dynamically from `resources/packages/`, matching the
   pattern used by layout-test."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [reitit.ring :as ring]))


;; =============================================================================
;; DYNAMIC LOADING OF REITIT IMPLS
;; =============================================================================

(def ^:private reitit-ns
  (let [impls-file (io/resource "packages/web/reitit/impls.clj")]
    (when impls-file
      (load-file (java.io.File/.getPath (io/file impls-file))))
    (find-ns 'graphden.packages.web.reitit.impls)))


(defn- unwrap
  "defbase wraps impls as `(fn [args ctx] ...)` where `args` is a
   keyword-keyed map. Return the underlying impl."
  [sym]
  (when reitit-ns
    @(ns-resolve reitit-ns sym)))


(def ^:private middleware-impl (unwrap 'middleware))
(def ^:private ring-route-paths-impl (unwrap 'ring-route-paths))
(def ^:private routes->js-bundle-impl (unwrap 'routes->js-bundle))


(defn- route-paths
  [router]
  (ring-route-paths-impl {:router router} nil))


(defn- js-bundle
  [paths]
  (routes->js-bundle-impl {:paths paths} nil))


(defn- call-mw
  "Call the middleware factory impl. `body` is now a 1-arg callable
   that takes a context map `{:request _ :next-handler _}`."
  [mw-name body]
  (middleware-impl {:name mw-name :body body} nil))


;; =============================================================================
;; TESTS — middleware factory shape
;; =============================================================================

(deftest middleware-produces-reitit-spec
  (testing "middleware returns a map with :name and :wrap"
    (let [spec (call-mw :test-mw (fn [_ctx] {:status 200}))]
      (is (map? spec))
      (is (= :test-mw (:name spec)))
      (is (fn? (:wrap spec))))))


(deftest middleware-wrap-passes-context-map
  (testing "wrapped handler invokes body with {:request _, :next-handler _}"
    (let [captured (atom nil)
          body (fn [ctx] (reset! captured ctx) {:status 204 :body "ok"})
          spec (call-mw :capture body)
          next-handler (fn [_] {:status 500 :body "should not reach"})
          wrapped ((:wrap spec) next-handler)
          resp (wrapped {:uri "/x" :request-method :get})]
      (is (= {:uri "/x" :request-method :get} (:request @captured))
          "the original request is passed under :request key")
      (is (identical? next-handler (:next-handler @captured))
          "the next link in the chain is passed under :next-handler key")
      (is (= 204 (:status resp))))))


(deftest middleware-early-return
  (testing "body short-circuits by returning a response without invoking next-handler"
    (let [next-called (atom false)
          body (fn [_ctx] {:status 401 :body "nope"})
          spec (call-mw :deny body)
          next-handler (fn [_] (reset! next-called true) {:status 200})
          wrapped ((:wrap spec) next-handler)
          resp (wrapped {:uri "/x"})]
      (is (= 401 (:status resp)))
      (is (= "nope" (:body resp)))
      (is (false? @next-called)
          "early-return body must not trigger next-handler"))))


(deftest middleware-body-can-delegate-via-context
  (testing "body invokes next-handler from the ctx map (mirrors what :proceed fn-def does)"
    (let [body (fn [{:keys [request next-handler]}]
                 (next-handler (assoc request :tagged true)))
          spec (call-mw :pass-through body)
          handler (fn [req] {:status 200 :req req})
          wrapped ((:wrap spec) handler)
          resp (wrapped {:uri "/x"})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:req :tagged]))
          "next-handler received the body-modified request"))))


(deftest middleware-chain-composition
  (testing "two middlewares chain outer-to-inner; each delegates via its ctx"
    (let [tag (fn [k]
                (fn [{:keys [request next-handler]}]
                  (next-handler (assoc request k true))))
          outer ((:wrap (call-mw :outer (tag :outer-saw)))
                 ((:wrap (call-mw :inner (tag :inner-saw)))
                  (fn [req] {:status 200 :req req})))
          resp (outer {:uri "/x"})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:req :outer-saw])))
      (is (true? (get-in resp [:req :inner-saw]))
          "inner middleware ran after outer delegated"))))


(deftest middleware-short-circuits-inner
  (testing "outer middleware returning a response stops the chain"
    (let [inner-called (atom false)
          outer-body (fn [_ctx] {:status 403 :body "forbidden"})
          inner-body (fn [{:keys [request next-handler]}]
                       (reset! inner-called true)
                       (next-handler request))
          outer ((:wrap (call-mw :gate outer-body))
                 ((:wrap (call-mw :trace inner-body))
                  (fn [_] {:status 200})))
          resp (outer {:uri "/x"})]
      (is (= 403 (:status resp)))
      (is (false? @inner-called)
          "outer's early-return must prevent inner from running"))))


;; =============================================================================
;; TESTS — ring-route-paths (path enumeration from compiled router)
;; =============================================================================

(deftest ring-route-paths-extracts-from-ring-handler
  (testing "given a reitit ring handler, returns all path patterns in route order"
    (let [handler (ring/ring-handler
                    (ring/router
                      [["/health" {:get (constantly {:status 200})}]
                       ["/api/users/:id" {:get (constantly {:status 200})}]]))
          paths (route-paths handler)]
      (is (= ["/health" "/api/users/:id"] paths)))))


(deftest ring-route-paths-extracts-from-bare-router
  (testing "given a bare reitit.core/Router (not wrapped as ring-handler), still works — `or get-router self` coercion"
    (let [router (ring/router
                   [["/a" {:get (constantly {:status 200})}]
                    ["/b/:x" {:get (constantly {:status 200})}]])
          paths (route-paths router)]
      (is (= ["/a" "/b/:x"] paths)))))


(deftest ring-route-paths-flattens-nested-groups
  (testing "nested reitit data shape — common prefix gets joined into each leaf"
    (let [router (ring/router
                   ["/api"
                    ["/x" {:get (constantly {:status 200})}]
                    ["/y/:z" {:get (constantly {:status 200})}]])]
      (is (= ["/api/x" "/api/y/:z"] (route-paths router))))))


;; =============================================================================
;; TESTS — routes->js-bundle (codegen of `window.API`)
;; =============================================================================

(deftest js-bundle-wraps-in-iife
  (testing "output is an IIFE assigning to window.API"
    (let [out (js-bundle ["/api/health"])]
      (is (str/includes? out "(function () {"))
      (is (str/includes? out "window.API = {"))
      (is (str/includes? out "})();")))))


(deftest js-bundle-emits-static-paths-as-string-constants
  (testing "static path → `key: \"path\"` entry"
    (let [out (js-bundle ["/api/branches"])]
      (is (str/includes? out "api_branches: \"/api/branches\","))
      ;; sanity: no function for the static path
      (is (not (str/includes? out "api_branches: function"))))))


(deftest js-bundle-emits-parametric-paths-as-functions
  (testing "param segment → `key: function(p){ return ... + encodeURIComponent(p) + ...; }`"
    (let [out (js-bundle ["/api/fns/:id"])]
      (is (str/includes? out "api_fns_id: function(id) { return"))
      (is (str/includes? out "encodeURIComponent(id)"))
      ;; Leading-`/` literal for the static prefix; the `/` separator
      ;; before the param lives on the param-emit side.
      (is (str/includes? out "\"/api/fns\""))
      (is (str/includes? out "\"/\" + encodeURIComponent(id)")))))


(deftest js-bundle-handles-static-segment-after-param
  (testing "/api/branches/:ref/conflicts → no trailing slash leaks from the loop buffer"
    (let [out (js-bundle ["/api/branches/:ref/conflicts"])]
      ;; Correct path is `/conflicts` (no trailing slash). Buggy
      ;; emit would land `/conflicts/` and the JS call would hit
      ;; the wrong URL.
      (is (str/includes? out "\"/conflicts\"") "trailing literal must keep no trailing slash")
      (is (not (str/includes? out "\"/conflicts/\""))
          "no spurious trailing slash from buffer-accumulation"))))


(deftest js-bundle-preserves-trailing-slash-when-path-has-one
  (testing "/api/foo/ (trailing slash in source) preserves trailing in output"
    ;; Key has trailing `_` because slashes mangle to underscores —
    ;; intentionally distinct from `/api/foo` (no slash) since
    ;; reitit treats those as different routes.
    (let [out (js-bundle ["/api/foo/"])]
      (is (str/includes? out "api_foo_: \"/api/foo/\""))))

  (testing "/api/bar/:id/ (param then trailing /) → trailing slash on final literal"
    (let [out (js-bundle ["/api/bar/:id/"])]
      (is (str/includes? out "\"/\" + encodeURIComponent(id) + \"/\"")))))


(deftest js-bundle-consecutive-params
  (testing "/api/:a/:b — both params get their own `/` separator"
    (let [out (js-bundle ["/api/:a/:b"])]
      (is (str/includes? out "function(a, b)"))
      (is (str/includes? out "\"/api\" + \"/\" + encodeURIComponent(a) + \"/\" + encodeURIComponent(b)")))))


(deftest js-bundle-hyphenated-params-become-underscored
  (testing "JS function parameter names must be valid identifiers — `-` → `_` in param names AND key"
    (let [out (js-bundle ["/api/fns/:fn-id/versions"])]
      ;; function param ident — `fn_id`, not `fn-id`
      (is (str/includes? out "function(fn_id)"))
      (is (str/includes? out "encodeURIComponent(fn_id)"))
      ;; key — `api_fns_fn_id_versions`
      (is (str/includes? out "api_fns_fn_id_versions:")))))


(deftest js-bundle-sorts-and-dedupes-entries
  (testing "entries land in sorted order with duplicates collapsed (stable bundle hash)"
    (let [out (js-bundle ["/api/zebra" "/api/apple" "/api/apple"])
          entries (->> (str/split-lines out)
                       (filter #(str/includes? % ": "))
                       (filter #(str/includes? % "/api/")))]
      (is (= 2 (count entries)) "dedup collapses repeated paths")
      (is (str/starts-with? (str/triml (first entries)) "api_apple:")
          "first entry is the alphabetically-first path"))))


(deftest js-bundle-emits-leading-comment
  (testing "output starts with an AUTO-GENERATED warning so editors don't manually edit"
    (let [out (js-bundle ["/api/x"])]
      (is (str/starts-with? out "// AUTO-GENERATED")))))


(deftest js-bundle-empty-input-still-valid-js
  (testing "no paths → an empty `window.API = {}` IIFE — must still parse as JS"
    (let [out (js-bundle [])]
      (is (str/includes? out "window.API = {"))
      (is (str/includes? out "}"))
      (is (str/includes? out "})();")))))


(deftest js-bundle-roundtrip-from-router
  (testing "compiled router → ring-route-paths → routes->js-bundle produces JS that mentions every path"
    (let [router (ring/router
                   [["/api/health" {:get (constantly {:status 200})}]
                    ["/api/fns/:id" {:get (constantly {:status 200})}]
                    ["/api/branches/:ref/diff" {:get (constantly {:status 200})}]])
          out (-> router route-paths js-bundle)]
      (is (str/includes? out "api_health: \"/api/health\","))
      (is (str/includes? out "api_fns_id: function(id)"))
      (is (str/includes? out "api_branches_ref_diff: function(ref)"))
      ;; Static literal after param keeps the no-trailing-slash shape.
      (is (str/includes? out "\"/diff\""))
      (is (not (str/includes? out "\"/diff/\""))))))
