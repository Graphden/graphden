(ns ci-staged-test
  (:require
    [ci-staged :as staged]
    [clojure.test :refer [deftest is run-tests]]))


(def runtime-roster
  ["tools/runtime-test/editor-root.test.js"
   "tools/runtime-test/popover.test.js"
   "tools/runtime-test/tour-picker.test.js"])


(deftest runtime-dependencies-are-explicit-and-unknown-widens
  (is (= ["tools/runtime-test/editor-root.test.js"
          "tools/runtime-test/tour-picker.test.js"]
         (:files (staged/affected :runtime
                                  #{"resources/packages/app/editor/editor-root.js"}
                                  runtime-roster))))
  (is (= ["tools/runtime-test/popover.test.js"]
         (:files (staged/affected :runtime
                                  #{"tools/runtime-test/popover.test.js"}
                                  runtime-roster))))
  (is (= runtime-roster
         (:files (staged/affected :runtime
                                  #{"resources/packages/app/editor/editor-unknown.js"}
                                  runtime-roster)))))


(deftest inspector-contracts-select-direct-runtime-consumers
  (let [roster ["tools/runtime-test/fn-usages-section.test.js"
                "tools/runtime-test/inspector-graph-lifecycle.test.js"
                "tools/runtime-test/tour-picker.test.js"]]
    (is (= (take 2 roster)
           (:files (staged/affected :runtime
                                    #{"resources/packages/app/ui-inspector/fns.edn"}
                                    roster))))
    (is (= [(first roster)]
           (:files (staged/affected :runtime
                                    #{"resources/packages/app/editor/editor-fn-usages.js"}
                                    roster))))))


(deftest shared-input-and-empty-diff
  (is (= runtime-roster
         (:files (staged/affected :runtime #{"scripts/ci_staged.clj"} runtime-roster))))
  (is (= [] (:files (staged/affected :runtime #{} runtime-roster))))
  (is (= [] (:files (staged/affected :biome #{} ["a.js" "b.js"]))))
  (is (= ["a.js" "b.js"]
         (:files (staged/affected :biome #{"biome.jsonc"} ["a.js" "b.js"])))))


(deftest receipt-is-bound-to-plan-toolchain-and-complete-coverage
  (let [plan {:schema 1 :kind :runtime :sha "a" :base "b"
              :roster runtime-roster
              :affected ["tools/runtime-test/popover.test.js"]
              :remainder ["tools/runtime-test/editor-root.test.js"
                          "tools/runtime-test/tour-picker.test.js"]}
        good {:schema 1 :phase :affected :complete true :exit 0
              :plan plan :toolchain {:node "v22" :biome nil}
              :commands [{:paths ["tools/runtime-test/popover.test.js"]
                          :argv ["node" "popover.test.js"]
                          :cwd "tools/runtime-test" :exit 0}]}]
    (is (staged/valid-affected-receipt? good plan {:node "v22" :biome nil}))
    (is (not (staged/valid-affected-receipt? (assoc good :complete false) plan {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt? (assoc good :commands []) plan {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt?
               (assoc-in good [:commands 0 :argv] ["true"])
               plan {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt?
               (update-in good [:commands 0] dissoc :argv)
               plan {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt?
               (assoc-in good [:commands 0 :cwd] "wrong-dir")
               plan {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt? good (assoc plan :sha "c") {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt? good plan {:node "v24" :biome nil})))))


(deftest status-distinguishes-pending-stale-interrupted-and-passed
  (let [plan {:schema 1 :kind :runtime :sha "a"
              :roster runtime-roster :reason :declared-dependency
              :affected ["tools/runtime-test/popover.test.js"]
              :remainder ["tools/runtime-test/editor-root.test.js"
                          "tools/runtime-test/tour-picker.test.js"]}
        toolchain {:node "v22" :biome nil}
        receipt {:schema 1 :plan plan :phase :affected :toolchain toolchain
                 :complete true :exit 0
                 :commands [{:paths (:affected plan)
                             :argv ["node" "popover.test.js"]
                             :cwd "tools/runtime-test" :exit 0}]}]
    (is (= :pending (staged/phase-status plan :affected nil toolchain (constantly false))))
    (is (= :passed (staged/phase-status plan :affected receipt toolchain (constantly false))))
    (is (= :stale (staged/phase-status plan :affected receipt {:node "v24"} (constantly false))))
    (is (= :stale (staged/phase-status (assoc plan :sha "b") :affected receipt toolchain
                                       (constantly false))))
    (is (= :running (staged/phase-status plan :affected (assoc receipt :complete false :pid 1)
                                         toolchain (constantly true))))
    (is (= :interrupted (staged/phase-status plan :affected (assoc receipt :complete false :pid 1)
                                             toolchain (constantly false))))
    (is (= :failed (staged/phase-status plan :affected (assoc receipt :exit 1)
                                        toolchain (constantly false))))
    (is (= :failed (staged/phase-status plan :affected (assoc receipt :commands [])
                                        toolchain (constantly false))))
    (is (= :failed (staged/phase-status
                     plan :affected (assoc-in receipt [:commands 0 :argv] ["true"])
                     toolchain (constantly false))))))


(deftest biome-receipt-requires-one-exact-batch-command
  (let [plan {:schema 1 :kind :biome :sha "a"
              :roster ["a.js" "b.js"] :affected ["a.js" "b.js"] :remainder []}
        toolchain {:node "v22" :biome "2.0"}
        good {:schema 1 :phase :affected :complete true :exit 0
              :plan plan :toolchain toolchain
              :commands [{:paths ["a.js" "b.js"]
                          :argv ["npx" "--no-install" "biome" "check" "a.js" "b.js"]
                          :cwd nil
                          :exit 0}]}]
    (is (staged/valid-affected-receipt? good plan toolchain))
    (is (not (staged/valid-affected-receipt?
               (assoc good :commands [{:paths ["a.js"]
                                       :argv ["npx" "--no-install" "biome" "check" "a.js"]
                                       :cwd nil
                                       :exit 0}
                                      {:paths ["b.js"]
                                       :argv ["npx" "--no-install" "biome" "check" "b.js"]
                                       :cwd nil
                                       :exit 0}])
               plan toolchain)))))


(deftest invalid-base-widens-the-roster
  (let [plan (staged/make-plan :runtime "not-a-ref")]
    (is (nil? (:base plan)))
    (is (= :unknown-diff (:reason plan)))
    (is (= (:roster plan) (:affected plan)))
    (is (= [] (:remainder plan)))))


(let [result (run-tests 'ci-staged-test)]
  (when (pos? (+ (:fail result) (:error result)))
    (System/exit 1)))
