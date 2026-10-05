(ns graphden.system.branch-type-order-test
  "A cold branch records referenced functions before their callers."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.types.diagnostics :as diag]))


(use-fixtures :once (setup/create-container-fixture) exec/with-isolated-rich-types)


(deftest cold-branch-recheck-restores-transitive-free-args-before-checking-a-child
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "order-source" :int)
              slot (setup/create-slot! storage "value" :int)
              caller-base (setup/create-base-fn! storage "order-call" :int)
              caller-slot (setup/create-slot! storage "item" :int)
              leaf (setup/create-composed-fn! storage "order-index" (:id base))
              wrapper (setup/create-composed-fn! storage "order-wrapper" (:id caller-base))
              child (setup/create-composed-fn! storage "order-child" (:id wrapper))
              view (sp/create-entity storage :slot
                                     {:name "index" :source-slot-id (:id slot)
                                      :type-fn-id (get setup/primitive-fn-ids :int)})]
          (setup/attach-slot! storage (:id base) (:id slot) 0)
          (setup/attach-slot! storage (:id caller-base) (:id caller-slot) 0)
          (setup/attach-slot! storage (:id leaf) (:id view) 0)
          (sp/create-entity storage :binding {:fn-id (:id leaf) :slot-id (:id slot)})
          (setup/bind-ref! storage (:id wrapper) (:id caller-slot) (:id leaf))
          (setup/bind-value! storage (:id child) (:id view) 2)
          (registry/record-rich-types-raw! (:id base) :order-source
                                           {:return :int :args {:value :int} :effects #{}})
          (registry/record-rich-types-raw! (:id caller-base) :order-call
                                           {:return :int :args {:item :int} :effects #{}})
          (let [ctx (assoc (exec/create-context {:storage storage})
                           :compile-deps (atom (deps/build-deps-state
                                                 (cr/graph-snapshot {:storage storage})))
                           :rich-types-atom (registry/fork-rich-types-atom
                                              (registry/active-rich-types-atom))
                           :per-org-rich-atom (registry/fork-per-org-rich-atom
                                                (registry/active-per-org-rich-atom)))]
            (is (= {:item :int}
                   (get-in @(:rich-types-atom ctx) [:by-id (:id caller-base) :args]))
                "the cold child context inherits the active base signatures")
            (recheck/call-with-ctx-slices
              ctx
              #(recheck/record-own-fn-types! ctx nil
                                             [(:id child) (:id wrapper) (:id leaf)]))
            (testing "the wrapper keeps its referenced function's free index"
              (is (= {:index :int}
                     (get-in @(:rich-types-atom ctx) [:by-id (:id wrapper) :args]))))
            (testing "its child can bind that index on the first cold recheck"
              (is (= {:args {} :return :int}
                     (select-keys (get-in @(:rich-types-atom ctx) [:by-id (:id child)])
                                  [:args :return])))
              (is (empty? (diag/errors-for-fn nil (:id child)))))))
        (finally (sp/close storage))))))


(deftest dependency-order-reports-cycles-without-dropping-independent-functions
  (is (= {:ordered [:leaf :caller :cycle-a :cycle-b]
          :cyclic [:cycle-a :cycle-b]}
         (deps/dependency-order {:leaf #{:caller}
                                 :external #{:leaf}
                                 :cycle-a #{:cycle-b}
                                 :cycle-b #{:cycle-a}}
                                [:cycle-b :caller :leaf :cycle-a]))))


(deftest a-branch-with-no-own-functions-needs-no-storage-read
  (is (nil? (recheck/record-own-fn-types! {} nil []))))
