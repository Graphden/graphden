(ns graphden.packages.web.branch-router-impls-test
  "Unit tests for the `web/branch-router` base-fn primitives that
   `:branch-routing-wrap` composes over.

   `:current-branch-router` is the wrap's `:test` — nil means \"no
   router installed yet\" and the whole per-branch layer is bypassed,
   so reading the wrong atom silently routes every request to the
   default branch. `:dispatch-to-branch` carries three guarantees that
   cost real incidents: the `/livez` probe answers before any
   registry-touching work, a streaming body is realized ONCE so a
   downstream form parser doesn't see a spent InputStream (the live
   `POST /api/orgs` that created an org named \"\"), and each dispatch
   runs under a FRESH call cache (a nested dispatch sharing the outer
   memo recursed until the stack overflowed)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "branch-router"))


(defn- call
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(defn- router-answering
  "A minimal router whose app-router seam answers every request — enough
   to drive `dispatch` end-to-end without a branch registry."
  [respond]
  {:base-ctx {:app-router (fn [_base-ctx request] (respond request))}})


(deftest current-branch-router-reads-the-installed-router
  (testing "no router installed → nil, and the wrap falls back to single-branch"
    (binding [br/*active-router-override* (atom nil)]
      (is (nil? (call :current-branch-router {})))))

  (testing "the INSTALLED router is what the base-fn hands the wrap"
    (binding [br/*active-router-override* (atom ::the-router)]
      (is (= ::the-router (call :current-branch-router {})))))

  (testing "it re-reads the atom — a router installed later is picked up"
    ;; The wrap is compiled once at boot, before `:exec/branch-router`
    ;; fires; a value captured at compile time would pin nil forever.
    (let [holder (atom nil)]
      (binding [br/*active-router-override* holder]
        (is (nil? (call :current-branch-router {})))
        (reset! holder ::late)
        (is (= ::late (call :current-branch-router {})))))))


(deftest dispatch-answers-livez-before-touching-the-router
  (testing "/livez short-circuits — a router with NO base-ctx still answers"
    ;; The liveness probe must survive a mid-rebuild registry; if it
    ;; fell through to branch resolution, a rebuild would fail the probe
    ;; and the orchestrator would kill a healthy pod.
    (let [resp (call :dispatch-to-branch {:router {} :request {:uri "/livez"}})]
      (is (= 200 (:status resp)))
      (is (= "{\"status\":\"alive\"}" (:body resp))))))


(deftest dispatch-realizes-a-streaming-body-once-for-every-consumer
  (testing "an InputStream body reaches the downstream handler as a String"
    ;; A form POST to a fall-through route used to hit `parse-form-body`
    ;; as an unread stream and parse to {} — the live incident where
    ;; `POST /api/orgs name=x` created an org with a blank name.
    (let [seen (atom ::unset)
          router (router-answering (fn [req] (reset! seen (:body req)) {:status 204}))
          body (java.io.ByteArrayInputStream. (String/.getBytes "name=x" "UTF-8"))]
      (is (= {:status 204}
             (call :dispatch-to-branch {:router router
                                        :request {:uri "/api/orgs" :body body}})))
      (is (= "name=x" @seen))))

  (testing "a body that is already a String passes through untouched"
    (let [seen (atom ::unset)
          router (router-answering (fn [req] (reset! seen (:body req)) {:status 204}))]
      (call :dispatch-to-branch {:router router
                                 :request {:uri "/x" :body "already-read"}})
      (is (= "already-read" @seen)))))


(deftest dispatch-runs-under-a-fresh-call-cache
  (testing "the dispatched handler does NOT share the caller's memo map"
    ;; A nested dispatch (MCP's `branch` argument re-dispatching) that
    ;; shared the outer cache answered the INNER request from the OUTER
    ;; request's memo and recursed until the stack overflowed.
    (let [outer (java.util.HashMap.)
          seen (atom nil)
          router (router-answering
                   (fn [_req] (reset! seen ce/*request-call-cache*) {:status 200}))]
      (binding [ce/*request-call-cache* outer]
        (call :dispatch-to-branch {:router router :request {:uri "/x"}}))
      (is (some? @seen) "a cache IS bound for the dispatched handler")
      (is (not (identical? outer @seen))
          "and it is a fresh one, not the caller's"))))


(deftest dispatch-declares-its-network-effect-before-running
  (testing "the effect is recorded at THIS layer, not left to the handler"
    (let [trace (atom [])]
      (binding [cr/*effect-trace* trace]
        (call :dispatch-to-branch {:router {} :request {:uri "/livez"}}))
      (is (contains? (set @trace) :network))))

  (testing "a context that forbids :network refuses the dispatch, before it runs"
    ;; record-effect! is called FIRST by convention — the gate has to
    ;; fire before the downstream handler performs any HTTP work.
    (let [ran (atom false)
          router (router-answering (fn [_] (reset! ran true) {:status 200}))]
      (binding [cr/*allowed-effects* #{:db}]
        (is (thrown? clojure.lang.ExceptionInfo
              (call :dispatch-to-branch {:router router :request {:uri "/x"}}))))
      (is (false? @ran) "the handler never ran"))))
