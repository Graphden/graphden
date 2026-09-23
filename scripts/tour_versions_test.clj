(ns tour-versions-test
  "Self-test for the lesson-edition guard (`scripts/tour_versions.clj`).
   Runs as the `tour-versions-selftest` registry check (`bb tour-versions-test`):
   a guard that passes too much fails silently — a changed lesson ships at an
   edition readers already finished, and nobody is told to re-read.

   Standalone bb script: exit 0 = PASS, 1 = FAIL."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests testing]]
    [tour-versions :as tv]))


(defn- lesson
  [slug version fp]
  {:id (keyword slug) :slug slug :version version :fingerprint fp})


(defn- problems
  [ls recorded]
  (vec (tv/problems ls recorded)))


(deftest recorded-at-current-version-and-steps-passes
  (is (empty? (problems [(lesson "a" 2 "fp")] {"a" {:version 2 :steps "fp"}}))))


(deftest steps-changed-at-the-same-version-fails
  (let [[p :as ps] (problems [(lesson "a" 2 "new")] {"a" {:version 2 :steps "old"}})]
    (is (= 1 (count ps)))
    (is (str/includes? p "bump it"))))


(deftest bump-without-re-record-fails
  (testing "the steps changed and the version was bumped, but nobody re-recorded"
    (let [[p :as ps] (problems [(lesson "a" 3 "new")] {"a" {:version 2 :steps "old"}})]
      (is (= 1 (count ps)))
      (is (str/includes? p "record edition 3"))))
  (testing "a bumped version whose steps then change again cannot slip through"
    (is (seq (problems [(lesson "a" 3 "newer")] {"a" {:version 2 :steps "old"}})))))


(deftest unrecorded-lesson-fails
  (testing "a new lesson at edition 1 must be recorded"
    (let [[p :as ps] (problems [(lesson "n" 1 "fp")] {})]
      (is (= 1 (count ps)))
      (is (str/includes? p "record edition 1"))))
  (testing "a new lesson must start at edition 1"
    (is (str/includes? (first (problems [(lesson "n" 4 "fp")] {})) "start at :version 1"))))


(deftest version-must-be-a-positive-integer
  (is (= 1 (count (problems [(lesson "a" 0 "fp")] {"a" {:version 0 :steps "fp"}}))))
  (is (= 1 (count (problems [(lesson "a" "2" "fp")] {})))))


(deftest vanished-lesson-is-fine
  (is (empty? (problems [] {"gone" {:version 1 :steps "fp"}}))))


(let [{:keys [fail error]} (run-tests 'tour-versions-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
