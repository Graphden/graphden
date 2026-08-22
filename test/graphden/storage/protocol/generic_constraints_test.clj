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
