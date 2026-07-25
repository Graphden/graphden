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
  "Give a storage handle its own last-bump state: the newest bumped
   value AND when it happened (the heal grace-period reads the
   timestamp). Harmless on non-Postgres handles (atoms stay at 0)."
  [storage]
  (assoc storage
         :graph-epoch-last (atom 0)
         :graph-epoch-last-at (atom 0)))


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
          (when-let [a (:graph-epoch-last storage)]
            (swap! a max v))
          (when-let [t (:graph-epoch-last-at storage)]
            (reset! t (System/currentTimeMillis)))
          v)
        (catch Exception e (warn-once e) nil)))))


(defn last-bumped
  "The newest epoch THIS handle's writes produced — the value eager
   invalidation paths mark as validated. 0 when nothing was bumped."
  [storage]
  (or (some-> (:graph-epoch-last storage) deref) 0))


(defn last-bumped-at
  "Wall-clock ms of this handle's newest bump. The router's heal skips
   while a LOCAL write is inside its grace window: the eager
   invalidation for that write is normally still in flight, and
   healing over it would full-clear on every write of a busy suite
   (the e2e gate demonstrated exactly that). An aborted eager path
   simply heals after the grace expires."
  [storage]
  (or (some-> (:graph-epoch-last-at storage) deref) 0))


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
