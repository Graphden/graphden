(ns ^:integration graphden.packages.branch-import-types-test
  "Bundle imports publish checked types before any imported function runs."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once
  (ga/fixture (ns-name *ns*))
  (impls/impls-fixture "storage" "branches"))


(defn- import!
  [ctx branch-id ns-path]
  ((impls/impl-of :sync-fn-defs-branch!)
   {:branch-id branch-id
    :fn-defs (mapv #(assoc % :namespace ns-path)
                   [{:name :child :parent :wrapper :args {:event 7}}
                    {:name :wrapper :parent :const :args {:value :leaf}}
                    {:name :leaf :parent :const
                     :args {:value {:as :event :type :int}}}
                    {:name :invalid :parent :const
                     :return-type :int :args {:value "wrong type"}}])}
   ctx))


(defn- checked-signatures
  [ctx ns-path]
  (let [by-id (:by-id @(:rich-types-atom ctx))]
    (into {} (for [n [:leaf :wrapper :child]]
               [n (select-keys (get by-id (records/fn-id ns-path n))
                               [:args :return])]))))


(def ^:private expected-signatures
  {:leaf {:args {:event :int} :return :int}
   :wrapper {:args {:event :int} :return :int}
   :child {:args {} :return :int}})


(defn- request-context
  []
  (assoc (:ctx ga/*bootstrap*)
         :rich-types-atom (registry/fork-rich-types-atom (registry/active-rich-types-atom))
         :per-org-rich-atom (registry/fork-per-org-rich-atom (registry/active-per-org-rich-atom))))


(deftest routed-import-records-the-target-slice-before-returning
  (binding [br/*active-router-override* (atom nil)
            recheck/*ctx-build-async-recheck?* false
            diag/*diagnostics-override* (atom {})]
    (let [ctx (request-context)
          storage (:storage ctx)
          branch (vs/create-branch! storage "import-types-routed")
          branch-id (:id branch)
          router (br/create-router ctx "_app-ring-response")
          target-ctx (br/ctx-for router branch-id)
          before @(:rich-types-atom ctx)
          ns-path "import.routed"]
      (br/set-active-router! router)
      (is (= 4 (count (import! ctx branch-id ns-path))))
      (testing "no imported function has run; its propagated arguments are already known"
        (is (= expected-signatures (checked-signatures target-ctx ns-path))))
      (testing "an invalid definition keeps its diagnostic instead of receiving a valid type"
        (is (seq (diag/errors-for-fn branch-id (records/fn-id ns-path :invalid))))
        (is (nil? (get-in @(:rich-types-atom target-ctx)
                          [:by-id (records/fn-id ns-path :invalid)]))))
      (is (= before @(:rich-types-atom ctx)) "the request's branch has not gained target types"))))


(deftest standalone-import-into-the-current-branch-updates-its-context
  (binding [br/*active-router-override* (atom nil)
            diag/*diagnostics-override* (atom {})]
    (let [ctx (request-context)
          branch-id (vs/current-branch-id (:storage ctx))
          ns-path "import.same"]
      (import! ctx branch-id ns-path)
      (is (= expected-signatures (checked-signatures ctx ns-path)))
      (is (seq (diag/errors-for-fn branch-id (records/fn-id ns-path :invalid)))))))


(deftest standalone-import-into-another-branch-does-not-change-request-types
  (binding [br/*active-router-override* (atom nil)
            diag/*diagnostics-override* (atom {})]
    (let [ctx (request-context)
          storage (:storage ctx)
          branch-id (:id (vs/create-branch! storage "import-types-standalone"))
          before-rich @(:rich-types-atom ctx)
          before-org @(:per-org-rich-atom ctx)
          ns-path "import.other"]
      (is (= 4 (count (import! ctx branch-id ns-path))))
      (is (= before-rich @(:rich-types-atom ctx)))
      (is (= before-org @(:per-org-rich-atom ctx)))
      (is (nil? (sp/read-entity storage :fn (records/fn-id ns-path :child))))
      (is (some? (sp/read-entity (vs/switch-branch storage branch-id)
                                 :fn (records/fn-id ns-path :child))))
      (is (empty? (diag/errors-for-fn branch-id (records/fn-id ns-path :child))))
      (is (seq (diag/errors-for-fn branch-id (records/fn-id ns-path :invalid)))))))
