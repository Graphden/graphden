(ns ^:integration graphden.fleet.router-test
  "Internal forward-hop (`graphden.fleet.router`, docs/FLEET_RFC.md §6.1): a pod
   that doesn't hold a cell forwards the request to its placement holder instead
   of 421'ing. A stub 'executor' HTTP server stands in for the holder."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.fleet.placement :as placement]
    [graphden.fleet.router :as router]
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.placement.schema :as placement-schema]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [org.httpkit.server :as hk]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- storage-with-placement!
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (-> (mds/create-builder) (gds/extend-builder)
                           (vts/extend-builder) (vds/extend-builder)
                           (placement-schema/extend-builder) (ds/build)))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    raw))


(defn- stub-executor
  "An 'executor' that echoes the method + path it received, so the test can
   confirm the forward actually reached it."
  []
  (hk/run-server
    (fn [req]
      {:status 200 :headers {"Content-Type" "text/plain" "X-Served-By" "holder"}
       :body (str (name (:request-method req)) " " (:uri req))})
    {:port 0}))


(deftest forwards-to-the-holder-when-not-self
  (let [storage (storage-with-placement!)
        entry (setup/create-base-fn! storage "cell-root" :any)
        eid (:id entry)
        holder (stub-executor)
        port (:local-port (meta holder))]
    (try
      (testing "no placement → nil (caller 421s)"
        (is (nil? (router/forward-or-nil storage "pod-1" port "acme" eid
                                         {:request-method :get :uri "/"}))))

      ;; Place the cell on 'localhost' (a resolvable DNS name in the test) at
      ;; the stub's port — self is a different pod.
      (placement/assign! storage {:org "acme" :entry-fn-id eid
                                  :executor-id "localhost" :epoch 1})

      (testing "placed on another executor → forwards, returns its response"
        (let [resp (router/forward-or-nil storage "pod-1" port "acme" eid
                                          {:request-method :get :uri "/checkout"})]
          (is (= 200 (:status resp)))
          (is (= "holder" (get-in resp [:headers "x-served-by"])) "response came from the holder")
          (is (= "get /checkout" (:body resp)) "method + path reached the holder intact")))

      (testing "placed HERE (self) → nil (serve locally, don't forward to self)"
        (is (nil? (router/forward-or-nil storage "localhost" port "acme" eid
                                         {:request-method :get :uri "/"}))))

      (testing "nil self-id (fleet identity unset) → never forward"
        (is (nil? (router/forward-or-nil storage nil port "acme" eid
                                         {:request-method :get :uri "/"}))))

      (finally
        (holder)
        (sp/close storage)))))


(deftest forward-preserves-the-tenant-host
  ;; Regression: the forward-hop MUST keep the request's Host — for a tenant app
  ;; it is `<org>.<base-domain>`, the routing key the holder's app-router
  ;; resolves the org from. Dropping it (plain reverse-proxy convention) made the
  ;; holder see its own FQDN, fail to resolve the org, and serve the apex editor
  ;; instead of the tenant's app (found on a kind cluster, 2026-07-12).
  (let [storage (storage-with-placement!)
        entry (setup/create-base-fn! storage "cell-root-host" :any)
        eid (:id entry)
        seen-host (atom :unset)
        holder (hk/run-server
                 (fn [req]
                   (reset! seen-host (get-in req [:headers "host"]))
                   {:status 200 :body "ok"})
                 {:port 0})
        port (:local-port (meta holder))]
    (try
      (placement/assign! storage {:org "acme" :entry-fn-id eid
                                  :executor-id "localhost" :epoch 1})
      (router/forward-or-nil storage "pod-1" port "acme" eid
                             {:request-method :get :uri "/"
                              :headers {"host" "acme.graphden.app"}})
      (is (= "acme.graphden.app" @seen-host)
          "the holder receives the original tenant-subdomain Host, not the target FQDN")
      (finally
        (holder)
        (sp/close storage)))))
