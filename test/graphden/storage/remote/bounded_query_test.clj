(ns graphden.storage.remote.bounded-query-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-source :as source]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]))


(defn- failure-type
  [operation]
  (try (operation) nil
       (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(deftest bounded-snapshot-queries-retain-conservative-candidate-budget
  (let [owner (random-uuid) other (random-uuid)
        a {:id (random-uuid) :fn-id owner :org-id "a" :value 1}
        b {:id (random-uuid) :fn-id owner :org-id "b" :value 2}
        storage (remote/from-bundle {:binding [a b {:id (random-uuid) :fn-id other}]})]
    (is (= :storage-error/candidate-limit
           (failure-type #(sp/query-bounded-entities storage :binding {:fn-id owner :org-id "a"} 1))))
    (let [result (sp/query-bounded-entities storage :binding {:fn-id owner :org-id "a"} 2)]
      (is (= [a] result))
      (is (= 2 (::sp/candidate-count (meta result)))))
    (is (= [] (sp/query-bounded-entities storage :binding {:fn-id (random-uuid)} 0)))
    (is (= [{:id (:id a) :org-id "a"} {:id (:id b) :org-id "b"}]
           (sp/query-identity-candidates storage :binding {:fn-id owner} nil 2)))
    (doseq [operation [#(sp/query-bounded-entities storage :binding {} 2)
                       #(sp/query-bounded-entities storage :binding {:fn-id owner} -1)
                       #(sp/query-identity-candidates storage :binding {:fn-id owner} {:version-entity :binding-version} 2)]]
      (is (= :storage-error/bounded-query-unsupported (failure-type operation))))))


(deftest manifest-collection-uses-only-the-fixed-snapshot-bundle
  (let [id (random-uuid) slot-id (random-uuid)
        function {:id id :name "fixed" :parent-ids []}
        binding {:id (random-uuid) :fn-id id :slot-id slot-id :value "fixed-value"}
        storage (remote/from-bundle {:fn [function] :slot [{:id slot-id}]
                                     :fn-slot [{:id (random-uuid) :fn-id id :slot-id slot-id}]
                                     :binding [binding]})
        authorized (atom [])]
    (with-redefs [remote/fetch-graph-rows (fn [& _] (throw (ex-info "Unexpected network read" {})))]
      (let [graph (source/collect-manifest storage id #(swap! authorized conj (:id %)))]
        (is (= [id] @authorized))
        (is (= [function] (:fns graph)))
        (is (= [binding] (:bindings graph)))))))
