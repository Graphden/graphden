(ns graphden.fleet.metrics
  "Weight / load signals for the placement controller (docs/FLEET_RFC.md §6.3).

   A cell's WEIGHT is what the controller packs and rebalances by. It combines a
   mostly-static STRUCTURAL cost (`fn-count` — the size of the cell's forward-
   closure, i.e. how many fns must be compiled to serve the root) with a live
   LOAD signal (`org-pending-load` — the org's in-flight executions). Phase 1
   only COLLECTS these; the packer + hysteresis that consume them are Phase 2.

   Everything here is a pure read over data the executor already keeps — the
   `:forward-deps` index (`load-cell!` walks the same one) and the shared
   `:fn-execution` table (the per-org concurrency quota counts the same rows).
   No new state, no new counters."
  (:require
    [graphden.executor.compile.deps :as deps]
    [graphden.storage.protocol.core :as sp]))


(def default-weights
  "`weight = w-fn-count·fn-count + w-load·load`. fn-count and the pending-
   execution count both live on a small-integer scale, so equal weights are a
   sane default; a deployment overrides them (e.g. weight load higher when CPU,
   not memory, is the binding constraint)."
  {:w-fn-count 1.0 :w-load 1.0})


(defn cell-fn-count
  "How many fns the cell rooted at `root-fn-id` compiles — its structural
   weight. `forward-deps` is the `{fn-id → #{deps}}` index from
   `deps/build-deps-state` (the same index `load-cell!` walks), so this is the
   exact set `load-cell!` would compile. A root with no forward edges is a
   one-fn cell (count 1)."
  [forward-deps root-fn-id]
  (count (deps/forward-closure forward-deps [root-fn-id])))


(defn org-pending-load
  "Live load for `org`: the count of non-terminal (`:pending`) `:fn-execution`
   rows. This is the SAME fleet-wide counter the per-org concurrency quota reads
   (`crud.fn-execution.persist/over-fleet-org-cap?`) — the shared table is the
   source of truth, so any pod sees the org's whole in-flight load, not just its
   own. The pending set is bounded by that quota (~32/org), so the scan is
   small; `:fn-execution` is non-versioned, so the query hits real rows."
  [storage org]
  (count (sp/query-entities storage :fn-execution {:org-id org :status :pending})))


(defn cell-weight
  "Fold structural cost + live load into the single scalar the packer orders by.

   `load` is per-ORG (all of an org's cells share it), so Phase 1 attributes the
   whole org load to each cell. That is exact for the common single-cell-per-org
   deployment (an org app is one cell today, docs/FLEET_RFC.md §12) and
   conservative otherwise — a busier org's cells simply sort heavier. Per-cell
   load attribution is a Phase 2 refinement, gated on multi-cell orgs proving
   common."
  ([forward-deps storage org root-fn-id]
   (cell-weight forward-deps storage org root-fn-id default-weights))
  ([forward-deps storage org root-fn-id {:keys [w-fn-count w-load]}]
   (+ (* w-fn-count (cell-fn-count forward-deps root-fn-id))
      (* w-load (org-pending-load storage org)))))
