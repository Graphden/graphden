(ns graphden.executor.compile.test-support
  "Shared helper for the `executor.compile.*` unit tests — four sibling
   NSes carried a byte-identical 5-table lookups snapshot."
  (:require
    [graphden.executor.compile.lookups :as l]
    [graphden.storage.protocol.core :as sp]))


(defn lookups-for
  "Snapshot the five graph tables from `storage` into the lookups map
   the compile passes consume."
  [storage]
  (l/build-lookups
    {:fns        (sp/query-entities storage :fn {})
     :slots      (sp/query-entities storage :slot {})
     :fn-slots   (sp/query-entities storage :fn-slot {})
     :bindings   (sp/query-entities storage :binding {})
     :list-items (sp/query-entities storage :binding-list-item {})}))
