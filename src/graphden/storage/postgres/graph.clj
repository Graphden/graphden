(ns graphden.storage.postgres.graph
  "ExecutionGraph resolution for PostgreSQL — recursive-CTE seed, then
   bulk-load.

   The generic BFS in `storage.protocol.generic-graph` issues 4
   queries per fn-node visited (fn / fn-slot / binding / per-binding
   items), so a graph of N reachable fns costs ~4N round-trips. For
   anything but tiny graphs this dominates `read-graph` latency, and
   `read-graph` runs on every `invalidate-graph-cache!` (every
   mutation through CRUD).

   Strategy here:

     1. ONE recursive CTE walks every outgoing edge from the root in
        a single SQL statement and returns the set of reachable
        `fn.id`s. The traversal mirrors `process-fn-node`'s BFS:
          - `fn_parent_ids` junction               (parent inheritance)
          - `fn.base_fn_id`                        (refinement)
          - `fn.element_fn_id`                     (list-type)
          - `fn.return_type_fn_id`                 (declared return)
          - `binding.ref_fn_id`                    (call-site refs)
          - `binding.type_override_fn_id`          (per-binding type)
          - `binding_list_item.ref_fn_id`          (sequence refs)
        Slot.type-fn-id is intentionally NOT chased here — the
        generic BFS doesn't either; both backends compensate by
        bulk-loading all slot rows at the end.

     2. With the reachable id-set in hand, every entity table is
        loaded with a single `WHERE id IN (…)` (or `fn_id IN (…)` /
        `binding_id IN (…)`) query — five queries total instead of
        ~4N. Decoding goes through `sp/query-entities` so codec /
        junction / field-spec handling stays identical to the rest
        of CRUD.

   Net: O(1) round-trips against the database for any graph size,
   plus one round-trip per ref-many junction (fn.parent-ids only)."
  (:require
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-graph :as generic]
    [graphden.storage.protocol.graph :as graph]
    [next.jdbc :as jdbc]))


(def ^:private reachable-fns-sql
  "Recursive CTE that, given a seed fn-id, returns every fn-id
   reachable through the execution-graph edges. The recursive term
   uses a `LATERAL` subquery so each iteration of the recursion
   evaluates ALL outgoing-edge sources for the row it expanded —
   the alternative (one UNION arm per source, each rejoining
   `reachable`) is harder to read and Postgres plans it the same.

   `?::uuid` is the seed parameter. `DISTINCT` at the end collapses
   the multi-source duplicates the recursion produces (e.g. the
   same fn reached via parent and via a ref binding)."
  (str
    "WITH RECURSIVE reachable(fn_id) AS ("
    "  SELECT ?::uuid"
    "  UNION"
    "  SELECT child.fn_id"
    "  FROM reachable r"
    "  CROSS JOIN LATERAL ("
    ;;     parent-ids junction
    "    SELECT j.target_id AS fn_id"
    "      FROM fn_parent_ids j WHERE j.owner_id = r.fn_id"
    "    UNION ALL"
    ;;     fn-row scalar FKs
    "    SELECT f.base_fn_id"
    "      FROM fn f WHERE f.id = r.fn_id AND f.base_fn_id IS NOT NULL"
    "    UNION ALL"
    "    SELECT f.element_fn_id"
    "      FROM fn f WHERE f.id = r.fn_id AND f.element_fn_id IS NOT NULL"
    "    UNION ALL"
    "    SELECT f.return_type_fn_id"
    "      FROM fn f WHERE f.id = r.fn_id AND f.return_type_fn_id IS NOT NULL"
    "    UNION ALL"
    ;;     binding outgoing refs (ref + type-override)
    "    SELECT b.ref_fn_id"
    "      FROM binding b"
    "      WHERE b.fn_id = r.fn_id AND b.ref_fn_id IS NOT NULL"
    "    UNION ALL"
    "    SELECT b.type_override_fn_id"
    "      FROM binding b"
    "      WHERE b.fn_id = r.fn_id AND b.type_override_fn_id IS NOT NULL"
    "    UNION ALL"
    ;;     binding-list-item refs (sequence elements)
    "    SELECT bli.ref_fn_id"
    "      FROM binding_list_item bli"
    "      JOIN binding b ON bli.binding_id = b.id"
    "      WHERE b.fn_id = r.fn_id AND bli.ref_fn_id IS NOT NULL"
    "  ) child"
    "  WHERE child.fn_id IS NOT NULL"
    ")"
    " SELECT DISTINCT fn_id FROM reachable"))


(defn- reachable-fn-ids
  "Run the recursive CTE and return the set of UUIDs reachable from
   `seed-id`. `seed-id` is required to exist in the `fn` table —
   the caller guards via `read-entity` first to keep the not-found
   error path identical to `generic/resolve-execution-graph`."
  [ds seed-id]
  (let [rows (util/with-sql-error-handling
               "Database error" :resolve-execution-graph
               {:fn-id seed-id}
               (jdbc/execute! ds [reachable-fns-sql seed-id]
                              (util/query-opts)))]
    (into #{} (keep :fn_id) rows)))


(defn resolve-execution-graph
  "PostgreSQL-optimised resolver. Two phases:

     1. CTE walk → set of reachable fn-ids.
     2. Bulk-load fn / fn-slot / binding / binding-list-item via
        `sp/query-entities` with `{:id [...]}` (or `{:fn-id [...]}` /
        `{:binding-id [...]}`) IN-clauses. Slot rows are loaded in
        bulk too — matches the generic resolver's behaviour. Codec /
        junction / field-spec handling all flow through the standard
        CRUD path so the returned `ExecutionGraphResult` is
        bit-identical to the generic version."
  [ds storage fn-id]
  (when-not (sp/read-entity storage :fn fn-id)
    (throw (ex-info "Function not found"
                    {:type :not-found
                     :fn-id fn-id})))
  (let [fn-ids (reachable-fn-ids ds fn-id)]
    (if (empty? fn-ids)
      ;; Defensive — the CTE always emits at least the seed; an
      ;; empty result implies the seed row vanished between the
      ;; existence check above and the CTE run. Fall through to the
      ;; generic resolver so the user sees the same not-found error
      ;; shape rather than an empty graph.
      (generic/resolve-execution-graph storage fn-id)
      (let [fns          (sp/query-entities storage :fn        {:id fn-ids})
            fn-slots     (sp/query-entities storage :fn-slot   {:fn-id fn-ids})
            bindings     (sp/query-entities storage :binding   {:fn-id fn-ids})
            binding-ids  (into #{} (map :id) bindings)
            list-items   (if (seq binding-ids)
                           (sp/query-entities storage :binding-list-item
                                              {:binding-id binding-ids})
                           [])
            ;; Slot rows the closure can REACH = those named by the
            ;; loaded fn-slots' :slot-id. Every downstream consumer
            ;; (lookups/build-lookups, executor/composition) looks
            ;; slots up only via `(get slot-map (:slot-id fs))` —
            ;; nothing reaches a slot outside the fn-slot junction
            ;; set, so the narrower query is sufficient. Pre-fix this
            ;; loaded EVERY slot on every recursive-CTE resolve.
            slot-ids     (into #{} (keep :slot-id) fn-slots)
            slots        (if (seq slot-ids)
                           (sp/query-entities storage :slot {:id (vec slot-ids)})
                           [])
            fns-map      (into {} (map (juxt :id identity)) fns)]
        (graph/->execution-graph
          {:fns fns-map
           :slots slots
           :fn-slots fn-slots
           :bindings bindings
           :list-items list-items})))))
