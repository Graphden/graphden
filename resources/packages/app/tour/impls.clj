(ns graphden.packages.app.tour.impls
  "Impls for the interactive tutorial's progress counters — two thin
   boundaries over `graphden.util.counters`.

   The tutorial is 25 lessons long and nobody knows where readers stop.
   The counters registry is already exposed on `/metrics` and
   `/metrics/prometheus`, and a Prometheus scrape is what turns a
   process-local, restart-resetting count into a series you can read a
   funnel off — so this needs no storage of its own.

   Nothing identifying is recorded: the lesson id, the step index, and
   which of three things happened. No account, no session, no time
   beyond the scrape's own timestamp.

   The VALIDATION in both is the point, not decoration: this is
   reachable unauthenticated (the landing demo's anonymous session runs
   lesson 01) and the counters map is process-global and unbounded, so a
   caller that could name its own key would be a memory-growth vector.
   Two digits, a three-value enum and a bounded step index can only ever
   produce a bounded set of names."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.util.counters :as counters]))


(def ^:private events
  "The three points worth counting. A funnel needs a denominator
   (`started`), a numerator (`finished`) and something in between to
   locate the drop-off (`step`, one per advance)."
  #{"started" "step" "finished"})


(defn- lesson-id
  "`lesson` as a two-digit id, or nil."
  [lesson]
  (let [s (some-> lesson str)]
    (when (and s (re-matches #"\d{2}" s)) s)))


(defbase count-tour-event!
  "Bump `:tour-<event>-<lesson>`. Returns the counter name, or nil when
   the input is not a lesson id + a known event."
  [lesson event]
  (cr/record-effect! :io)
  (let [id (lesson-id lesson)
        ev (some-> event str)]
    (when (and id (contains? events ev))
      ;; FLAT key, not `:tour/…`: the Prometheus exposition drops a
      ;; keyword's namespace (`:registry/rebuild` →
      ;; `graphden_counters_rebuild`), so a namespaced key would surface
      ;; as `graphden_counters_started_01` — indistinguishable from a
      ;; structural counter. Renaming the exporter would rename every
      ;; existing metric, and dashboards point at those.
      (let [k (keyword (str "tour-" ev "-" id))]
        (counters/count! k)
        (str k)))))


(defbase count-tour-step!
  "Bump `:tour-step-<lesson>-<n>` — the per-step bucket the drop-off is
   read off. Returns the counter name, or nil for a non-lesson / an
   index outside a lesson's possible length."
  [lesson step]
  (cr/record-effect! :io)
  (let [id (lesson-id lesson)
        n (some-> step str parse-long)]
    (when (and id n (<= 0 n 99))
      (let [k (keyword (str "tour-step-" id "-" n))]
        (counters/count! k)
        (str k)))))


(def impls
  {:count-tour-event! count-tour-event!
   :count-tour-step! count-tour-step!})
