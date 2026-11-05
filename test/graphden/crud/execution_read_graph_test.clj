(ns ^:integration ^:serial graphden.crud.execution-read-graph-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.execution-api :as api]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "execution-read-graph" ["core" "web" "app"])]
      (binding [*graph* graph]
        (try (f) (finally (setup/close-graph! graph)))))))


(deftest reconstructs-safe-execution-facts-once-test
  (let [eid (random-uuid) version (random-uuid) fid (random-uuid)
        arg-a (random-uuid) arg-b (random-uuid) child-a (random-uuid) child-b (random-uuid)
        child-version (random-uuid) child-fn (random-uuid)
        calls (atom 0)
        row {:id eid :fn-version-id version :status :succeeded :result 42
             :path-trace {:entries [{:fn-id (str fid) :hidden "secret"}]}}
        args [{:id arg-a :value nil} {:id arg-b :value 8}]
        items [{:execution-arg-id arg-a :position 2 :value 30}
               {:execution-arg-id arg-a :position 0 :value 10}
               {:execution-arg-id arg-a :position 1 :value 20}]
        kids [{:id child-b :fn-version-id (random-uuid) :status :pending
               :started-at "later" :started-ms 2}
              {:id child-a :fn-version-id child-version :status :failed
               :started-at "earlier" :started-ms 1 :error "safe"}]]
    (with-redefs [fn-exec/execution-read-facts
                  (fn [_ id]
                    (is (= eid id))
                    (swap! calls inc)
                    {:row row :arg-rows args :arg-item-rows items :child-rows kids
                     :fn-id-by-version {version fid child-version child-fn}
                     :fn-name-by-id {fid "parent" child-fn "child"}})]
      (let [out (api/get-execution (:ctx *graph*) eid)]
        (is (= row (dissoc out :args :fn-id :children)))
        (is (= fid (:fn-id out)))
        (is (= [(assoc (first args) :items (vec (sort-by :position items)))
                (assoc (second args) :items [])] (:args out)))
        (is (= [{:id child-a :fn-id child-fn :fn-name "child" :status :failed
                 :started-at "earlier" :finished-at nil :error "safe"}
                {:id child-b :fn-id nil :fn-name nil :status :pending
                 :started-at "later" :finished-at nil :error nil}]
               (:children out)))
        (is (= 1 @calls) "one scoped acquisition for the whole composed response")))))


(deftest missing-execution-stays-nil-test
  (with-redefs [fn-exec/execution-read-facts (fn [_ _] nil)]
    (is (nil? (api/get-execution (:ctx *graph*) nil)))
    (is (nil? (api/get-execution (:ctx *graph*) (random-uuid))))))


(deftest acquisition-finishes-viewer-filtering-before-exposing-facts-test
  (let [{:keys [storage ctx all-name->id]} *graph*
        fid (:add all-name->id)
        version (lookup/resolve-fn-version-id ctx fid)
        raw-trace {:entries [{:value "private"}]}
        safe-trace {:entries [{:hidden "secret"}]}
        row (sp/create-entity storage :fn-execution
                              {:fn-version-id version :status :succeeded
                               :started-at (java.time.Instant/now) :path-trace raw-trace})
        child (sp/create-entity storage :fn-execution
                                {:fn-version-id version :status :succeeded
                                 :started-at (java.time.Instant/now) :parent-execution-id (:id row)
                                 :result "private" :path-trace raw-trace :error-data "private"})
        filters (atom 0)]
    (with-redefs [fn-exec/viewer-path-trace
                  (fn [actual-storage trace]
                    (is (identical? storage actual-storage))
                    (is (= raw-trace trace))
                    (swap! filters inc)
                    safe-trace)]
      (is (nil? (fn-exec/execution-read-facts ctx (random-uuid))))
      (is (zero? @filters) "an absent parent exposes no trace")
      (let [facts (fn-exec/execution-read-facts ctx (:id row))]
        (is (= safe-trace (get-in facts [:row :path-trace])))
        (is (= 1 @filters))
        (is (= [(:id child)] (mapv :id (:child-rows facts))))
        (is (not-any? #(contains? (first (:child-rows facts)) %) [:result :path-trace :error-data]))
        (is (= {fid "add"} (:fn-name-by-id facts)))))))
