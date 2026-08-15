(ns graphden.web.errors
  "The ONE `:type` → HTTP mapping for the whole surface (audit-7
   error-honesty). Before this, status was decided ad-hoc per handler
   family: the tenancy addon had the only real mapper, `/api/execute`
   answered 200 to every logical failure, conflicts came back 400 or
   200, and an uncaught throw lost its `:type` into an opaque 500.

   Three exports:
   - `status-for`      — ex-data `:type` keyword → HTTP status.
   - `safe-error-body` — the uniform JSON error envelope; the MESSAGE
     is included only for whitelisted (author-facing) type families,
     everything else gets an opaque reference id — self-hosted and
     cloud alike (raw internals in an HTTP body were a leak on every
     deployment shape, not a tenant concern).
   - `wrap-error-boundary` — Ring wrap for the top of the app chain:
     an uncaught throw becomes its mapped status + safe body instead
     of http-kit's bare 500.

   JSON-RPC (the MCP route) is deliberately EXEMPT: the spec requires
   HTTP 200 with an in-band error object; its handlers never throw
   through to this boundary in normal operation.

   ERROR_CODES.md documents the full table; `error-codes-doc-test`
   keeps the doc and this map from drifting."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.util.counters :as counters]))


(def status-for-type
  "ex-data `:type` → HTTP status. Types absent here fall back by
   NAMESPACE family (`family-status`), then to 500."
  {;; not found
   :not-found 404
   :user/not-found 404
   ;; conflicts — the write is well-formed; the current state refuses it
   :constraint-violation/fn-name-collision 409
   :constraint-violation/position-collision 409
   :constraint-violation/unique 409
   :merge-conflict 409
   :merge-protection-violation 409
   :user/exists 409
   ;; authz
   :authz/forbidden 403
   :authz/branch-protected 403
   ;; capacity / size
   :execution/over-capacity 429
   :quota/entity-limit 429
   :execution/args-too-large 413
   ;; server-side configuration absent
   :vault/not-configured 503
   ;; misc explicit 400s that would otherwise family-default anyway
   :grant/invalid-capability 400
   :user/invalid 400
   :domain/unverified 400})


(def ^:private family-status
  "Namespace-of-`:type` → status, for whole author-error families."
  {"validation-error" 400
   "constraint-violation" 400
   "type-check" 400
   "packages" 400
   "refinement" 400
   "capability" 403
   "execution-error" 400
   "graph-error" 400
   "secrets" 400
   "branch-router" 404})


(def ^:private message-visible-families
  "Type families whose ex-MESSAGE is author-facing and safe to return
   verbatim (the sync/boot layer's actionable texts). Anything else is
   replaced by an opaque reference id — the message may carry SQL,
   internal ids, or stack context."
  #{"validation-error" "constraint-violation" "type-check" "packages"
    "refinement" "capability" "execution" "execution-error"
    "graph-error" "secrets" "authz" "user" "grant" "domain"
    "merge-conflict" "merge-protection-violation" "not-found" "vault"
    "quota"})


(defn status-for
  "HTTP status for an ex-data `:type` (or the bare keyword). nil-safe:
   unknown/absent types are 500."
  [type-kw]
  (or (get status-for-type type-kw)
      (when (keyword? type-kw)
        (get family-status (or (namespace type-kw) (name type-kw))))
      500))


(defn status-for-ex-data
  "Status from full ex-data: the `:type` mapping first; an otherwise-
   unmapped storage error with a `42xxx` sql-state (undefined column /
   syntax — the DB rejecting USER-SHAPED input) is a 400, not a 500
   (a real outage keeps 500/503)."
  [data]
  (let [st (status-for (:type data))]
    (if (and (= 500 st)
             (some-> (:sql-state data) str (str/starts-with? "42")))
      400
      st)))


(defn- type-family
  [type-kw]
  (when (keyword? type-kw)
    (or (namespace type-kw) (name type-kw))))


(defn safe-error-body
  "The uniform machine-readable error envelope as a Clojure map:
   `{:ok false :error <type-kw-or-\"internal\"> :message <safe>}` plus
   `:ref` (a correlation id also written to the server log) when the
   original message was withheld."
  [type-kw message]
  (let [visible? (contains? message-visible-families (type-family type-kw))
        err (if (keyword? type-kw)
              (subs (str type-kw) 1)
              "internal")]
    (if (and visible? (not (str/blank? (str message))))
      {:ok false :error err :message (str message)}
      (let [ref (str (random-uuid))]
        {:ok false :error err
         :message "Internal error — see server log."
         :ref ref}))))


(defn response-for-throwable
  "Ring response for an uncaught throwable: mapped status, safe JSON
   body, full details to the server log keyed by the body's ref (when
   withheld)."
  [^Throwable t]
  (let [data (ex-data t)
        type-kw (:type data)
        status (status-for type-kw)
        body (safe-error-body type-kw (Throwable/.getMessage t))]
    ;; Operational signal for /metrics (Prometheus alerting, C3): a 5xx is a
    ;; server fault worth paging on; 4xx are client errors, not counted.
    (when (>= status 500) (counters/count! :http/server-error))
    (if (:ref body)
      (log/error t "unhandled request error" {:ref (:ref body) :type type-kw})
      (log/warn "request error" {:type type-kw :status status
                                 :message (Throwable/.getMessage t)}))
    {:status status
     :headers (cond-> {"Content-Type" "application/json"}
                (= 429 status) (assoc "Retry-After" "1"))
     :body (json/generate-string body)}))


(defn wrap-error-boundary
  "Ring wrap: catch anything the inner handler throws and answer with
   the mapped status + safe body. Interrupt flags pass through
   untouched (pool hygiene is the abort-shield's concern)."
  [handler]
  (fn [req]
    (try
      (handler req)
      ;; Exception, not Throwable — java.lang.Error (OOM, linkage)
      ;; must keep crashing the process, per repo convention.
      (catch Exception t
        (response-for-throwable t)))))
