(ns ^:integration graphden.packages.web.http-server-test
  "The `:http-server` handle contract: a 0-arg stopper whose metadata
   carries the listener's `:endpoint` — the port ACTUALLY bound, so a
   `:port 0` service reports the OS-picked one. The reconciler reads it
   to record where the service answers."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]
    [org.httpkit.client :as http]))


(use-fixtures :once (impls/impls-fixture "web" "http"))


(deftest http-server-handle-carries-its-endpoint-test
  (let [start (impls/impl-of :http-server)
        stop (start {:handler (fn [_req] {:status 200 :body "ok"}) :port 0} nil)]
    (try
      (testing "the handle is still a plain stopper"
        (is (fn? stop)))
      (testing "the endpoint names the bound port, not the requested 0"
        (let [{:keys [port]} (:endpoint (meta stop))]
          (is (integer? port))
          (is (pos? port))
          (is (= 200 (:status @(http/get (str "http://127.0.0.1:" port "/")))))))
      (finally (stop)))))
