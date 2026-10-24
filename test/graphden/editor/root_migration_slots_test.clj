(ns graphden.editor.root-migration-slots-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.root-migration-slots :as slots]
    [graphden.packages.records.ids :as ids]))


(deftest inherited-callback-slot-replaces-the-parser-placeholder
  (let [callback-id (random-uuid)
        ancestor-id (random-uuid)
        actual-slot-id (random-uuid)
        wrapper-id (ids/fn-id "migration-probe" :wrapper)
        placeholder (ids/slot-id callback-id :model)
        records [{:kind :fn :id wrapper-id :parent-ids [callback-id]}
                 {:kind :binding :id (random-uuid) :fn-id wrapper-id :slot-id placeholder}]
        definitions [{:namespace "migration-probe" :name :wrapper :args {:model {:value {}}}}]
        graph {:fns [{:id callback-id :parent-ids [ancestor-id]}
                     {:id ancestor-id :parent-ids []}]
               :slots [{:id actual-slot-id :name "model"}]
               :fn-slots [{:fn-id ancestor-id :slot-id actual-slot-id :position 0}]
               :bindings [] :list-items []}
        resolved (slots/resolve-bindings records definitions graph)]
    (is (not= placeholder actual-slot-id))
    (is (= actual-slot-id (:slot-id (second resolved))))
    (is (= :legacy-callback-slots
           (try (slots/resolve-bindings records definitions (assoc graph :slots []))
                nil
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))
