(ns ci-select-test
  "Self-test for the CI selection core (`scripts/ci_select.clj`) + validation
   of the real `scripts/checks.edn`. Runs as the `ci-selftest` registry check
   (`bb ci-test`): a bug in the selector does not fail loudly — it silently
   under-runs the landing gate — so the mechanism guards itself.

   Standalone bb script: exit 0 = PASS, 1 = FAIL."
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


(load-file "scripts/ci_select.clj")


;; ---------------------------------------------------------------------------
;; The real registry: shape-valid, tasks exist, canonical paths map as intended
;; ---------------------------------------------------------------------------

(def real-registry (ci-select/read-registry "scripts/checks.edn"))


(deftest real-registry-validates
  (is (= real-registry (ci-select/validate-registry! real-registry))))


(deftest real-registry-bb-tasks-exist
  ;; bb.edn task bodies are code (not pure EDN), so ask bb itself.
  (let [out (:out (p/shell {:out :string} "bb" "tasks"))
        listed (into #{}
                     (keep #(second (re-matches #"^(\S+).*" %)))
                     (str/split-lines out))]
    (doseq [c real-registry]
      (is (contains? listed (:bb c))
          (str "checks.edn " (:name c) " points at bb task " (:bb c)
               " which `bb tasks` does not list")))))


(defn run-names
  [changed]
  (into #{} (map :name)
        (:run (ci-select/partition-checks real-registry {:changed changed}))))


(deftest canonical-path-mapping
  ;; Drift guard for the real :relevant patterns: the paths below MUST keep
  ;; selecting these checks. A pattern edit that breaks one of these rows
  ;; silently green-lights unchecked changes — exactly the failure the
  ;; checks.edn header warns about.
  (let [src (run-names #{"src/graphden/executor/core.clj"})
        docs (run-names #{"docs/tutorial/01-intro.md"})
        js (run-names #{"resources/packages/app/editor/editor-main.js"})
        iac (run-names #{"deploy/kind/postgres.yaml"})
        compose (run-names #{"docker-compose.yml"})
        sh (run-names #{"dev/new-script.sh"})]
    (is (every? src ["clj-kondo" "splint" "cljstyle" "tests-unit" "tests-perf" "perf" "devtour"]))
    (is (not-any? docs ["tests-unit" "tests-perf" "perf" "clj-kondo" "biome"]))
    (is (every? docs ["markdownlint" "lychee" "typos" "gitleaks" "commitlint"]))
    (is (every? js ["biome" "tests-js" "tests-unit"]))
    (is (contains? iac "trivy") "deploy/ IaC manifests are trivy-scanned — must stay relevant")
    (is (contains? compose "trivy") "docker-compose is trivy-scanned — must stay relevant")
    (is (contains? sh "shellcheck"))))


;; ---------------------------------------------------------------------------
;; validate-registry! rejections
;; ---------------------------------------------------------------------------

(defn rejects?
  [registry]
  (try (ci-select/validate-registry! registry) false
       (catch Exception _ true)))


(deftest validation-rejects-malformed-entries
  (let [ok {:name "x" :bb "x" :timeout 1000 :group :clj}]
    (is (not (rejects? [ok])))
    (is (rejects? [(dissoc ok :timeout)]) "missing :timeout")
    (is (rejects? [(assoc ok :group :tst)]) "typo'd :group")
    (is (rejects? [(assoc ok :relevant ["[unclosed"])]) "bad regex")
    (is (rejects? [(assoc ok :relevant "\\.clj$")]) ":relevant must be a vector")
    (is (rejects? [ok (assoc ok :group :web)]) "duplicate names")))


;; ---------------------------------------------------------------------------
;; partition-checks semantics (synthetic registry)
;; ---------------------------------------------------------------------------

(def synth
  [{:name "lint-a" :bb "a" :timeout 1 :group :clj :relevant ["\\.clj$"]}
   {:name "lint-b" :bb "b" :timeout 1 :group :web :relevant ["\\.js$"]}
   {:name "always" :bb "c" :timeout 1 :group :sec}
   {:name "suite" :bb "d" :timeout 1 :group :test :relevant ["^src/"]}
   {:name "grade" :bb "e" :timeout 1 :group :post-test :relevant ["^src/"]}])


(defn names-of
  [parts k]
  (into #{} (map :name) (get parts k)))


(deftest no-scoping-runs-everything
  (let [parts (ci-select/partition-checks synth {})]
    (is (= #{"lint-a" "lint-b" "always" "suite" "grade"} (names-of parts :run)))
    (is (empty? (:scoped parts)))
    (is (empty? (:manual parts)))))


(deftest diff-scoping-splits-and-keeps-always-on
  (let [parts (ci-select/partition-checks synth {:changed #{"src/x.clj"}})]
    (is (= #{"lint-a" "always" "suite" "grade"} (names-of parts :run)))
    (is (= #{"lint-b"} (names-of parts :scoped)))))


(deftest post-test-orphaned-when-no-test-runs
  ;; grade's OWN pattern matches, but suite was manually skipped — grading a
  ;; stale perf run would pass on a regression it never saw.
  (let [parts (ci-select/partition-checks synth {:changed #{"src/x.clj"}
                                                 :skip-set #{"suite"}})]
    (is (= #{"suite"} (names-of parts :manual)))
    (is (contains? (names-of parts :scoped) "grade"))
    (is (not (contains? (names-of parts :run) "grade")))))


(deftest skip-by-group-name-removes-members
  (let [parts (ci-select/partition-checks synth {:skip-set #{"clj" "test"}})]
    (is (= #{"lint-a" "suite"} (names-of parts :manual)))
    (is (contains? (names-of parts :scoped) "grade") "post-test orphaned by group skip")
    (is (= #{"lint-b" "always"} (names-of parts :run)))))


(deftest group-restriction-composes-with-scoping
  (let [parts (ci-select/partition-checks synth {:wanted #{:clj :web}
                                                 :changed #{"src/x.clj"}})]
    (is (= #{"lint-a"} (names-of parts :run)))
    (is (= #{"lint-b"} (names-of parts :scoped)))
    (is (not-any? #{"suite" "grade" "always"}
                  (into (names-of parts :run) (names-of parts :scoped)))
        "checks outside --groups are not even listed as scoped")))


(deftest relevant-conservative-defaults
  (is (ci-select/relevant? {:relevant ["\\.clj$"]} nil) "no diff info → relevant")
  (is (ci-select/relevant? {} #{"anything"}) "no :relevant key → always relevant")
  (is (not (ci-select/relevant? {:relevant ["\\.clj$"]} #{"a.md"}))))


(deftest empty-groups-arg-is-refused
  ;; `--groups ""` would select ZERO checks — a green run that ran nothing.
  (is (thrown? Exception (ci-select/select-checks real-registry ["--groups" ""])))
  (is (thrown? Exception (ci-select/select-checks real-registry ["--groups" " , "]))))


(let [{:keys [fail error]} (run-tests 'ci-select-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
