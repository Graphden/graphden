(ns ^:integration graphden.packages.registry-selection-test
  "Registry selection policy executes as graph composition over canonical boundaries."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.packages.semver :as semver]
    [graphden.storage.postgres.util :as pg-util]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.exec-harness :as harness]
    [next.jdbc :as jdbc]))


(use-fixtures :once
  (harness/exec-fixture (str (ns-name *ns*)) ["core" "web" "registry"]))


(defn- run-graph
  [name args]
  (exec/execute-with-named-args harness/*context* (harness/fn-id name) args))


(deftest version-selection-preserves-canonical-order-and-constraints
  (let [versions [nil "0.0.3" "0.1.8" "1.2" "v1.2.3" "1.2.3-rc1"
                  "1.2.3+build" "1.2000.0" "2.0.0" "junk"]]
    (doseq [spec [nil "" "latest" "*" " " "1.2.3" ">=1.2" "<1.2"
                  "<=1.2.3" ">1.2000" "~>1.2" "~>1.2.3" "^0.1" "^0.0.3" "9.0"]]
      (let [constraint (if (contains? #{nil "" "latest"} spec) "*" spec)
            expected (->> versions
                          (filter #(and (some? %) (semver/satisfies-constraint? % constraint)))
                          (sort-by semver/parse-version)
                          last)]
        (is (= expected (run-graph "semver-pick" {:versions versions :spec spec}))
            (str "constraint " (pr-str spec)))))
    (testing "equal tuple order is stable and the winning spelling survives"
      (is (= "1.2.3+build"
             (run-graph "semver-pick" {:versions ["v1.2.3" "1.2.3-rc1" "1.2.3+build"]
                                       :spec "latest"}))))
    (testing "components are not capped by the marketplace's numeric rank"
      (is (= "1.2000.0"
             (run-graph "semver-pick" {:versions ["1.2000.0" "1.1000.999"] :spec nil}))))
    (testing "string coercion is only a comparison boundary, not an output conversion"
      (is (= 2 (run-graph "semver-pick" {:versions [1 2] :spec nil}))))
    (testing "no candidates or no matching candidates yields nil"
      (is (nil? (run-graph "semver-pick" {:versions [] :spec nil})))
      (is (nil? (run-graph "semver-pick" {:versions [nil] :spec "latest"}))))))


(deftest dependencies-use-one-scoped-batch-and-preserve-first-missing-order
  (let [calls (atom [])
        missing-a "selection-missing-a"
        missing-b "selection-missing-b"
        query! (fn [conn sql opts]
                 (when (some #{missing-a missing-b} (rest sql))
                   (swap! calls conj sql))
                 (jdbc/execute! conn sql opts))]
    (binding [pg-util/*jdbc-override* {:execute! query!}]
      (let [result (run-graph "missing-package-dependencies"
                              {:dependencies [:add missing-a :add
                                              :qualified/selection-missing-a missing-b]})]
        (is (= [:selection-missing-a :selection-missing-b] result))
        (is (vector? result))
        (is (= :selection-missing-b (get result 1))))
      (is (= 1 (count @calls)) "one IN-query, not one query per dependency")
      (is (= 1 (count (filter #{missing-a} (rest (first @calls)))))
          "duplicate and qualified spellings normalize before the read"))
    (testing "the graph skips acquisition entirely for empty input"
      (let [reads (atom 0)]
        ;; Compilation and identity lookups are warm; count every SQL read.
        (run-graph "missing-package-dependencies" {:dependencies []})
        (let [id (harness/fn-id "missing-package-dependencies")]
          (binding [pg-util/*jdbc-override*
                    {:execute! (fn [conn sql opts]
                                 (swap! reads inc)
                                 (jdbc/execute! conn sql opts))}]
            (let [result (exec/execute-with-named-args harness/*context* id {:dependencies []})]
              (is (= [] result))
              (is (vector? result))))
          (is (zero? @reads)))))))


(deftest selection-entry-points-are-composed
  (doseq [name ["semver-pick" "missing-package-dependencies"]]
    (is (seq (:parent-ids (sp/read-entity harness/*storage* :fn (harness/fn-id name))))
        (str name " is graph composition, not a native wrapper"))))
