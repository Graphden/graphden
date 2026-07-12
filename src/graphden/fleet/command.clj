(ns graphden.fleet.command
  "Directed cross-pod cell commands (docs/FLEET_RFC.md §6.3) — the transport
   that fills the move-controller's `load-on` / `evict-on` seams in a real
   fleet.

   A move MUST load-before-flip, so the load half is a SYNCHRONOUS, ACKed
   request (HTTP), NOT a broadcast NOTIFY: the controller has to know the target
   actually compiled the cell before it flips the routing map. NOTIFY is used
   for eventually-consistent invalidation; a move needs an ack, so it can't be.

   Addressing reuses the forward-hop convention (option 1, T2.6): an executor-id
   IS a DNS name, so a pod's URL is `http://<id>:<port>`.

   AUTH is platform CONTROL-PLANE, not tenant: a move relocates ANY org's cell,
   so it is gated on a dedicated shared secret (`GRAPHDEN_INTERNAL_TOKEN`),
   constant-time compared — deliberately NOT the tenant auth-provider (which
   maps a token to ONE org; a cell command has cross-org platform authority).
   Every fleet pod shares the one internal token (a k8s secret). Unset ⇒ the
   endpoint fail-closes (denies all) and moves are disabled.

   Server: `make-command-handler` → a `branch-router/dispatch` seam matching
   `POST /internal/fleet/cell/{load|evict}/{root-fn-id}`, running
   `load-cell!` / `evict-cell!` on THIS pod's registry.
   Client: `directed-load` / `directed-evict` build the seams the controller
   calls; `execute-move!` assembles them from a live ctx + runs `move-cell!` —
   the Phase-1 'on command' entry (ops / REPL; Phase 2's controller calls
   `move-cell!` directly under a leader lock)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.fleet.controller :as controller]
    [org.httpkit.client :as http])
  (:import
    (java.util
      UUID)))


(def path-prefix
  "URL prefix for the internal cell-command endpoint. Under `/internal/` so it
   reads as infra, never a tenant path."
  "/internal/fleet/cell/")


(defn parse-command-uri
  "`/internal/fleet/cell/{load|evict}/{uuid}` → `{:op :load|:evict :root-fn-id
   <UUID>}`, else nil (a non-command path — the seam returns nil so
   `dispatch` falls through). A malformed uuid or unknown op is nil too."
  [uri]
  (when (and (string? uri) (str/starts-with? uri path-prefix))
    (let [[op-str id-str] (str/split (subs uri (count path-prefix)) #"/" 2)]
      (when (and (#{"load" "evict"} op-str) (not (str/blank? id-str)))
        (try
          {:op (keyword op-str) :root-fn-id (UUID/fromString id-str)}
          (catch IllegalArgumentException _ nil))))))


;; =============================================================================
;; Server — the dispatch seam
;; =============================================================================

(defn- json-resp
  [status m]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string m)})


(defn- authorized?
  "Constant-time bearer check against the shared internal token. A blank/unset
   token never validates (fail-closed)."
  [internal-token request]
  (and (not (str/blank? internal-token))
       (auth/constant-time-equal? (auth/extract-bearer request) internal-token)))


(defn handle-command
  "Run a parsed cell command on `ctx`'s registry.

   `:load` compiles the cell and returns its fn-count; an EMPTY load (the cell
   isn't in this pod's shard, so it can't serve it) is a 409 so the controller
   ABORTS the move rather than flipping to a pod that can't hold the cell.
   `:evict` drops the cell and returns the evicted count — idempotent (200 even
   when nothing was held)."
  [ctx {:keys [op root-fn-id]}]
  (case op
    :load (let [loaded (cr/load-cell! ctx root-fn-id)]
            (if (seq loaded)
              (json-resp 200 {:ok true :loaded (count loaded)})
              (json-resp 409 {:ok false :error "cell not in this executor's shard"})))
    :evict (json-resp 200 {:ok true :evicted (count (cr/evict-cell! ctx root-fn-id))})))


(defn make-command-handler
  "Build the `:fleet-command` dispatch seam — `(fn [ctx request]
   response-or-nil)`. nil for any request that isn't a POST to the internal
   cell-command path (→ `dispatch` falls through to the app/editor flow).
   `internal-token` gates it (control-plane authority)."
  [internal-token]
  (fn [ctx request]
    (when (= :post (:request-method request))
      (when-let [cmd (parse-command-uri (:uri request))]
        (if (authorized? internal-token request)
          (try
            (handle-command ctx cmd)
            (catch Exception e
              (log/error e "fleet cell command failed" {:cmd cmd})
              (json-resp 500 {:ok false :error "command failed"})))
          (json-resp 401 {:ok false :error "unauthorized"}))))))


;; =============================================================================
;; Client — the seams the controller calls
;; =============================================================================

(defn- command-url
  [executor-id port op root-fn-id]
  (str "http://" executor-id ":" port path-prefix (name op) "/" root-fn-id))


(defn send-command
  "POST a cell command to `executor-id`; true IFF it acked 200. A non-200
   (incl. 409 not-in-shard, 401 unauthorized) or a transport error → false —
   the controller reads a false `load-on` as abort-before-flip, so a target that
   can't (or won't) load never gets the routing flipped to it."
  [executor-id port token op root-fn-id]
  (let [resp @(http/request {:method :post
                             :url (command-url executor-id port op root-fn-id)
                             :headers (cond-> {}
                                        token (assoc "Authorization" (str "Bearer " token)))
                             :timeout 30000
                             :as :text})]
    (cond
      (:error resp)
      (do (log/warn (:error resp) "fleet command transport failed"
                    {:executor-id executor-id :op op})
          false)

      (= 200 (:status resp))
      true

      :else
      (do (log/warn "fleet command rejected"
                    {:executor-id executor-id :op op :status (:status resp)})
          false))))


(defn directed-load
  "`load-on` seam: POST a `:load` to the target, ACK-gated (true iff 200)."
  [port token]
  (fn [executor-id root-fn-id]
    (send-command executor-id port token :load root-fn-id)))


(defn directed-evict
  "`evict-on` seam: POST an `:evict` to the source. Best-effort (post-flip)."
  [port token]
  (fn [executor-id root-fn-id]
    (send-command executor-id port token :evict root-fn-id)))


(defn fleet-port
  "The port every fleet pod's app server listens on (`GRAPHDEN_PORT`, default
   8080) — the same one the forward-hop router dials."
  []
  (or (some-> (System/getenv "GRAPHDEN_PORT") parse-long) 8080))


(defn internal-token
  "The shared control-plane secret (`GRAPHDEN_INTERNAL_TOKEN`) — nil/blank when
   unset (endpoint fail-closes, moves disabled)."
  []
  (System/getenv "GRAPHDEN_INTERNAL_TOKEN"))


(defn execute-move!
  "Assemble the directed seams from a live `ctx` + run ONE move. `ctx` provides
   `:storage` (the placement map); the port + internal token come from env. `cmd`
   is `{:org :entry-fn-id :to-executor}`. This is the Phase-1 'execute a move on
   command' entry — call it from ops / REPL. Returns `move-cell!`'s result map."
  [ctx {:keys [org entry-fn-id to-executor]}]
  (let [port (fleet-port)
        token (internal-token)]
    (controller/move-cell! (:storage ctx)
                           {:org org
                            :entry-fn-id entry-fn-id
                            :to-executor to-executor
                            :load-on (directed-load port token)
                            :evict-on (directed-evict port token)})))
