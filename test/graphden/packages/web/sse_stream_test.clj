(ns ^:integration graphden.packages.web.sse-stream-test
  "Real-socket tests for the `:sse-stream` base-fn impl — a live
   httpkit server, a raw EventSource-style reader, and the stream's
   change-dedupe / lifetime-close / capacity-cap contracts. Mirrors
   `graphden.system.sse-test`'s socket approach; the impl itself is
   loaded through the package loader (no full bootstrap)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.wait :as wait]
    [org.httpkit.server :as hk])
  (:import
    (java.io
      BufferedReader
      InputStreamReader)
    (java.net
      HttpURLConnection
      URI)))


(use-fixtures :once (impls/impls-fixture "web" "sse"))


(defn- start-stream-server!
  "httpkit server on an ephemeral port whose every request opens an
   `:sse-stream` with the given args. Returns {:port :stop}."
  [{:keys [render interval-ms max-lifetime-ms]}]
  (let [f (impls/impl-of :sse-stream)
        stop (hk/run-server
               (fn [req]
                 (f {:request req
                     :render render
                     :interval-ms (or interval-ms 1000)
                     :max-lifetime-ms (or max-lifetime-ms 60000)}
                    nil))
               {:port 0})]
    {:port (:local-port (meta stop))
     :stop stop}))


(defn- read-stream!
  "Open the stream and collect raw lines into `lines` (an atom) on a
   daemon thread until the server closes it. Returns the connection."
  [port lines]
  (let [conn ^HttpURLConnection (java.net.URL/.openConnection
                                  (URI/.toURL (URI. (str "http://localhost:" port "/s"))))]
    (HttpURLConnection/.setReadTimeout conn 15000)
    (doto (Thread.
            (fn []
              (try
                (with-open [r (BufferedReader.
                                (InputStreamReader.
                                  (HttpURLConnection/.getInputStream conn) "UTF-8"))]
                  (loop []
                    (when-let [line (BufferedReader/.readLine r)]
                      (swap! lines conj line)
                      (recur))))
                (catch Exception _ nil))))
      (Thread/.setDaemon true)
      (Thread/.start))
    conn))


(defn- data-frames
  [lines]
  (into [] (comp (filter #(str/starts-with? % "data: "))
                 (map #(subs % 6)))
        lines))


(deftest stream-pushes-initial-and-changed-content-only-test
  (let [content (atom "<p>A</p>")
        {:keys [port stop]} (start-stream-server!
                              {:render (fn [] @content)
                               :interval-ms 1000})
        lines (atom [])
        conn (read-stream! port lines)]
    (try
      (testing "the initial render is pushed immediately"
        (is (wait/wait-for 5000 #(some #{"<p>A</p>"} (data-frames @lines)))
            "first frame arrived"))
      (testing "unchanged ticks push nothing; a change pushes once"
        ;; Let at least two unchanged ticks pass, then change.
        (Thread/sleep 2500)
        (reset! content "<p>B</p>")
        (is (wait/wait-for 5000 #(some #{"<p>B</p>"} (data-frames @lines)))
            "changed frame arrived")
        (let [frames (data-frames @lines)]
          (is (= 1 (count (filter #{"<p>A</p>"} frames)))
              "the unchanged content was pushed exactly once — dedupe held")))
      (finally
        (HttpURLConnection/.disconnect conn)
        (stop)))))


(deftest stream-lifetime-close-test
  (let [{:keys [port stop]} (start-stream-server!
                              {:render (fn [] "<p>x</p>")
                               :max-lifetime-ms 1})
        lines (atom [])
        conn (read-stream! port lines)]
    (try
      (testing "an expired stream closes with the close event"
        (is (wait/wait-for 5000 #(some #{"event: close"} @lines))
            "close event arrived (client-side EventSource would reconnect)"))
      (finally
        (HttpURLConnection/.disconnect conn)
        (stop)))))


(deftest stream-capacity-cap-test
  ;; The cap seam is an ATOM inside the loaded impls module (a dynamic
  ;; var would be invisible on the httpkit worker thread). Only this
  ;; NS touches it, and kaocha runs one NS's tests sequentially, so
  ;; the set-then-restore can't race the socket tests above.
  (let [cap (var-get (ns-resolve (the-ns 'graphden.packages.web.sse.impls)
                                 'max-streams-test-cap))]
    (reset! cap 0)
    (try
      (let [{:keys [port stop]} (start-stream-server!
                                  {:render (fn [] "<p>x</p>")})]
        (try
          (let [conn ^HttpURLConnection (java.net.URL/.openConnection
                                          (URI/.toURL (URI. (str "http://localhost:" port "/s"))))]
            (HttpURLConnection/.setReadTimeout conn 5000)
            (testing "over-cap requests get a plain 503, not a stream"
              (is (= 503 (HttpURLConnection/.getResponseCode conn)))))
          (finally (stop))))
      (finally (reset! cap nil)))))
