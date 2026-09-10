(ns ^:integration graphden.packages.platform-tests-test
  "The PLATFORM's own in-graph tests — the shipped packages' `*.tests`
   namespaces (core.tests, web.tests, app-base.tests, …) — run through
   the real runner over the real synced graph, and every one of them
   must pass.

   Those fn-defs are the graph's analogue of a unit suite for the
   fn-def layer: `docs/TESTS.md` § Platform tests. Nothing else runs
   them — a tenant's [Run all] skips platform tests by design (the
   `:platform?` flag on `test-runs/run-tests!`), so this test IS the
   gate: a platform test that fails, or that cannot run (an unbound
   free arg, a recorded type error), reds the suite here.

   Also pins the two halves of the platform flag: the default run
   excludes exactly the package-owned tests, and the status join
   marks them."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.test-runs :as test-runs]
    [graphden.executor.test-setup :as setup]))


(def ^:private package-set
  "The shipped first-party packages — the prod `:package-names` list."
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "graphden.packages.platform-tests-test"
                  package-set)]
      (binding [*graph* graph]
        (try (f) (finally (setup/close-graph! graph)))))))


(defn- failure-lines
  [out]
  (for [r (:results out)
        :when (not= :succeeded (:status r))]
    (str "  " (:fn-name r) " → " (name (:status r))
         (when-let [e (:error r)] (str ": " e))
         (when-let [d (:error-data r)] (str " " (pr-str d))))))


(deftest every-platform-test-passes
  (let [rows (test-runs/test-fn-rows (:ctx *graph*))
        platform (filter test-runs/platform-test-row? rows)]
    (testing "the shipped packages carry platform tests"
      (is (seq platform) "no `*.tests` namespace in the shipped packages"))
    (testing "every platform test is package-owned (synced this boot)"
      (is (= (count rows) (count platform))
          (str "non-platform tests in a package bundle: "
               (pr-str (map :name (remove test-runs/platform-test-row? rows))))))
    (let [out (test-runs/run-tests! (:ctx *graph*) {:platform? true})]
      (testing "the platform suite runs in full"
        (is (= (count platform) (:total out))))
      (testing "and every test passes — no failed, no not-runnable, no rejected"
        (is (and (zero? (:failed out)) (zero? (:other out)))
            (str "\n" (str/join "\n" (failure-lines out))))))))


(deftest platform-tests-are-flagged-and-skipped-by-default
  (testing "the status join flags platform rows"
    (let [rows (test-runs/tests-with-statuses (:ctx *graph*))]
      (is (seq rows))
      (is (every? :platform? rows))))
  (testing "a default (org) run skips them — nothing else is on this branch"
    (let [out (test-runs/run-tests! (:ctx *graph*) {})]
      (is (zero? (:total out))))))
