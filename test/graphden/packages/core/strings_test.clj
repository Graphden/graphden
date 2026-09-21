(ns graphden.packages.core.strings-test
  "Unit tests for `core.strings` base-fn impls — currently the regex
   primitives. Mirrors `system_test` / `logic_test`: the package's
   impls.clj is slurp+eval'd via the loader's `load-module-impls`."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.config :as sp-config]
    [graphden.test-infra.impls :as impls]))


;; The 100ms production default guards against pathological patterns;
;; on a cold shared CI runner the FIRST Pattern compile can blow it on
;; JIT/classload alone (GitHub run 33352560953 failed exactly so). The
;; tests here exercise regex SEMANTICS, not the guard's tightness —
;; give them slack. (No timing-based test of the guard itself: a
;; too-small budget is a RACE against the compile thread, not a
;; deterministic trip.) One `use-fixtures` call — a second one
;; REPLACES the first.
(use-fixtures :once
  (impls/impls-fixture "core" "strings")
  (fn [t]
    (binding [sp-config/*regex-compile-timeout-ms* 5000]
      (t))))


;; ============================================================================
;; :re-replace — regex replace (the regex sibling of literal :str-replace).
;; Added for the :render-prometheus graph decomposition (key sanitising).
;; ============================================================================

(deftest re-replace-test
  (let [impl (impls/impl-of :re-replace)]
    (testing "every regex match is replaced"
      (is (= "os_load_avg"
             (impl {:string (delay "os load-avg")
                    :pattern (delay "[^a-z0-9_]")
                    :replacement (delay "_")}
                   nil))))
    (testing "group refs work in the replacement"
      (is (= "b-a"
             (impl {:string (delay "a-b")
                    :pattern (delay "(\\w+)-(\\w+)")
                    :replacement (delay "$2-$1")}
                   nil))))
    (testing "no match → string unchanged"
      (is (= "abc"
             (impl {:string (delay "abc")
                    :pattern (delay "[0-9]+")
                    :replacement (delay "_")}
                   nil))))
    (testing "nil string flows through unchanged"
      (is (nil? (impl {:string (delay nil)
                       :pattern (delay "x")
                       :replacement (delay "_")}
                      nil))))
    (testing "oversized pattern rejected by the safe-compile boundary"
      (is (thrown? clojure.lang.ExceptionInfo
            (impl {:string (delay "abc")
                   :pattern (delay (str/join (repeat 10000 "a")))
                   :replacement (delay "_")}
                  nil))))))


;; ============================================================================
;; :re-groups / :re-seq / :str-index-of / :str-pad-* — the extraction batch
;; ============================================================================

(deftest re-groups-extracts-the-first-match-with-its-groups
  (let [f (impls/impl-of :re-groups)]
    (is (= ["2026-09-21" "2026" "09" "21"]
           (f {:string (delay "on 2026-09-21 and 2026-10-01") :pattern (delay "(\\d{4})-(\\d{2})-(\\d{2})")} nil)))
    (testing "no groups → the whole match alone; an unmatched optional group → nil"
      (is (= ["abc"] (f {:string (delay "xabcx") :pattern (delay "abc")} nil)))
      (is (= ["ab" nil] (f {:string (delay "ab") :pattern (delay "ab(c)?")} nil))))
    (testing "no match / non-string → nil"
      (is (nil? (f {:string (delay "zzz") :pattern (delay "\\d")} nil)))
      (is (nil? (f {:string (delay nil) :pattern (delay "\\d")} nil))))))


(deftest re-seq-lists-every-match
  (let [f (impls/impl-of :re-seq)]
    (is (= [["a1" "1"] ["a2" "2"]] (f {:string (delay "a1 b a2") :pattern (delay "a(\\d)")} nil)))
    (is (= [["x"] ["x"]] (f {:string (delay "xox") :pattern (delay "x")} nil)))
    (is (= [] (f {:string (delay "none") :pattern (delay "\\d")} nil)))
    (is (= [] (f {:string (delay 42) :pattern (delay "\\d")} nil)))))


(deftest str-index-of-is-nil-when-absent
  (let [f (impls/impl-of :str-index-of)]
    (is (= 2 (f {:string (delay "abcabc") :substring (delay "c")} nil)))
    (is (zero? (f {:string (delay "abc") :substring (delay "")} nil)))
    (is (nil? (f {:string (delay "abc") :substring (delay "z")} nil)))
    (is (nil? (f {:string (delay nil) :substring (delay "z")} nil)))))


(deftest str-pad-left-and-right
  (let [l (impls/impl-of :str-pad-left) r (impls/impl-of :str-pad-right)]
    (is (= "007" (l {:string (delay "7") :length (delay 3) :pad (delay "0")} nil)))
    (is (= "7  " (r {:string (delay "7") :length (delay 3) :pad (delay " ")} nil)))
    (testing "a multi-character pad is cycled and cut to the room left"
      (is (= "abab7" (l {:string (delay "7") :length (delay 5) :pad (delay "ab")} nil)))
      (is (= "7aba" (r {:string (delay "7") :length (delay 4) :pad (delay "ab")} nil))))
    (testing "already long enough → unchanged; a blank pad falls back to a space"
      (is (= "hello" (l {:string (delay "hello") :length (delay 3) :pad (delay "0")} nil)))
      (is (= "  x" (l {:string (delay "x") :length (delay 3) :pad (delay "")} nil))))))
