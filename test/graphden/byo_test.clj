(ns ^:integration graphden.byo-test
  "The packaged BYO executor assembly (`graphden.byo/start-byo!`): boot it
   against a hub (graph-rows HTTP + SSE relay) and prove it SERVES the org's
   handler over HTTP, refreshes on an SSE push, and fails loudly on a missing
   handler.

   Kept minimal — `:packages []` + an injected handler impl — so it doesn't
   load the whole package set. The RemoteStorage / SSE round-trip proper is in
   `storage.remote.core-test` / `system.sse-test` / `storage.remote.e2e-test`;
   this test is about the ASSEMBLY wiring."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.byo :as byo]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.export :as export]
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.sse :as sse]
    [graphden.versioning.storage.core :as vs]
    [org.httpkit.client :as http]
    [org.httpkit.server :as hk]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(def ^:private token "byo-token")


(defn- hub-storage!
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (-> (mds/create-builder) (gds/extend-builder)
                           (vts/extend-builder) (vds/extend-builder) (ds/build)))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning raw "main")))


(defn- graph-rows-server
  [storage]
  (hk/run-server
    (fn [req]
      (if (= "/api/export/graph-rows" (:uri req))
        {:status 200 :headers {"Content-Type" "application/edn"}
         :body (pr-str (export/read-graph storage))}
        {:status 404 :body ""}))
    {:port 0}))


(defn- wait-for
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline)
                          (Thread/sleep 25) (recur))))))


(deftest start-byo-serves-the-handler-and-refreshes-over-sse
  (let [storage (hub-storage!)
        ;; A handler impl injected via :extra-base-fns; returns a Ring response
        ;; whose body echoes a value read from a captured atom so the test can
        ;; observe a live refresh.
        served-body (atom "v1")]
    (exec/register-base-fn! :byo-echo (fn [_args _ctx] {:status 200 :body @served-body}))
    (let [base (setup/create-base-fn! storage "byo-echo" :any)
          handler (setup/create-composed-fn! storage "byo-handler" (:id base))
          hub (graph-rows-server storage)
          hub-url (str "http://localhost:" (:local-port (meta hub)))
          relay-listener {:callbacks (atom #{})}
          relay (sse/start-relay! {:port 0 :notify-listener relay-listener :auth-provider nil})
          sse-url (str "http://localhost:" (:local-port (meta (:server relay))))
          handle (byo/start-byo! {:hub-url hub-url :sse-url sse-url :token token
                                  :org "acme" :handler-fn "byo-handler"
                                  :port 0 :packages []
                                  :extra-base-fns {:byo-echo (exec/get-base-fn :byo-echo)}})
          byo-port (:local-port (meta (:server handle)))
          GET (fn [] @(http/get (str "http://localhost:" byo-port "/") {:as :text :timeout 5000}))]
      (try
        (testing "the BYO executor serves the org's handler over HTTP"
          (let [resp (GET)]
            (is (= 200 (:status resp)))
            (is (= "v1" (:body resp)) "ran byo-handler from the graph fetched over HTTP")))

        (testing "an SSE-pushed invalidation live-refreshes the served graph"
          ;; The handler's body is read from the atom at execute time, so a
          ;; refresh+recompile is what re-runs it. Change the atom, push an
          ;; invalidation, and the next request reflects it.
          (is (wait-for 3000 #(seq @(:subscribers relay))) "BYO source connected")
          (reset! served-body "v2")
          (doseq [cb @(:callbacks relay-listener)]
            (cb {:kind :fn :op :invalidate :id (str (:id handler))}))
          (is (wait-for 5000 #(= "v2" (:body (GET))))
              "after the SSE push + refresh, the handler serves the new value"))
        (finally
          (byo/stop-byo! handle)
          (sse/stop-relay! relay)
          (hub)
          (sp/close storage))))))


(deftest byo-handler-runs-with-effects-unclamped
  ;; A BYO executor runs the customer's OWN graph on their OWN hardware, so its
  ;; handler must NOT carry the cloud effect clamp (which forbids :network et
  ;; al. to protect shared infra). Guard the decision: a handler that records a
  ;; :network effect serves 200 here; under the cloud clamp it would 500.
  (let [storage (hub-storage!)]
    (exec/register-base-fn! :byo-net
                            (fn [_args _ctx]
                              (cr/record-effect! :network)
                              {:status 200 :body "net-ok"}))
    (let [base (setup/create-base-fn! storage "byo-net" :any)
          _ (setup/create-composed-fn! storage "byo-net-handler" (:id base))
          hub (graph-rows-server storage)
          hub-url (str "http://localhost:" (:local-port (meta hub)))
          handle (byo/start-byo! {:hub-url hub-url :token token :org "acme"
                                  :handler-fn "byo-net-handler" :port 0 :packages []
                                  :extra-base-fns {:byo-net (exec/get-base-fn :byo-net)}})
          byo-port (:local-port (meta (:server handle)))]
      (try
        (let [resp @(http/get (str "http://localhost:" byo-port "/") {:as :text :timeout 5000})]
          (is (= 200 (:status resp)) "network effect was allowed (no cloud clamp)")
          (is (= "net-ok" (:body resp))))
        (finally
          (byo/stop-byo! handle)
          (hub)
          (sp/close storage))))))


(deftest start-byo-throws-on-a-missing-handler-fn
  (let [storage (hub-storage!)
        hub (graph-rows-server storage)
        hub-url (str "http://localhost:" (:local-port (meta hub)))]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"handler fn not found"
            (byo/start-byo! {:hub-url hub-url :token token :org "acme"
                             :handler-fn "does-not-exist" :port 0 :packages []})))
      (finally (hub) (sp/close storage)))))


(deftest start-byo-preflight-rejects-missing-config
  ;; A forgotten env var should fail with a clear message BEFORE any network /
  ;; NPE deeper in. Each required key, omitted in turn, throws :byo/missing-config.
  (let [full {:hub-url "http://localhost:1" :token "t" :org "o"
              :handler-fn "h" :port 0 :packages []}]
    (doseq [k [:hub-url :token :org :handler-fn]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required config"
            (byo/start-byo! (assoc full k nil)))
          (str "omitting " k " is rejected")))
    (testing "a blank string counts as missing too"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required config"
            (byo/start-byo! (assoc full :org "  ")))))))
