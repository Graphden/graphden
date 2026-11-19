(ns graphden.storage.postgres.source-revision-roster-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.schema.graph.schema :as graph]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.versioned.schema :as versioned]
    [graphden.storage.postgres.source-revision-roster :as roster]))


(deftest versioned-source-roster-includes-physical-junctions
  (let [tables (roster/tables (versioned/build-schema (malli/create-builder)))]
    (doseq [table ["fn" "fn_version" "fn_parent_ids" "slot" "fn_slot"
                   "fn_slot_version" "binding" "binding_version" "binding_list_item"
                   "binding_list_item_version" "ns" "branch" "branch_merge"
                   "resource_override" "resource_override_version"]]
      (is (contains? tables table) table))
    (is (not (contains? tables "graphden_source_revision")))
    (is (not (contains? tables "grant")))
    (is (= (seq tables) (sort tables)))))


(deftest incomplete-source-schema-cannot-install-a-partial-roster
  (is (thrown? clojure.lang.ExceptionInfo
        (roster/tables (graph/build-schema (malli/create-builder))))))
