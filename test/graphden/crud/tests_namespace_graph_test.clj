(ns ^:integration graphden.crud.tests-namespace-graph-test
  "The `tests` namespace convention end to end, over REAL fn-defs.

   Everything around this had coverage and the middle did not.
   `crud.test-runs-test` pins the runnability gate; `crud.test-autorun-test`
   pins the reverse closure; `crud.fn-execution-test/
   test-runs-discovery-run-status-test` pins discovery, run buckets and
   status derivation — but with fns whose bodies are Clojure impls
   registered by the test. `packages.core.logic-test` pins `:assert` and
   `:assert-eq` by calling `impl-of` directly. So the vocabulary was tested
   as an impl, the runner was tested with fake tests, and the authoring path
   docs/TESTS.md actually documents — compose `:assert-eq` over a fn-def,
   put it in a `tests` namespace, press Run — was never executed by anything.

   `examples/tests-namespace/fns.edn` holds the fn-defs. They are ordinary
   graph compositions, synced through the ordinary loader, run through the
   ordinary executor; this test only asks the runner what it found."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.test-runs :as test-runs]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    ;; `core` for the assertion vocabulary, `examples` for the tests
    ;; themselves — and `web`/`app` because they are not optional here:
    ;; `examples/recipe-mini-app` refs `:ring-router`, so loading
    ;; `["core" "examples"]` dies with `Unknown parent: :ring-router`.
    ;; This is the same set `layout.graph-real-test` asks for, so the two
    ;; share one golden bootstrap rather than minting a second.
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "graphden.crud.tests-namespace-graph-test"
                  ["core" "web" "app" "examples"])]
      (binding [*graph* graph]
        (try (f) (finally (sp/close (:storage graph))))))))


(defn- results-by-name
  [out]
  (into {} (map (juxt :fn-name identity)) (:results out)))


(deftest discovery-finds-the-tests-and-skips-the-scaffolding-test
  (let [names (into #{} (map :name) (test-runs/test-fn-rows (:ctx *graph*)))]
    (testing "every public fn in examples.tests is a test"
      (is (contains? names "addition-sums-its-arguments"))
      (is (contains? names "str-concatenates-its-parts"))
      (is (contains? names "empty-list-is-empty"))
      (is (contains? names "needs-an-argument-so-cannot-run"))
      (is (contains? names "intentionally-failing-example")))
    (testing "`_`-private helpers in the same namespace are not tests"
      (is (not-any? #(str/starts-with? % "_") names)
          (str "private scaffolding leaked into discovery: "
               (pr-str (sort (filter #(str/starts-with? % "_") names))))))))


(deftest assert-eq-composition-passes-and-fails-as-documented-test
  (let [out (test-runs/run-tests! (:ctx *graph*) {})
        by-name (results-by-name out)]
    (testing "a satisfied :assert-eq is a pass"
      (is (= :succeeded (:status (get by-name "addition-sums-its-arguments")))
          (str "2 + 3 = 5 must pass: " (pr-str (get by-name "addition-sums-its-arguments"))))
      (is (= :succeeded (:status (get by-name "str-concatenates-its-parts")))))

    (testing "a satisfied :assert is a pass"
      (is (= :succeeded (:status (get by-name "empty-list-is-empty")))))

    (testing "a violated :assert-eq is a fail, and says which invariant broke"
      (let [r (get by-name "intentionally-failing-example")]
        (is (= :failed (:status r)))
        (is (str/includes? (str/lower-case (str (:error r))) "assert")
            (str "the assertion must be legible in the error: " (pr-str (:error r))))))

    (testing "an unbound arg is not-runnable — a distinct outcome from failed"
      (let [r (get by-name "needs-an-argument-so-cannot-run")]
        (is (= :not-runnable (:status r)))
        (is (str/includes? (str (:error r)) "expected")
            (str "the blocking arg must be named: " (pr-str (:error r))))))

    (testing "the summary buckets match"
      (is (= 3 (:passed out)))
      (is (= 1 (:failed out)))
      (is (= 1 (:other out))
          "not-runnable counts as neither passed nor failed")
      (is (= 5 (:total out))))))


(deftest failing-assert-eq-carries-both-operands-test
  ;; The operands ride `:error-data`, never the message — a secret-tainted
  ;; value must not reach the human-visible string. That split is what makes
  ;; the failure useful AND safe, so pin that the data half is populated.
  (let [out (test-runs/run-tests!
              (:ctx *graph*)
              {:fn-ids [(str (:id (first (filter #(= "intentionally-failing-example"
                                                     (:name %))
                                                 (test-runs/test-fn-rows (:ctx *graph*))))))]})
        r (first (:results out))]
    (is (= 1 (:total out)) "the :fn-ids subset ran exactly one test")
    (is (= :failed (:status r)))
    (let [data (:error-data r)]
      (is (some? data) "a failed assertion records its operands")
      (is (= 5 (:actual data)) (str "actual operand: " (pr-str data)))
      (is (= 6 (:expected data)) (str "expected operand: " (pr-str data))))))
