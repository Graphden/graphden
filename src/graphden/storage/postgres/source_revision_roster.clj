(ns graphden.storage.postgres.source-revision-roster
  "Physical coverage for graph source freshness. Includes identity and version
   tables plus their schema-declared ref-many junctions, rather than relying on
   the older write-path epoch's incomplete list of logical entities."
  (:require
    [clojure.set :as set]
    [graphden.schema.protocol.protocol :as schema]
    [graphden.schema.versioned.schema :as versioned]
    [graphden.storage.postgres.ddl :as ddl]
    [graphden.storage.postgres.util :as pg]))


(def ^:private required-identities
  #{:fn :slot :fn-slot :binding :binding-list-item :ns :branch :branch-merge})


(defn tables
  "Derive the source table roster from a complete versioned graph schema.
   Missing required entities or mirrors are rejected. Resource overrides are
   included when present; authorization tables are not source freshness and
   continue to require independent fresh checks. No DDL or I/O is performed."
  [source-schema]
  (let [available (set (schema/entities source-schema))
        identities (cond-> required-identities
                     (contains? available :resource-override) (conj :resource-override))
        entities (into identities (keep versioned/version-entity-for) identities)
        missing (set/difference entities available)]
    (when (seq missing)
      (throw (ex-info "Incomplete source revision schema"
                      {:type ::incomplete-schema :missing missing})))
    (into (sorted-set)
          (mapcat (fn [entity]
                    (cons (pg/kw->snake-case entity)
                          (keep (fn [[field spec]]
                                  (when (= :ref-many (:type spec))
                                    (ddl/junction-table-name entity field)))
                                (schema/entity-fields source-schema entity)))))
          entities)))
