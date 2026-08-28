(ns graphden.packages.hub-sync-test
  "Tests for the /api/sync/* hub push/pull surface (registry package) —
   the two wire adapters against a stub hub, and the unconfigured guard
   (GRAPHDEN_HUB_URL unset ⇒ status {:configured false}, mutations 409)."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [org.httpkit.server :as http-kit]))


(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (fn [t]
    ;; Same package set as registry-test — shares its golden template.
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!
                            "hub-sync-test" ["core" "web" "app" "registry" "mcp"])]
      (t))))


(defn- fn-id
  [kw]
  (or (get (:all-name->id *bootstrap*) kw)
      (throw (ex-info (str "fn not in bootstrap: " kw) {:fn kw}))))


(defn- run-fn
  [kw args]
  (exec/execute-with-named-args (:ctx *bootstrap*) (fn-id kw) args))


(defn- with-stub-hub
  "Boot a stub hub on an ephemeral port; every request is captured (body
   pre-slurped under :body-text) before `handler` answers it. Calls
   `(f base-url captured-atom)`."
  [handler f]
  (let [captured (atom [])
        wrapped (fn [req]
                  (let [req (assoc req :body-text (some-> (:body req) slurp))]
                    (swap! captured conj req)
                    (handler req)))
        stop (http-kit/run-server wrapped {:port 0})
        port (:local-port (meta stop))]
    (try (f (str "http://127.0.0.1:" port) captured)
         (finally (stop)))))


(deftest hub-fetch-bundle-pull-read
  (testing "happy path decodes the hub's EDN bundle and rides the branch header"
    (with-stub-hub
      (fn [req]
        (if (= "/api/export/graph" (:uri req))
          {:status 200 :headers {"Content-Type" "application/edn"}
           :body (pr-str {:fns [{:name :from-hub :parent :const :args {:value 1}}]
                          :namespaces []})}
          {:status 404 :body "no"}))
      (fn [base captured]
        (let [r (run-fn :hub-fetch-bundle {:hub-url base :branch "dev"})]
          (is (nil? (:error r)))
          (is (= [:from-hub] (mapv :name (:fns r))))
          (is (= "dev" (get-in (first @captured) [:headers "x-graphden-branch"])))
          (testing "no GRAPHDEN_HUB_TOKEN in the test env → no bearer sent"
            (is (nil? (get-in (first @captured) [:headers "authorization"]))))))))
  (testing "non-200 → hub-fetch-failed as data"
    (with-stub-hub
      (fn [_] {:status 500 :body "boom"})
      (fn [base _]
        (let [r (run-fn :hub-fetch-bundle {:hub-url base :branch nil})]
          (is (= "hub-fetch-failed" (:error r)))
          (is (= 500 (:status r)))))))
  (testing "unreadable body → hub-bundle-unreadable as data"
    (with-stub-hub
      (fn [_] {:status 200 :body "{:not-closed"})
      (fn [base _]
        (is (= "hub-bundle-unreadable"
               (:error (run-fn :hub-fetch-bundle {:hub-url base :branch nil})))))))
  (testing "unreachable hub → hub-unreachable as data"
    (let [r (run-fn :hub-fetch-bundle {:hub-url "http://127.0.0.1:9" :branch nil})]
      (is (= "hub-unreachable" (:error r))))))


(deftest hub-push-bundle-push-write
  (testing "happy path POSTs the wire bundle as create+prune and parses the report"
    (with-stub-hub
      (fn [req]
        (if (= "/api/import/graph" (:uri req))
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:ok true :branch "push/main" :fn-ids ["x"]})}
          {:status 404 :body "no"}))
      (fn [base captured]
        (let [bundle {:fns [{:name :mine :parent :const :args {:value 2}}]
                      :namespaces [] :secrets [] :secret-paths-included? false}
              r (run-fn :hub-push-bundle! {:hub-url base :target "push/main" :bundle bundle})]
          (is (true? (:ok r)))
          (is (= ["x"] (:fn-ids r)))
          (let [req (first @captured)]
            (is (= "/api/import/graph" (:uri req)))
            (testing "create+prune+target ride the query string"
              (is (re-find #"create=true" (:query-string req)))
              (is (re-find #"prune=true" (:query-string req)))
              (is (re-find #"target=push%2Fmain" (:query-string req))))
            (testing "the body is the {:fns […]} EDN the import route reads"
              (is (= [:mine] (mapv :name (:fns (edn/read-string (:body-text req))))))))))))
  (testing "hub refusal → hub-import-failed with status + detail as data"
    (with-stub-hub
      (fn [_] {:status 409 :body "{\"ok\":false,\"reason\":\"branch-protected\"}"})
      (fn [base _]
        (let [r (run-fn :hub-push-bundle! {:hub-url base :target "push/main" :bundle {:fns []}})]
          (is (= "hub-import-failed" (:error r)))
          (is (= 409 (:status r)))
          (is (re-find #"branch-protected" (str (:detail r))))))))
  (testing "unreachable hub → hub-unreachable as data"
    (let [r (run-fn :hub-push-bundle! {:hub-url "http://127.0.0.1:9" :target "t" :bundle {:fns []}})]
      (is (= "hub-unreachable" (:error r))))))


(deftest sync-routes-unconfigured-guard
  ;; The route compositions read GRAPHDEN_HUB_URL from the server env —
  ;; unset in a test JVM, which is exactly the state under test.
  (is (empty? (str (System/getenv "GRAPHDEN_HUB_URL")))
      "test assumes GRAPHDEN_HUB_URL is not set in the test environment")
  (testing "status body reports unconfigured and never carries a token"
    (let [body (run-fn :_sync-status-body {})]
      (is (false? (:configured body)))
      (is (nil? (:hub-url body)))
      (is (= #{:configured :hub-url} (set (keys body))))))
  (testing "push answers the 409 hub-not-configured envelope"
    (let [resp (run-fn :_sync-push-result {:request {}})]
      (is (= 409 (:status resp)))
      (is (= "hub-not-configured" (get (json/parse-string (str (:body resp))) "reason")))))
  (testing "pull answers the 409 hub-not-configured envelope"
    (let [resp (run-fn :_sync-pull-result {:request {}})]
      (is (= 409 (:status resp)))
      (is (= "hub-not-configured" (get (json/parse-string (str (:body resp))) "reason"))))))
