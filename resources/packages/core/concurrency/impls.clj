(ns graphden.packages.core.concurrency.impls
  "Implementations for core/concurrency base-fns. Each wraps ONE
   Clojure / Java primitive — `(force ...)`, `Thread/sleep`,
   `(Thread. ...)`, `(Thread/isInterrupted)` — so the imperative
   pieces of long-running patterns can be composed at the fn-def
   layer instead of buried inside monolithic base-fns."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


;; =============================================================================
;; :do — force steps in declaration order, return last
;;
;; `:steps` is declared `:lazy-seq-args` in the registry below, so each
;; item arrives as a `delay`. We force them sequentially; side effects
;; therefore fire left-to-right and the impl is honest about its
;; "imperative sequencing" semantics. Without lazy-seq-args the
;; executor is free to resolve args in parallel and the ordering
;; would be undefined.
;; =============================================================================

(defbase do-fn
  [steps]
  (let [forced (mapv force steps)]
    (last forced)))


;; =============================================================================
;; :sleep — Thread/sleep. Honors interrupt so a sleeping loop body
;; sees the parent :future's stopper without delay.
;; =============================================================================

(defbase sleep-fn
  [ms]
  (cr/record-effect! :time)
  (when (pos? (long ms))
    (Thread/sleep (long ms)))
  nil)


;; =============================================================================
;; :sleep-until-ms — block until wall-clock target. Companion to :sleep,
;; composes with :cron-next-after without intermediate arithmetic.
;; =============================================================================

(defbase sleep-until-ms-fn
  [target-ms]
  (cr/record-effect! :time)
  (let [now (System/currentTimeMillis)
        delta (- (long target-ms) now)]
    (when (pos? delta)
      (Thread/sleep delta)))
  nil)


;; =============================================================================
;; :future — spawn body in daemon thread, return stopper-thunk.
;;
;; Catches InterruptedException so a clean shutdown via the returned
;; stopper doesn't surface as a noisy stack trace. Other exceptions
;; are logged (the body crashed) but don't propagate to the spawner —
;; the spawn call returns normally, the future just dies.
;;
;; Daemon flag = thread doesn't keep the JVM alive on its own. The
;; service registry's halt path explicitly stops everything; daemon
;; is the safety net for un-supervised futures (test fixtures etc.).
;; =============================================================================

(defbase future-fn
  [body]
  (cr/record-effect! :process)
  ;; Capture the conveyed dynamic bindings (the effect gate + org context) on
  ;; THIS thread and re-establish them inside the worker, so a tenant service's
  ;; ongoing work stays sandboxed instead of reverting to unrestricted in the
  ;; fresh thread (task #6). A platform future captures nil → unrestricted, as
  ;; before.
  (let [conveyed (cr/capture-conveyed-bindings)
        ;; Exit record for the reconciler's liveness pass: nil while the
        ;; body runs, `:done` after a clean return, `:failed` after an
        ;; uncaught throw (interrupt = a stop, recorded as `:done`).
        exit (atom nil)
        thread (Thread.
                 ^Runnable
                 (fn []
                   (try
                     ;; A future is a NEW logical execution on a fresh thread:
                     ;; give it its OWN call-cache instead of sharing the map
                     ;; the body captured from the spawning execute's ctx (a
                     ;; cross-thread share → ConcurrentModificationException
                     ;; under concurrent eviction). `*request-call-cache*` is
                     ;; not conveyed, so bind a fresh one here.
                     (with-bindings conveyed
                       (cr/with-fresh-call-cache body))
                     (reset! exit :done)
                     (catch InterruptedException _ (reset! exit :done))
                     (catch Exception e
                       (reset! exit :failed)
                       (log/warn e "future body threw"))))
                 "graphden-future")]
    (Thread/.setDaemon thread true)
    (Thread/.start thread)
    ;; The stopper carries the daemon's liveness for the service reconciler:
    ;; `:alive?` (is the thread still running) and `:exit` (how it ended), so
    ;; `:restart-policy :always` restarts a clean exit and `:on-failure` only
    ;; a throw — the distinction the policy always promised.
    (with-meta (fn stopper
                 []
                 (Thread/.interrupt thread))
      {:alive? (fn [] (Thread/.isAlive thread))
       :exit exit})))


;; =============================================================================
;; :loop-until-interrupted — run body repeatedly while !interrupted
;;
;; Two exit paths:
;;   1. Body returns normally — loop checks isInterrupted, exits if true.
;;   2. Blocking call inside body throws InterruptedException — caught
;;      here, loop exits.
;;
;; Either way, cooperative shutdown is clean. NOT a base-fn that should
;; be a service directly (no :process effect): it loops on the CURRENT
;; thread. Compose with :future to get the spawn.
;; =============================================================================

(defbase loop-until-interrupted-fn
  [body]
  (try
    (while (not (Thread/.isInterrupted (Thread/currentThread)))
      ;; Each iteration is a NEW logical execution: run the body under a
      ;; fresh per-request call-cache, exactly as `:http-server` does per
      ;; request. Without this the loop shares ONE memo for its whole
      ;; lifetime, so a step's ref (a queue take, a fetch) is computed on
      ;; the first pass and served from cache on every later one — a loop
      ;; that only appears to repeat.
      (cr/with-fresh-call-cache body))
    (catch InterruptedException _ nil))
  nil)


;; =============================================================================
;; :cron-next-after — Quartz CronExpression parse + next-fire math.
;;
;; Two-step:
;;   1. Construct a CronExpression (parses + validates the cron string;
;;      throws ParseException on malformed). Rethrown as
;;      `:cron/parse-error` ex-info so callers see a stable :type tag.
;;   2. .getNextValidTimeAfter (java.util.Date.) — Quartz internally
;;      walks cron fields forward; returns a Date.
;;
;; Returns the Date as epoch-ms long. The caller composes with
;; current-time-ms via :sub to compute the sleep duration.
;; =============================================================================

(defbase cron-parse-fn
  "Parse a Quartz cron expression into a `CronExpression` handle. Throws
   `:cron/parse-error` ex-info on malformed input — the boundary
   converts Quartz's `ParseException` (whose message includes the bad
   substring + offset) into a stable `:type` tag for graph callers."
  [cron]
  (try (org.quartz.CronExpression. ^String cron)
       (catch java.text.ParseException e
         (throw (ex-info (str "Invalid cron expression: "
                              (Throwable/.getMessage e))
                         {:type :cron/parse-error
                          :cron cron})))))


