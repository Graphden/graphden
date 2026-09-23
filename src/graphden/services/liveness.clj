(ns graphden.services.liveness
  "Did a running copy die in place, and what does its restart policy want?

   A service handle may carry `:alive?` (`http-kit`'s listener status; a
   `:future`'s thread state) and `:exit` (an atom — `:done` / `:failed`).
   The reconcile pass checks every copy each tick (`check-liveness!`) and,
   when one has died in place, applies the row's `:restart-policy` for real:
   `:always` restarts any exit, `:on-failure` only a throw, `:never` leaves
   it stopped (the `exited` placeholder) until the row is toggled. Repeated
   short-lived exits back off exponentially (the `backoff` placeholder).

   The placeholders are the reconciler's `running`-map vocabulary, so they
   keep its namespace (`:graphden.services.reconciler/exited` …)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.services.instances :as instances]))


(def exited
  "Placeholder for a copy that exited in place and whose policy leaves it
   down."
  :graphden.services.reconciler/exited)


(def backoff
  "Placeholder for a copy that exited in place and restarts once its delay
   (`backoff-until`) elapses."
  :graphden.services.reconciler/backoff)


(defn- copy-exited?
  "Did a running copy die in place? True when its handle carries an
   `:alive?` probe that now answers false. Handles without a probe (a
   nil stopper, a fire-and-forget) are trusted as running."
  [entry]
  (when-let [alive? (:alive? (instances/handle-meta (:stopper entry)))]
    (not (try (alive?) (catch Exception _ false)))))


(defn- restart-after-exit?
  "Does the row's `:restart-policy` want a copy that exited restarted?
   `:always` — any exit; `:on-failure` — only when the handle's `:exit`
   records a throw; `:never` — no."
  [entry]
  (case (:restart-policy entry)
    :always true
    :on-failure (= :failed (some-> (:exit (instances/handle-meta (:stopper entry))) deref))
    false))


(def ^:dynamic *exit-backoff-cap-ms*
  "Ceiling on the restart delay after repeated in-place exits."
  60000)


(def ^:dynamic *exit-stable-ms*
  "A copy that lived at least this long before exiting counts as a
   stable run: its restart is immediate and the exit counter resets."
  60000)


(defonce ^:private exit-backoff
  ;; sid → {:exits n :until ms}. Restart-after-exit used to be
  ;; immediate every pass: a `:restart-policy :always` service whose fn
  ;; returns at once (`:exit :done` — a one-shot that should have been
  ;; an `:interval`) restarted on EVERY liveness tick forever, a WARN
  ;; per tick (7 restarts in 15 s in the e2e gate). Now the
  ;; first restart is immediate and the delay doubles per further
  ;; short-lived exit — 1 s, 2 s, … `*exit-backoff-cap-ms*` — while a
  ;; run that lasted `*exit-stable-ms*` resets it.
  (atom {}))


(defn- exit-backoff-ms
  "The delay before restarting `sid` after an in-place exit, updating
   the counter: 0 after a stable run (counter reset) and for the FIRST
   short-lived exit (a one-off crash restarts at once, as before), then
   `min(cap, 1 s × 2^(exits-2))` — 1 s, 2 s, 4 s, …"
  [sid started-at now-ms]
  (let [ran-ms (- now-ms (if (instance? java.time.Instant started-at)
                           (java.time.Instant/.toEpochMilli started-at)
                           now-ms))]
    (if (>= ran-ms *exit-stable-ms*)
      (do (swap! exit-backoff dissoc sid) 0)
      (let [n (inc (get-in @exit-backoff [sid :exits] 0))
            wait-ms (if (= n 1)
                      0
                      (min *exit-backoff-cap-ms*
                           (* 1000 (bit-shift-left 1 (min 16 (- n 2))))))]
        (swap! exit-backoff assoc sid {:exits n :until (+ now-ms wait-ms)})
        wait-ms))))


(defn backoff-until
  "Epoch ms at which `sid`'s delayed restart is due, or nil."
  [sid]
  (get-in @exit-backoff [sid :until]))


(defn forget-exits!
  "Drop `sid`'s exit history — a row that was disabled / deleted / edited
   starts afresh when it comes back."
  [sid]
  (swap! exit-backoff dissoc sid))


(defn drop-due-backoffs!
  "Top-of-pass: a `backoff` placeholder whose delay has elapsed is
   dropped so the diff restarts the service this pass; the others stay
   (still counted as running, so the diff leaves them alone)."
  [running-atom now-ms]
  (let [due (into #{} (keep (fn [[sid {:keys [until]}]]
                              (when (<= until now-ms) sid)))
                  @exit-backoff)]
    (swap! running-atom
           (fn [m] (into {} (remove (fn [[sid v]] (and (= backoff v) (contains? due sid)))) m)))))


(defn check-liveness!
  "The per-tick liveness pass over this pod's running copies: heartbeat
   every live instance row; for a copy that died in place, release its
   lock + row and either drop it from `running-atom` (so the diff below
   restarts it this pass), park it as `backoff` until its restart
   delay elapses (`exit-backoff-ms` — repeated short-lived exits back
   off exponentially), or park it as `exited` per `restart-after-
   exit?`. Called under `reconcile-monitor`."
  [running-atom lock-conn storage]
  (doseq [[sid entry] @running-atom
          :when (and (map? entry) (some? (:stopper entry)))]
    (if (copy-exited? entry)
      (let [restart? (restart-after-exit? entry)
            backoff-ms (when restart?
                         (exit-backoff-ms sid (:started-at entry) (System/currentTimeMillis)))]
        (log/warn "service copy exited in place"
                  (cond-> {:service-id sid :fn-id (:fn-id entry)
                           :exit (some-> (:exit (instances/handle-meta (:stopper entry))) deref)
                           :restart-policy (:restart-policy entry)
                           :restart? restart?}
                    (some-> backoff-ms pos?) (assoc :backoff-ms backoff-ms)))
        (instances/stop-and-forget! lock-conn running-atom sid storage)
        (cond
          (not restart?) (swap! running-atom assoc sid exited)
          (pos? backoff-ms) (swap! running-atom assoc sid backoff)))
      (instances/heartbeat-instance! storage (:instance-id entry)))))
