(ns graphden.types.diagnostics
  "Per-branch, in-memory store of structured type-check diagnostics
   (ROADMAP Block 3 \"Error Tolerance\", Phase 1).

   DERIVED state only: every entry is recomputed by a type-check run
   (the crud post-mutation guard, the package sync sweep) and NEVER
   persisted — the roadmap forbids stored validity, because a stored
   flag can silently drift from the graph it describes. Losing the
   store (restart) loses nothing: the next check re-records.

   Shape: `{branch-id {fn-id [diagnostic …]}}` where `branch-id` may
   be nil (= default branch / no branch-router context). Per-branch
   because an fn can be valid on main and broken on a feature branch —
   version rows differ per branch, so validity does too.

   A `diagnostic` is a small EDN map — the cleaned ex-data of a
   `:types/check-failed` throw (see `from-ex`): `:message` plus
   whichever structured keys the checker stamped (`:expected`,
   `:actual`, `:arg-name`, …).

   Isolation: mirrors `executor.registry/*registry-override*` —
   reads/writes go through `(or *diagnostics-override* root)`, and the
   kaocha parallel plugin binds the override to a fresh `{}`-seeded
   atom per NS-thread. Deliberately dependency-free: crud, packages
   sync, and (later) editor partial impls all read it.")


(defonce ^:private diagnostics-store
  ;; {branch-id {fn-id [diagnostic …]}}
  (atom {}))


(def ^:dynamic *diagnostics-override*
  "Parallel-test isolation seam — when bound (to an atom), all reads
   and writes land on it instead of the process-global store. Seeded
   `{}` by the kaocha parallel plugin (an empty store is the correct
   fresh-thread state — entries are derived, not configuration)."
  nil)


(defn- target-atom
  []
  (or *diagnostics-override* diagnostics-store))


(defn snapshot-for-isolation
  "Snapshot of the active store — for fixtures that want to seed an
   override from the current state instead of `{}`."
  []
  @(target-atom))


(def ^:private diagnostic-keys
  "The meaningful structured keys a `:types/check-failed` (or other
   typed crud failure) carries in its ex-data. `:source-file` /
   `:source-line` are the flattened `*source-info*` stamp."
  [:type :fn-name :parent-name :arg-name :binding :expected :actual
   :reason :constraint :source-file :source-line :source-info])


(defn from-ex
  "Cleaned diagnostic map from an `ExceptionInfo`: `:message` (the
   human-readable string) + the meaningful ex-data keys, nils pruned.
   Never returns nil/empty — a message-only map is the floor."
  [e]
  (let [cleaned (into {}
                      (remove (comp nil? val))
                      (select-keys (ex-data e) diagnostic-keys))]
    (assoc cleaned :message (str (ex-message e)))))


(defn clear-fn!
  "Drop `fn-id`'s entry under `branch-id` (and the branch key itself
   when it empties)."
  [branch-id fn-id]
  (swap! (target-atom)
         (fn [m]
           (let [m' (update m branch-id dissoc fn-id)]
             (if (empty? (get m' branch-id))
               (dissoc m' branch-id)
               m'))))
  nil)


(defn record!
  "Record `diags` (a seq of diagnostic maps) for `fn-id` under
   `branch-id`. Empty/nil `diags` clears the fn's entry — so a
   check-then-record loop needs no success/failure branching."
  [branch-id fn-id diags]
  (if (seq diags)
    (swap! (target-atom) assoc-in [branch-id fn-id] (vec diags))
    (clear-fn! branch-id fn-id))
  nil)


(defn clear-branch!
  "Drop every entry under `branch-id` — branch delete / post-merge
   invalidation (the target's entries are stale once source versions
   surface on it; the next check re-records survivors)."
  [branch-id]
  (swap! (target-atom) dissoc branch-id)
  nil)


(defn errors-for-fn
  "The diagnostics vector recorded for `fn-id` on `branch-id`, or nil."
  [branch-id fn-id]
  (get-in @(target-atom) [branch-id fn-id]))


(defn branch-errors
  "Map of `{fn-id [diagnostic …]}` for `branch-id` (empty map when none)."
  [branch-id]
  (get @(target-atom) branch-id {}))


(defn error-count
  "Total diagnostics recorded on `branch-id` (across all fns)."
  [branch-id]
  (reduce + 0 (map count (vals (branch-errors branch-id)))))
