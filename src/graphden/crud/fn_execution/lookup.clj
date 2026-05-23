(ns graphden.crud.fn-execution.lookup
  "Read-only helpers for the /api/execute pipeline — resolve fn-id /
   fn-version-id from the request shape, look up the free-arg slot
   map for a fn. No DB writes happen here.

   Extracted from `graphden.crud.fn-execution` so the
   parse/validate/apply orchestrator can stay focused on policy."
  (:require
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn resolve-fn-version-id
  "Find the current `:fn-version-id` for logical `fn-id` on the active
   branch. Latest version by `:created-at`. Returns nil when the fn
   has no version row (shouldn't happen for any loaded fn — every
   create goes through the versioned-storage decorator)."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)
        branch-id (when (vs/versioned-storage? storage)
                    (vs/current-branch-id storage))
        query (cond-> {:fn-id fn-id}
                branch-id (assoc :branch-id branch-id))
        versions (sp/query-entities storage :fn-version query)]
    (->> versions
         (sort-by :created-at)
         last
         :id)))


(defn query-fn-by-name
  "Storage schemas vary on whether `fn.name` is stored as text or as
   an enum (the package-loader codec roundtrip is sometimes one,
   sometimes the other). Try both shapes; swallow the
   validation-error/type-mismatch from the side that doesn't fit."
  [storage fn-name]
  (letfn [(try-one
            [v]
            (try (first (sp/query-entities storage :fn {:name v}))
                 (catch clojure.lang.ExceptionInfo e
                   (when-not (= :validation-error/type-mismatch
                                (:type (ex-data e)))
                     (throw e))
                   nil)))]
    (or (try-one fn-name)
        (try-one (keyword fn-name)))))


(defn resolve-fn-id
  "Translate `{:fn-id … OR :fn-name …}` request shape into a fn-id.
   Returns the UUID or nil."
  [storage parsed]
  (cond
    (:fn-id parsed)   (:fn-id parsed)
    (:fn-name parsed) (some-> (query-fn-by-name storage (:fn-name parsed)) :id)
    :else nil))


(defn- inheritance-chain
  "Transitive parents of `fn-id`, including `fn-id` itself. BFS order."
  [storage fn-id]
  (loop [acc [fn-id] queue [fn-id]]
    (if-let [fid (first queue)]
      (let [fn-row (sp/read-entity storage :fn fid)
            pids (remove (set acc) (or (:parent-ids fn-row) []))]
        (recur (into acc pids) (into (rest queue) pids)))
      acc)))


(defn- free-args-via
  "Internal: `{arg-name → slot-id}` for `fn-id`'s free args, walking
   ref-fn-id bindings transitively. `visited` guards against cycles
   (GraphConstraints forbid them already, defence-in-depth).

   Bulk-queried entities are threaded as `db` so the recursion doesn't
   re-fetch."
  [fn-id visited storage db]
  (if (contains? visited fn-id)
    {}
    (let [{:keys [slots-by-id all-bindings all-list-items all-fn-slots]} db
          visited' (conj visited fn-id)
          chain-fns (set (inheritance-chain storage fn-id))
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
                               (merge acc (free-args-via rfid visited' storage db)))
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
        db {:slots-by-id (into {} (map (juxt :id identity))
                               (sp/query-entities storage :slot {}))
            :all-bindings (sp/query-entities storage :binding {})
            :all-list-items (sp/query-entities storage :binding-list-item {})
            :all-fn-slots (sp/query-entities storage :fn-slot {})}]
    (free-args-via fn-id #{} storage db)))
