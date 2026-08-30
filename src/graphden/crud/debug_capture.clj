(ns graphden.crud.debug-capture
  "«Catch next request» — a one-shot, TTL-bounded trap on a branch's
   web handler. While armed, the next matching HTTP request through
   the branch router runs with the Debug path-trace bound
   (`trace-all`, optionally value capture) and persists a standard
   `:fn-execution` row: the sanitized request as the handler's arg,
   the Ring response as the result, the call tree as `:path-trace`.
   The captured run then replays through the SAME history/trace UI as
   a traced `/api/execute` run — no new entity, no new viewer.

   Runtime-only state (the `*traced-fn-ids*` doctrine — PHILOSOPHY §
   \"Per-fn debug/trace toggles are not a stored field\"): the trap
   registry is an in-process atom keyed `[org-id branch-id]`, one trap
   per key, consumed atomically by the first matching request; a
   restart disarms. Org-keyed sharding routes an org's requests to its
   own pod (docs/SCALING.md), so in the fleet the arming editor and
   the captured request meet on the same process by construction; a
   multi-pod PUBLIC deployment would need the request to hit the
   arming pod (accepted — this is a debug affordance, not a data
   plane).

   Secret-safety: the capture rides the path-trace seam, so every
   protection applies unchanged — capture-time class hiding,
   fail-closed unknowns, ancestor poisoning, read-time re-redaction.
   Request sanitization additionally strips credential-bearing
   headers BEFORE anything is stored; the response strips
   `Set-Cookie`."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(def default-ttl-ms (* 10 60 1000))
(def max-ttl-ms (* 60 60 1000))
(def max-captured-body-chars (* 64 1024))


(defonce ^:private traps
  ;; `{[org-id branch-id] → {:path-prefix … :capture-values? …
  ;;                         :armed-at-ms … :expires-at-ms …}}`
  (atom {}))


(defonce ^:private last-captures
  ;; `{[org-id branch-id] → execution-id}` — the newest captured run
  ;; per scope, so the Debug panel can offer «open its trace» without
  ;; knowing the handler fn. Same runtime-only lifetime as `traps`.
  (atom {}))


(def ^:private infra-path-prefixes
  "Paths served by the editor/platform itself. A CATCH-ALL trap (no
   explicit `:path-prefix`) skips these — otherwise the editor's own
   polling (`/partials/*`, `/api/*`) consumes the trap the moment it
   is armed, before the user's app request ever arrives. An EXPLICIT
   prefix targets whatever it names, including these."
  ["/api/" "/partials/" "/assets/" "/events/" "/auth/" "/version"])


(defn- now-ms
  []
  (System/currentTimeMillis))


(defn arm!
  "Arm (or re-arm, replacing) the current org+branch's trap. Returns
   the armed trap. `path-prefix` nil/blank → catch-all with the
   infra-path exclusion; `ttl-ms` clamps to (0, `max-ttl-ms`]."
  [branch-id {:keys [path-prefix capture-values? ttl-ms]}]
  (let [k [(tc/current-org) branch-id]
        ttl (-> (or ttl-ms default-ttl-ms) long (max 1000) (min max-ttl-ms))
        trap {:path-prefix (when-not (str/blank? path-prefix) path-prefix)
              :capture-values? (boolean capture-values?)
              :armed-at-ms (now-ms)
              :expires-at-ms (+ (now-ms) ttl)}]
    (swap! traps assoc k trap)
    trap))


(defn disarm!
  "Remove the current org+branch's trap. Returns true when one was armed."
  [branch-id]
  (let [k [(tc/current-org) branch-id]
        [old _] (swap-vals! traps dissoc k)]
    (contains? old k)))


(defn trap-status
  "The current org+branch's live trap (nil when unarmed/expired)."
  [branch-id]
  (let [t (get @traps [(tc/current-org) branch-id])]
    (when (and t (< (now-ms) (:expires-at-ms t)))
      t)))


(defn last-captured-execution-id
  "The newest captured run's execution id for the current org+branch
   (nil when nothing was captured since the process started)."
  [branch-id]
  (get @last-captures [(tc/current-org) branch-id]))


(defn force-expire-for-test!
  "Test seam — rewind the trap's expiry so the TTL-drop logic is
   testable without sleeping. No production caller."
  [branch-id trap]
  (swap! traps (fn [m]
                 (let [k [(tc/current-org) branch-id]]
                   (if (identical? (get m k) trap)
                     (assoc m k (assoc trap :expires-at-ms 0))
                     m)))))


(defn- matches?
  [{:keys [path-prefix expires-at-ms]} request]
  (let [uri (or (:uri request) "")]
    (and (< (now-ms) expires-at-ms)
         (if path-prefix
           (str/starts-with? uri path-prefix)
           (not-any? #(str/starts-with? uri %) infra-path-prefixes)))))


(defn any-traps?
  "The dispatch fast-path test — one deref + `seq`, nothing else.
   The per-request cost of the whole feature while unarmed."
  []
  (boolean (seq @traps)))


(defn consume-trap!
  "Atomically claim the trap for the CURRENT org + `branch-id` when
   `request` matches — the ONE-SHOT: of N concurrent matching requests
   exactly one gets the trap (the `swap-vals!` transition that removed
   the key wins). The org comes from the bound request scope, so a
   trap armed by org A can never fire on org B's request. Expired
   traps are dropped on the way. nil when unarmed / no match."
  [branch-id request]
  (let [k [(tc/current-org) branch-id]
        t (get @traps k)]
    (cond
      (nil? t) nil
      (>= (now-ms) (:expires-at-ms t))
      (do (swap! traps (fn [m] (if (identical? (get m k) t) (dissoc m k) m)))
          nil)
      (not (matches? t request)) nil
      :else
      (let [[old _] (swap-vals! traps
                                (fn [m] (if (identical? (get m k) t) (dissoc m k) m)))]
        (when (identical? (get old k) t)
          t)))))


;; =============================================================================
;; Captured run — bind the trace, run the handler, persist the row.
;; =============================================================================

(def ^:private sensitive-request-headers
  "Credential-bearing request headers that must never reach storage —
   capturing a tenant user's session cookie or bearer token would turn
   the debug row into a credential store."
  #{"authorization" "proxy-authorization" "cookie" "x-api-key"
    "x-graphden-token"})


(defn- sanitize-request
  "The persisted arg snapshot of a captured request: method / uri /
   query / non-credential headers / string body (char-capped). Never
   the raw Ring map — it carries the socket, the auth headers and
   arbitrary middleware keys."
  [request]
  (let [body (:body request)]
    (cond-> {:request-method (some-> (:request-method request) name)
             :uri (:uri request)}
      (:query-string request) (assoc :query-string (:query-string request))
      (map? (:headers request))
      (assoc :headers (into {}
                            (remove (fn [[k _]]
                                      (contains? sensitive-request-headers
                                                 (str/lower-case (str k)))))
                            (:headers request)))
      (string? body)
      (assoc :body (if (> (count body) max-captured-body-chars)
                     (str (subs body 0 max-captured-body-chars) "…")
                     body)))))


(defn- sanitize-response
  "The persisted result snapshot of the captured response — the Ring
   map minus `Set-Cookie` (a session grant is a credential, same rule
   as the request side). Size caps ride `write-finished!`'s standard
   result machinery (5 MB / unserializable → nil + truncated flag)."
  [response]
  (if (and (map? response) (map? (:headers response)))
    (update response :headers
            (fn [hs]
              (into {}
                    (remove (fn [[k _]] (= "set-cookie" (str/lower-case (str k)))))
                    hs)))
    response))


(defn- persist-captured!
  "Write the captured run as a standard `:fn-execution` row (against
   the branch ctx's storage — org-stamped by the tenancy decorator
   like any request-path write). Best-effort: a persist failure logs
   and the response still returns."
  [branch-id branch-ctx handler-fn-id request trace effect-trace outcome started-at-ms]
  (try
    (when-let [fn-version-id (lookup/resolve-fn-version-id branch-ctx handler-fn-id)]
      (let [storage (:storage branch-ctx)
            row (persist/create-pending-row!
                  storage fn-version-id
                  (persist/declared-effects-of handler-fn-id) nil branch-id)
            free-slots (lookup/free-arg-slot-map-cached branch-ctx handler-fn-id)]
        ;; The row is created AFTER the run (a failed persist can't leak
        ;; a zombie pending row), so correct :started-at back to the
        ;; handler-entry time for an honest duration.
        (sp/update-entity storage :fn-execution (:id row)
                          {:started-at (java.time.Instant/ofEpochMilli started-at-ms)})
        (persist/persist-args! storage (:id row)
                               {:request (sanitize-request request)}
                               free-slots)
        (persist/write-finished!
          storage (:id row)
          (->> (case (:status outcome)
                 :succeeded {:status :succeeded
                             :result (sanitize-response (:result outcome))}
                 :failed (let [^Exception t (:throwable outcome)]
                           {:status :failed
                            :error (or (ex-message t) (str (class t)))
                            :error-data (ex-data t)}))
               (merge {:runtime-effects (persist/snapshot-runtime-effects effect-trace)
                       :path-trace (persist/snapshot-path-trace trace)})
               (persist/redact-outcome handler-fn-id)
               (persist/stamp-touched-secret handler-fn-id)))
        (swap! last-captures assoc [(tc/current-org) branch-id] (:id row))
        (log/info "debug-capture: captured request persisted"
                  {:execution-id (:id row)
                   :uri (:uri request)
                   :duration-ms (- (now-ms) started-at-ms)})
        (:id row)))
    (catch Exception e
      (log/warn e "debug-capture: persist failed — response unaffected"
                {:uri (:uri request)})
      nil)))


(defn run-captured!
  "Run `thunk` (the branch handler invocation) with the Debug
   path-trace bound per `trap`, persist the outcome, and return the
   response (or rethrow the handler's throwable) EXACTLY as the
   uncaptured path would — capture must never change what the caller
   observes."
  [trap branch-id branch-ctx handler-fn-id request thunk]
  (let [t0 (now-ms)
        trace (ce/new-path-trace (when (:capture-values? trap)
                                   {:capture-values? true}))
        effect-trace (atom #{})]
    (binding [cr/*path-trace* trace
              cr/*effect-trace* effect-trace
              ce/*traced-fn-ids* (atom ce/trace-all)]
      ;; Exception, not Throwable: an Error (OOM, StackOverflow)
      ;; propagates uncaptured — persisting it matters less than not
      ;; interfering with the JVM's error path.
      (let [outcome (try {:status :succeeded :result (thunk)}
                         (catch Exception t {:status :failed :throwable t}))]
        (persist-captured! branch-id branch-ctx handler-fn-id request
                           trace effect-trace outcome t0)
        (if (= :succeeded (:status outcome))
          (:result outcome)
          (throw (:throwable outcome)))))))
