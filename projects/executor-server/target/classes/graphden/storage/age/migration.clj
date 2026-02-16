(ns graphden.storage.age.migration
  "Schema migration logic for AGE storage.
   Delegates to postgres-storage for SQL table migrations."
  (:require
    [graphden.storage.postgres.migration :as pg-migration]))


(defn do-initialize
  "Performs schema initialization/migration.
   Delegates to postgres-storage migration logic."
  [pool schema]
  (pg-migration/do-initialize pool schema))
