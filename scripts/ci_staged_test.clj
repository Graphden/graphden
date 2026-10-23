(ns ci-staged-test
  (:require [ci-staged :as staged]
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
              :commands [{:paths ["tools/runtime-test/popover.test.js"] :exit 0}]}]
    (is (staged/valid-affected-receipt? good plan {:node "v22" :biome nil}))
    (is (not (staged/valid-affected-receipt? (assoc good :complete false) plan {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt? (assoc good :commands []) plan {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt? good (assoc plan :sha "c") {:node "v22" :biome nil})))
    (is (not (staged/valid-affected-receipt? good plan {:node "v24" :biome nil})))))

(let [result (run-tests 'ci-staged-test)]
  (when (pos? (+ (:fail result) (:error result)))
    (System/exit 1)))
