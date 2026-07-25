(ns graphden.storage.postgres.graph-epoch
  "The graph EPOCH — a Postgres sequence bumped BEFORE every
   graph-shaped write, read lazily by the branch-router to validate
   its cached per-branch contexts.

   WHY (audit-6): compiled-registry freshness used to depend entirely
   on imperative post-commit steps — the writer's eager invalidate on
   its own request thread plus a best-effort cross-pod NOTIFY. A
   client abort interrupts the request thread between commit and
   invalidate; several write paths never emitted NOTIFY at all
   (package fork / materialize / update). Either way a pod kept
   serving pre-write compiled closures with NO self-heal. The epoch
   turns freshness into a comparison against the source of truth:
   eager invalidate + NOTIFY remain pure LATENCY optimizations (which
   is what notify.clj always claimed), and a skipped one heals on the
   next context fetch.

   Design points:
   - `nextval` BEFORE the write (bump-then-write): an interrupt
     between bump and write leaves a spurious epoch — harmless
     over-invalidation; a committed write is always preceded by a
     visible bump. No transaction coupling needed; sequences are
     lock-free under concurrent writers, and a rolled-back write's
     bump is equally harmless.
   - Scope: graph-shaped entities only (`graph-epoch-entities`).
     Executions / services / grants write constantly in normal
     serving and must not churn the epoch.
   - The storage handle remembers ITS OWN last bump (`:graph-epoch-last`
     atom, assoc'd by `attach-state`). Eager invalidation paths mark
     the router's validated watermark with that exact value — never a
     fresh read — so a concurrent sibling's bump can't be skipped past.
   - Degradation: no pool, or the sequence missing (a cleaned but
     not-yet-initialized test DB) → warn-once no-op. Over-invalidation
     safe, never wrong."
  (:require
    [clojure.tools.logging :as log]
    [next.jdbc :as jdbc]))


(def sequence-name "graphden_graph_epoch")


(def graph-epoch-entities
  "Entities whose writes change what a compiled registry would produce.
   Superset of crud's `fn-graph-entity-types`: `:ns` (names/paths feed
   compilation), `:branch` (delete must invalidate the cached ctx) and
   `:branch-merge` (merge changes every resolved read on the target)."
  #{:fn :slot :fn-slot :binding :binding-list-item
    :ns :branch :branch-merge})


(defn ensure-sequence!
  "CREATE SEQUENCE IF NOT EXISTS — hooked into schema initialization."
  [ds]
  (jdbc/execute! ds [(str "CREATE SEQUENCE IF NOT EXISTS " sequence-name)]))


(defn attach-state
  "Give a storage handle its own epoch LEDGER: a sorted-map of every
   locally-bumped value → {:at ms :noted? bool}, plus a sorted-set of
   foreign epochs covered via NOTIFY. The router's validation
   classifies each epoch in (watermark, global] against these — the
   scalar max-advance watermark of the first design silently skipped
   past interleaved foreign epochs whose NOTIFY was lost (audit-7
   FINDING 1). Harmless on non-Postgres handles (structures stay
   empty)."
  [storage]
  (assoc storage
         :graph-epoch-local (atom (sorted-map))
         :graph-epoch-covered (atom (sorted-set))))


