(ns graphden.input.query-lookup-views-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.query-lookup-views :as views]
    [graphden.packages.records.ids :as ids]))


(deftest only-the-frozen-query-kernels-can-supply-private-views
  (let [fixtures [["core.collections" :get '{:coll [:union :null [:list :any] [:map a :any]] :key :any :default :any} :any]
                  ["core.collections" :get-in '{:map [:union :null [:map a :any]] :path [:list [:union :int :keyword :text]] :default :any} :any]
                  ["core.strings" :str-to-keyword {:string [:union :null :text]} [:union :keyword :null]]
                  ["web.crud" :_query-param-raw {:query-string [:union :null :text] :param-name :text} [:union :null :text]]]
        roots (into {} (for [[module native args return] fixtures
                             :let [id (ids/fn-id module native)]]
                         [id {:fn-id id :name native :namespace module :args args :return return :effects #{}
                              :execution (cond-> #{:jvm-server} (= :get native) (conj :browser-main))
                              :taint-propagate? true}]))
        visible (set (keys roots))
        result (views/views roots visible)
        donor (ids/fn-id "web.crud" :_query-param-raw)
        raw (last result)]
    (is (= 4 (count result)))
    (is (= visible (set (map :original result))))
    (is (= {:query-string :any :param-name :any} (get-in raw [:signature :args])))
    (is (= donor (:original raw)))
    (is (nil? (views/views roots (disj visible donor))))
    (is (nil? (views/views roots (conj visible (:id raw)))))
    (doseq [[key value] [[:fn-id (random-uuid)] [:name :other] [:namespace "foreign"]
                         [:args {:param-name :any}] [:return :any] [:execution #{:browser-main}]
                         [:effects #{:network/call}] [:taint-propagate? false]
                         [:return-type-rule identity]]]
      (is (nil? (views/views (assoc-in roots [donor key] value) visible))))))
