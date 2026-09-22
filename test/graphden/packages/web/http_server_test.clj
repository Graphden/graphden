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


(deftest http-server-serves-keyword-keyed-headers-test
  ;; A response fn-def's `:headers` literal keywordizes on the JSONB
  ;; round-trip; http-kit's writer casts every key to String and threw
  ;; a ClassCastException (500) for a bare `text-ok-response` child bound
  ;; as the handler — a wrap was silently mandatory. The adapter now
  ;; stringifies once on the way out.
  (let [start (impls/impl-of :http-server)
        stop (start {:handler (fn [_req]
                                {:status 200
                                 :headers {:Content-Type "text/plain; charset=utf-8"}
                                 :body "hello"})
                     :port 0}
                    nil)]
    (try
      (let [{:keys [port]} (:endpoint (meta stop))
            {:keys [status headers body]} @(http/get (str "http://127.0.0.1:" port "/hello"))]
        (is (= 200 status))
        (is (= "hello" body))
        (is (= "text/plain; charset=utf-8" (:content-type headers))))
      (finally (stop)))))
