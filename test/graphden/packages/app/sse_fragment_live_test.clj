(ns ^:integration graphden.packages.app.sse-fragment-live-test
  "The SSE fragment composition (`:sse-fragment-handler` →
   `:_sse-fragment-rendered` → `:sse-stream`) re-renders its fragment on
   EVERY tick. The impl-level tests (`packages.web.sse-stream-test`) hand
   the base-fn a raw Clojure render fn; this one drives the real GRAPH
   handler through a live httpkit server, because the 2026-09-02 freeze
   was in the composition, not the impl: `:fragment` was a captured
   hiccup VALUE, and captured args resolve once per wrap (once-thunks,
   ADR-thunk-once-and-cache-keys), so every tick rendered the first
   value, hashed equal, and pushed nothing — the Tests panel and the
   demo clock were dead after their first frame. `:fragment` is now a
   fn CALLED per tick; this pins it with the demo clock (5 s keepalive)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.wait :as wait]
    [org.httpkit.server :as hk])
  (:import
    (java.io
      BufferedReader
      InputStreamReader)
    (java.net
      HttpURLConnection
      URI)))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- read-stream!
  [port lines]
  (let [conn ^HttpURLConnection (java.net.URL/.openConnection
                                  (URI/.toURL (URI. (str "http://localhost:" port "/clock"))))]
    (HttpURLConnection/.setReadTimeout conn 20000)
    (let [t (Thread.
              (fn []
                (try
                  (with-open [r (BufferedReader.
                                  (InputStreamReader.
                                    (HttpURLConnection/.getInputStream conn) "UTF-8"))]
                    (loop []
                      (when-let [line (BufferedReader/.readLine r)]
                        (swap! lines conj line)
                        (recur))))
                  (catch Exception _ nil))))]
      (Thread/.setDaemon t true)
      (Thread/.start t))
    conn))


(deftest fragment-is-re-rendered-on-every-tick
  (let [{:keys [ctx]} ga/*bootstrap*
        handler-id (ga/fn-id :_contact-demo-clock-handler)
        stop (hk/run-server
               (fn [req] (exec/execute ctx handler-id {:request req}))
               {:port 0})
        port (:local-port (meta stop))
        lines (atom [])
        conn (read-stream! port lines)
        frames (fn []
                 (->> @lines
                      (filter #(str/starts-with? % "data:"))
                      (map #(re-find #"server time: \d+" %))
                      (remove nil?)
                      distinct
                      vec))]
    (try
      (testing "the first frame carries the clock"
        (is (wait/wait-for 8000 #(seq (frames)))
            (str "no clock frame within 8 s: " (pr-str @lines))))
      (testing "the 5 s keepalive tick renders a NEW time — the fragment fn ran again"
        (is (wait/wait-for 12000 #(>= (count (frames)) 2))
            (str "the stream never pushed a second, different clock frame: "
                 (pr-str (frames)))))
      (finally
        (HttpURLConnection/.disconnect conn)
        (stop)))))
