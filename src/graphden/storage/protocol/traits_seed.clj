(ns graphden.storage.protocol.traits-seed
  "Seeds the well-known trait rows. Lives in the STORAGE layer — the
   effect (writing rows) does not belong in the schema-definition
   layer, whose previous `seed-traits!` had to hide the upward
   dependency behind `requiring-resolve`. Schema (bottom layer) stays
   pure data; callers (system init, merge bootstrap) require this ns."
  (:require
    [graphden.schema.traits.schema :as traits]
    [graphden.storage.protocol.core :as sp]))


(defn seed-traits!
  "Idempotently create the built-in `merge-protected` trait row."
  [storage]
  (when-not (sp/read-entity storage :trait traits/merge-protected-trait-uuid)
    (sp/create-entity storage :trait
                      {:id traits/merge-protected-trait-uuid
                       :name "merge-protected"
                       :description "Value will not be transferred during branch merge"})))
