(ns graphden.util.abort-shield
  "Abort-atomicity for write pipelines on interruptible request
   threads (audit-7).

   http-kit interrupts its worker when the client disconnects; a write
   request dying between the graph-epoch bump and the eager
   invalidation's note leaves an un-noted epoch, and the router's
   grace-expiry heal fires ~10s later. The heal is CORRECT — but a
   Playwright-style fire-and-navigate client aborts writes routinely,
   and each abort cost a background full recompile. Making the write
   pipeline finish regardless of the client turns the heal back into
   what it is meant to be: the rare backstop for crashes and lost
   NOTIFYs, not the common path.

   `run!` executes `f` on a small dedicated executor and joins
   UNINTERRUPTIBLY: an interrupt during the join is remembered and
   re-asserted only AFTER `f` completed — the task itself is never
   cancelled, so bump→write→invalidate→note runs as one atom with
   respect to client aborts. The interrupt flag is NOT left set when
   the caller is a pooled worker returning to its pool would poison
   the next request — the caller receives the completed result (or
   `f`'s own exception) and http-kit simply fails to write the
   response to the gone client.

   The join is uninterruptible but NOT unbounded: it waits at most
   `*join-timeout-ms*`. A task that outruns that is presumed HUNG (a
   wedged socket, a lock never released) — the caller stops waiting,
   logs, best-effort-cancels the task and throws `:abort-shield/timeout`
   rather than pinning the request thread and blocking shutdown forever.
   The bound only ever fires for a genuinely stuck task; a healthy write
   pipeline finishes in milliseconds."
  (:refer-clojure :exclude [run!])
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    (java.util.concurrent
      ExecutorService
      Executors
      Future
      TimeUnit
      TimeoutException)))


(defonce ^:private ^ExecutorService pool
  (Executors/newCachedThreadPool
    (let [n (atom 0)]
      (reify java.util.concurrent.ThreadFactory
        (newThread
          [_ r]
          (doto (Thread. r (str "abort-shield-" (swap! n inc)))
            (Thread/.setDaemon true)))))))


(def ^:dynamic *join-timeout-ms*
  "Upper bound (ms) on the uninterruptible join before a shielded task is
   presumed hung and abandoned (log + best-effort cancel + throw
   `:abort-shield/timeout`). Keeps a wedged write from pinning the request
   thread / blocking shutdown. Env `GRAPHDEN_ABORT_SHIELD_TIMEOUT_MS`
   (default 30 000). Dynamic so tests can bind a small value."
  (or (some-> (System/getenv "GRAPHDEN_ABORT_SHIELD_TIMEOUT_MS") parse-long)
      30000))


(defn- abandon-hung!
  "Log, best-effort-cancel the presumed-hung task (interrupt its shield
   thread), and throw `:abort-shield/timeout` so the caller stops waiting."
  [^Future fut timeout-ms]
  (log/warn "abort-shield: task exceeded" timeout-ms
            "ms join budget — presumed hung, abandoning (write may be incomplete)")
  (Future/.cancel fut true)
  (throw (ex-info (str "abort-shield: task exceeded " timeout-ms "ms join budget")
                  {:type :abort-shield/timeout :timeout-ms timeout-ms})))


(defn run!
  "Run `f` to completion regardless of interrupts on the calling
   thread; return its value or throw its exception. Interrupts
   received while waiting are swallowed (see ns doc — the pooled
   caller must not carry the flag back to its pool). The join is bounded
   by `*join-timeout-ms*`: a task that overruns is abandoned with
   `:abort-shield/timeout` so a hang can't block the thread indefinitely."
  [f]
  (if (str/starts-with? (Thread/.getName (Thread/currentThread))
                        "abort-shield-")
    ;; Already on a shield thread (nested write pipeline) — run inline.
    (f)
    ;; bound-fn*: raw executor submit does NOT convey dynamic bindings
    ;; — without it the shield thread saw a nil *request-bump-log*,
    ;; bumps went unlogged, notes drained nothing, and EVERY shielded
    ;; write became a heal (run-9: 41 heals).
    (let [^Future fut (ExecutorService/.submit pool ^Callable (bound-fn* f))
          timeout-ms *join-timeout-ms*
          ;; A single DEADLINE, not a per-attempt timeout: repeated
          ;; interrupts must not reset the clock, or a steadily-interrupted
          ;; caller could wait unboundedly and defeat the whole point.
          deadline (+ (System/currentTimeMillis) timeout-ms)]
      (loop []
        (let [remaining (- deadline (System/currentTimeMillis))]
          (if-not (pos? remaining)
            (abandon-hung! fut timeout-ms)
            (let [r (try
                      {:v (Future/.get fut remaining TimeUnit/MILLISECONDS)}
                      (catch InterruptedException _ ::interrupted)
                      (catch TimeoutException _ ::timed-out)
                      (catch java.util.concurrent.ExecutionException e
                        {:t (or (Throwable/.getCause e) e)}))]
              (cond
                (= r ::interrupted) (recur)
                (= r ::timed-out) (abandon-hung! fut timeout-ms)
                (:t r) (throw (:t r))
                :else (:v r)))))))))
