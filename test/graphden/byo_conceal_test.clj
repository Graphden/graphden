(ns ^:integration ^:serial graphden.byo-conceal-test
  "A BYO executor is given its org's graph as the org may SEE it
   (`GET /api/export/graph-rows` → `crud.entities/concealed-export-rows`,
   docs/SECURITY_MODEL.md layer 9): a fn whose composition is hidden from
   the org ships signature-only, and running it — or anything built on it —
   on the executor is refused as `:execution-error/fn-concealed`, while the
   org's own fns run as before.

   `^:serial` — the view-impl seam is a process-global atom."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.byo :as byo]
    [graphden.crud.entities :as entities]
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
    [graphden.versioning.storage.core :as vs]
    [org.httpkit.client :as http]
    [org.httpkit.server :as hk]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- hub-storage!
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (-> (mds/create-builder) (gds/extend-builder)
                           (vts/extend-builder) (vds/extend-builder) (ds/build)))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning raw "main")))


(defn- hide-named
  "A stub view-impl filter shaped like the tenancy addon's."
  [fn-name]
  (fn [graph]
    (entities/strip-impl-of graph (into #{} (comp (filter #(= fn-name (:name %))) (map :id))
                                        (:fns graph)))))


(defn- concealing-hub
  "The hub's graph-rows route as the `graph-rows` base-fn serves it: the
   request storage's rows through `concealed-export-rows`."
  [storage]
  (hk/run-server
    (fn [req]
      (if (= "/api/export/graph-rows" (:uri req))
        {:status 200 :headers {"Content-Type" "application/edn"}
         :body (pr-str (entities/concealed-export-rows (export/read-graph storage)))}
        {:status 404 :body ""}))
    {:port 0}))


(defn- error-type
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest a-concealed-fn-ships-signature-only-and-refuses-to-run
  (let [storage (hub-storage!)]
    (exec/register-base-fn! :byo-conceal-echo (fn [_args _ctx] {:status 200 :body "own"}))
    (let [base (setup/create-base-fn! storage "byo-conceal-echo" :any)
          own (setup/create-composed-fn! storage "byo-own-handler" (:id base))
          hidden (setup/create-composed-fn! storage "byo-shared-fn" (:id base))
          on-hidden (setup/create-composed-fn! storage "byo-built-on-shared" (:id hidden))]
      (reset! entities/view-impl-filter (hide-named "byo-shared-fn"))
      (try
        (testing "the bundle carries the hidden fn as a signature-only row"
          (let [row (first (filter #(= (:id hidden) (:id %))
                                   (:fns (entities/concealed-export-rows (export/read-graph storage)))))]
            (is (true? (:concealed? row)))
            (is (= [] (:parent-ids row)) "no parent chain")))
        (let [hub (concealing-hub storage)
              handle (byo/start-byo! {:hub-url (str "http://localhost:" (:local-port (meta hub)))
                                      :token "t" :org "acme" :handler-fn "byo-own-handler"
                                      :port 0 :packages []
                                      :extra-base-fns {:byo-conceal-echo
                                                       (exec/get-base-fn :byo-conceal-echo)}})
              ctx (:ctx handle)]
          (try
            (testing "the org's own fn still runs"
              (let [resp @(http/get (str "http://localhost:" (:local-port (meta (:server handle))) "/")
                                    {:as :text :timeout 5000})]
                (is (= 200 (:status resp)))
                (is (= "own" (:body resp))))
              (is (= {:status 200 :body "own"} (cr/execute ctx (:id own) {}))))
            (testing "running the concealed fn is a canonical refusal, not a crash"
              (is (= {:type :execution-error/fn-concealed
                      :fn-id (:id hidden) :concealed-fn-id (:id hidden)}
                     (error-type #(cr/execute ctx (:id hidden) {})))))
            (testing "so is running a fn built on it — naming the concealed one"
              (is (= {:type :execution-error/fn-concealed
                      :fn-id (:id on-hidden) :concealed-fn-id (:id hidden)}
                     (error-type #(cr/execute ctx (:id on-hidden) {})))))
            (finally
              (byo/stop-byo! handle)
              (hub))))
        (finally
          (reset! entities/view-impl-filter nil)
          (sp/close storage))))))
