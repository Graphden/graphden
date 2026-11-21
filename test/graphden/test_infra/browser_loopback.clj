(ns graphden.test-infra.browser-loopback
  "Serve an isolated Ring fixture to its Playwright browser process."
  (:require
    [clojure.test :refer [is]]
    [org.httpkit.server :as http])
  (:import
    (java.util
      Map)
    (java.util.concurrent
      TimeUnit)))


(defn exercise!
  [handler token script & args]
  (let [http-handler (bound-fn [req]
                       ;; Match Graphden's HTTP response boundary.
                       (update (handler req) :headers update-keys name))
        stop (http/run-server http-handler {:ip "127.0.0.1" :port 0})]
    (try
      (let [origin (str "http://127.0.0.1:" (:local-port (meta stop)))
            builder (ProcessBuilder. (into ["node" script origin] (map str) args))
            _ (Map/.put (ProcessBuilder/.environment builder) "GD_BROWSER_FIXTURE_TOKEN" token)
            process (ProcessBuilder/.start (ProcessBuilder/.inheritIO builder))]
        (try
          (is (Process/.waitFor process 60 TimeUnit/SECONDS) "Playwright completed the live fixture")
          (when-not (Process/.isAlive process)
            (is (zero? (Process/.exitValue process))))
          (finally
            (when (Process/.isAlive process) (Process/.destroyForcibly process)))))
      (finally (stop)))))
