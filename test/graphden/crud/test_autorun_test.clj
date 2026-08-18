(ns graphden.crud.test-autorun-test
  "Pure-selection tests for the write-triggered test auto-run. The
   debounced runner + invalidate! wiring is exercised end-to-end by
   the live stack; here we pin the selection semantics — reverse-
   closure membership, the purity gate, and the cold-index no-op."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.test-autorun :as autorun]))


(def ^:private reverse-deps
  ;; :util ← :mid ← :test-a ; :util ← :test-b ; :other ← :test-c
  {:util #{:mid :test-b}
   :mid #{:test-a}
   :other #{:test-c}})


(def ^:private test-rows
  [{:id :test-a} {:id :test-b} {:id :test-c}])


(deftest affected-test-ids-walks-the-reverse-closure
  (testing "a deep dependency edit reaches tests through intermediaries"
    (is (= [:test-a :test-b]
           (autorun/affected-test-ids reverse-deps test-rows #{:util}
                                      (constantly true)))))
  (testing "an unrelated edit reaches only its own dependents"
    (is (= [:test-c]
           (autorun/affected-test-ids reverse-deps test-rows #{:other}
                                      (constantly true)))))
  (testing "editing a test itself re-runs it (seeds are in the blast)"
    (is (= [:test-a]
           (autorun/affected-test-ids reverse-deps test-rows #{:test-a}
                                      (constantly true))))))


(deftest affected-test-ids-gates-on-purity
  (testing "non-pure tests never auto-run"
    (is (= [:test-b]
           (autorun/affected-test-ids reverse-deps test-rows #{:util}
                                      #{:test-b}))
        "purity predicate filters the blast intersection")))


(deftest affected-test-ids-no-ops-safely
  (testing "cold index / empty seeds / no tests → empty, never a throw"
    (is (= [] (autorun/affected-test-ids nil test-rows #{:util} (constantly true))))
    (is (= [] (autorun/affected-test-ids reverse-deps test-rows nil (constantly true))))
    (is (= [] (autorun/affected-test-ids reverse-deps [] #{:util} (constantly true))))))
