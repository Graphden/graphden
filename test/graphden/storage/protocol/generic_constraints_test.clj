(ns graphden.storage.protocol.generic-constraints-test
  "Tests for `graphden.storage.protocol.generic-constraints` — the
   reusable StorageCRUD-driven dependency-cycle check that backends
   without an optimised constraint implementation fall back on."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-constraints :as gc]))


(use-fixtures :once (setup/create-container-fixture))


;; ============================================================================
;; constraint-type-ref-names — pure
;; ============================================================================

(deftest constraint-type-ref-names-test
  (testing "union branches surface as bare-name strings, op head dropped"
    (is (= #{"my-int" "my-text"}
           (gc/constraint-type-ref-names [:union :my-int :my-text]))))

  (testing "refine — base name kept, atomic op + literal dropped"
    (is (= #{"int"} (gc/constraint-type-ref-names [:refine :int [:> 0]]))))

  (testing "fn-type — names buried in the args-map and ret are found"
    (is (= #{"my-arg-type" "my-ret"}
           (gc/constraint-type-ref-names [:fn {:req :my-arg-type} :my-ret]))))

  (testing "compound of pure ops + numbers → empty set"
    (is (= #{} (gc/constraint-type-ref-names [:and [:> 0] [:< 10]]))))

  (testing "nil / non-collection → empty set"
    (is (= #{} (gc/constraint-type-ref-names nil)))))


(deftest validate-no-dependency-cycle-test
  (testing "a nil ref and two unrelated fns → nil (no cycle)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [a (setup/create-base-fn! storage "gcc-a")
              b (setup/create-base-fn! storage "gcc-b")]
          (is (nil? (gc/validate-no-dependency-cycle! storage (:id a) nil)))
          (is (nil? (gc/validate-no-dependency-cycle! storage (:id a) (:id b)))))
        (finally (sp/close storage)))))

  (testing "a self-reference is allowed (recursion, depth-bounded at runtime)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [a (setup/create-base-fn! storage "gcc-self")]
          (is (nil? (gc/validate-no-dependency-cycle! storage (:id a) (:id a)))))
        (finally (sp/close storage)))))

  (testing "a constraint-bearing fn is walked without error (non-cyclic → nil)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [a (setup/create-base-fn! storage "gcc-plain")
              ;; A fn whose :constraint vector carries type-row name
              ;; refs — exercises the constraint-ref branch of the
              ;; dependency-chain walker.
              b (sp/create-entity storage :fn
                                  {:name "gcc-union" :parent-ids []
                                   :constraint [:union :int :text]})]
          (is (nil? (gc/validate-no-dependency-cycle! storage (:id a) (:id b)))))
        (finally (sp/close storage)))))

  (testing "a binding-ref cycle a→b→c→a is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "gcc-base")
              slot-x (setup/create-slot! storage "x" (:id base))
              _      (setup/attach-slot! storage (:id base) (:id slot-x) 0)
              a      (setup/create-composed-fn! storage "gcc-cyc-a" (:id base))
              b      (setup/create-composed-fn! storage "gcc-cyc-b" (:id base))
              c      (setup/create-composed-fn! storage "gcc-cyc-c" (:id base))]
          (setup/bind-ref! storage (:id b) (:id slot-x) (:id c))
          (setup/bind-ref! storage (:id c) (:id slot-x) (:id a))
          ;; Adding a→b would close a→b→c→a.
          (let [ex (try (gc/validate-no-dependency-cycle! storage (:id a) (:id b))
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= :constraint-violation/dependency-cycle (:type (ex-data ex))))))
        (finally (sp/close storage))))))


(deftest dependency-closure-test
  (testing "a cycle that closes only through an IDENTITY edge is legal"
    ;; a→b (call), b→c (call), c→a through a `:fn-ref` slot: c names a
    ;; without evaluating it, so a is NOT in b's dependency closure and
    ;; the a→b edge is allowed — two services may name each other.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "gcc-id-base")
              slot-x (setup/create-slot! storage "x" (:id base))
              slot-s (setup/create-slot! storage "s" :fn-ref)
              _      (setup/attach-slot! storage (:id base) (:id slot-x) 0)
              _      (setup/attach-slot! storage (:id base) (:id slot-s) 1)
              a      (setup/create-composed-fn! storage "gcc-id-a" (:id base))
              b      (setup/create-composed-fn! storage "gcc-id-b" (:id base))
              c      (setup/create-composed-fn! storage "gcc-id-c" (:id base))]
          (setup/bind-ref! storage (:id b) (:id slot-x) (:id c))
          (setup/bind-ref! storage (:id c) (:id slot-s) (:id a))
          (is (= #{(:id c) (:id base) (:return-type-fn-id base)}
                 (gc/dependency-closure storage (:id b)))
              "the closure stops at the identity edge (the base's declared
               return type-row is a dependency like any other)")
          (is (nil? (gc/validate-no-dependency-cycle! storage (:id a) (:id b))))
          ;; The same edge through the CALL slot is the cycle it always was.
          (setup/bind-ref! storage (:id c) (:id slot-x) (:id a))
          (is (= :constraint-violation/dependency-cycle
                 (:type (ex-data (try (gc/validate-no-dependency-cycle! storage (:id a) (:id b))
                                      (catch clojure.lang.ExceptionInfo e e)))))))
        (finally (sp/close storage)))))

  (testing "a closure larger than the old visit cap is walked, not refused"
    ;; The per-fn walk capped itself at 1000 visits (`chain-too-deep`) —
    ;; naming the editor's own listener (a closure of thousands of fns)
    ;; failed the write. The resolver-backed closure is bounded by the
    ;; graph, like a compile.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "gcc-big-base")
              slot-x (setup/create-slot! storage "x" (:id base))
              _      (setup/attach-slot! storage (:id base) (:id slot-x) 0)
              n      1100
              fns    (mapv #(setup/create-composed-fn! storage (str "gcc-big-" %) (:id base))
                           (range n))]
          ;; A chain f0 → f1 → … → f(n-1) through the call slot.
          (doseq [[from to] (partition 2 1 fns)]
            (setup/bind-ref! storage (:id from) (:id slot-x) (:id to)))
          (let [closure (gc/dependency-closure storage (:id (first fns)))]
            (is (= (inc n) (count closure))
                "every other chain member, the shared base and its return type-row")
            (is (contains? closure (:id (last fns)))))
          (is (nil? (gc/validate-no-dependency-cycle! storage (:id (first fns)) (:id (last fns))))
              "head → tail: the tail depends on nothing but the base")
          (is (= :constraint-violation/dependency-cycle
                 (:type (ex-data (try (gc/validate-no-dependency-cycle!
                                        storage (:id (last fns)) (:id (first fns)))
                                      (catch clojure.lang.ExceptionInfo e e)))))
              "tail → head closes the 1100-long loop — found, not refused as too deep"))
        (finally (sp/close storage))))))
