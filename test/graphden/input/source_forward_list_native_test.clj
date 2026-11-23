(ns graphden.input.source-forward-list-native-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.core :as registry]
    [graphden.input.source :as source]
    [graphden.input.source-forward-list-test :as fixture]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(deftest stock-list-reference-forwarding-preserves-the-complete-checked-route
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [{:keys [definitions prepared entry]} (fixture/fixture)
          loaded (:base-fn-defs (loader/load-packages ["core"]))
          _ (doseq [[ns-id name] [["core.collections" :list] ["core.strings" :str-upper]]]
              (registry/record-rich-types! (ids/fn-id ns-id name) name (get loaded name)))
          package (snapshot/package-index prepared definitions)
          owner (:identity entry)
          migrated (snapshot/with-migrated-forwarding package owner [:string] (:by-id @registry/*rich-types-override*))
          verified (get-in migrated [:closure-baselines owner])
          path [:items :item-2 :string]
          certificate (get-in verified [:v2 :scoped-inputs path])
          binding (ids/binding-id owner (ids/slot-id (ids/fn-id "core.collections" :list) :items))
          item (ids/binding-list-item-id binding 2)]
      (is (= :authored-source-equivalent (:verification verified)))
      (is (= [binding item] (get-in certificate [:row :scope-path])))
      (is (= [:items :item-2] (mapv :name (:edges certificate))))
      (is (= item (get-in certificate [:edges 1 :source-list-operand :item-id])))
      (is (= [:string] (:source-names certificate)))
      (is (= :supported (:status (source/rewrite-forwarded-fields prepared entry [:string] [certificate]))))
      (doseq [changed [(assoc-in certificate [:list-reference :item-id] (random-uuid))
                       (assoc-in certificate [:edges 1 :source-list-operand :position] 0)
                       (assoc-in certificate [:edges 0 :source-list-fragment :owner-id] (random-uuid))]]
        (is (= :forwarded-certificate-mismatch
               (try (source/rewrite-forwarded-fields prepared entry [:string] [changed])
                    nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
      (is (not (contains? package :closure-baselines))))))
