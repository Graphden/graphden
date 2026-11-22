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


(defn exercise-with-timeout!
  "Bound the entire browser process independently of its individual waits."
  [timeout-seconds handler token script & args]
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
          (let [finished? (Process/.waitFor process timeout-seconds TimeUnit/SECONDS)]
            (is finished? "Playwright completed the live fixture")
            (when finished?
              (is (zero? (Process/.exitValue process)))))
          (finally
            (when (Process/.isAlive process) (Process/.destroyForcibly process)))))
      (finally (stop)))))


(defn exercise!
  [handler token script & args]
  (apply exercise-with-timeout! 60 handler token script args))
