(ns graphden.storage.remote.sse
  "Remote SSE invalidation SOURCE — the BYO-executor side of the relay in
   `system.sse` (docs/SCALING.md § SSE invalidation).

   A remote / BYO executor can't `LISTEN` on Postgres, so it holds an SSE
   connection to the hub's `/events/stream` and turns each frame back into the
   same parsed event a local pod gets from `graphden_events`. It then hands
   that event to `on-event` — the caller wires that to `RemoteStorage/refresh!`
   + a compiled-registry rebuild, so the remote executor stays fresh on graph
   changes with the SAME push model as pods on Postgres.

   Reconnects with exponential backoff on a dropped connection, mirroring the
   PG listen-loop, so a network blip doesn't leave the executor deaf."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.notify :as pg-notify])
  (:import
    (java.io
      BufferedReader
      InputStream
      InputStreamReader)
    (java.net
      URI)
    (java.net.http
      HttpClient
      HttpRequest
      HttpRequest$Builder
      HttpResponse
      HttpResponse$BodyHandlers)))


(defn- handle-line
  "One SSE line → dispatch. `data: <payload>` lines are parsed with the shared
   `notify/parse-payload` and passed to `on-event`; `:`-comment lines
   (keepalive) and blanks are ignored."
  [on-event ^String line]
  (when (str/starts-with? line "data:")
    (let [payload (str/trim (subs line 5))]
      (when-let [event (pg-notify/parse-payload payload)]
        (try (on-event event)
             (catch Exception e
               (log/warn e "SSE on-event threw" {:event event})))))))


(defn- stream-once!
  "Open ONE SSE connection and pump lines to `on-event` until it closes or
   `running?` flips false. Returns normally on clean close; throws on a
   connection error so the caller reconnects.

   Uses `java.net.http.HttpClient` (not the httpkit client): it resolves on
   the response HEAD and hands back a streaming InputStream, which is what SSE
   needs. httpkit's client buffers the whole response and would never yield
   the stream."
  [hub-url token on-event running?]
  (let [url (str hub-url "/events/stream")
        client (HttpClient/newHttpClient)
        req (-> (HttpRequest/newBuilder (URI/create url))
                (HttpRequest$Builder/.header "Authorization" (str "Bearer " token))
                (HttpRequest$Builder/.GET)
                (HttpRequest$Builder/.build))
        resp (HttpClient/.send client req (HttpResponse$BodyHandlers/ofInputStream))
        status (HttpResponse/.statusCode resp)]
    (when (not= 200 status)
      (throw (ex-info (str "SSE connect returned " status) {:url url :status status})))
    (with-open [rdr (BufferedReader. (InputStreamReader. ^InputStream (HttpResponse/.body resp) "UTF-8"))]
      (loop []
        (when @running?
          (let [line (BufferedReader/.readLine rdr)]
            (when (some? line)                       ; nil = stream closed
              (handle-line on-event line)
              (recur))))))))


(defn start-source!
  "Spawn a daemon thread holding an SSE connection to `hub-url`, parsing each
   event and calling `on-event`. Reconnects with backoff (1s → 30s) while
   running. Returns a handle for `stop-source!`.

   `on-event` receives the parsed `{:kind :op :id :branch-id}` map — the same
   shape `graphden_events` delivers locally."
  [{:keys [hub-url token on-event]}]
  (let [running? (atom true)
        thread (Thread.
                 (fn []
                   (loop [backoff-ms 1000]
                     (when @running?
                       (let [ok? (try (stream-once! hub-url token on-event running?)
                                      true
                                      (catch Exception e
                                        (when @running?
                                          (log/warn e "SSE stream dropped — reconnecting"
                                                    {:hub hub-url}))
                                        false))]
                         (when (and @running? (not ok?))
                           (Thread/sleep (long backoff-ms)))
                         (recur (if ok? 1000 (min 30000 (* 2 backoff-ms))))))))
                 "remote-sse-source")]
    (Thread/.setDaemon thread true)
    (Thread/.start thread)
    (log/info "Remote SSE source started" {:hub hub-url})
    {:running? running? :thread thread}))


(defn stop-source!
  "Stop the SSE source thread."
  [{:keys [running? ^Thread thread]}]
  (when running? (reset! running? false))
  (when thread (Thread/.interrupt thread) (Thread/.join thread 2000))
  (log/info "Remote SSE source stopped"))
