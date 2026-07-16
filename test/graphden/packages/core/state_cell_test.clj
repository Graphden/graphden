(ns ^:integration graphden.packages.core.state-cell-test
  "End-to-end tests for the mutable-state primitives — `:swap`,
   `:swap-conj` (now a fn-def over `:swap` + closure-capture), and the
   registry-persistent `:cell`.

   Three contracts:

   1. `:swap-conj` still works through the executor — proving the
      closure-capture wiring (the conj'd `:value` is captured at the
      call site while `:current` is supplied per-swap) matches the
      old base-fn behaviour. This is the regression sentinel for every
      `:try`-journal use in secrets/crud.

   2. `:cell` PERSISTS across `execute`s. A fn-graph that pushes into a
      `:cell` and returns its count returns 1, 2, 3, … on repeated
      calls — the atom is baked once per compiled registry, not
      re-allocated per call (an `:atom` would return 1 every time).

   3. a `:cell` with a non-literal (fn-ref) `:initial-value` compiles
      fine but degrades to per-call `:atom` semantics — a single such
      cell must never fail the whole-registry compile.

   Setup mirrors `recursion-test`: full integrant `:dev` system on the
   test container so the executor sees a VersionedStorage-wrapped
   backend."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [t]
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
         (try
           (binding [*context* (:ctx graph)
                     *storage* (:storage graph)]
             (t))
           (finally (sp/close (:storage graph))))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(defn- sync!
  [fn-defs]
  (setup/sync-and-invalidate! *context* *storage* fn-defs))


;; ============================================================================
;; 1. :swap-conj through the executor — closure-capture regression
;; ============================================================================

(deftest swap-conj-accumulates-into-a-shared-atom
  (testing "`:swap-conj` conjes captured values into one per-call `:atom`"
    ;; `:t-journal` is result-cached per top-level call, so every ref
    ;; (both pushes AND the read) resolves to the SAME atom instance —
    ;; exactly the `:try`-journal idiom.
    (sync!
      [{:name :t-journal :parent :atom :args {:initial-value {:value []}}}
       {:name :t-push-1 :parent :swap-conj :args {:a :t-journal :value {:value 1}}}
       {:name :t-push-2 :parent :swap-conj :args {:a :t-journal :value {:value 2}}}
       {:name :t-journal-read :parent :deref :args {:a :t-journal}}
       {:name :t-journal-run :parent :do
        :args {:steps [:t-push-1 :t-push-2 :t-journal-read]}}])
    (is (= [1 2] (exec/execute *context* (fn-id "t-journal-run") {}))
        "both captured values landed on the shared atom, in order")))


(deftest atom-is-fresh-per-execute
  (testing "an `:atom` does NOT persist across calls — each execute starts []"
    (sync!
      [{:name :t-a2 :parent :atom :args {:initial-value {:value []}}}
       {:name :t-a2-push :parent :swap-conj :args {:a :t-a2 :value {:value 1}}}
       {:name :t-a2-read :parent :deref :args {:a :t-a2}}
       {:name :t-a2-run :parent :do :args {:steps [:t-a2-push :t-a2-read]}}])
    (let [id (fn-id "t-a2-run")]
      (is (= [1] (exec/execute *context* id {})) "call 1 → [1]")
      (is (= [1] (exec/execute *context* id {})) "call 2 → still [1] (fresh atom)"))))


;; ============================================================================
;; 2. :cell — persistence across executes
;; ============================================================================

(deftest cell-persists-across-executes
  (testing "a `:cell` accumulates across separate `execute` calls"
    (sync!
      [{:name :t-cell :parent :cell :args {:initial-value {:value []}}}
       {:name :t-cell-push :parent :swap-conj :args {:a :t-cell :value {:value 1}}}
       {:name :t-cell-deref :parent :deref :args {:a :t-cell}}
       {:name :t-cell-count :parent :count :args {:coll :t-cell-deref}}
       {:name :t-cell-run :parent :do :args {:steps [:t-cell-push :t-cell-count]}}])
    (let [id (fn-id "t-cell-run")]
      ;; Same baked atom every call → the vector grows 1 → 2 → 3.
      (is (= 1 (exec/execute *context* id {})) "call 1 → count 1")
      (is (= 2 (exec/execute *context* id {})) "call 2 → count 2 (cell persisted)")
      (is (= 3 (exec/execute *context* id {})) "call 3 → count 3"))))


(deftest cell-refs-resolve-to-the-same-instance
  (testing "two fn-defs referencing one `:cell` see the same atom"
    (sync!
      [{:name :t-cell-b :parent :cell :args {:initial-value {:value []}}}
       {:name :t-cb-push :parent :swap-conj :args {:a :t-cell-b :value {:value 9}}}
       {:name :t-cb-read :parent :deref :args {:a :t-cell-b}}
       {:name :t-cb-run :parent :do :args {:steps [:t-cb-push :t-cb-read]}}])
    (let [id (fn-id "t-cb-run")]
      (exec/execute *context* id {})
      ;; A second run reads what the first wrote, through a DIFFERENT
      ;; deref fn-def — proving `:t-cell-b` is one shared instance.
      (is (= [9 9] (exec/execute *context* id {}))
          "the deref ref and the push ref share one baked atom"))))


;; ============================================================================
;; 3. :cell with a non-literal :initial-value degrades to per-call `:atom`
;;    (graceful — a fn-ref can't be baked, but must NOT fail compile-all)
;; ============================================================================

(deftest cell-with-ref-initial-value-degrades-to-per-call
  (testing "a `:cell` whose `:initial-value` is a fn-ref compiles fine but is NOT persistent"
    (sync!
      [{:name :_t-seed-vec :parent :const :args {:value {:value []}}}
       {:name :t-ref-cell :parent :cell :args {:initial-value :_t-seed-vec}}
       {:name :t-rc-push :parent :swap-conj :args {:a :t-ref-cell :value {:value 1}}}
       {:name :t-rc-read :parent :deref :args {:a :t-ref-cell}}
       {:name :t-rc-run :parent :do :args {:steps [:t-rc-push :t-rc-read]}}])
    (let [id (fn-id "t-rc-run")]
      ;; No compile error (graceful degradation — the ref can't be baked),
      ;; and a fresh atom each call, exactly like `:atom`.
      (is (= [1] (exec/execute *context* id {})) "call 1 → [1]")
      (is (= [1] (exec/execute *context* id {}))
          "call 2 → still [1]: a ref initial-value forfeits persistence"))))
