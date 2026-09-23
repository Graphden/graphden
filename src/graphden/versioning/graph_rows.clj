(ns graphden.versioning.graph-rows
  "The whole-graph read: every row of the five slot/binding-model tables,
   `{:fns :slots :fn-slots :bindings :list-items}`. One definition for the
   type-API graph cache (`crud.types-api`), the layout loader
   (`layout.data`) and the export / BYO bootstrap bundle
   (`packages.export`) — each used to carry its own copy."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


(defn read-all
  "Every graph row. A VersionedStorage resolves the current per-branch
   view in one branch-chain-cached batch (`vs/query-all-graph-entities`);
   any other storage answers five plain queries."
  [storage]
  (if (instance? VersionedStorage storage)
    (vs/query-all-graph-entities storage)
    {:fns        (vec (sp/query-entities storage :fn {}))
     :slots      (vec (sp/query-entities storage :slot {}))
     :fn-slots   (vec (sp/query-entities storage :fn-slot {}))
     :bindings   (vec (sp/query-entities storage :binding {}))
     :list-items (vec (sp/query-entities storage :binding-list-item {}))}))
