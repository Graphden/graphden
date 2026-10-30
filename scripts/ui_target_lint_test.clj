(ns ui-target-lint-test
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest is run-tests]]
    [ui-target-lint :as lint]))


(deftest executable-targets-are-refused-but-graph-declarations-remain-valid
  (let [root (fs/create-temp-dir {:prefix "ui-target-lint-"})]
    (try
      (fs/create-dirs (fs/path root "src/graphden/runtime"))
      (fs/create-dirs (fs/path root "src/graphden/ui"))
      (fs/create-dirs (fs/path root "resources/packages/app/example"))
      (spit (str (fs/path root "src/graphden/runtime/example.clj"))
            "(ns graphden.runtime.example)\n(def target (ids/fn-id \"app.ui-canvas\" :canvas-view))\n")
      (spit (str (fs/path root "src/graphden/ui/component_contracts.clj"))
            "(ns graphden.ui.component-contracts)\n(def target (ids/fn-id \"app.ui-canvas\" :canvas-view))\n")
      (spit (str (fs/path root "resources/packages/app/example/fns.edn"))
            "{:namespace \"app.example\" :fns [{:name :target :args {:value \"app.ui-canvas/canvas-view\"}}]}")
      (let [failures (vec (lint/scan root))]
        (is (= 2 (count failures)))
        (is (some #(re-find #"runtime/example.clj" %) failures))
        (is (some #(re-find #"example/fns.edn" %) failures))
        (is (not-any? #(re-find #"component_contracts.clj" %) failures)))
      (finally (fs/delete-tree root)))))


(let [{:keys [fail error]} (run-tests 'ui-target-lint-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
