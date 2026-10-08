(ns ^:integration ^:serial graphden.http-host.http-test
  "A real stored handler, real PostgreSQL lease and real HTTP transport.
   Serial because the deployment adapter is a process-wide install seam."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.accounts.core :as accounts]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.init :as accounts-init]
    [graphden.accounts.provider :as accounts-provider]
    [graphden.auth.provider :as auth]
    [graphden.crud.entities :as entities]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as context]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.http-host.core :as host]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.init.storage]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.golden-app :as golden]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]
    [org.httpkit.client :as client]
    [org.httpkit.server :as server])
  (:import
    (java.util.concurrent
      ExecutorService
      Future
      TimeUnit)))


(use-fixtures :once (golden/fixture (ns-name *ns*) ["core" "web"]))
(def ^:dynamic *calls* nil)


(defbase probe
  [prefix request]
  (cr/record-effect! :state)
  (swap! *calls* conj request)
  {:status 200 :headers {"Content-Type" "text/plain"}
   :body (str prefix (:uri request))})


(defn- prepared
  []
  (exec/register-base-fn! :temporary-http-probe probe)
  (let [base (:storage golden/*bootstrap*)
        _ (sp/initialize (vs/unwrap base) (ig/init-key :db/schema {}))
        branch (vs/create-branch! base (str "temporary-http-" (random-uuid)))
        storage (vs/switch-branch base (:id branch))
        ctx (assoc (context/create-context {:storage storage :base-fns (exec/get-default-registry)})
                   :auth-provider (auth/single-token-provider "fixture-host-owner"))
        defs [{:name :temporary-http-probe
               :args {:prefix {:type :text} :request {:type :ring-request-shape}}
               :return-type :ring-response-shape :effects #{:state}}
              {:name :temporary-http-answer :parent :temporary-http-probe :args {:prefix "first"}}]]
    (setup/sync-and-invalidate! ctx storage defs)
    {:base base :storage storage :ctx ctx :branch branch
     :fn-id (ids/fn-id nil :temporary-http-answer)
     :router (br/create-router ctx "temporary-http-answer")}))


(deftest publish-request-change-stop-and-delete-branch
  (let [previous @host/adapter
        pool (persist/make-execution-pool 4 8)]
    (reset! host/adapter nil)
    (try
      (binding [*calls* (atom []) persist/*execution-pool-override* pool]
        (let [{:keys [router fn-id ctx storage base branch]} (prepared)
              handler (host/make-router router)
              stop (server/run-server (bound-fn [request] (handler request)) {:port 0})
              origin (str "http://localhost:" (:local-port (meta stop)))
              id (random-uuid)
              publish (fn [id]
                        @(client/post (str origin host/api-prefix)
                                      {:headers {"Authorization" "Bearer fixture-host-owner"
                                                 "Content-Type" "application/json" "Origin" origin}
                                       :body (json/generate-string {:create-id id :fn-id fn-id})}))]
          (try
            (let [created (publish id)
                  data (json/parse-string (:body created) true)
                  url (get-in data [:publication :url])]
              (is (= 201 (:status created)) (:body created))
              (is (= [] @*calls*) "Publication validates but never invokes the handler")
              (when url
                (let [fetched @(client/get (str url "/hello?x=1")
                                           {:headers {"Cookie" "gd_session=must-not-arrive"
                                                      "Authorization" "Bearer must-not-arrive"}})]
                  (is (= 200 (:status fetched)))
                  (is (= "first/hello" (:body fetched)))
                  (is (= "x=1" (:query-string (last @*calls*))))
                  (is (nil? (get-in (last @*calls*) [:headers "cookie"])))
                  (is (nil? (get-in (last @*calls*) [:headers "authorization"]))))
                (let [posted @(client/post (str url "/body")
                                           {:body "ordinary POST body"
                                            :headers {"Content-Type" "text/plain"}})]
                  (is (= 200 (:status posted)))
                  (is (= :post (:request-method (last @*calls*))))
                  (is (= "ordinary POST body" (:body (last @*calls*)))))
                (let [binding-row (first (sp/query-entities storage :binding {:fn-id fn-id}))]
                  (entities/update-entity :binding (:id binding-row) {:value "second"} ctx))
                (is (= "second/hello" (:body @(client/get (str url "/hello")))))
                (is (= 409 (:status (publish id))) "create-id never turns into update")
                (is (= 200 (:status @(client/delete (str origin host/api-prefix "/" id)
                                                    {:headers {"Authorization" "Bearer fixture-host-owner"}}))))
                (is (= 404 (:status @(client/get (str url "/hello"))))))
              (let [next-id (random-uuid)
                    next-response (publish next-id)
                    next-url (get-in (json/parse-string (:body next-response) true) [:publication :url])]
                (is (= 201 (:status next-response)))
                (vs/delete-branch! base (:id branch))
                (is (nil? (sp/read-entity (vs/unwrap base) :session next-id)))
                (when next-url (is (= 404 (:status @(client/get next-url)))))))
            (finally (stop)))))
      (finally
        (ExecutorService/.shutdownNow pool)
        (reset! host/adapter previous)))))


(deftest account-api-publication-rechecks-creating-session
  (let [previous @host/adapter
        pool (persist/make-execution-pool 4 8)]
    (reset! host/adapter nil)
    (try
      (binding [*calls* (atom []) persist/*execution-pool-override* pool]
        (let [{:keys [ctx storage fn-id]} (prepared)
              raw (vs/unwrap storage)
              _ (sp/initialize raw (ig/init-key :db/schema {:extensions [(accounts-init/schema-extension)]}))
              account (accounts/create-account! raw {:display-name "HTTP fixture owner"})
              token (accounts/mint-session! raw (str (:id account)) {:kind "api" :scopes "execute"})
              read-token (accounts/mint-session! raw (str (:id account)) {:kind "api" :scopes "read"})
              browser-token (accounts/mint-session! raw (str (:id account)))
              token-row (first (sp/query-entities raw :session {:token-hash (crypto/sha256-hex token)}))
              router (br/create-router (assoc ctx :auth-provider (accounts-provider/accounts-provider raw))
                                       "temporary-http-answer")
              handler (host/make-router router)
              id (random-uuid)
              request {:request-method :post :uri host/api-prefix
                       :headers {"authorization" (str "Bearer " token) "host" "localhost"}
                       :body (json/generate-string {:create-id id :fn-id fn-id})}
              serve #(host/serve router id {:request-method :get :uri "/"} "/" nil)]
          (is (= 403 (:status (handler (assoc-in request [:headers "authorization"] (str "Bearer " read-token)))))
              "An owner's read-only token cannot publish")
          (host/publish! router request)
          (is (= 200 (:status (serve))))
          (is (= 403 (:status (handler {:request-method :delete :uri (str host/api-prefix "/" id)
                                        :headers {"authorization" (str "Bearer " read-token)}})))
              "An owner's read-only token cannot revoke")
          (let [listing (handler {:request-method :get :uri host/api-prefix
                                  :headers {"authorization" (str "Bearer " read-token)}})]
            (is (= 200 (:status listing)))
            (is (= 1 (count (:publications (json/parse-string (:body listing) true))))))
          (sp/update-entity raw :session (:id token-row) {:expires-at 1})
          (is (= 404 (:status (serve))) "Expiry of the creating API credential revokes access")
          (sp/update-entity raw :session (:id token-row) {:expires-at nil})
          (is (= 200 (:status (serve))))
          (sp/delete-entity raw :session (:id token-row))
          (is (= 404 (:status (serve))) "An active account cannot revive a revoked source API token")
          (is (= 200 (:status (handler {:request-method :delete :uri (str host/api-prefix "/" id)
                                        :headers {"cookie" (str "gd_session=" browser-token)
                                                  "x-graphden-branch" "already-deleted"}})))
              "Current browser owner can clean up a revoked source token on a missing branch")
          (is (nil? (sp/read-entity raw :session id)))))
      (finally
        (ExecutorService/.shutdownNow pool)
        (reset! host/adapter previous)))))


(deftest auth-disabled-installation-does-not-invent-a-publication-owner
  (let [{:keys [ctx fn-id]} (prepared)
        router (br/create-router (dissoc ctx :auth-provider) "temporary-http-answer")
        handler (host/make-router router)
        status (handler {:request-method :get :uri host/api-prefix})
        body (json/parse-string (:body status) true)
        create (handler {:request-method :post :uri host/api-prefix
                         :headers {"host" "localhost"}
                         :body (json/generate-string {:create-id (random-uuid) :fn-id fn-id})})]
    (is (= 200 (:status status)))
    (is (= {:ok true :available false :reason "authentication-required" :publications []} body))
    (is (= 403 (:status create)))))


(deftest one-slot-host-rejects-overlap-and-recovers-after-release
  (let [previous @host/adapter
        pool (persist/make-execution-pool 1 1)]
    (reset! host/adapter nil)
    (try
      (binding [*calls* (atom [])
                persist/*execution-pool-override* pool
                persist/*max-concurrent-executions-per-org* 1]
        (let [{:keys [router fn-id storage base branch]} (prepared)
              handler (host/make-router router)
              stop (server/run-server (bound-fn [request] (handler request)) {:port 0})
              origin (str "http://localhost:" (:local-port (meta stop)))]
          (try
            (let [created @(client/post (str origin host/api-prefix)
                                        {:headers {"Authorization" "Bearer fixture-host-owner"
                                                   "Content-Type" "application/json" "Origin" origin}
                                         :body (json/generate-string {:create-id (random-uuid) :fn-id fn-id})})
                  url (get-in (json/parse-string (:body created) true) [:publication :url])]
              (is (= 201 (:status created)) (:body created))
              (when url
                (is (= 200 (:status @(client/get url))) "An external request needs only one slot")
                (let [before @*calls*
                      release (persist/acquire-execution-slot! storage (tc/current-org) false)]
                  (is (some? release))
                  (try
                    (is (= 503 (:status @(client/get url))) "A waiting graph caller cannot borrow another slot")
                    (is (= before @*calls*) "Admission rejection does not invoke the graph")
                    (finally (when release (release)))))
                (is (= 200 (:status @(client/get url))) "Finishing the first run restores availability")
                (is (= 2 (count @*calls*)))))
            (finally
              (stop)
              (vs/delete-branch! base (:id branch))))))
      (finally
        (ExecutorService/.shutdownNow pool)
        (reset! host/adapter previous)))))


(deftest saturated-worker-queue-rejects-without-leaking-the-org-reservation
  (let [previous @host/adapter
        pool (persist/make-execution-pool 1 1)
        started (promise)
        unblock (promise)]
    (reset! host/adapter nil)
    (try
      (binding [*calls* (atom []) persist/*execution-pool-override* pool]
        (let [{:keys [router fn-id base branch]} (prepared)
              id (random-uuid)
              request {:request-method :post :uri host/api-prefix
                       :headers {"authorization" "Bearer fixture-host-owner" "host" "localhost"}
                       :body (json/generate-string {:create-id id :fn-id fn-id})}
              serve #(host/serve router id {:request-method :get :uri "/"} "/" nil)]
          (try
            (host/publish! router request)
            (let [running (ExecutorService/.submit pool
                                                   ^Runnable (fn [] (deliver started true) @unblock))]
              (is (true? (deref started 5000 :timeout)))
              (let [queued (ExecutorService/.submit pool ^Runnable (fn [] nil))]
                (is (= 503 (:status (serve))))
                (is (= [] @*calls*) "A full shared queue never invokes the handler")
                (deliver unblock true)
                (Future/.get running 5 TimeUnit/SECONDS)
                (Future/.get queued 5 TimeUnit/SECONDS)))
            (binding [persist/*max-concurrent-executions-per-org* 1]
              (is (= 200 (:status (serve))) "Rejected submission released its organization reservation"))
            (finally (vs/delete-branch! base (:id branch))))))
      (finally
        (deliver unblock true)
        (ExecutorService/.shutdownNow pool)
        (reset! host/adapter previous)))))
