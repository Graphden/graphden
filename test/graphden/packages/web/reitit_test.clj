(ns graphden.packages.web.reitit-test
  "Unit tests for the `:middleware` factory impl. `:proceed` is now a
   pure fn-def composition (no impl) — its behavior is exercised by
   the auth integration tests via the actual fn-graph executor.

   Loads impls dynamically from `resources/packages/`, matching the
   pattern used by layout-test."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))


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
