(ns graphden.integration.test-helpers
  "Shared helpers for integration tests that synthesize derived
   fn-defs in storage. Centralizes the slot-lookup helper that
   `cron-schedule-runtime-test` and `cron-schedule-service-test`
   both need to build admin-style bindings to a slot owned by an
   ancestor of the derived fn (not by the derived fn itself)."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn slot-by-owner-name
  "Find the slot owned by `owner-fn-name` whose name matches
   `slot-name`. Used by cron service tests to resolve the captured-
   arg slots before creating an admin-side binding row:
     - `:cron` lives on `:cron-next-after`
     - `:fn` is the rename slot owned by `:_fire-target` (its
       `:source-slot-id` points at `:call-noargs :func`)
   Both slots are OUTSIDE the derived fn's inheritance chain — they
   surface only via closure-capture (docs/CLOSURE_CAPTURE.md), so
   the binding row goes onto the derived fn but the slot-id comes
   from the owner. Returns nil when no slot matches."
  [storage owner-fn-name slot-name]
  (let [owner (first (sp/query-entities storage :fn {:name owner-fn-name}))
        owner-id (:id owner)
        junctions (sp/query-entities storage :fn-slot {:fn-id owner-id})
        slots-by-id (into {} (map (juxt :id identity))
                          (sp/query-entities storage :slot {}))]
    (some (fn [j]
            (when-let [s (get slots-by-id (:slot-id j))]
              (when (= slot-name (:name s)) s)))
          junctions)))
