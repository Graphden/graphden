(ns graphden.fleet.placement
  "Read/write the fleet placement map (docs/FLEET_RFC.md §6.1): which executor
   currently holds each org's cell, keyed by `(org, entry-fn-id)` with an
   `:epoch` that a move bumps so a cached reader can tell it's stale. Thin
   wrappers over the `:placement` entity — the router reads `executor-for`, the
   controller (or, in Phase 0, a human) writes `assign!`."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn placement-for
  "The current `:placement` row for `(org, entry-fn-id)`, or nil if unplaced.
   Highest `:epoch` wins — defensive against a stale duplicate a partial move
   might leave behind."
  [storage org entry-fn-id]
  (->> (sp/query-entities storage :placement {:org org :entry-fn-id entry-fn-id})
       (sort-by :epoch >)
       first))


(defn executor-for
  "The executor-id currently holding `(org, entry-fn-id)`, or nil if unplaced."
  [storage org entry-fn-id]
  (:executor-id (placement-for storage org entry-fn-id)))


(defn assign!
  "Place `(org, entry-fn-id)` on `executor-id` at `epoch`. Upserts on the key:
   a MOVE updates the existing row (bumping the epoch + swapping the executor),
   an initial placement creates it. Returns the row."
  [storage {:keys [org entry-fn-id executor-id epoch]}]
  (if-let [existing (placement-for storage org entry-fn-id)]
    (sp/update-entity storage :placement (:id existing)
                      {:executor-id executor-id :epoch epoch})
    (sp/create-entity storage :placement
                      {:org org
                       :entry-fn-id entry-fn-id
                       :executor-id executor-id
                       :epoch epoch})))
