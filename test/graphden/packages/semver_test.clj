(ns graphden.packages.semver-test
  "Tests for semantic-version parsing + constraint matching."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.semver :as semver]))


(deftest parse-version-test
  (testing "full + partial + suffixed versions"
    (is (= [1 2 3] (semver/parse-version "1.2.3")))
    (is (= [1 2 0] (semver/parse-version "1.2")))
    (is (= [1 0 0] (semver/parse-version "1")))
    (is (= [1 2 3] (semver/parse-version "1.2.3-rc1")))
    (is (= [1 2 3] (semver/parse-version "1.2.3+build.7")))
    (is (nil? (semver/parse-version nil)))
    (is (= [0 0 0] (semver/parse-version "not-a-version"))))
  (testing "a conventional leading v/V is stripped, not fused into major
            (was `[0 2 3]` — the `v` broke the major's digit match)"
    (is (= [1 2 3] (semver/parse-version "v1.2.3")))
    (is (= [1 2 3] (semver/parse-version "V1.2.3")))
    (is (= [1 2 3] (semver/parse-version "v1.2.3-rc1")))
    (is (= [2 0 0] (semver/parse-version "v2")))
    (is (true? (semver/satisfies-constraint? "v1.5.0" ">=v1.0.0")))
    (is (false? (semver/satisfies-constraint? "v2.0.0" "~>v1.0")))))


(deftest parse-constraint-test
  (is (= :any (:op (semver/parse-constraint nil))))
  (is (= :any (:op (semver/parse-constraint "*"))))
  (is (= :any (:op (semver/parse-constraint "  "))))
  (is (= {:op :gte :version [1 5 0]} (select-keys (semver/parse-constraint ">=1.5.0") [:op :version])))
  (is (= {:op :eq :version [1 2 0]} (select-keys (semver/parse-constraint "1.2.0") [:op :version])))
  (is (= {:op :eq :version [1 2 0]} (select-keys (semver/parse-constraint "=1.2.0") [:op :version])))
  (is (= {:op :twiddle :version [1 2 3]} (select-keys (semver/parse-constraint "~>1.2.3") [:op :version])))
  (is (= {:op :caret :version [1 2 3]} (select-keys (semver/parse-constraint "^1.2.3") [:op :version])))
  (is (= 2 (:parts (semver/parse-constraint "~>1.2"))))
  (is (= 3 (:parts (semver/parse-constraint "~>1.2.3")))))


(deftest any-and-exact-test
  (is (true? (semver/satisfies-constraint? "9.9.9" nil)))
  (is (true? (semver/satisfies-constraint? "9.9.9" "*")))
  (is (true? (semver/satisfies-constraint? "1.2.0" "1.2.0")))
  (is (true? (semver/satisfies-constraint? "1.2.0" "=1.2.0")))
  (is (false? (semver/satisfies-constraint? "1.2.1" "1.2.0"))))


(deftest comparison-ops-test
  (testing ">= / >"
    (is (true? (semver/satisfies-constraint? "1.5.0" ">=1.5.0")))
    (is (true? (semver/satisfies-constraint? "2.0.0" ">=1.5.0")))
    (is (false? (semver/satisfies-constraint? "1.4.9" ">=1.5.0")))
    (is (false? (semver/satisfies-constraint? "1.5.0" ">1.5.0")))
    (is (true? (semver/satisfies-constraint? "1.5.1" ">1.5.0"))))
  (testing "<= / <"
    (is (true? (semver/satisfies-constraint? "1.5.0" "<=1.5.0")))
    (is (false? (semver/satisfies-constraint? "1.5.1" "<=1.5.0")))
    (is (true? (semver/satisfies-constraint? "1.4.9" "<1.5.0")))
    (is (false? (semver/satisfies-constraint? "1.5.0" "<1.5.0")))))


(deftest twiddle-test
  (testing "~>1.2.3 → >=1.2.3 <1.3.0"
    (is (true? (semver/satisfies-constraint? "1.2.3" "~>1.2.3")))
    (is (true? (semver/satisfies-constraint? "1.2.9" "~>1.2.3")))
    (is (false? (semver/satisfies-constraint? "1.2.2" "~>1.2.3")))
    (is (false? (semver/satisfies-constraint? "1.3.0" "~>1.2.3"))))
  (testing "~>1.2 → >=1.2.0 <2.0.0"
    (is (true? (semver/satisfies-constraint? "1.2.0" "~>1.2")))
    (is (true? (semver/satisfies-constraint? "1.9.9" "~>1.2")))
    (is (false? (semver/satisfies-constraint? "2.0.0" "~>1.2")))
    (is (false? (semver/satisfies-constraint? "1.1.9" "~>1.2")))))


(deftest caret-test
  (testing "^1.2.3 → >=1.2.3 <2.0.0"
    (is (true? (semver/satisfies-constraint? "1.2.3" "^1.2.3")))
    (is (true? (semver/satisfies-constraint? "1.9.9" "^1.2.3")))
    (is (false? (semver/satisfies-constraint? "2.0.0" "^1.2.3")))
    (is (false? (semver/satisfies-constraint? "1.2.2" "^1.2.3"))))
  (testing "^0.2.3 → >=0.2.3 <0.3.0 (0.x special-cases minor)"
    (is (true? (semver/satisfies-constraint? "0.2.9" "^0.2.3")))
    (is (false? (semver/satisfies-constraint? "0.3.0" "^0.2.3"))))
  (testing "^0.0.3 → >=0.0.3 <0.0.4"
    (is (true? (semver/satisfies-constraint? "0.0.3" "^0.0.3")))
    (is (false? (semver/satisfies-constraint? "0.0.4" "^0.0.3")))))


(deftest nil-version-fails-non-any-test
  (is (false? (semver/satisfies-constraint? nil ">=1.0.0")))
  (is (true? (semver/satisfies-constraint? nil "*"))))
