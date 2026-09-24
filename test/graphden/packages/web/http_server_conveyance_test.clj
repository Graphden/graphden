(ns ^:serial graphden.packages.web.http-server-conveyance-test
  "`:http-server` serves every request on an http-kit WORKER thread, which
   inherits none of the starting execution's dynamic bindings. A tenant
   service's server is started inside the tenant's restricted execution
   (its org + its plan's effect gate); its requests must run under the
   same, or a request handler reads the vault with the platform's
   unconfined scope. `^:serial`: registers `tctx/*current-org*` as a
   conveyed var — the process-global seam the tenancy addon sets at load."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.tenancy.context :as tctx]
    [graphden.test-infra.impls :as impls]
    [org.httpkit.client :as http]))


(use-fixtures :once (impls/impls-fixture "web" "http"))


(def ^:private tenant-effects
  #{:network :process :time :db})


(defn- vault-get-status
  "What a `:vault-get` of `path` answers on the CURRENT thread, as an HTTP
   status: 403 for the client's per-org refusal, 200 for a read, 500 for
   anything else (the fake vault address refuses connections)."
  [path]
  (let [vault-get (:vault-get ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                               "web" "vault"))]
    (try (vault-get {:path path} {:vault {:address "http://127.0.0.1:1" :token "t"}})
         200
         (catch clojure.lang.ExceptionInfo e
           (if (= :vault/path-forbidden (:type (ex-data e))) 403 500)))))


(deftest a-tenant-servers-requests-run-under-the-starting-binding
  (cr/register-conveyed-var! #'tctx/*current-org*)
  (let [seen (atom nil)
        start (impls/impl-of :http-server)
        handler (fn [_req]
                  (reset! seen {:org (tctx/current-org) :effects cr/*allowed-effects*})
                  {:status (vault-get-status "org/other/db/password") :body "x"})
        stop (binding [cr/*allowed-effects* tenant-effects]
               (tctx/with-org "acme"
                              (start {:handler handler :port 0} nil)))]
    (try
      (let [{:keys [port]} (:endpoint (meta stop))
            {:keys [status]} @(http/get (str "http://127.0.0.1:" port "/"))]
        (testing "the worker thread sees the tenant's org and effect gate"
          (is (= {:org "acme" :effects tenant-effects} @seen)))
        (testing "a handler reading another org's secret is refused (403)"
          (is (= 403 status))))
      (finally (stop)))))


(deftest a-platform-servers-requests-stay-unrestricted
  (let [seen (atom nil)
        start (impls/impl-of :http-server)
        stop (start {:handler (fn [_req]
                                (reset! seen {:org (tctx/current-org) :effects cr/*allowed-effects*})
                                {:status 200 :body "ok"})
                     :port 0}
                    nil)]
    (try
      (let [{:keys [port]} (:endpoint (meta stop))]
        (is (= 200 (:status @(http/get (str "http://127.0.0.1:" port "/")))))
        (is (= {:org tctx/public-org :effects nil} @seen)))
      (finally (stop)))))
