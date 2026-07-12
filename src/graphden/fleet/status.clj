(ns graphden.fleet.status
  "Read-only fleet observability (docs/FLEET_RFC.md §6). A snapshot of the
   placement map + per-executor load, so an operator can SEE what the fleet is
   doing without going to Postgres. Served behind the same internal-token gate
   as the cell commands (see `fleet.command`); pure reads over `:placement` and
   the cells the controller manages."
  (:require
    [graphden.fleet.control-loop :as cl]
    [graphden.fleet.packer :as packer]))


(defn fleet-status
  "Snapshot for `GET /internal/fleet/status`. `self-id` is this pod's
   executor-id. Returns

     {:executor-id <self>
      :placements [{:org <slug> :entry-fn-id <uuid-str> :executor-id <holder>} …]
      :loads {<executor-id> <weight>}}

   `:loads` is best-effort — if the `:forward-deps` index isn't primed yet a
   cell's structural weight falls back to 1, so treat it as advisory."
  [ctx self-id]
  (let [storage (:storage ctx)
        forward-deps (:forward-deps (some-> (:compile-deps ctx) deref))
        current (cl/current-placement storage)
        cells (cl/discover-cells storage forward-deps)
        executors (into (sorted-set) (vals current))]
    {:executor-id self-id
     :placements (mapv (fn [[[org entry] holder]]
                         {:org org :entry-fn-id (str entry) :executor-id holder})
                       (sort-by key current))
     :loads (packer/loads-of cells current executors)}))
