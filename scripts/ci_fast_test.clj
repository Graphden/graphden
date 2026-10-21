(ns ci-fast-test
  (:require
    [ci-fast :as fast]
    [clojure.test :refer [deftest is run-tests]]))


(deftest changed-file-checks-never-expand-to-full-tree
  (is (= [["cljstyle" "check" "scripts/a.clj"]
          ["clj-kondo" "--lint" "scripts/a.clj"]
          ["node" "--check" "tools/runtime-test/a.test.js"]
          ["bash" "-n" "dev/wtq/wt"]
          ["shellcheck" "dev/wtq/wt"]
          ["npx" "--no-install" "markdownlint-cli2" "--no-globs" "README.md"]]
         (fast/commands ["scripts/a.clj" "tools/runtime-test/a.test.js" "dev/wtq/wt" "README.md"] true true true)))
  (is (= [["clojure" "-M:cljstyle" "check" "a b.clj"]
          ["clojure" "-M:kondo" "--lint" "a b.clj"]]
         (fast/commands ["a b.clj"] false false false)))
  (is (= [] (fast/commands [] true true true))))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