(def ^:dynamic *request-bump-log*
  "Per-REQUEST log of the exact bump values this request produced —
   bound by the HTTP boundary (web/http `http-server`), read+cleared
   by the eager-invalidation tail's `note!`. Request-scoped binding
   (not a ThreadLocal): an aborted request unwinds the binding, its
   bumps stay un-noted in the ledger, and the grace-expiry heal covers
   them — a reused pool thread can never mark a dead request's bumps
   as applied. nil outside a request (background writers never note;
   their bumps heal after grace, which for boot-time writers is
   absorbed by the router's creation-time watermark seed)."
  nil)


(def ^:private degraded-warned (atom false))


(defn- warn-once
  [e]
  (when (compare-and-set! degraded-warned false true)
    (log/warn e (str "graph-epoch degraded to no-op — invalidation "
                     "self-heal is OFF until the sequence exists "
                     "(schema init creates it)"))))


(defn bump!
  "nextval BEFORE a graph-shaped write; remembers the value on the
   handle. No-op (nil) for non-graph entities, pool-less handles, or a
   missing sequence."
  [storage entity-name]
  (when (contains? graph-epoch-entities entity-name)
    (when-let [pool (:pool storage)]
      (try
        ;; single-column row; take the value positionally — next.jdbc
        ;; qualifies column keys by relation, so keyword access is
        ;; brittle across the two query shapes here.
        (let [v (some-> (jdbc/execute-one!
                          pool [(str "SELECT nextval('" sequence-name "')")])
                        vals first)]
          (when (and v (:graph-epoch-local storage))
            (swap! (:graph-epoch-local storage)
                   assoc v {:at (System/currentTimeMillis) :noted? false})
            (when *request-bump-log*
              (swap! *request-bump-log* conj v)))
          v)
        (catch Exception e (warn-once e) nil)))))


(defn note-applied!
  "Mark bump values as APPLIED (their eager invalidation completed).
   With explicit `vs`, marks those; without, drains the request's
   `*request-bump-log*`. Never touches the watermark — advancing is
   the validator's job, and only when the whole (watermark, global]
   range is accounted for."
  ([storage]
   (when *request-bump-log*
     (let [vs @*request-bump-log*]
       (reset! *request-bump-log* [])
       (note-applied! storage vs))))
  ([storage vs]
   (when-let [ledger (:graph-epoch-local storage)]
     (when (seq vs)
       (swap! ledger
              (fn [m]
                (reduce (fn [acc v]
                          (if (contains? acc v)
                            (assoc-in acc [v :noted?] true)
                            acc))
                        m vs)))))))


(defn cover-foreign!
  "Mark foreign epochs as covered — a sibling's NOTIFY carried the
   writer's bump values and the delta was applied locally."
  [storage vs]
  (when-let [covered (:graph-epoch-covered storage)]
    (when (seq vs)
      (swap! covered into vs))))


(defn classify-range
  "For each epoch in (w, global]: `:foreign` (not ours, not covered —
   heal now), `:aborted` (ours, un-noted, older than `grace-ms` —
   eager path died, heal now), `:pending` (ours, un-noted, young —
   eager in flight, wait), `:applied` (ours-noted or covered).
   Returns the set of statuses present. Ranges wider than 512 return
   #{:foreign} outright — one coarse heal beats walking an unbounded
   gap."
  [storage w global grace-ms]
  (if (> (- global w) 512)
    #{:foreign}
    (let [local @(:graph-epoch-local storage)
          covered @(:graph-epoch-covered storage)
          now (System/currentTimeMillis)]
      (into #{}
            (map (fn [e]
                   (if-let [{:keys [at noted?]} (get local e)]
                     (cond noted? :applied
                           (> (- now at) grace-ms) :aborted
                           :else :pending)
                     (if (contains? covered e) :applied :foreign))))
            (range (inc w) (inc global))))))


(defn prune!
  "Drop ledger + covered entries ≤ the advanced watermark."
  [storage w]
  (when-let [ledger (:graph-epoch-local storage)]
    (swap! ledger (fn [m] (into (sorted-map) (subseq m > w)))))
  (when-let [covered (:graph-epoch-covered storage)]
    (swap! covered (fn [s] (into (sorted-set) (subseq s > w))))))


(defn current
  "The global epoch (sequence `last_value`, no bump). nil on a
   pool-less handle or missing sequence — callers treat nil as
   'cannot validate, skip healing'."
  [storage]
  (when-let [pool (:pool storage)]
    (try
      (some-> (jdbc/execute-one!
                pool [(str "SELECT last_value FROM " sequence-name)])
              vals first)
      (catch Exception e (warn-once e) nil))))
