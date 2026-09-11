(ns graphden.test-infra.storage-double
  "A read-only `StorageCRUD` stand-in over a literal `{entity-type {id
   row}}` map, for UNIT tests of code that reaches storage for a row
   or two and nothing else (the package-layer impl shims, the
   parent-closure walkers).

   It is a fixture graph, not a simulation: reads answer from the map,
   and every WRITE throws. A test that needs write semantics, version
   resolution or SQL belongs on a real container fixture instead —
   this double is deliberately too small to fake one.

   Three NSes had begun to carry their own partial `reify`, which
   clj-kondo flags as an incomplete protocol; one shared, COMPLETE
   implementation keeps them honest about what is and isn't supported."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn- matches?
  "`where` semantics the unit callers rely on: a collection value is a
   membership test (`{:id [id1 id2]}` — the batched frontier query the
   inheritance walkers make), anything else is equality."
  [row where]
  (every? (fn [[k v]]
            (if (coll? v)
              (contains? (set v) (get row k))
              (= v (get row k))))
          where))


(defn- refuse!
  [op]
  (throw (ex-info (str "storage-double is read-only: " op
                       " needs a real storage fixture")
                  {:type :test/unsupported-storage-op :op op})))


(defn rows-storage
  "A `StorageCRUD` answering reads out of `tables` — `{entity-type {id
   row}}`. Returns a reify (NOT a map), so code that must unwrap a
   `VersionedStorage` before using it fails loudly here instead of
   silently reading the wrapper."
  [tables]
  (reify sp/StorageCRUD

    (read-entity
      [_ entity-name id]
      (get-in tables [entity-name id]))

    (query-entities
      [_ entity-name where]
      (filterv #(matches? % where) (vals (get tables entity-name))))

    (query-entities
      [this entity-name where _opts]
      (sp/query-entities this entity-name where))

    (query-latest-per-group
      [_ _entity-name _where _group-cols]
      (refuse! "query-latest-per-group"))

    (create-entity [_ _entity-name _data] (refuse! "create-entity"))

    (update-entity [_ _entity-name _id _data] (refuse! "update-entity"))

    (delete-entity [_ _entity-name _id] (refuse! "delete-entity"))))
