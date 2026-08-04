(ns graphden.types.diagnostics-test
  "Unit tests for the per-branch type-check diagnostics store —
   pure in-memory API + the `*diagnostics-override*` isolation seam.

   Every test binds the override to a fresh atom so nothing leaks
   into the process-global store (the same pattern the kaocha
   parallel plugin applies per NS-thread)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.diagnostics :as diag]))


(defn- with-fresh-store*
  [thunk]
  (binding [diag/*diagnostics-override* (atom {})]
    (thunk)))


(defmacro with-fresh-store
  [& body]
  `(with-fresh-store* (fn [] ~@body)))


(deftest record-and-read-test
  (with-fresh-store
    (let [branch (random-uuid)
          fn-id (random-uuid)
          d {:message "boom" :expected :int :actual :text}]
      (testing "record! then errors-for-fn round-trips the diagnostics vector"
        (diag/record! branch fn-id [d])
        (is (= [d] (diag/errors-for-fn branch fn-id))))

      (testing "branch-errors returns the per-fn map"
        (is (= {fn-id [d]} (diag/branch-errors branch))))

      (testing "error-count sums diagnostics across fns"
        (diag/record! branch (random-uuid) [d d])
        (is (= 3 (diag/error-count branch))))

      (testing "re-record replaces, not appends"
        (diag/record! branch fn-id [d d])
        (is (= [d d] (diag/errors-for-fn branch fn-id)))))))


(deftest nil-branch-id-test
  ;; nil branch-id = default branch / no branch-router context —
  ;; a first-class key, not an error.
  (with-fresh-store
    (let [fn-id (random-uuid)
          d {:message "default-branch failure"}]
      (diag/record! nil fn-id [d])
      (is (= [d] (diag/errors-for-fn nil fn-id)))
      (is (= 1 (diag/error-count nil)))
      (diag/clear-fn! nil fn-id)
      (is (nil? (diag/errors-for-fn nil fn-id))))))


(deftest empty-diags-clears-test
  (with-fresh-store
    (let [branch (random-uuid)
          fn-id (random-uuid)]
      (diag/record! branch fn-id [{:message "boom"}])
      (testing "record! with nil diags clears the entry"
        (diag/record! branch fn-id nil)
        (is (nil? (diag/errors-for-fn branch fn-id))))
      (diag/record! branch fn-id [{:message "boom"}])
      (testing "record! with [] clears the entry too"
        (diag/record! branch fn-id [])
        (is (nil? (diag/errors-for-fn branch fn-id)))
        (is (zero? (diag/error-count branch)))))))


(deftest clear-fn-prunes-empty-branch-test
  (with-fresh-store
    (let [branch (random-uuid)
          fn-id (random-uuid)]
      (diag/record! branch fn-id [{:message "x"}])
      (diag/clear-fn! branch fn-id)
      (testing "the branch key itself is pruned when its last fn clears"
        (is (= {} (diag/snapshot-for-isolation))))
      (testing "clear-fn! on an absent entry is a no-op"
        (diag/clear-fn! branch (random-uuid))
        (is (= {} (diag/snapshot-for-isolation)))))))


(deftest clear-branch-test
  (with-fresh-store
    (let [a (random-uuid)
          b (random-uuid)
          fn-id (random-uuid)]
      (diag/record! a fn-id [{:message "on-a"}])
      (diag/record! b fn-id [{:message "on-b"}])
      (diag/clear-branch! a)
      (testing "only the cleared branch is dropped"
        (is (zero? (diag/error-count a)))
        (is (= {} (diag/branch-errors a)))
        (is (= [{:message "on-b"}] (diag/errors-for-fn b fn-id)))))))


(deftest branch-independence-test
  ;; The same fn-id can be broken on branch A and clean on branch B —
  ;; per-branch keying keeps the recordings independent.
  (with-fresh-store
    (let [a (random-uuid)
          b (random-uuid)
          fn-id (random-uuid)]
      (diag/record! a fn-id [{:message "broken on A"}])
      (is (some? (diag/errors-for-fn a fn-id)))
      (is (nil? (diag/errors-for-fn b fn-id)))
      (testing "clearing on A doesn't disturb a later recording on B"
        (diag/record! b fn-id [{:message "now broken on B"}])
        (diag/clear-fn! a fn-id)
        (is (nil? (diag/errors-for-fn a fn-id)))
        (is (= [{:message "now broken on B"}] (diag/errors-for-fn b fn-id)))))))


(deftest override-isolation-test
  (let [probe-branch (random-uuid)
        fn-id (random-uuid)]
    (testing "writes under a bound override never reach the root store"
      (binding [diag/*diagnostics-override* (atom {})]
        (diag/record! probe-branch fn-id [{:message "override-only"}])
        (is (= 1 (diag/error-count probe-branch))))
      ;; Outside the binding: the root store never saw the branch.
      (is (nil? (diag/errors-for-fn probe-branch fn-id)))
      (is (zero? (diag/error-count probe-branch))))

    (testing "snapshot-for-isolation reads the ACTIVE store"
      (binding [diag/*diagnostics-override*
                (atom {probe-branch {fn-id [{:message "seeded"}]}})]
        (is (= {probe-branch {fn-id [{:message "seeded"}]}}
               (diag/snapshot-for-isolation)))))))


(deftest from-ex-test
  (testing "selects the meaningful ex-data keys, prunes nils, keeps :message"
    (let [e (ex-info "Type-check failed in fn-def :x"
                     {:type :types/check-failed
                      :fn-name :x
                      :parent-name :add
                      :arg-name :a
                      :binding {:value "s"}
                      :expected :int
                      :actual :text
                      :constraint nil
                      :parent :add          ; not a selected key
                      :source-line 12})
          d (diag/from-ex e)]
      (is (= {:type :types/check-failed
              :fn-name :x
              :parent-name :add
              :arg-name :a
              :binding {:value "s"}
              :expected :int
              :actual :text
              :source-line 12
              :message "Type-check failed in fn-def :x"}
             d))))

  (testing "an ex-info with no relevant data still yields a message-carrying map"
    (is (= {:message "bare"} (diag/from-ex (ex-info "bare" {}))))))
