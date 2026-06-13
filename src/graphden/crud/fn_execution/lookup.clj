(ns graphden.crud.fn-execution.lookup
  "Read-only helpers for the /api/execute pipeline — resolve fn-id /
   fn-version-id from the request shape, look up the free-arg slot
   map for a fn. No DB writes happen here.

   Extracted from `graphden.crud.fn-execution` so the
   parse/validate/apply orchestrator can stay focused on policy."
  (:require
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


(defn resolve-fn-version-id
  "Find the current `:fn-version-id` for logical `fn-id` on the active
   branch. Walks the branch chain (+ branch-merge records) via the
   versioned-storage resolver, so a fn inherited from a parent
   branch without an own override on the current branch correctly
   resolves to the parent's version. Returns nil when the fn has no
   version row visible on this branch (shouldn't happen for any
   loaded fn — every create goes through the versioned-storage
   decorator).

   Pre-fix this filtered by `:branch-id` direct-match only, so
   inherited-from-main fns on branch X returned nil — every
   fn-execution write on a non-creator branch then had a nil
   version-id, breaking history filtering."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)]
    (when (vs/versioned-storage? storage)
      (let [base (vs/unwrap storage)
            branch-id (vs/current-branch-id storage)]
        ;; Chain cache is now process-wide (`global-chain-cache`);
        ;; no per-call binding needed.
        (:id (res/resolve-version base :fn fn-id branch-id))))))


(defn query-fn-by-name
  "Storage schemas vary on whether `fn.name` is stored as text or as
   an enum (the package-loader codec roundtrip is sometimes one,
   sometimes the other). Try both shapes; swallow the
   validation-error/type-mismatch from the side that doesn't fit.

   `swallow-all?` — when true, swallow ANY ExceptionInfo (used by
   seeder paths that just want to try a name and skip on any
   failure). Defaults to swallowing only `:validation-error/type-
   mismatch` — re-raise other errors so genuine storage failures
   don't get silently dropped."
  ([storage fn-name] (query-fn-by-name storage fn-name false))
  ([storage fn-name swallow-all?]
   (letfn [(try-one
             [v]
             (try (first (sp/query-entities storage :fn {:name v}))
                  (catch clojure.lang.ExceptionInfo e
                    (when-not (or swallow-all?
                                  (= :validation-error/type-mismatch
                                     (:type (ex-data e))))
                      (throw e))
                    nil)))]
     (or (try-one fn-name)
         (try-one (keyword fn-name))))))


(defn query-fn-id-by-name
  "Like `query-fn-by-name`, but returns just the fn `:id` (a UUID) or
   nil. Used by seeder / branch-router paths that only need the id."
  ([storage fn-name] (query-fn-id-by-name storage fn-name false))
  ([storage fn-name swallow-all?]
   (some-> (query-fn-by-name storage fn-name swallow-all?) :id)))


(defn resolve-fn-id
  "Translate `{:fn-id … OR :fn-name …}` request shape into a fn-id.
   Returns the UUID or nil."
  [storage parsed]
  (cond
    (:fn-id parsed)   (:fn-id parsed)
    (:fn-name parsed) (some-> (query-fn-by-name storage (:fn-name parsed)) :id)
    :else nil))


(defn resolve-fn
  "Like `resolve-fn-id` but returns the full :fn row in a single
   storage round-trip. Use this when the caller needs both `:id` AND
   `:name` (or other columns) — saves the extra `read-entity` you'd
   otherwise chain after `resolve-fn-id`. Returns nil if neither
   identifier resolves."
  [storage parsed]
  (cond
    (:fn-id parsed)   (sp/read-entity storage :fn (:fn-id parsed))
    (:fn-name parsed) (query-fn-by-name storage (:fn-name parsed))
    :else nil))


(defn- collect-reachable-graph
  "BFS from `fn-id` over parent-ids + ref-fn-id edges, loading only
   the rows that are actually reachable. Pre-fix this loaded the
   ENTIRE :slot / :binding / :binding-list-item / :fn-slot tables on
   every /api/execute request — for a project with thousands of
   unrelated fns under a single root, that's ~10000× the rows we
   actually need.

   The BFS converges in 1–3 iterations for typical fns because most
   fn-graphs reach only a small connected component."
  [storage root-fn-id]
  (loop [seen #{}
         frontier #{root-fn-id}
         fn-rows []
         fn-slots []
         bindings []
         list-items []]
    (if (empty? frontier)
      ;; Closure complete — fetch the slot rows for everything we
      ;; collected in one final batch.
      (let [slot-ids (into #{} (map :slot-id) fn-slots)
            slot-rows (if (empty? slot-ids)
                        {}
                        (sp/read-entities storage :slot (vec slot-ids)))]
        {:fns-by-id (into {} (map (juxt :id identity)) fn-rows)
         :slots-by-id (into {} (map (juxt :id identity)) (vals slot-rows))
         :all-bindings bindings
         :all-list-items list-items
         :all-fn-slots fn-slots})
      (let [front-vec (vec frontier)
            ;; One round-trip per entity type, narrowed by frontier.
            new-fns (sp/query-entities storage :fn {:id front-vec})
            new-fn-slots (sp/query-entities storage :fn-slot {:fn-id front-vec})
            new-bindings (sp/query-entities storage :binding {:fn-id front-vec})
            new-binding-ids (mapv :id new-bindings)
            new-list-items (if (empty? new-binding-ids)
                             []
                             (sp/query-entities storage :binding-list-item
                                                {:binding-id new-binding-ids}))
            seen' (into seen frontier)
            ;; Fns reachable next level: ancestors + ref-targets.
            next-fn-ids (into #{}
                              (comp cat
                                    (remove seen')
                                    (remove nil?))
                              [(mapcat :parent-ids new-fns)
                               (keep :ref-fn-id new-bindings)
                               (keep :ref-fn-id new-list-items)])]
        (recur seen'
               next-fn-ids
               (into fn-rows new-fns)
               (into fn-slots new-fn-slots)
               (into bindings new-bindings)
               (into list-items new-list-items))))))


(defn- inheritance-chain-in-memory
  "Transitive parents of `fn-id` walked from the pre-loaded
   `fns-by-id` map. Identical semantics to the old storage-backed
   `inheritance-chain` but with zero round-trips."
  [fn-id fns-by-id]
  (loop [acc [fn-id] frontier #{fn-id}]
    (if (empty? frontier)
      acc
      (let [visited (set acc)
            next-frontier (->> frontier
                               (mapcat #(:parent-ids (get fns-by-id %)))
                               (remove visited)
                               set)]
        (recur (into acc next-frontier) next-frontier)))))


(defn- free-args-via
  "Internal: `{arg-name → slot-id}` for `fn-id`'s free args, walking
   ref-fn-id bindings transitively. `visited` guards against cycles
   (GraphConstraints forbid them already, defence-in-depth).

   `db` is the pre-loaded reachable-closure from
   `collect-reachable-graph` — every storage round-trip happened
   upfront, so the recursive resolution is pure in-memory."
  [fn-id visited db]
  (if (contains? visited fn-id)
    {}
    (let [{:keys [fns-by-id slots-by-id all-bindings all-list-items
                  all-fn-slots]} db
          visited' (conj visited fn-id)
          chain-fns (set (inheritance-chain-in-memory fn-id fns-by-id))
          chain-fn-slots (filter #(chain-fns (:fn-id %)) all-fn-slots)
          chain-bindings (filter #(chain-fns (:fn-id %)) all-bindings)
          chain-binding-ids (set (map :id chain-bindings))
          chain-list-items (filter #(chain-binding-ids (:binding-id %))
                                   all-list-items)
          bound-slot-ids (->> chain-bindings
                              (filter #(or (some? (:value %))
                                           (some? (:ref-fn-id %))
                                           (true? (:list-append %))))
                              (map :slot-id)
                              set)
          direct (into {}
                       (keep (fn [{:keys [slot-id]}]
                               (when-not (bound-slot-ids slot-id)
                                 (when-let [s (get slots-by-id slot-id)]
                                   [(keyword (:name s)) slot-id]))))
                       chain-fn-slots)
          ;; Recurse into every ref-fn-id reachable from chain bindings
          ;; — slot-bound refs + list-item refs. Each one is a captured
          ;; sub-graph whose still-unbound free-args propagate up as
          ;; free-args of the outer fn-def.
          ref-fids (distinct
                     (concat (keep :ref-fn-id chain-bindings)
                             (keep :ref-fn-id chain-list-items)))
          transitive (reduce (fn [acc rfid]
                               (merge acc (free-args-via rfid visited' db)))
                             {}
                             ref-fids)
          ;; A slot bound at THIS level removes that slot from the
          ;; combined free-arg map — both direct and transitive. Lets
          ;; a derived fn-def bind a captured arg (e.g.
          ;; `:my-cron :args {:cron …}`) and have it disappear.
          combined (merge transitive direct)]
      (into {}
            (remove (fn [[_ sid]] (bound-slot-ids sid)))
            combined))))


(defn free-arg-slot-map
  "Return `{arg-name → slot-id}` for `fn-id`'s free args.

   Includes:
   1. Slots in the inheritance chain that have no value/ref/list
      binding at any chain level (direct free args, existing semantics).
   2. Transitive free args of fn-graphs reachable via ref-fn-id
      bindings — both slot-bound refs and binding-list-item refs. The
      referenced fn-graph's still-unbound free-args surface as
      captured-args of the outer fn-def. NEW (closure-capture
      commit 2/6).

   A slot whose id appears in any chain-level binding is removed from
   the combined map — that's how `:my-cron :args {:cron …}` makes
   `:cron` disappear from `:my-cron`'s free-arg map despite `:cron`
   being a captured arg inherited from `:schedule`.

   See `docs/CLOSURE_CAPTURE.md` § Implementation Contract for the
   semantics. Subsequent commits layer wrap-time capture in
   `hof-callable` (3) and type-checker propagation (4) on top."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)
        db (collect-reachable-graph storage fn-id)]
    (free-args-via fn-id #{} db)))
