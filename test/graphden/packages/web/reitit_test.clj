(ns graphden.packages.web.reitit-test
  "Unit tests for web/reitit base-fns: middleware factory, proceed
   (next-in-chain delegate), and router middleware-chain composition.

   Loads impls dynamically from `resources/packages/` and exercises
   them directly, matching the pattern used by layout-test."
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
   map keyed by symbols (the arg names). Return a thin wrapper that
   accepts a plain map and calls the underlying impl."
  [sym]
  (when reitit-ns
    @(ns-resolve reitit-ns sym)))


(def ^:private middleware-impl (unwrap 'middleware))
(def ^:private proceed-impl (unwrap 'proceed))


;; =============================================================================
;; HELPERS
;; =============================================================================

(defn- call-mw
  "Call the middleware factory impl. Body is a Ring-shaped fn `(fn [req] resp)`.
   defbase compiles arg-lookups against __args keyed by keywords."
  [name body]
  (middleware-impl {:name name :body body} nil))


(defn- call-proceed
  "Call proceed with a request."
  [request]
  (proceed-impl {:request request} nil))


;; =============================================================================
;; TESTS — middleware factory
;; =============================================================================

(deftest middleware-produces-reitit-spec
  (testing "middleware returns a map with :name and :wrap"
    (let [spec (call-mw :test-mw (fn [_req] {:status 200}))]
      (is (map? spec))
      (is (= :test-mw (:name spec)))
      (is (fn? (:wrap spec))))))


(deftest middleware-wrap-calls-body
  (testing "the composed handler invokes body with the incoming request"
    (let [captured (atom nil)
          body (fn [req] (reset! captured req) {:status 204 :body "ok"})
          spec (call-mw :capture body)
          wrapped ((:wrap spec) (fn [_] {:status 500 :body "should not reach"}))
          resp (wrapped {:uri "/x" :method :get})]
      (is (= {:uri "/x" :method :get} @captured))
      (is (= 204 (:status resp)))
      (is (= "ok" (:body resp))))))


(deftest middleware-early-return
  (testing "body can short-circuit by returning a response without proceeding"
    (let [handler-called (atom false)
          body (fn [_req] {:status 401 :body "nope"})
          spec (call-mw :deny body)
          handler (fn [_] (reset! handler-called true) {:status 200})
          wrapped ((:wrap spec) handler)
          resp (wrapped {:uri "/x"})]
      (is (= 401 (:status resp)))
      (is (= "nope" (:body resp)))
      (is (false? @handler-called)
          "early-return body must not trigger the next handler"))))


;; =============================================================================
;; TESTS — proceed
;; =============================================================================

(deftest proceed-throws-outside-middleware
  (testing "proceed outside a middleware context throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (call-proceed {:uri "/x"})))))


(deftest proceed-delegates-to-next-handler
  (testing "proceed inside a middleware body invokes the wrapped handler"
    (let [body (fn [req] (call-proceed (assoc req :tagged true)))
          spec (call-mw :pass-through body)
          handler (fn [req] {:status 200 :req req})
          wrapped ((:wrap spec) handler)
          resp (wrapped {:uri "/x"})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:req :tagged]))
          "proceed carried the transformed request into the handler"))))


(deftest middleware-chain-composition
  (testing "two middlewares chain outer-to-inner; proceed walks down the stack"
    (let [outer-body (fn [req] (call-proceed (assoc req :outer-saw true)))
          inner-body (fn [req] (call-proceed (assoc req :inner-saw true)))
          outer ((:wrap (call-mw :outer outer-body))
                 ((:wrap (call-mw :inner inner-body))
                  (fn [req] {:status 200 :req req})))
          resp (outer {:uri "/x"})]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:req :outer-saw])))
      (is (true? (get-in resp [:req :inner-saw]))
          "inner middleware ran after outer delegated"))))


(deftest middleware-short-circuits-inner
  (testing "outer middleware returning a response stops the chain"
    (let [inner-called (atom false)
          outer-body (fn [_req] {:status 403 :body "forbidden"})
          inner-body (fn [req] (reset! inner-called true)
                               (call-proceed req))
          outer ((:wrap (call-mw :gate outer-body))
                 ((:wrap (call-mw :trace inner-body))
                  (fn [_] {:status 200})))
          resp (outer {:uri "/x"})]
      (is (= 403 (:status resp)))
      (is (false? @inner-called)
          "outer's early-return must prevent inner from running"))))
