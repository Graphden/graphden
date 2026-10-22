(ns ci-priority-core-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-priority-core :as priority]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is run-tests]]))


(deftest exact-frontend-hints-never-filter-browser-suite
  (let [changed #{"resources/packages/app/editor/editor-undo.js"}
        all (priority/browser-order nil)
        ordered (priority/browser-order changed)]
    (is (= "edit-undo.test.js" (first ordered)))
    (is (= (frequencies all) (frequencies ordered)))
    (is (some #{"tools/runtime-test/undo-journal.test.js"}
              (priority/runtime-first changed)))))


(deftest changed-browser-test-runs-first-but-unknown-editor-keeps-all
  (let [changed #{"tools/browser-test/edit-a11y-audit.test.js"}
        unknown #{"resources/packages/app/editor/components.css"}]
    (is (= "edit-a11y-audit.test.js" (first (priority/browser-order changed))))
    (is (= (priority/browser-order nil) (priority/browser-order unknown)))
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order changed))))))


(deftest matching-editor-stem-prioritizes-related-browser-files
  (let [ordered (priority/browser-order
                  #{"resources/packages/app/editor/editor-secrets.js"})]
    (is (= ["edit-secrets-list.test.js" "edit-secrets-panel.test.js"
            "edit-secrets-rotate.test.js"]
           (subvec ordered 0 3)))
    (is (= (frequencies (priority/browser-order nil)) (frequencies ordered)))))


(deftest backend-focus-is-exact-and-bounded
  (is (= ["graphden.types.core-test"]
         (priority/unit-first #{"src/graphden/types/core.clj"})))
  (is (= ["graphden.layout.data-test"]
         (priority/unit-first #{"src/graphden/layout/data.clj"})))
  (is (= [] (priority/unit-first #{"test/graphden/e2e_test.clj"})))
  (is (= [] (priority/unit-first #{"test/graphden/perf/scenarios_test.clj"})))
  (is (= [] (priority/unit-first #{"test/graphden/accounts/routes_test.clj"})))
  (is (= [] (priority/unit-first #{"test/graphden/storage/postgres/constraints_test.clj"})))
  (is (= [] (priority/unit-first #{"src/graphden/no_such_file.clj"})))
  (is (= [] (priority/unit-first
              #{"test/graphden/types/core_test.clj"
                "test/graphden/types/check_test.clj"
                "test/graphden/types/rules_test.clj"
                "test/graphden/types/folds_test.clj"}))))


(deftest backend-integration-focus-selects-real-pg-tests
  (is (= ["graphden.accounts.routes-test"]
         (priority/integration-first #{"test/graphden/accounts/routes_test.clj"})))
  (is (= ["graphden.storage.postgres.constraints-test"]
         (priority/integration-first
           #{"test/graphden/storage/postgres/constraints_test.clj"})))
  (is (= [] (priority/integration-first #{"src/graphden/types/core.clj"})))
  (is (= [] (priority/integration-first #{"test/graphden/types/core_test.clj"}))))


(deftest runtime-order-preserves-full-roster-once
  (let [all (priority/runtime-order nil)
        changed #{"tools/runtime-test/undo-journal.test.js"}
        ordered (priority/runtime-order changed)]
    (is (= "tools/runtime-test/undo-journal.test.js" (first ordered)))
    (is (= (frequencies all) (frequencies ordered)))
    (is (every? #(= 1 %) (vals (frequencies ordered))))
    (is (= all (priority/runtime-order #{"unknown"})))))


;; Task output colors are supplied by bb.edn :init in normal execution.
(def green "")
(def red "")
(def reset "")


(deftest js-task-executes-full-roster-once-in-its-own-directory
  (let [calls (atom [])
        task (get-in (edn/read-string (slurp "bb.edn")) [:tasks 'test-js :task])
        expected (set (map (fn [path]
                             ["tools/runtime-test" "node" (str (fs/file-name path))])
                           (fs/glob "tools/runtime-test" "*.test.js")))]
    (with-redefs [priority/changed-paths (constantly nil)
                  p/shell (fn [opts executable filename]
                            (swap! calls conj [(:dir opts) executable filename])
                            {:exit 0})]
      (eval task))
    (is (= expected (set @calls)))
    (is (= (count expected) (count @calls)))))


(let [{:keys [fail error]} (run-tests 'ci-priority-core-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
