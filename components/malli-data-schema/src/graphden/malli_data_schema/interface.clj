(ns graphden.malli-data-schema.interface
  "Public interface for malli-based data schema implementation."
  (:require
   [graphden.malli-data-schema.core :as core]))


(defn create-builder
  "Creates a new data schema builder.
   Use with the DataSchemaBuilder protocol functions to define your schema."
  []
  (core/create-builder))


(defn schema->malli
  "Returns the underlying malli schema for an entity.
   Useful for advanced validation or schema introspection."
  [data-schema entity-name]
  (core/schema->malli data-schema entity-name))