(defbase cron-fire-after-fn
  "Given a parsed `CronExpression` handle and a wall-clock instant
   (epoch-ms), return the next epoch-ms at which the cron would fire.
   Single Quartz call wrapped in `Date` ↔ epoch-ms interop — both sides
   speak epoch-ms so this composes cleanly with `:current-time-ms`.

   Quartz returns null when the expression has no valid time after
   the given instant (e.g. a year-locked cron whose year is already
   past). Calling `(.getTime null)` would NPE with no `:type` tag,
   surfacing as a generic ClassCastException down the schedule loop,
   so guard the null and convert to a clean `:cron/no-future-fire`
   ex-info that callers can pattern-match on."
  [expr now-ms]
  (let [now-date (java.util.Date. (long now-ms))
        next-date (org.quartz.CronExpression/.getNextValidTimeAfter expr now-date)]
    (if next-date
      (java.util.Date/.getTime next-date)
      (throw (ex-info "Cron expression has no valid future fire time"
                      {:type :cron/no-future-fire
                       :now-ms now-ms})))))


;; =============================================================================
;; Mutable-state primitives — `clojure.core` atom + swap + deref, exposed
;; as atomic base-fns so ANY keyed-accumulator or cache pattern is
;; composable at the fn-def layer instead of a bespoke impl. Two lifetimes:
;;
;;   :atom — a FRESH atom per top-level `execute` (result-cache-scoped).
;;           Every ref to one `:atom` fn-def in a single call resolves to
;;           the SAME instance → that's what backs the `:try`-journal idiom
;;           (each request its own independent rollback journal).
;;
;;   :cell — a PERSISTENT atom, allocated ONCE per compiled registry and
;;           baked into the closure like a `:const` literal (see the
;;           `:compile-time-value?` marker below + compile_eager). It
;;           survives across `execute`s, so a `:cell` + `:swap` + `:deref`
;;           graph is a real in-process cache. Scope is the compiling
;;           registry (per-JVM for the server handler; per-branch-ctx
;;           elsewhere) — NOT shared across executor instances (that needs
;;           an external store; see docs). Re-allocated on recompile =
;;           cache reset, exactly like `defonce` on namespace reload.
;;
;; `:swap` / `:deref` are lifetime-agnostic — they work on either.
;; `:swap-conj` is now a fn-def (`(swap a conj)`) over `:swap`, not a
;; base-fn: the conj is graph-visible composition, not hidden here.
;; =============================================================================

(defbase atom-fn
  "Create a fresh `clojure.core/atom` holding `initial-value`. Returns
   the atom instance for use with `:swap` / `:deref`. The result
   is cached per top-level invocation, so every fn-def referencing this
   atom fn-def derefs to the SAME instance — that's what makes the
   journal-shared-across-phases idiom work."
  [initial-value]
  (atom initial-value))


(defbase cell-fn
  "Create a `clojure.core/atom` holding `initial-value`. Registered
   `:compile-time-value?` so compile_eager evaluates this ONCE at
   compile time and bakes `(constantly <the-atom>)` into the closure —
   the instance therefore persists across every `execute` served by
   that compiled registry (a real in-process cache), and is shared by
   every fn-def referencing this cell. Re-allocated on recompile.
   Impl body is IDENTICAL to `:atom`; the lifetime difference is purely
   the compile-time-bake marker, not the allocation."
  [initial-value]
  (atom initial-value))


