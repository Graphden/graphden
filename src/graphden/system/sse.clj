(ns graphden.system.sse
  "Server-Sent-Events relay for the `graphden_events` invalidation stream
   (docs/SCALING.md § SSE invalidation).

   A remote / BYO executor has no Postgres connection, so it can't `LISTEN` on
   `graphden_events`. This relay is a SECOND consumer of that same stream — it
   registers a callback on the `:db/notify-listener` (exactly like the
   in-process reconciler / cache-invalidator do) and forwards each event over
   SSE to subscribed remote executors, which feed it into the same
   transport-agnostic `on-notify` on their end.

   It runs on its OWN httpkit server / port, PARALLEL to the app HTTP server
   and to the dedicated LISTEN connection — invalidation is infrastructure
   that sits below the graph-composed application router, not an app route.
   That keeps the async SSE channel out of the graph dispatch + tenancy
   request-scope, which expect ordinary response maps.

   v1 relays EVERY fn-invalidate event to EVERY subscriber (the wire payload
   carries no org-id, so the relay can't org-filter). A remote executor's
   RemoteStorage refetches its OWN org-scoped bundle on any event — an over-
   refetch, but BYO orgs have small graphs and writes are infrequent.
   Org-tagging the payload for precise fan-out is a future optimization."
  (:require
    [clojure.tools.logging :as log]
    [graphden.auth.provider :as auth]
    [graphden.storage.postgres.notify :as pg-notify]
    [org.httpkit.server :as hk])
  (:import
    (org.httpkit.server
      AsyncChannel)))


(defn- sse-frame
  "Format one parsed event as an SSE `data:` frame (the same wire string the
   PG channel carries, so the remote side reuses `notify/parse-payload`)."
  [event]
  (str "data: " (pg-notify/format-payload event) "\n\n"))


(defn- authorized?
  "Bearer-token check against `auth-provider`. The relay carries only
   invalidation signals (fn-ids, no graph content), but it's still gated so a
   stranger can't hold open connections. nil provider ⇒ open (single-tenant /
   tests)."
  [auth-provider request]
  (or (nil? auth-provider)
      (boolean (:authenticated? (auth/authenticate auth-provider request)))))


(defn make-handler
  "Ring handler for `GET /events/stream`. Authenticates, opens an SSE channel,
   registers it in `subscribers`, and de-registers on close. `subscribers` is
   an atom of a set of `AsyncChannel`s the relay callback sends to."
  [subscribers auth-provider]
  (fn [request]
    (if-not (authorized? auth-provider request)
      {:status 401
       :headers {"Content-Type" "application/json" "WWW-Authenticate" "Bearer"}
       :body "{\"ok\":false,\"error\":\"unauthorized\"}"}
      (hk/as-channel
        request
        {:on-open (fn [ch]
                    ;; Register first, then send the SSE response HEAD (status +
                    ;; headers, no body — httpkit makes it a chunked stream) so
                    ;; the client's `@(http/get)` resolves and subsequent
                    ;; `data:` frames flow.
                    (swap! subscribers conj ch)
                    (hk/send! ch {:status 200
                                  :headers {"Content-Type" "text/event-stream"
                                            "Cache-Control" "no-cache"}}
                              false)
                    (hk/send! ch ": connected\n\n" false))
         :on-close (fn [ch _status] (swap! subscribers disj ch))}))))


(defn broadcast!
  "Send one parsed event to every open subscriber; drop any that fail to
   accept (closed underneath us). Returns the number delivered."
  [subscribers event]
  (let [frame (sse-frame event)]
    (reduce (fn [n ^AsyncChannel ch]
              (if (try (hk/send! ch frame false) (catch Exception _ false))
                (inc n)
                (do (swap! subscribers disj ch) n)))
            0
            @subscribers)))


(defn start-relay!
  "Start the SSE relay: an httpkit server on `port` serving `/events/stream`,
   plus a callback on `notify-listener` that broadcasts each `graphden_events`
   event to the connected subscribers. Returns a handle for `stop-relay!`.

   Only fn-invalidate + execution events are worth relaying to a remote
   executor; `:service` events are pod-local reconcile signals. The remote
   side ignores what it doesn't handle, so we forward everything and let
   `on-notify` filter — keeping the relay dumb."
  [{:keys [port notify-listener auth-provider]}]
  (let [subscribers (atom #{})
        handler (fn [request]
                  (if (= "/events/stream" (:uri request))
                    ((make-handler subscribers auth-provider) request)
                    {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))
        server (hk/run-server handler {:port port})
        callback (when notify-listener
                   (pg-notify/register! notify-listener
                                        (fn [event] (broadcast! subscribers event))))]
    (log/info "SSE invalidation relay started" {:port port})
    {:server server :subscribers subscribers
     :notify-listener notify-listener :callback callback}))


(defn stop-relay!
  "Unregister the callback + stop the httpkit server."
  [{:keys [server notify-listener callback]}]
  (when (and notify-listener callback)
    (pg-notify/unregister! notify-listener callback))
  (when server (server))
  (log/info "SSE invalidation relay stopped"))
