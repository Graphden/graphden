(ns ci-fast-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-fast :as fast]
    [ci-select :as select]
    [clojure.edn :as edn]
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
