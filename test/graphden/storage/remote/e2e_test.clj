(ns ^:integration graphden.storage.remote.e2e-test
  "The whole BYO executor loop, end-to-end in ONE JVM — as close to a real
   two-machine deployment as a test can get.

   HUB: a real Postgres graph + an HTTP `GET /api/export/graph-rows` endpoint +
   an SSE relay.
   BYO: a `RemoteStorage` bootstrapped from the hub over HTTP + an SSE source
   whose `on-event` refreshes it and recompiles.

   Change the graph on the hub, fire the invalidation the way
   `notify-after-write!` does, and prove the BYO executor picks up the new
   value — bootstrap + SSE push + refresh + recompile, all wired."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ectx]
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
    [graphden.storage.remote.core :as remote]
    [graphden.storage.remote.sse :as remote-sse]
    [graphden.system.sse :as sse]
    [graphden.versioning.storage.core :as vs]
    [org.httpkit.server :as hk]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(def ^:private token "hub-token")


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
  "A minimal hub: serves the raw-rows bundle (bearer-checked) — exactly what
   `GET /api/export/graph-rows` returns in prod."
  [storage]
  (hk/run-server
    (fn [req]
      (if (and (= "/api/export/graph-rows" (:uri req))
               (= (str "Bearer " token) (get-in req [:headers "authorization"])))
        {:status 200
         :headers {"Content-Type" "application/edn"}
         :body (pr-str (export/read-graph storage))}
        {:status 401 :body ""}))
    {:port 0}))


(defn- wait-for
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline)
                          (Thread/sleep 25) (recur))))))


(deftest byo-executor-bootstraps-then-live-refreshes-over-sse
  (let [storage (hub-storage!)]
    (exec/register-base-fn! :echo-x (fn [args _ctx] (get args :x)))
    (let [base (setup/create-base-fn! storage "echo-x" :any)
          slot (setup/create-slot! storage "x" :any)
          _ (setup/attach-slot! storage (:id base) (:id slot) 0)
          composed (setup/create-composed-fn! storage "echo-n" (:id base))
          _ (setup/bind-value! storage (:id composed) (:id slot) 1)
          fn-id (:id composed)
          ;; ── HUB ────────────────────────────────────────────────────────
          hub (graph-rows-server storage)
          hub-url (str "http://localhost:" (:local-port (meta hub)))
          relay-listener {:callbacks (atom #{})}
          relay (sse/start-relay! {:port 0 :notify-listener relay-listener
                                   :auth-provider nil})
          relay-url (str "http://localhost:" (:local-port (meta (:server relay))))
          ;; ── BYO executor ──────────────────────────────────────────────
          remote-storage (remote/create-remote-storage hub-url token)
          byo-ctx (ectx/create-context {:storage remote-storage
                                        :base-fns (exec/get-default-registry)})
          _ (cr/rebuild! byo-ctx)
          ;; on-event: refetch the graph + recompile, exactly the BYO wiring.
          refreshed (atom 0)
          source (remote-sse/start-source!
                   {:hub-url relay-url :token token
                    :on-event (fn [event]
                                (when (= :fn (:kind event))
                                  (remote/refresh! remote-storage)
                                  (cr/rebuild! byo-ctx)
                                  (swap! refreshed inc)))})]
      (try
        (testing "BYO executor bootstrapped the graph over HTTP and runs it"
          (is (= 1 (cr/execute byo-ctx fn-id {}))))

        (testing "a change on the hub + an SSE invalidation live-refreshes the BYO executor"
          ;; The SSE source connects asynchronously — wait for the relay to see
          ;; it before firing, or the event has no subscriber to reach.
          (is (wait-for 3000 #(seq @(:subscribers relay))) "BYO source connected to the relay")
          ;; Change the graph on the hub (rebind echo-n → 2).
          (let [binding-id (:id (first (sp/query-entities storage :binding {:fn-id fn-id})))]
            (sp/update-entity storage :binding binding-id {:value 2 :value-present true}))
          ;; Fire the invalidation the way notify-after-write! does.
          (doseq [cb @(:callbacks relay-listener)]
            (cb {:kind :fn :op :invalidate :id (str fn-id)}))
          (is (wait-for 5000 #(pos? @refreshed)) "BYO executor received the SSE event + refreshed")
          (is (= 2 (cr/execute byo-ctx fn-id {}))
              "and now executes the NEW value, pulled fresh over HTTP"))
        (finally
          (remote-sse/stop-source! source)
          (sse/stop-relay! relay)
          (hub)
          (sp/close storage))))))
