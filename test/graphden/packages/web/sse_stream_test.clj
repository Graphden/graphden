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
   `:sse-stream` with the given args (`:ctx` carries e.g. the fake
   `:notify-listener` for the wake tests). Returns {:port :stop}."
  [{:keys [render interval-ms max-lifetime-ms wake-on-writes ctx]}]
  (let [f (impls/impl-of :sse-stream)
        stop (hk/run-server
               (fn [req]
                 (f {:request req
                     :render render
                     :interval-ms (or interval-ms 1000)
                     :max-lifetime-ms (or max-lifetime-ms 60000)
                     :wake-on-writes (boolean wake-on-writes)}
                    ctx))
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


;; =============================================================================
;; wake-on-writes — event-driven ticks off the NOTIFY bus
;; =============================================================================

(deftest wake-on-writes-pushes-on-event-not-interval-test
  ;; interval = 60 s (keepalive only), so any frame after the first
  ;; PROVES the event path: fire a fake graphden_events callback and
  ;; the changed fragment must arrive within the ~200 ms debounce, not
  ;; a minute later.
  (let [listener {:callbacks (atom #{})}
        content (atom "<p>A</p>")
        renders (atom 0)
        {:keys [port stop]} (start-stream-server!
                              {:render (fn [] (swap! renders inc) @content)
                               :interval-ms 60000
                               :wake-on-writes true
                               :ctx {:notify-listener listener}})
        lines (atom [])
        conn (read-stream! port lines)
        fire! (fn []
                (doseq [cb @(:callbacks listener)]
                  (cb {:kind :fn :op :invalidate :id "x"})))]
    (try
      (testing "the stream registered its wake callback"
        (is (wait/wait-for 5000 #(seq @(:callbacks listener)))))
      (testing "initial frame arrives"
        (is (wait/wait-for 5000 #(some #{"<p>A</p>"} (data-frames @lines)))))
      (testing "a write event pushes the changed fragment within ~1 s"
        (reset! content "<p>B</p>")
        (fire!)
        (is (wait/wait-for 2000 #(some #{"<p>B</p>"} (data-frames @lines)))
            "event-driven push beat the 60 s keepalive"))
      (testing "an event BURST coalesces into one debounced render"
        (let [before @renders]
          (reset! content "<p>C</p>")
          (dotimes [_ 10] (fire!))
          (is (wait/wait-for 2000 #(some #{"<p>C</p>"} (data-frames @lines))))
          (is (<= (- @renders before) 2)
              "ten events cost at most two renders, not ten")))
      (finally
        (HttpURLConnection/.disconnect conn)
        (stop)))))


(deftest wake-callback-unregisters-on-stream-close-test
  ;; Deterministic teardown proof via the lifetime path (the same
  ;; `cleanup!` the on-close and send-failure paths run — a client
  ;; disconnect is only NOTICED on a later write, which makes it an
  ;; unreliable thing to assert on directly).
  (let [listener {:callbacks (atom #{})}
        ;; interval 1000 (not 60000): with a 1 ms lifetime the FIRST
        ;; tick can land in the same millisecond as the deadline and
        ;; push instead of closing — the close then rides the next
        ;; keepalive tick, which must come promptly.
        {:keys [port stop]} (start-stream-server!
                              {:render (fn [] "<p>x</p>")
                               :interval-ms 1000
                               :max-lifetime-ms 1
                               :wake-on-writes true
                               :ctx {:notify-listener listener}})
        lines (atom [])
        conn (read-stream! port lines)]
    (try
      (testing "the expired stream unregistered its wake callback"
        (is (wait/wait-for 5000 #(some #{"event: close"} @lines))
            "lifetime close arrived")
        (is (wait/wait-for 5000 #(empty? @(:callbacks listener)))
            "no dead callback lingers on the bus"))
      (finally
        (HttpURLConnection/.disconnect conn)
        (stop)))))


;; =============================================================================
;; Tenancy correctness — binding capture + per-tick time-bound
;; =============================================================================

(def ^:dynamic *probe-org*
  "Stands in for the request-scoped dynamic confinement vars
   (*current-org* / *allowed-effects*) the stream must capture."
  nil)


(deftest ticks-run-under-the-opening-requests-bindings-test
  ;; The render closure reads a dynamic var bound ONLY around the
  ;; opening request's handler call. Scheduler/pool ticks see the
  ;; captured value — without bound-fn* they'd see the root nil and a
  ;; tenant fragment would render org-unscoped.
  (let [listener {:callbacks (atom #{})}
        f (impls/impl-of :sse-stream)
        stop (hk/run-server
               (fn [req]
                 (binding [*probe-org* "tenant-a"]
                   (f {:request req
                       :render (fn [] (str "<p>" (or *probe-org* "UNSCOPED") "</p>"))
                       :interval-ms 60000
                       :max-lifetime-ms 60000
                       :wake-on-writes true}
                      {:notify-listener listener})))
               {:port 0})
        port (:local-port (meta stop))
        lines (atom [])
        conn (read-stream! port lines)]
    (try
      (testing "the initial frame renders under the request's binding"
        (is (wait/wait-for 5000 #(some #{"<p>tenant-a</p>"} (data-frames @lines)))))
      (testing "an event-driven tick (scheduler thread) still sees the
                captured binding — never the root value"
        (is (wait/wait-for 5000 #(seq @(:callbacks listener))))
        ;; Force a re-render by... the content is constant, so drive a
        ;; second frame via a probe-visible change: rebind is impossible
        ;; post-capture BY DESIGN — assert no UNSCOPED frame ever shows.
        (doseq [cb @(:callbacks listener)] (cb {:kind :fn}))
        (Thread/sleep 600)
        (is (not-any? #(str/includes? % "UNSCOPED") @lines)
            "no tick rendered outside the captured sandbox"))
      (finally
        (HttpURLConnection/.disconnect conn)
        (stop)))))


(deftest slow-render-skips-tick-but-stream-survives-test
  ;; A fragment that overruns the tick budget (min(interval, 10 s))
  ;; costs its own tick — the stream stays open and recovers once the
  ;; render behaves again.
  (let [listener {:callbacks (atom #{})}
        slow? (atom true)
        {:keys [port stop]} (start-stream-server!
                              {:render (fn []
                                         (when @slow? (Thread/sleep 3000))
                                         "<p>ok</p>")
                               :interval-ms 1000
                               :wake-on-writes true
                               :ctx {:notify-listener listener}})
        lines (atom [])
        conn (read-stream! port lines)]
    (try
      (testing "while slow, ticks are skipped — no frames, no crash"
        (Thread/sleep 2500)
        (is (empty? (data-frames @lines)) "overrunning renders pushed nothing"))
      (testing "once fast again, the next tick pushes"
        (reset! slow? false)
        (is (wait/wait-for 5000 #(some #{"<p>ok</p>"} (data-frames @lines)))
            "the stream survived the timeouts and recovered"))
      (finally
        (HttpURLConnection/.disconnect conn)
        (stop)))))
