(ns graphden.types.execution-specialization-test
  "Call-local execution constraints on inherited HOF and collection inputs."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(use-fixtures :each
  (fn [t]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              types/*type-aliases-override* (atom {})]
      (t))))


(def ^:private e [:execution-var 'E])
(def ^:private portable #{:jvm-server :browser-main})


(defn- callback
  [execution]
  [:fn {:item :int} :int #{} execution])


(defn- wrapper
  [execution]
  [:fn {:handler (callback execution)} (callback execution) #{} execution])


(defn- seed-wrapper!
  []
  (registry/record-rich-types-raw! :wrapper-root
                                   {:args {:wrap (wrapper e)
                                           :handlers [:map :keyword (callback e)]}
                                    :return [:map :keyword (callback e)]
                                    :execution portable}))


(defn- check-entry!
  [definition]
  (check/check-fn-def! definition)
  (registry/rich-type-of (:name definition)))


(deftest inherited-callable-and-map-pins-specialize-the-whole-local-contract
  (seed-wrapper!)
  (let [root (registry/rich-type-of :wrapper-root)]
    (doseq [execution [#{:browser-main} #{:jvm-server}]
            rename? [false true]
            reverse? [false true]]
      (let [handlers {:type [:map :keyword (callback execution)]}
            handlers (cond-> handlers rename? (assoc :as :callbacks))
            entries [[:handlers handlers] [:wrap {:type (wrapper e)}]]
            args (into (array-map) (cond-> entries reverse? reverse))
            child (check-entry! {:name :specialized :parent :wrapper-root :args args})]
        (is (= (wrapper execution) (get-in child [:args :wrap])))
        (is (= [:map :keyword (callback execution)]
               (get-in child [:args (if rename? :callbacks :handlers)])))
        (is (= [:map :keyword (callback execution)] (:return child)))
        (is (= root (registry/rich-type-of :wrapper-root)))))))


(deftest sibling-specializations-do-not-change-a-shared-parent
  (seed-wrapper!)
  (let [browser (check-entry! {:name :browser :parent :wrapper-root
                               :args {:handlers {:type [:map :keyword (callback #{:browser-main})]}}})
        server (check-entry! {:name :server :parent :wrapper-root
                              :args {:handlers {:type [:map :keyword (callback #{:jvm-server})]}}})]
    (is (= (wrapper #{:browser-main}) (get-in browser [:args :wrap])))
    (is (= (wrapper #{:jvm-server}) (get-in server [:args :wrap])))
    (is (= (wrapper e) (get-in (registry/rich-type-of :wrapper-root) [:args :wrap])))))


(deftest concrete-specialization-rejects-server-and-unknown-handler-collections
  (seed-wrapper!)
  (check-entry! {:name :browser :parent :wrapper-root
                 :args {:handlers {:type [:map :keyword (callback #{:browser-main})]}}})
  (doseq [execution [#{:jvm-server} :unknown]]
    (registry/record-rich-types-raw! :handlers-value
                                     {:args {} :return [:map :keyword (callback execution)]})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
          (check-entry! {:name :bad :parent :browser :args {:handlers :handlers-value}})))))


(deftest nested-list-map-pins-specialize-wrapper-results
  (let [nested (fn [execution] [:list [:map :keyword (callback execution)]])]
    (registry/record-rich-types-raw! :nested-root
                                     {:args {:left (nested e) :right (callback e)}
                                      :return (nested e)})
    (let [child (check-entry! {:name :nested :parent :nested-root
                               :args {:left {:type (nested #{:browser-main})}}})]
      (is (= (callback #{:browser-main}) (get-in child [:args :right])))
      (is (= (nested #{:browser-main}) (:return child))))))


(deftest callback-support-intersects-independently-of-binding-order
  (registry/record-rich-types-raw! :callbacks-root
                                   {:args {:left (callback e) :right (callback e)}
                                    :return (callback e)})
  (doseq [[left right result] [[portable #{:browser-main} #{:browser-main}]
                               [#{:browser-main} portable #{:browser-main}]
                               [#{:browser-main} #{:jvm-server} #{}]
                               [:unknown portable #{}]]
          reverse? [false true]]
    (registry/record-rich-types-raw! :left-callback
                                     {:args {:item :int} :return :int :execution left})
    (registry/record-rich-types-raw! :right-callback
                                     {:args {:item :int} :return :int :execution right})
    (let [entries [[:left :left-callback] [:right :right-callback]]
          child (check-entry! {:name :pair :parent :callbacks-root
                               :args (into (array-map) (cond-> entries reverse? reverse))})]
      (is (= (callback result) (:return child)))
      (is (= {} (:args child))))))


(deftest higher-order-inputs-remain-contravariant-after-specialization
  (seed-wrapper!)
  (doseq [input [#{:browser-main} portable]
          reverse? [false true]]
    (registry/record-rich-types-raw! :wrapper-value
                                     {:args {}
                                      :return [:fn {:handler (callback input)}
                                               (callback portable) #{} portable]})
    (let [entries [[:handlers {:type [:map :keyword (callback #{:browser-main})]}]
                   [:wrap :wrapper-value]]
          definition {:name :wrapped :parent :wrapper-root
                      :args (into (array-map) (cond-> entries reverse? reverse))}]
      (if (= input portable)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
              (check-entry! definition)))
        (is (= [:map :keyword (callback #{:browser-main})]
               (:return (check-entry! definition))))))))


(deftest type-pin-with-value-variables-specializes-sibling-and-return
  (registry/record-rich-types-raw! :poly-root
                                   {:args {:func [:fn {:item 'a} 'b #{} e]
                                           :coll [:list 'a]}
                                    :return [:list [:fn {} 'b #{} e]]})
  (let [child (check-entry! {:name :poly :parent :poly-root
                             :args {:func {:as :transform
                                           :type [:fn {:item :int} :text #{} #{:browser-main}]}}})]
    (is (= [:list :int] (get-in child [:args :coll])))
    (is (= [:fn {:item :int} :text #{} #{:browser-main}] (get-in child [:args :transform])))
    (is (= [:list [:fn {} :text #{} #{:browser-main}]] (:return child)))))


(deftest captures-keep-their-types-and-cannot-invent-browser-support
  (registry/record-rich-types-raw! :invoke-root
                                   {:args {:func (callback #{:browser-main})}
                                    :return :int})
  (doseq [support [portable #{:jvm-server} :unknown]]
    (registry/record-rich-types-raw! :capturing-callback
                                     {:args {:item :int :configuration :text}
                                      :return :int :execution support})
    (let [definition {:name :capturing :parent :invoke-root
                      :args {:func :capturing-callback}}]
      (if (= support portable)
        (is (= {:configuration :text} (:args (check-entry! definition))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
              (check-entry! definition)))))))


(deftest unsupported-native-operation-cannot-fill-a-browser-callback-slot
  (registry/record-rich-types! :unsupported-native
                               {:args {:item :int} :return-type :int :effects #{}
                                :impl (fn [args _ctx] (:item args))})
  (registry/record-rich-types-raw! :browser-invoke
                                   {:args {:func (callback #{:browser-main})} :return :int})
  (is (= #{:jvm-server} (:execution (registry/rich-type-of :unsupported-native))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
        (check-entry! {:name :unsupported-browser :parent :browser-invoke
                       :args {:func :unsupported-native}}))))
