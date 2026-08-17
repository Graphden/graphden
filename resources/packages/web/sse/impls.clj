(ns graphden.packages.web.sse.impls
  "Implementation for the web/sse base function — the tenant-facing
   Server-Sent-Events stream primitive behind `:sse-fragment-route`."
  (:require
    [clojure.string :as str]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.server :as http-kit])
  (:import
    (java.util.concurrent
      Executors
      ScheduledExecutorService
      ScheduledFuture
      TimeUnit)))


;; One shared scheduler for every stream's ticks — a stream costs a
;; scheduled task, not a thread. Sized small on purpose: render ticks
;; are short graph executions; a slow render just delays its own
;; stream's next tick.
(defonce ^:private scheduler
  (delay (Executors/newScheduledThreadPool 2)))


;; Live-stream counter — the capacity gate. Env-tunable so an operator
;; can raise it for an SSE-heavy deployment.
(defonce ^:private live-streams (atom 0))


(defonce max-streams-test-cap
  ;; Test seam — an ATOM (not a dynamic var) because the cap is read
  ;; on the httpkit worker thread, where a test thread's binding
  ;; wouldn't be visible. nil in production.
  (atom nil))


(defn- max-streams
  []
  (or @max-streams-test-cap
      (some-> (System/getenv "GRAPHDEN_SSE_MAX_STREAMS") parse-long)
      200))


(defn- sse-frame
  "One `event: message` SSE frame. Multi-line HTML must be split into
   one `data:` line per source line (the SSE wire format reassembles
   them with \\n)."
  [html]
  (str "event: message\n"
       (str/join "\n" (map #(str "data: " %) (str/split-lines (str html))))
       "\n\n"))


(def ^:dynamic *send-override*
  "Parallel-test seam — mirror of `graphden.system.sse/*send-override*`:
   when bound, frame sends call `(f ch frame close?)` instead of the
   real httpkit send. Thread-local `binding`, never `with-redefs`."
  nil)


(defn- send!
  [ch frame close?]
  (if-let [f *send-override*]
    (f ch frame close?)
    (http-kit/send! ch frame close?)))


(defbase sse-stream
  "Open a Server-Sent-Events stream on this request and push the
   0-arg `render` callable's HTML whenever it CHANGES, re-executing it
   every `interval-ms` (clamped to >= 1000). The stream closes itself
   after `max-lifetime-ms` (clamped to <= 30 min) — the browser's
   EventSource auto-reconnects, so a long-lived page keeps updating
   through bounded stream generations. Returns 503 when the
   deployment-wide stream cap (GRAPHDEN_SSE_MAX_STREAMS, default 200)
   is reached.

   §3.3 atomic unit: the channel open, the tick schedule, the
   changed-only dedupe and the close/cancel lifecycle share state that
   cannot split across graph nodes without leak/race risk (a tick
   firing after close, a cancelled stream still counted against the
   cap). Each tick runs the render as a NEW logical execution under a
   fresh per-request call-cache; ticks are for READ-rendering — their
   effects are not re-gated per tick (the stream's own :network +
   :process cover the contract)."
  [request render interval-ms max-lifetime-ms]
  (cr/record-effect! :network)
  (cr/record-effect! :process)
  (let [interval (max 1000 (long interval-ms))
        lifetime (min (* 30 60 1000) (long max-lifetime-ms))
        deadline (+ (System/currentTimeMillis) lifetime)]
    (if (>= (long @live-streams) (long (max-streams)))
      {:status 503
       :headers {"Content-Type" "text/plain" "Retry-After" "10"}
       :body "SSE stream capacity reached - retry shortly"}
      (let [state (atom {:prev nil :task nil})
            tick! (fn [ch]
                    (try
                      (if (> (System/currentTimeMillis) deadline)
                        (send! ch "event: close\ndata: lifetime\n\n" true)
                        (let [html (str (cr/with-fresh-call-cache render))
                              h (hash html)]
                          (when (not= h (:prev @state))
                            (swap! state assoc :prev h)
                            (send! ch (sse-frame html) false))))
                      (catch Exception _
                        (try (send! ch "event: close\ndata: error\n\n" true)
                             (catch Exception _ nil)))))]
        (http-kit/as-channel
          request
          {:on-open (fn [ch]
                      (swap! live-streams inc)
                      (send! ch {:status 200
                                 :headers {"Content-Type" "text/event-stream"
                                           "Cache-Control" "no-cache"
                                           "X-Accel-Buffering" "no"}}
                             false)
                      (tick! ch)
                      (swap! state assoc :task
                             (ScheduledExecutorService/.scheduleAtFixedRate
                               @scheduler
                               (fn [] (tick! ch))
                               interval interval TimeUnit/MILLISECONDS)))
           :on-close (fn [_ch _status]
                       (swap! live-streams dec)
                       (when-let [t (:task @state)]
                         (ScheduledFuture/.cancel t false)))})))))


;; The package loader pairs each base-fn declared in `fns.edn` with its
;; impl by looking up this `impls` map (keyword name -> impl fn).
(def impls
  {:sse-stream sse-stream})
