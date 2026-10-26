(ns ci-fast-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [babashka.tasks :refer [clojure]]
    [ci-fast :as fast]
    [ci-report :as report]
    [ci-select :as select]
    [ci-staged :as staged]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is run-tests]]))


(deftest changed-file-checks-never-expand-to-full-tree
  (is (= [["cljstyle" "check" "scripts/a.clj"]
          ["clojure" "-M:splint" "scripts/a.clj"]
          ["clj-kondo" "--lint" "scripts/a.clj"]
          ["node" "--check" "tools/runtime-test/a.test.js"]
          ["bash" "-n" "dev/wtq/wt"]
          ["shellcheck" "dev/wtq/wt"]
          ["npx" "--no-install" "markdownlint-cli2" "--no-globs" "README.md"]]
         (fast/commands ["scripts/a.clj" "tools/runtime-test/a.test.js" "dev/wtq/wt" "README.md"] true true true)))
  (is (= [["clojure" "-M:cljstyle" "check" "a b.clj"]
          ["clojure" "-M:splint" "a b.clj"]
          ["clojure" "-M:kondo" "--lint" "a b.clj"]]
         (fast/commands ["a b.clj"] false false false)))
  (is (= [] (fast/commands [] true true true))))


(deftest edited-jvm-test-runs-after-cheap-checks-and-before-broad-consumers
  (let [commands (fast/commands
                   ["test/graphden/editor/config_resolution_test.clj"
                    "resources/packages/app/ui-inspector/fns.edn"]
                   true true true)
        direct ["bb" "wt" "test" "--focus" "graphden.editor.config-resolution-test"]
        guard fast/large-forms-command
        splint (first (filter #(= ["clojure" "-M:splint"] (vec (take 2 %))) commands))
        broader (first (filter #(some #{"graphden.editor.component-bundle-test"} %) commands))
        position (fn [command] (count (take-while #(not= command %) commands)))]
    (is (= ["cljstyle" "check"] (vec (take 2 (first commands)))))
    (is (= guard (second commands)))
    (is (= ["bb" "devtour-check"] (nth commands 2)))
    (is (some? splint))
    (is (< (position splint) (position direct)))
    (is (< (position ["bb" "type-sweep"]) (position direct)))
    (is (some? broader))
    (is (not-any? #{"graphden.editor.config-resolution-test"} broader))
    (is (< (position direct) (position broader)))))


(deftest changed-clojure-source-triggers-large-form-guard-before-broad-checks
  (let [commands (fast/commands ["src/graphden/editor/component_templates.clj"] true true true)]
    (is (= fast/large-forms-command (second commands)))))


(deftest root-browser-consumers-run-before-the-broad-template-suite
  (let [root-test "graphden.editor.root-compatibility-integration-test"
        browser-test "graphden.executor.browser-plan-test"
        ui-commands (fast/commands ["resources/packages/app/ui-tutorial-catalog/fns.edn"] true true true)
        browser-commands (fast/commands ["src/graphden/executor/browser_plan.clj"] true true true)
        focused (fn [commands]
                  (first (filter #(= ["bb" "wt" "test"] (vec (take 3 %))) commands)))]
    (is (= root-test (nth (focused ui-commands) 4)))
    (is (= ["bb" "wt" "test" "--focus" root-test "--focus" browser-test]
           (focused browser-commands)))))


(deftest large-form-guard-runs-in-the-fast-babashka-process
  (is (zero? (:exit (apply p/shell {:continue true :out :string :err :string}
                           fast/large-forms-command)))))


(deftest removed-test-file-does-not-run-a-missing-namespace
  (is (= [fast/large-forms-command]
         (fast/commands [] ["test/graphden/editor/removed_test.clj"] true true true))))


(deftest graph-edit-preflight-covers-the-c3-failures
  (let [commands (fast/commands ["resources/packages/app/ui-inspector/fns.edn"] true true true)]
    (is (some #(= ["bb" "wt" "test"] (vec (take 3 %))) commands))
    (is (some #{["bb" "graph-lint"]} commands))
    (is (some #{["bb" "reachability"]} commands))
    (is (some #{["bb" "type-sweep"]} commands))
    (is (some #{["bb" "devtour-check"]} commands))
    (is (< (count (take-while #(not= ["bb" "devtour-check"] %) commands))
           (count (take-while #(not= ["bb" "type-sweep"] %) commands))))
    (is (not-any? #{["clojure" "-M:dev" "tools/ui_preview/generate_builtin_plans.clj" "--check"]}
                  commands))))


(deftest ci-tool-edits-run-the-ci-selftest-before-the-gate
  (let [commands (fast/commands ["scripts/ci.clj"] true true true)]
    (is (some #{["bb" "ci-test"]} commands)))
  (is (some #{["bb" "ci-test"]}
            (fast/commands [] ["scripts/ci_runner_order_test.clj"] true true true))))


(deftest package-impl-preflight-runs-declared-consumers
  (let [paths ["resources/packages/registry/registry/impls.clj"
               "test/graphden/editor/component_config_test.clj"]
        commands (fast/commands paths true true true)
        jvm (filter #(= ["bb" "wt" "test"] (vec (take 3 %))) commands)]
    (is (= ["bb" "wt" "test" "--focus"
            "graphden.types.taint-propagate-guard-test"]
           (first jvm)))
    (is (= ["bb" "wt" "test" "--focus"
            "graphden.editor.component-config-test"]
           (second jvm)))))


(deftest deleted-graph-source-still-runs-semantic-checks
  (is (= [["bb" "devtour-check"]
          ["bb" "graph-lint"]
          ["bb" "reachability"]
          ["bb" "type-sweep"]
          ["bb" "wt" "test"
           "--focus" "graphden.editor.root-compatibility-integration-test"
           "--focus" "graphden.editor.component-bundle-test"
           "--focus" "graphden.editor.component-config-test"
           "--focus" "graphden.editor.config-resolution-test"
           "--focus" "graphden.executor.structural-projection-test"
           "--focus" "graphden.executor.builtin-plans-test"]]
         (fast/commands [] ["resources/packages/app/ui-inspector/fns.edn"] true true true)))
  (is (= [["bb" "devtour-check"]]
         (fast/commands [] ["resources/packages/app/editor/removed.js"] true true true))))


(deftest failed-preflight-records-the-command-before-returning-red
  (let [receipts (atom [])]
    (with-redefs [select/changed-files (constantly ["scripts/ci_fast.clj"])
                  fast/commands (fn [& _] [["fixture" "red"]])
                  fast/run-command! (fn [_] (throw (ex-info "fixture failed" {:exit 7})))
                  staged/roster (constantly [])
                  staged/affected (fn [& _] {:files [] :reason :declared-dependency})
                  p/shell (fn [& _] {:out "0123456789012345678901234567890123456789\n" :exit 0})
                  report/write-atomic! (fn [path value] (swap! receipts conj [path value]))]
      (is (thrown? Exception (fast/run-checks! "base"))))
    (let [[_ receipt] (last @receipts)]
      (is (true? (:complete receipt)))
      (is (= 1 (:exit receipt)))
      (is (= 7 (get-in receipt [:commands 0 :exit])))
      (is (= ["fixture" "red"] (get-in receipt [:commands 0 :argv]))))))


(deftest runtime-preflight-runs-before-focused-jvm-suites
  (let [seen (atom [])]
    (with-redefs [select/changed-files (constantly ["scripts/ci_fast.clj"])
                  fast/commands (fn [& _]
                                  [["bb" "devtour-check"]
                                   ["bb" "wt" "test" "--focus" "fixture"]])
                  fast/run-command! (fn [command] (swap! seen conj command))
                  staged/roster (constantly [])
                  staged/affected (fn [& _]
                                    {:files ["tools/runtime-test/fixture.test.js"]
                                     :reason :declared-dependency})
                  p/shell (fn [& args]
                            (when (:dir (first args))
                              (swap! seen conj (vec (rest args))))
                            {:out "0123456789012345678901234567890123456789\n" :exit 0})
                  report/write-atomic! (fn [& _])]
      (fast/run-checks! "base"))
    (is (= [["bb" "devtour-check"]
            ["node" "fixture.test.js"]
            ["bb" "wt" "test" "--focus" "fixture"]]
           @seen))))


(deftest parent-watchdog-records-a-timeout-in-the-current-receipt
  (let [written (atom [])
        receipt {:schema 1 :complete false :sha "abc" :started-at-ms 200
                 :commands [{:argv ["slow"] :started-at-ms 210}]}
        write! (fn [path value] (swap! written conj [path value]))]
    (with-redefs [fs/exists? (constantly true)
                  clojure.core/slurp (constantly (pr-str receipt))
                  report/write-atomic! write!]
      (is (true? (fast/mark-timeout! "target/preflight/latest.edn" 190)))
      (is (= 124 (get-in (last @written) [1 :exit])))
      (is (= 124 (get-in (last @written) [1 :commands 0 :exit])))
      (is (= :watchdog-timeout (get-in (last @written) [1 :reason])))
      (reset! written [])
      (is (nil? (fast/mark-timeout! "target/preflight/latest.edn" 201)))
      (is (empty? @written)))))


(def all-paths fast/clj-scan-paths)


(deftest extra-lint-paths-widen-existing-scanners-without-duplicating-roots
  (with-redefs [select/changed-files (constantly ["src/a.clj" "docs/a.edn" "src-old/a.clj"
                                                  "a ' $(literal).edn" "removed.edn" "dev/a.json"])
                fs/regular-file? #(not= "removed.edn" %)]
    (is (= (into fast/clj-scan-paths ["a ' $(literal).edn" "docs/a.edn" "src-old/a.clj"])
           (fast/lint-paths fast/clj-scan-paths "base"))))
  (with-redefs [select/changed-files (constantly nil)]
    (is (thrown? Exception (fast/lint-paths fast/clj-scan-paths "unknown-base")))
    (is (thrown? Exception (fast/run-syntax! "unknown-base")))))


(deftest actual-scanner-task-forms-pass-extra-paths-as-separate-arguments
  (let [tasks (:tasks (edn/read-string (slurp "bb.edn")))
        outside "docs/a ' $(literal).edn"
        calls (atom [])]
    (with-redefs [fast/default-base (constantly "base")
                  select/changed-files (constantly [outside "src/a.clj"])
                  fs/regular-file? (constantly true)
                  clojure (fn [& args]
                            (swap! calls conj (into ["clojure"] args))
                            {:exit 0})
                  p/shell (fn [& args]
                            (if (= ["which clj-kondo"] (vec (rest args)))
                              {:exit 1}
                              (do (swap! calls conj (vec args)) {:exit 0})))]
      (binding [*ns* (the-ns 'ci-fast-test) *command-line-args* []]
        (doseq [task ['kondo 'splint 'cljstyle]] (eval (get-in tasks [task :task])))))
    (is (= (mapv #(into % (conj fast/clj-scan-paths outside))
                 [["clojure" "-M:kondo" "--lint"] ["clojure" "-M:splint"]
                  ["clojure" "-M:cljstyle" "check"]])
           @calls))))


(deftest formatting-tasks-work-without-an-external-clojure-cli
  (let [dir (fs/create-temp-dir)
        bin (fs/path dir "bin")
        fixture (str (fs/path dir "literal ' $(name).clj"))
        bb (str (fs/canonicalize (fs/which "bb")))]
    (try
      (fs/create-dirs bin)
      (fs/create-sym-link (fs/path bin "java") (fs/canonicalize (fs/which "java")))
      (is (not (fs/exists? (fs/path bin "clojure"))))
      (spit fixture "(ns fixture)\n\n\n(defn answer []\n(+ 1 2))\n")
      (let [run (fn [task]
                  (p/shell {:continue true :out :string :err :string
                            :extra-env {"PATH" (str bin)}}
                           bb task fixture))
            bad (run "cljstyle")
            fixed (run "fix")
            checked (run "cljstyle")]
        (is (not (zero? (:exit bad))) (str (:out bad) (:err bad)))
        (is (zero? (:exit fixed)) (str (:out fixed) (:err fixed)))
        (is (zero? (:exit checked)) (str (:out checked) (:err checked)))
        (let [result (p/shell {:continue true :out :string :err :string
                               :extra-env {"PATH" (str bin)}}
                              bb "-e" (str "(require (quote ci-fast)) (ci-fast/run-command! "
                                           (pr-str ["clojure" "-M:cljstyle" "check" fixture]) ")"))]
          (is (zero? (:exit result)) (str (:out result) (:err result))))
        (is (re-find #"\n  \(\+ 1 2\)" (slurp fixture))))
      (finally (fs/delete-tree dir)))))


(deftest unique-js-json-syntax-guards-reject-malformed-files-outside-editor
  (let [dir (fs/create-temp-dir)]
    (try
      (doseq [[name text] [["outside ' literal.js" "function broken( { "]
                           ["outside ' literal.json" "{ invalid json "]]]
        (let [path (str (fs/path dir name))]
          (spit path text)
          (let [command (first (fast/syntax-commands [path]))
                result (apply p/shell {:continue true :out :string :err :string} command)]
            (is (= path (last command)))
            (is (not (zero? (:exit result)))))))
      (finally (fs/delete-tree dir)))))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
