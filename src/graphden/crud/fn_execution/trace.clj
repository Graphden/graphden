(ns graphden.crud.fn-execution.trace
  "Cross-service tracing (docs/EXECUTION.md § Tracing across services).

   A persisted run binds `cr/*execution*` (`{:id :trace-id}`). When such
   a run calls another service over HTTP, `:service-get` / `:service-post`
   add the `X-Graphden-Trace` header — `<trace-id>;<execution-id>` — built
   by `trace-headers`. A listener that receives the header
   (`:http-server`, via `run-traced!`) handles the request as a NEW
   persisted execution linked to its caller: same `:trace-id`,
   `:parent-execution-id` = the caller's execution, and `cr/*execution*`
   bound to the new id for the handler's own outbound calls. So a call
   tree that crosses the wire stays one tree — every hop is a row, and
   `get-execution` lists a run's `:children` (docs/tutorial/35).

   A request WITHOUT the header is not persisted here: ordinary traffic
   pays nothing. The wire format is deliberately tiny (two uuids), and a
   malformed header is ignored, never an error."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.debug-capture :as capture]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.versioning.storage.core :as vs]))


(def header-name
  "The lower-cased Ring header name."
  "x-graphden-trace")


(defn format-header
  "`<trace-id>;<execution-id>` for an execution map, or nil when there
   is no persisted execution to name."
  [{:keys [id trace-id]}]
  (when id
    (str (or trace-id id) ";" id)))


(defn parse-header
  "`{:trace-id :parent-execution-id}` from a header value, or nil when
   absent / malformed."
  [value]
  (when (string? value)
    (let [[t e] (str/split (str/trim value) #";" 2)]
      (when (and t e)
        (try
          {:trace-id (java.util.UUID/fromString (str/trim t))
           :parent-execution-id (java.util.UUID/fromString (str/trim e))}
          (catch IllegalArgumentException _ nil))))))


(defn trace-headers
  "The header map an outbound call adds so the callee can link back —
   `{\"X-Graphden-Trace\" \"<trace>;<execution>\"}` under a persisted
   run, `{}` otherwise."
  []
  (if-let [v (format-header cr/*execution*)]
    {"X-Graphden-Trace" v}
    {}))


(defn incoming-trace
  "The trace a Ring request carries, or nil."
  [request]
  (parse-header (get-in request [:headers header-name])))


(defn run-traced!
  "Handle `request` through `thunk`. With a trace header present (and a
   known `handler-fn-id`): mint this hop's execution id, bind
   `cr/*execution*` to it (so the handler's own `:service-get` names it),
   run under a path-trace, persist the outcome as an execution row linked
   to the caller (`capture/persist-captured!`), and return the response
   (or rethrow) exactly as the untraced path would. Without a header:
   just `(thunk)`."
  [ctx handler-fn-id request thunk]
  (if-let [{:keys [trace-id parent-execution-id]}
           (and handler-fn-id (incoming-trace request))]
    (let [id (random-uuid)
          t0 (System/currentTimeMillis)
          trace (ce/new-path-trace)
          effect-trace (atom #{})
          branch-id (some-> (:storage ctx) vs/current-branch-id)]
      (binding [cr/*execution* {:id id :trace-id trace-id}
                cr/*path-trace* trace
                cr/*effect-trace* effect-trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (let [outcome (try {:status :succeeded :result (thunk)}
                           (catch Exception t {:status :failed :throwable t}))]
          (capture/persist-captured! branch-id ctx handler-fn-id request
                                     trace effect-trace outcome t0
                                     {:id id
                                      :trace-id trace-id
                                      :parent-execution-id parent-execution-id})
          (if (= :succeeded (:status outcome))
            (:result outcome)
            (throw (:throwable outcome))))))
    (do
      (when (and (nil? handler-fn-id) (incoming-trace request))
        (log/debug "trace header received but the handler carries no fn identity — not persisted"))
      (thunk))))
