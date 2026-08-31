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