(defbase swap-fn
  "`(swap! a func)` — atomically apply the 1-arg `func` to the atom's
   current value and install the result (`clojure.core/swap!`). Returns
   the new value. `func` is `:fn`-typed, so any extra data it folds in
   (a value to conj, a key/value to assoc) is closure-captured at the
   call site — exactly Clojure's `(swap! a #(f % captured))`. Records
   `:state`; `func` must be pure so a CAS retry is safe."
  [a func]
  (cr/record-effect! :state)
  (swap! a func))


(defbase reset-fn
  "`(reset! a v)` — install `v` as the atom's value regardless of the
   current one (`clojure.core/reset!`). Returns `v`. Unlike `:swap` it
   takes no function and does NOT read-modify-write atomically, so use
   it only where a lost concurrent write is harmless (an idempotent
   cache: the same key always maps to the same value, so a dropped
   store just recomputes next time). Records `:state`."
  [a v]
  (cr/record-effect! :state)
  (reset! a v))


(defbase deref-fn
  "`@a` — read the current value of an atom (`clojure.core/deref`).
   Returns whatever the atom currently holds."
  [a]
  (deref a))


(defbase with-heartbeat-fn
  "Run `body` while a daemon thread calls `beat` every `every-ms`; no
   beat lands after the body ends. The beat runs under the spawning
   thread's conveyed bindings (effect gate, org, the execution being
   traced), like `:future`'s body.

   Stopping is a flag + interrupt + bounded join, not the interrupt
   alone: an interrupt only wakes the sleep, so a beat already past it
   fired AFTER the body had returned (the lease of a message the
   consumer had just acked was extended once more; a flaky
   `no beat after the body returned` on a loaded CI host). The flag is
   read after every sleep, and the join waits out a beat in flight —
   capped, so a beat stuck in I/O cannot hold the caller."
  [body beat every-ms]
  (cr/record-effect! :process)
  (let [conveyed (cr/capture-conveyed-bindings)
        period (long every-ms)
        stop? (volatile! false)
        thread (Thread.
                 ^Runnable
                 (fn []
                   (with-bindings conveyed
                     (try
                       (loop []
                         (Thread/sleep period)
                         (when-not @stop?
                           (try (beat)
                                (catch InterruptedException e (throw e))
                                (catch Exception e (log/warn e "heartbeat beat threw")))
                           (recur)))
                       (catch InterruptedException _ nil))))
                 "graphden-heartbeat")]
    (Thread/.setDaemon thread true)
    (Thread/.start thread)
    (try
      (body)
      (finally
        (vreset! stop? true)
        (Thread/.interrupt thread)
        (Thread/.join thread 5000)))))


(def impls
  ;; `:taint-propagate? true` (2026-08-17 security fix): `:do` returns
  ;; its last step's value — a content-passing `:any`-slot fn. Without
  ;; the flag it silently DECLASSIFIED a secret (`(:do :steps [1
  ;; secret-ref])` returned the real secret with a registered type of
  ;; `:any` → `tainted-fn?` false → `/api/execute` never redacted it),
  ;; unlike every sibling that returns an input (`:if`/`:cond`/
  ;; `:coalesce`/…). With the flag AND `taint-with-secret-if-tainted`
  ;; now scanning `:elem-types`, a `:do` whose `:steps` list carries a
  ;; `[:secret …]` element is typed `[:secret :any]` → redacted.
  ;; Conservative (taints if ANY step is secret, not only the last) —
  ;; over-tainting hides a non-secret result, which is the safe
  ;; direction; keeping `:do`'s structural return `:any` avoids
  ;; re-typing its 22 consumers.
  {:do {:impl do-fn :lazy-seq-args #{:steps} :taint-propagate? true}
   :sleep sleep-fn
   :sleep-until-ms sleep-until-ms-fn
   :future future-fn
   :loop-until-interrupted loop-until-interrupted-fn
   :with-heartbeat {:impl with-heartbeat-fn :taint-propagate? true}
   :cron-parse cron-parse-fn
   :cron-fire-after cron-fire-after-fn
   ;; Cell taint (2026-08-17): a secret stored in an atom/cell must stay
   ;; redacted when read back. Without propagation, `:atom`/`:cell` return
   ;; `:any` and `:deref` returns `:any`, so `(deref (atom secret))` typed
   ;; `:any` → tainted-fn? false → the Run pane shows the secret. With the
   ;; flag (and `taint-with-secret-if-tainted` scanning arg types): a cell
   ;; CREATED from a `[:secret …]` value is typed `[:secret :any]`, and
   ;; `:deref`/`:swap`/`:reset` propagate that marker from the atom argument
   ;; to their result. Only ever taints when an input already carries a
   ;; marker, so ordinary (non-secret) cache cells are untouched. NB a
   ;; static analysis cannot follow a secret `:reset` into a cell CREATED
   ;; non-secret (the atom's type is fixed at creation) — that dynamic
   ;; case would need runtime taint tracking.
   :atom {:impl atom-fn :taint-propagate? true}
   :cell {:impl cell-fn :compile-time-value? true :taint-propagate? true}
   :swap {:impl swap-fn :taint-propagate? true}
   :reset {:impl reset-fn :taint-propagate? true}
   :deref {:impl deref-fn :taint-propagate? true}})
