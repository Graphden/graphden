(ns graphden.packages.app.tour.impls
  "The structured lesson eligibility read and interactive tutorial progress
   counters.

   The tutorial runs to dozens of lessons and nobody knows where readers
   stop.
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
   produce a bounded set of names.

   Why the checks are HERE and not graph composition (`:re-find?` +
   `:contains?` in the route, over a `:count!` primitive that takes a
   name): the bound has to hold for every caller of the base-fn, not
   just for this route. The counters map is shared by every org on a
   shard, so a fn-def any tenant AUTHOR composes could otherwise grow it
   without limit. Keeping the key construction inside the boundary is
   what makes the key space finite no matter who calls — the one case
   where a base-fn legitimately holds policy."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.structured-input :as input]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.input.authoring-principal :as principal]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.graph-writer :as writer]
    [graphden.util.counters :as counters]
    [graphden.versioning.storage.core :as vs]))


(def ^:private structured-lesson-templates
  {"43" (ids/fn-id "lessons.structured-input" :lesson-profile)
   "44" (ids/fn-id "lesson-contact.structured-input" :lesson-contact-route)
   "45" (ids/fn-id "optionalinput.structured" :optional-label)})


(defbase structured-lesson-available?
  "Check the current viewer's branch creation, template Extend and Run offers."
  [lesson-id]
  (boolean
    (when-let [template (get structured-lesson-templates lesson-id)]
      (when (and (:authenticated? (principal/current))
                 (get (:graphden.packages.owned/primitive-input-types (meta (:base-fns ctx))) template))
        (cr/record-effect! :db)
        (let [storage (request/require-storage ctx)]
          (try
            (writer/assert-write-authorized! (vs/unwrap storage) :branch
                                             {:base-branch-id (vs/current-branch-id storage)} nil)
            (let [{:keys [status run-available extend-available] :as view}
                  (input/read-input storage template true ctx)]
              (and (= :available status) (true? run-available) (true? extend-available)
                   (contains? view :extend-namespace-id) (nil? (:extend-namespace-id view))))
            (catch clojure.lang.ExceptionInfo error
              (case (:type (ex-data error))
                (:authz/forbidden :authz/branch-protected :branch/merge-required) false
                (throw error)))))))))


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
  ;; `:state`, not `:io`: a bump of an in-process aggregate counter is the
  ;; same effect class as `:swap` / `:reset` — and `:state` is inside the
  ;; cloud's request-level gate, so the demo session (the population the
  ;; funnel exists to measure) can actually record its progress.
  (cr/record-effect! :state)
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
  (cr/record-effect! :state)
  (let [id (lesson-id lesson)
        n (some-> step str parse-long)]
    (when (and id n (<= 0 n 99))
      (let [k (keyword (str "tour-step-" id "-" n))]
        (counters/count! k)
        (str k)))))


(def impls
  {:_structured-lesson-available? {:impl structured-lesson-available?}
   :count-tour-event! {:impl count-tour-event! :taint-propagate? true}
   :count-tour-step! {:impl count-tour-step! :taint-propagate? true}})
