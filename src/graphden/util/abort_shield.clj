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
   response to the gone client."
  (:refer-clojure :exclude [run!])
  (:require
    [clojure.string :as str])
  (:import
    (java.util.concurrent
      ExecutorService
      Executors
      Future)))


(defonce ^:private ^ExecutorService pool
  (Executors/newCachedThreadPool
    (let [n (atom 0)]
      (reify java.util.concurrent.ThreadFactory
        (newThread
          [_ r]
          (doto (Thread. r (str "abort-shield-" (swap! n inc)))
            (Thread/.setDaemon true)))))))


(defn run!
  "Run `f` to completion regardless of interrupts on the calling
   thread; return its value or throw its exception. Interrupts
   received while waiting are swallowed (see ns doc — the pooled
   caller must not carry the flag back to its pool)."
  [f]
  (if (str/starts-with? (Thread/.getName (Thread/currentThread))
                        "abort-shield-")
    ;; Already on a shield thread (nested write pipeline) — run inline.
    (f)
    ;; bound-fn*: raw executor submit does NOT convey dynamic bindings
    ;; — without it the shield thread saw a nil *request-bump-log*,
    ;; bumps went unlogged, notes drained nothing, and EVERY shielded
    ;; write became a heal (run-9: 41 heals).
    (let [^Future fut (ExecutorService/.submit pool ^Callable (bound-fn* f))]
      (loop []
        (let [r (try
                  {:v (Future/.get fut)}
                  (catch InterruptedException _ ::interrupted)
                  (catch java.util.concurrent.ExecutionException e
                    {:t (or (Throwable/.getCause e) e)}))]
          (cond
            (= r ::interrupted) (recur)
            (:t r) (throw (:t r))
            :else (:v r)))))))
