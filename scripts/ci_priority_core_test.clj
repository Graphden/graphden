(ns ci-priority-core-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-priority-core :as priority]
    [clojure.string :as str]
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


(deftest root-catalog-impact-includes-consumers-with-different-file-names
  (doseq [source ["resources/packages/app/editor/editor-root.js"
                  "resources/packages/app/editor/editor-tour-picker.js"
                  "resources/packages/app/ui-tutorial-catalog/fns.edn"
                  "tools/browser-test/tutorial-tour-helpers.js"]]
    (let [first-files (set (priority/browser-first #{source}))]
      (is (contains? first-files "edit-tutorial-tour-picker.test.js") source)
      (is (contains? first-files "edit-tutorial-tour-queues.test.js") source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source)))
  (is (contains? (set (priority/browser-first
                        #{"resources/packages/app/editor/editor-root.js"}))
                 "edit-account-menu-graph.test.js")))


(deftest package-module-without-ui-prefix-keeps-browser-order-valid
  (let [source "resources/packages/app/editor/fns.edn"
        ordered (priority/browser-order #{source})]
    (is (= (frequencies (priority/browser-order nil)) (frequencies ordered)))))


(deftest shipped-component-contracts-link-graphs-to-explicit-js-consumers
  (let [keymap #{"resources/packages/app/ui-keymap/fns.edn"}
        appearance #{"resources/packages/app/ui-appearance/fns.edn"}
        keymap-first (priority/browser-first keymap)]
    (is (= "edit-keymap-graph.test.js" (first keymap-first)))
    (is (= "edit-appearance-graph.test.js"
           (first (priority/browser-first appearance))))
    (is (not-any? #{"edit-a11y-audit.test.js"} keymap-first)
        "a plain mention of keymap does not make an unrelated test early")
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order keymap))))))


(deftest package-http-routes-prioritize-browser-consumers
  (doseq [source ["resources/packages/registry/registry/fns.edn"
                  "resources/packages/registry/registry/impls.clj"
                  "src/graphden/packages/pin_usage.clj"]]
    (let [early (set (priority/browser-first #{source}))]
      (is (contains? early "edit-tutorial-package-lifecycle.test.js") source)
      (is (contains? early "edit-packages-panel.test.js") source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source))))


(deftest shared-popover-impact-includes-the-personal-theme-walk
  (doseq [source ["resources/packages/app/editor/editor-edit-modes.js"
                  "resources/packages/web/runtime/graphden-popover.js"]]
    (let [first-files (set (priority/browser-first #{source}))]
      (is (contains? first-files "edit-ui-components.test.js") source)
      (is (contains? first-files "edit-arg-value.test.js") source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source))))


(deftest row-actions-owner-contract-prioritizes-its-browser-consumers
  (let [source "resources/packages/app/editor/editor-row-actions.js"
        early (priority/browser-first #{source})
        positions (zipmap early (range))]
    (is (contains? positions "edit-canvas-card-personal.test.js"))
    (is (contains? positions "edit-row-actions-pin.test.js"))
    (is (< (positions "edit-canvas-card-personal.test.js")
           (positions "edit-tutorial-tour-picker.test.js")))
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order #{source}))))))


(deftest local-browser-helper-changes-reach-transitive-test-consumers
  (doseq [source ["tools/browser-test/tutorial-actions.js"
                  "tools/browser-test/checked-action.js"]]
    (let [early (priority/browser-first #{source})]
      (is (some #{"edit-tutorial-tour.test.js"} early) source)
      (is (some #{"edit-tutorial-tour-args.test.js"} early) source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source)))
  (let [early (priority/browser-first
                #{"tools/browser-test/tutorial-tour-helpers.js"})]
    (is (some #{"edit-tutorial-tour.test.js"} early))))


(deftest browser-links-follow-unique-globals-and-specific-selectors
  (let [source "resources/packages/app/editor/editor-feature.js"
        consumer "resources/packages/app/editor/editor-consumer.js"
        test-path "tools/browser-test/edit-feature.test.js"
        indirect-test "tools/browser-test/edit-consumer.test.js"
        sources {source "function featureGlobal() {}\nconst selector = 'gd-specific-widget';"
                 consumer "function consumeFeature() { featureGlobal(); }"
                 test-path "document.querySelector('.gd-specific-widget');"
                 indirect-test "consumeFeature();"}
        graph (#'priority/browser-source-graph sources [source consumer] [test-path indirect-test])
        distances (#'priority/dependency-distances (:reverse graph) #{source})]
    (is (= 1 (get distances test-path)) "the selector links the source directly to its test")
    (is (= 1 (get distances consumer)) "a unique global links dependent modules")
    (is (= 2 (get distances indirect-test)) "a test of the dependent module is reachable"))
  (let [source-a "resources/packages/app/editor/editor-a.js"
        source-b "resources/packages/app/editor/editor-b.js"
        test-path "tools/browser-test/edit-ambiguous.test.js"
        sources {source-a "function sharedGlobal() {}"
                 source-b "function sharedGlobal() {}"
                 test-path "sharedGlobal();"}
        graph (#'priority/browser-source-graph sources [source-a source-b] [test-path])]
    (is (nil? (get (#'priority/dependency-distances (:reverse graph) #{source-a}) test-path))
        "an ambiguous owner must not invent a dependency")))


(deftest backend-priority-covers-direct-and-transitive-consumers
  (let [layers (priority/unit-layers #{"src/graphden/types/core.clj"})
        direct (first layers)
        indirect (second layers)
        names (vec (mapcat identity layers))]
    (is (some #{"graphden.types.core-test"} direct))
    (is (some #{"graphden.crud.validation-test"} indirect))
    (is (> (count layers) 2))
    (is (= (count names) (count (distinct names))))
    (is (= names (priority/unit-first #{"src/graphden/types/core.clj"}))))
  (is (some #{"graphden.layout.data-test"}
            (priority/unit-first #{"src/graphden/layout/data.clj"})))
  (let [layers (priority/unit-layers
                 #{"src/graphden/types/core.clj"
                   "test/graphden/types/core_test.clj"})]
    (is (= ["graphden.types.core-test"] (first layers)))
    (is (some #{"graphden.types.check-test"} (second layers))))
  (is (= [] (priority/unit-first #{"test/graphden/e2e_test.clj"})))
  (is (= [] (priority/unit-first #{"test/graphden/perf/scenarios_test.clj"})))
  (is (= [] (priority/unit-first #{"test/graphden/accounts/routes_test.clj"})))
  (is (= [] (priority/unit-first #{"test/graphden/storage/postgres/constraints_test.clj"})))
  (is (= [] (priority/unit-first #{"src/graphden/no_such_file.clj"})))
  (is (= 4 (count (priority/unit-first
                    #{"test/graphden/types/core_test.clj"
                      "test/graphden/types/check_test.clj"
                      "test/graphden/types/rules_test.clj"
                      "test/graphden/types/folds_test.clj"})))))


(deftest backend-integration-focus-selects-real-pg-tests
  (is (= ["graphden.accounts.routes-test"]
         (priority/integration-first #{"test/graphden/accounts/routes_test.clj"})))
  (is (= ["graphden.storage.postgres.constraints-test"]
         (priority/integration-first
           #{"test/graphden/storage/postgres/constraints_test.clj"})))
  (is (< 1 (count (priority/integration-first #{"src/graphden/types/core.clj"}))))
  (is (= [] (priority/integration-first #{"test/graphden/types/core_test.clj"}))))


(deftest literal-dynamic-loads-and-package-fixtures-enter-the-early-graph
  ;; compile-eager intentionally breaks a load cycle via requiring-resolve.
  (is (contains? (:deps (#'priority/namespace-entry
                         "src/graphden/executor/compile_eager.clj"))
                 'graphden.executor.compile-runtime))
  (is (contains? (:packages (#'priority/namespace-entry
                             "test/graphden/packages/core/hof_test.clj"))
                 ["core" "hof"]))
  (is (= ["graphden.packages.core.hof-test"
          "graphden.types.taint-propagate-guard-test"]
         (priority/unit-first
           #{"resources/packages/core/hof/impls.clj"})))
  (let [layers (priority/unit-layers
                 #{"resources/packages/core/hof/impls.clj"})]
    (is (some #{"graphden.types.taint-propagate-guard-test"} (first layers)))
    (is (= (count (mapcat identity layers))
           (count (distinct (mapcat identity layers))))))
  (is (not-any? #{"graphden.types.taint-propagate-guard-test"}
                (priority/unit-first
                  #{"resources/packages/core/hof/fns.edn"}))))


(deftest runtime-order-preserves-full-roster-once
  (let [all (priority/runtime-order nil)
        changed #{"tools/runtime-test/undo-journal.test.js"}
        ordered (priority/runtime-order changed)]
    (is (= "tools/runtime-test/undo-journal.test.js" (first ordered)))
    (is (= (frequencies all) (frequencies ordered)))
    (is (every? #(= 1 %) (vals (frequencies ordered))))
    (is (= all (priority/runtime-order #{"unknown"})))))


(deftest manual-priority-precedes-derived-tests-without-a-second-run
  (let [manual (priority/manual-hints
                 "runtime:org-switcher.test.js,browser:edit-appearance-graph.test.js,unit:graphden.executor.builtin-plans-test,unit:graphden.editor.component-config-test,runtime:org-switcher.test.js")
        runtime (priority/runtime-order #{"tools/runtime-test/undo-journal.test.js"}
                                        (:runtime manual))
        browser (priority/browser-order #{"tools/browser-test/edit-a11y-audit.test.js"}
                                        (:browser manual))
        plan (priority/plan #{"test/graphden/types/core_test.clj"} manual)]
    (is (= ["org-switcher.test.js"] (:runtime manual)))
    (is (= ["tools/runtime-test/org-switcher.test.js"
            "tools/runtime-test/undo-journal.test.js"] (subvec runtime 0 2)))
    (is (= ["edit-appearance-graph.test.js" "edit-a11y-audit.test.js"]
           (subvec browser 0 2)))
    (is (= "graphden.executor.builtin-plans-test" (first (:unit plan))))
    (is (= [["graphden.executor.builtin-plans-test"]
            ["graphden.editor.component-config-test"]]
           (subvec (:unit-layers plan) 0 2)))
    (is (= (frequencies (priority/runtime-order nil)) (frequencies runtime)))
    (is (= (frequencies (priority/browser-order nil)) (frequencies browser)))
    (is (= (count (:unit plan)) (count (distinct (:unit plan)))))))


(deftest manual-priority-rejects-typos-before-running
  (is (thrown? Exception (priority/manual-hints "unit:bad/name")))
  (is (thrown? Exception (priority/runtime-order nil ["missing.test.js"])))
  (is (thrown? Exception (priority/browser-order nil ["missing.test.js"])))
  (is (thrown? Exception (priority/plan nil {:unit ["missing.namespace-test"]}))))


(deftest fresh-bb-task-executes-full-roster-once-in-its-own-directory
  ;; A real child BB process compiles the task with ONLY bb.edn's :requires.
  ;; Evaluating its body in this test ns would mask a missing priority alias.
  (let [dir (fs/create-temp-dir)
        log (str (fs/path dir "node-calls.tsv"))
        stub (str (fs/path dir "node"))
        runtime-dir (str (fs/canonicalize "tools/runtime-test"))
        expected (set (map (fn [path] [runtime-dir "1" (str (fs/file-name path))])
                           (fs/glob "tools/runtime-test" "*.test.js")))]
    (try
      (spit stub "#!/usr/bin/env bash\nprintf '%s\\t%s\\t%s\\n' \"$PWD\" \"$#\" \"$1\" >> \"$CI_JS_TASK_CALL_LOG\"\n")
      (p/shell "chmod" "+x" stub)
      (let [result (p/shell {:out :string :err :string :continue true
                             :extra-env {"PATH" (str dir (System/getProperty "path.separator") (System/getenv "PATH"))
                                         "CI_JS_TASK_CALL_LOG" log "GRAPHDEN_CI_SINCE" ""
                                         "GRAPHDEN_CI_FIRST_TESTS" "runtime:org-switcher.test.js"}}
                            "bb" "test-js")
            calls (if (fs/exists? log) (mapv #(str/split % #"\t") (str/split-lines (slurp log))) [])]
        (is (zero? (:exit result)) (str (:out result) (:err result)))
        (is (= "org-switcher.test.js" (last (first calls))))
        (is (= expected (set calls)))
        (is (= (count expected) (count calls))))
      (finally (fs/delete-tree dir)))))


(let [{:keys [fail error]} (run-tests 'ci-priority-core-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
