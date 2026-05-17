(ns graphden.executor.compile-test
  "Tests for `graphden.executor.compile` targeting paths the executor
   integration tests don't reach:

   - the pure delta-invalidation index (forward-deps-of /
     build-reverse-deps / transitive-blast)
   - resolve-impl's compile-error throws
   - the sequence-binding runtime path (make-seq-entry /
     resolve-seq-item) via a real compile + execute."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile :as compile]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))
(use-fixtures :each exec/with-clean-registry)


(defn- graph-snapshot
  [storage]
  {:fns        (sp/query-entities storage :fn {})
   :slots      (sp/query-entities storage :slot {})
   :fn-slots   (sp/query-entities storage :fn-slot {})
   :bindings   (sp/query-entities storage :binding {})
   :list-items (sp/query-entities storage :binding-list-item {})})


;; ============================================================================
;; Delta-invalidation index — pure
;; ============================================================================

(deftest forward-deps-of-test
  (testing "edge sources: parent-ids, FK type refs, binding + list-item refs"
    (let [a (random-uuid) p (random-uuid) base (random-uuid)
          ref (random-uuid) tov (random-uuid) item-ref (random-uuid)
          bid (random-uuid)
          graph {:fns {a {:id a :parent-ids [p] :base-fn-id base
                          :element-fn-id nil :return-type-fn-id nil}}
                 :bindings [{:id bid :fn-id a :ref-fn-id ref
                             :type-override-fn-id tov}]
                 :list-items [{:binding-id bid :ref-fn-id item-ref}]}]
      (is (= #{p base ref tov item-ref}
             (compile/forward-deps-of a graph)))))

  (testing "a fn with no edges → empty set"
    (let [x (random-uuid)]
      (is (= #{} (compile/forward-deps-of
                   x {:fns {x {:id x :parent-ids []}}
                      :bindings [] :list-items []}))))))


(deftest build-reverse-deps-test
  (testing "forward edges are inverted into {dep → #{dependers}}"
    (let [a (random-uuid) b (random-uuid)
          ;; a has parent b → a depends on b → reverse: b → #{a}.
          graph {:fns {a {:id a :parent-ids [b]}
                       b {:id b :parent-ids []}}
                 :bindings [] :list-items []}
          rev (compile/build-reverse-deps graph)]
      (is (= #{a} (get rev b)))
      (is (nil? (get rev a))))))


(deftest transitive-blast-test
  (testing "the inverse closure includes seeds and everything reachable"
    (is (= #{:a :b :c}
           (compile/transitive-blast {:a #{:b} :b #{:c}} [:a]))))

  (testing "a node reached by two paths is visited once (cycle-safe)"
    (is (= #{:a :b :c :d}
           (compile/transitive-blast {:a #{:b :c} :b #{:d} :c #{:d}} [:a])))
    (is (= #{:a :b}
           (compile/transitive-blast {:a #{:b} :b #{:a}} [:a]))))

  (testing "a seed with no dependents → just the seed"
    (is (= #{:x} (compile/transitive-blast {} [:x])))))


;; ============================================================================
;; resolve-impl compile-error throws (via compile-fn)
;; ============================================================================

(deftest compile-fn-missing-impl-test
  (testing "a root base-fn with no registered impl → :compile-error/missing-impl"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base    (setup/create-base-fn! storage "ct-noimpl")
              lookups (assoc (l/build-lookups (graph-snapshot storage))
                             :base-fns {})
              ex (try (compile/compile-fn (:id base) lookups {})
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :compile-error/missing-impl (:type (ex-data ex)))))
        (finally (sp/close storage)))))

  (testing "a root fn with no name → :compile-error/anonymous-root"
    (let [storage (setup/create-test-storage)]
      (try
        (let [anon (sp/create-entity storage :fn
                                     {:name nil :parent-ids []
                                      :impl-hash "h" :anonymous-hash "ct-anon"})
              lookups (assoc (l/build-lookups (graph-snapshot storage))
                             :base-fns {})
              ex (try (compile/compile-fn (:id anon) lookups {})
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :compile-error/anonymous-root (:type (ex-data ex)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; Sequence-binding runtime path — compile + execute
;; ============================================================================

(deftest compile-execute-sequence-binding-test
  (testing "a :list-append binding compiles to a seq-entry the impl reduces"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :ct-sum (setup/fn-impl [nums] (reduce + 0 nums)))
        (let [base (setup/create-base-fn! storage "ct-sum")
              slot (setup/create-slot! storage "nums" :sequence)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              composed (setup/create-composed-fn! storage "ct-sum-123" (:id base))
              bind (sp/create-entity storage :binding
                                     {:fn-id (:id composed) :slot-id (:id slot)
                                      :list-append true :override-kind :fixed})
              _ (sp/create-entity storage :binding-list-item
                                  {:binding-id (:id bind) :position 0 :value 1})
              _ (sp/create-entity storage :binding-list-item
                                  {:binding-id (:id bind) :position 1 :value 2})
              _ (sp/create-entity storage :binding-list-item
                                  {:binding-id (:id bind) :position 2 :value 3})
              ctx (exec/create-context {:storage storage})]
          (is (= 6 (exec/execute ctx (:id composed) {}))))
        (finally (sp/close storage))))))


;; ============================================================================
;; set-always-fresh-fn-ids!
;; ============================================================================

(deftest set-always-fresh-fn-ids-test
  (testing "the always-fresh fn-id set is replaceable (returns the new set)"
    (let [id (random-uuid)]
      (is (= #{id} (compile/set-always-fresh-fn-ids! #{id})))
      (is (= #{} (compile/set-always-fresh-fn-ids! #{}))))))
