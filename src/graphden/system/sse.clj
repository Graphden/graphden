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

   Fan-out is per-org: each subscriber registers under its authenticated org,
   and an event tagged with the writing org (`:org-id`, added by
   `crud.entities/notify-after-write!`) goes only to that org's subscribers. A
   nil-org event (a public / platform / single-tenant write — shared rows that
   live in every bundle) goes to everyone. So a BYO executor is woken only by
   changes it actually holds."
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


(def ^:dynamic *send-override*
  "Parallel-test seam: when bound, `send!` calls this fn
   `(f ch frame close?)` instead of `org.httpkit.server/send!`. nil
   (production) = the real httpkit send. Tests `binding` this instead
   of `with-redefs`-ing the httpkit root Var — a root rebind is
   process-global and forced a `^:serial` pin on the sse suite
   (serial-reduction cluster A); a concurrent NS's real frame send
   landing in the window was silently swallowed by the stub. Mirrors
   `advisory-lock/*impl-override*`. Cost on the real path: one nil
   check per frame (itself a socket write)."
  nil)


(defn- send!
  "`org.httpkit.server/send!` behind the `*send-override*` seam."
  [ch frame close?]
  (if-let [f *send-override*]
    (f ch frame close?)
    (hk/send! ch frame close?)))


(defn- authenticate
  "Authenticate the request; return `{:ok? bool :org <string-or-nil>}`. The
   org (`(:org principal)`, read directly to avoid a tenancy dependency) keys
   which events this subscriber receives. nil provider ⇒ open, nil org (single-
   tenant / tests)."
  [auth-provider request]
  (if (nil? auth-provider)
    {:ok? true :org nil}
    (let [principal (auth/authenticate auth-provider request)]
      {:ok? (boolean (:authenticated? principal)) :org (:org principal)})))


(defn- deliver?
  "Does an event tagged for `event-org` go to a subscriber whose org is
   `sub-org`? A nil `event-org` (a public / platform / single-tenant write,
   which touches the shared rows in every bundle) goes to everyone; an
   org-tagged event goes only to that org's subscribers."
  [event-org sub-org]
  (or (nil? event-org) (= event-org sub-org)))


(defn open-subscriber!
  "Send the SSE response HEAD on `ch`, then register it in `subscribers`
   under `org`.

   ORDER IS LOAD-BEARING. Registering first opens a window in which a
   concurrent `broadcast!` finds the channel and writes a `data:` frame as
   its FIRST frame — httpkit sends that in place of the response head, and
   the client dies parsing body bytes as a status line (`Invalid status
   line: \"d\"`), drops the stream and reconnects. Sending the head first
   costs at most the events fired inside the same window, which the
   client's on-connect resync covers anyway (`storage.remote.sse`)."
  [subscribers ch org]
  (send! ch {:status 200
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache"}}
         false)
  (send! ch ": connected\n\n" false)
  (swap! subscribers assoc ch org))


(defn make-handler
  "Ring handler for `GET /events/stream`. Authenticates, opens an SSE channel,
   and registers it in `subscribers` (an atom of `{AsyncChannel → sub-org}`)
   keyed by the subscriber's authenticated org, so `broadcast!` can fan an
   event out only to the orgs it concerns. De-registers on close."
  [subscribers auth-provider]
  (fn [request]
    (let [{:keys [ok? org]} (authenticate auth-provider request)]
      (if-not ok?
        {:status 401
         :headers {"Content-Type" "application/json" "WWW-Authenticate" "Bearer"}
         :body "{\"ok\":false,\"error\":\"unauthorized\"}"}
        (hk/as-channel
          request
          {:on-open (fn [ch] (open-subscriber! subscribers ch org))
           :on-close (fn [ch _status] (swap! subscribers dissoc ch))})))))


(defn broadcast!
  "Send one parsed event to every subscriber the event concerns (`deliver?`);
   drop any that fail to accept (closed underneath us). Returns the number
   delivered."
  [subscribers event]
  (let [frame (sse-frame event)
        event-org (:org-id event)]
    (reduce-kv (fn [n ^AsyncChannel ch sub-org]
                 (cond
                   (not (deliver? event-org sub-org)) n
                   (try (send! ch frame false) (catch Exception _ false)) (inc n)
                   :else (do (swap! subscribers dissoc ch) n)))
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
  (let [subscribers (atom {})
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
