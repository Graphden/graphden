(ns graphden.packages.app.type-diagnostics-graph-test
  "Behavioural tests for the :type-diagnostics-list GRAPH reshape
   (app/editor-panels/fns.edn) — row build, arg coalescing (:arg-name,
   else a keyword :binding, else empty), the uuid label for anonymous
   fns, and the by-name sort — over the :branch-diagnostics-flat join.
   Driven through the executor on a golden clone; the org-drop
   security boundary itself is unit-tested at the impl
   (`editor-panels-test`)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.exec-harness :as harness]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*)) ["core" "web" "app"]))


(deftest type-diagnostics-rows-reshape
  (let [branch (vs/current-branch-id harness/*storage*)
        add-id (harness/fn-id "add")
        zip-id (harness/fn-id "zipmap")]
    (binding [diag/*diagnostics-override* (atom {})]
      (diag/record! branch zip-id [{:message "ref mismatch" :binding :keys}])
      (diag/record! branch add-id [{:message "Type mismatch on arg :nums" :arg-name :nums}
                                   {:message "no arg info"}])
      (let [rows (exec/execute-with-named-args
                   harness/*context*
                   (harness/fn-id "type-diagnostics-list") {})]
        (testing "one display row per diagnostic, sorted by fn name"
          (is (= ["add" "add" "zipmap"] (mapv :fn-name rows))))
        (testing ":arg-name path"
          (is (= "nums" (:arg (first (filter #(= "Type mismatch on arg :nums" (:message %)) rows))))))
        (testing "keyword :binding fallback path"
          (is (= "keys" (:arg (first (filter #(= "zipmap" (:fn-name %)) rows))))))
        (testing "no arg info → empty string"
          (is (= "" (:arg (first (filter #(= "no arg info" (:message %)) rows))))))
        (testing "row shape carries the stringified fn-id"
          (is (= (str add-id) (:fn-id (first rows)))))))))


(deftest anonymous-fn-rows-get-uuid-label
  (let [branch (vs/current-branch-id harness/*storage*)
        anon-id (:id (first (filter #(nil? (:name %))
                                    (sp/query-entities harness/*storage* :fn {}))))]
    (binding [diag/*diagnostics-override* (atom {})]
      (diag/record! branch anon-id [{:message "broken anonymous"}])
      (let [rows (exec/execute-with-named-args
                   harness/*context*
                   (harness/fn-id "type-diagnostics-list") {})]
        (is (= [(str anon-id)] (mapv :fn-name rows))
            "nil :fn-name coalesces to the fn-id string label")))))
