(ns graphden.schema.placement.schema
  "Fleet placement schema — the `:placement` entity (docs/FLEET_RFC.md §6.1).

   A `:placement` row is the live routing map the fleet's control plane owns:
   `(org, entry-fn-id) → executor-id`, so a request for an org's cell can be
   sent to the executor that currently HOLDS it (rather than 421'd). `:epoch`
   is bumped on every move so a move is atomic to readers — a router caches the
   map and only trusts a row whose epoch it hasn't superseded.

   Control-plane state, NOT graph state: like `:service`, it's desired/current
   operational data, so it is NON-VERSIONED (mutates in place; the versioned-
   storage decorator passes writes straight through, and it never appears in
   `versioning.storage.resolution/entity-config`). `:entry-fn-id` is a logical
   `:ref :fn` (the cell root), not a frozen version — placement follows the
   live graph.

   Populated MANUALLY in Phase 0 (docs/FLEET_RFC.md §8); the placement
   controller writes it in Phase 2. The router (`internal forward-hop`) reads
   it to find a cell's holder."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


;; =============================================================================
;; Field UUIDs — :placement
;; =============================================================================

(def ^:private placement-entity-uuid
  #uuid "1500e37c-0cd4-4636-b992-99b802c91b6c")


(def ^:private placement-org-field-uuid
  #uuid "e3747a57-88a3-4374-9129-e7ac843dcfe3")


(def ^:private placement-entry-fn-id-field-uuid
  #uuid "bda23d34-72fb-4b82-a1a2-f3048ed0e88f")


(def ^:private placement-executor-id-field-uuid
  #uuid "3f2ed574-f220-4bd3-87d5-e0b0d44e3d78")


(def ^:private placement-epoch-field-uuid
  #uuid "771a2a91-2ab1-4f00-88bf-6e8dc9bf307e")


;; =============================================================================
;; Schema
;; =============================================================================

(defn extend-builder
  "Extend a schema builder with the `:placement` entity. Chain after the graph
   schema (refs `:fn`). Non-versioned — control-plane state that mutates in
   place, same as `:service` / `:fn-execution`.

   Fields:
   - `:org`          org slug this placement is for (nil ⇒ a platform/public
                     cell every executor holds). Text, matches
                     `tenancy.context/current-org`.
   - `:entry-fn-id`  the cell root fn (logical `:ref :fn`).
   - `:executor-id`  which executor currently holds the cell.
   - `:epoch`        bumped on every move; readers trust the highest epoch."
  [builder]
  (ds/add-entity builder :placement placement-entity-uuid
                 {:org {:uuid placement-org-field-uuid
                        :type :text
                        :nullable? true}
                  :entry-fn-id {:uuid placement-entry-fn-id-field-uuid
                                :type :ref
                                :ref-entity :fn}
                  :executor-id {:uuid placement-executor-id-field-uuid
                                :type :text}
                  :epoch {:uuid placement-epoch-field-uuid
                          :type :int}}))
