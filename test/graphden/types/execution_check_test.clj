(ns graphden.types.execution-check-test
  "Execution contracts through the real checker without package-wide sweeps."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(use-fixtures :each
  (fn [test-fn]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              registry/*per-org-rich-override* (atom {})]
      (test-fn))))


(defn- callable
  [execution]
  [:fn {} :int #{} execution])


(defn- record-callback!
  [name execution]
  (registry/record-rich-types-raw! name
                                   {:args {} :return :int :effects #{} :execution execution}))


(deftest concrete-execution-contract-through-checker
  (registry/record-rich-types-raw! :browser-consumer
                                   {:args {:callback (callable #{:browser-main})} :return :int :effects #{}})
  (record-callback! :server-callback #{:jvm-server})
  (record-callback! :portable-callback #{:jvm-server :browser-main})
  (record-callback! :unknown-callback :unknown)
  (doseq [ref [:server-callback :unknown-callback]]
    (is (thrown? clojure.lang.ExceptionInfo
          (check/check-fn-def! {:name :bad-binding :parent :browser-consumer
                                :args {:callback ref}}))))
  (check/check-fn-def! {:name :portable-binding :parent :browser-consumer
                        :args {:callback :portable-callback}})
  (is (= :int (:return (registry/rich-type-of :portable-binding))))
  (is (= #{:jvm-server :browser-main}
         (types/fn-execution (check/assemble-fn-type :portable-callback)))))


(deftest shared-execution-variable-through-checker
  (let [e [:execution-var 'E]]
    (registry/record-rich-types-raw! :shared-profile-consumer
                                     {:args {:first (callable e) :second (callable e)}
                                      :return (callable e) :effects #{}})
    (record-callback! :portable-callback #{:jvm-server :browser-main})
    (record-callback! :browser-callback #{:browser-main})
    (record-callback! :server-callback #{:jvm-server})
    (doseq [[name first-ref second-ref]
            [[:portable-first :portable-callback :browser-callback]
             [:browser-first :browser-callback :portable-callback]
             [:server-only :server-callback :server-callback]]]
      (check/check-fn-def! {:name name :parent :shared-profile-consumer
                            :args {:first first-ref :second second-ref}}))
    (is (= #{:browser-main} (types/fn-execution (:return (registry/rich-type-of :portable-first)))))
    (is (= #{:browser-main} (types/fn-execution (:return (registry/rich-type-of :browser-first)))))
    (is (= #{:jvm-server} (types/fn-execution (:return (registry/rich-type-of :server-only)))))))


(deftest signature-refire-intersects-execution-solutions
  (let [e [:execution-var 'E]
        root {:args {:first (callable e) :second (callable e)}
              :return (callable e)}
        result (check/signature-return root
                                       {:first {:type (callable #{:jvm-server :browser-main})}
                                        :second {:type (callable #{:browser-main})}}
                                       :any)]
    (is (= #{:browser-main} (types/fn-execution result)))))


(deftest marked-callable-producer-through-checker
  (let [e [:execution-var 'E]
        expected [:secret (callable e)]
        actual [:secret (callable #{:browser-main})]]
    (registry/record-rich-types-raw! :marked-producer
                                     {:args {} :return actual :effects #{}})
    (registry/record-rich-types-raw! :marked-consumer
                                     {:args {:value expected} :return expected :effects #{}})
    (check/check-fn-def! {:name :marked-binding :parent :marked-consumer
                          :args {:value :marked-producer}})
    (is (= actual (:return (registry/rich-type-of :marked-binding))))))


(deftest broad-callable-producer-cannot-invent-browser-proof
  (doseq [wrap [identity #(vector :list %) #(vector :map :text %)]]
    (registry/record-rich-types-raw! :unknown-producer
                                     {:args {} :return (wrap [:fn {} :any :any :unknown]) :effects #{}})
    (registry/record-rich-types-raw! :browser-value-consumer
                                     {:args {:value (wrap [:fn {} :int :any #{:browser-main}])}
                                      :return :int :effects #{}})
    (is (thrown? clojure.lang.ExceptionInfo
          (check/check-fn-def! {:name :unknown-browser-binding
                                :parent :browser-value-consumer
                                :args {:value :unknown-producer}}))))
  (registry/record-rich-types-raw! :unknown-producer
                                   {:args {} :return [:fn {} :any :any :unknown] :effects #{}})
  (registry/record-rich-types-raw! :unknown-value-consumer
                                   {:args {:value [:fn {} :int :any :unknown]} :return :int :effects #{}})
  (check/check-fn-def! {:name :gradual-value-binding :parent :unknown-value-consumer
                        :args {:value :unknown-producer}})
  (is (= :int (:return (registry/rich-type-of :gradual-value-binding)))))


(deftest registry-derives-native-execution-from-implementation-and-identity
  (let [cases [[:add "core.arithmetic" #{:jvm-server :browser-main}]
               [:add "user.arithmetic" #{:jvm-server}]
               [:reduce "core.hof" #{:jvm-server}]]]
    (doseq [ordered [cases (reverse cases)]]
      (doseq [[name ns-path _] ordered]
        ;; Production records supply their exact row identity. Bare-name
        ;; two-arity re-records deliberately target the existing name entry.
        (registry/record-rich-types! (ids/fn-id ns-path name) name
                                     {:namespace ns-path :args {} :return-type :int :impl (fn [_ _] 1)
                                      :execution #{:browser-main}}))
      (doseq [[name ns-path expected] cases]
        (let [entry (registry/rich-type-of (keyword ns-path (clojure.core/name name)))]
          (is (= (ids/fn-id ns-path name) (:fn-id entry)))
          (is (= expected (:execution entry)))))))
  (registry/record-rich-types! :unproven
                               {:args {} :return-type :int :execution #{:jvm-server :browser-main}})
  (is (= :unknown (:execution (registry/rich-type-of :unproven)))))


(deftest composed-callable-execution-is-derived-from-invoked-callbacks
  (registry/record-rich-types! :map
                               {:namespace "core.hof"
                                :args {:func {:type (callable :unknown)} :coll {:type [:list :int]}}
                                :return-type [:list :int] :impl (fn [_ _] [])})
  (record-callback! :browser-stage-callback #{:browser-main})
  (record-callback! :server-stage-callback #{:jvm-server})
  (doseq [[name callback expected]
          [[:browser-stage-map :browser-stage-callback #{:browser-main}]
           [:server-stage-map :server-stage-callback #{:jvm-server}]]]
    (check/check-fn-def! {:name name :parent :map
                          :args {:func callback :coll [1]}})
    (is (= expected (:execution (registry/rich-type-of name))))))


(deftest ordinary-stage-dependencies-constrain-native-root-support
  (registry/record-rich-types! :const
                               {:namespace "core.logic" :args {:value {:type :text}}
                                :return-type :text :impl (fn [_ _] "")})
  (registry/record-rich-types-raw! :server-text
                                   {:args {} :return :text :effects #{} :execution #{:jvm-server}})
  (check/check-fn-def! {:name :constant-server-text :parent :const
                        :args {:value :server-text}})
  (is (= #{:jvm-server} (:execution (registry/rich-type-of :constant-server-text)))))


(deftest synthesised-type-implementation-proves-native-host-support
  (registry/record-rich-types! :record-probe {:type {:value :text}})
  (is (= #{:jvm-server} (:execution (registry/rich-type-of :record-probe)))))


(deftest literal-vectors-do-not-acquire-binding-grammar-execution-refs
  (registry/record-rich-types! :const
                               {:namespace "core.logic" :args {:value {:type 'a}}
                                :return-type 'a :impl (fn [_ _] nil)})
  (registry/record-rich-types-raw! :red
                                   {:args {} :return :keyword :effects #{} :execution #{:jvm-server}})
  (registry/record-rich-types-raw! :blue
                                   {:args {} :return :keyword :effects #{} :execution :unknown})
  (registry/record-rich-types-raw! :server
                                   {:args {} :return :text :effects #{} :execution #{:jvm-server}})
  (doseq [[name payload] [[:literal-keyword-vector [:red :blue]]
                          [:literal-ref-map-vector [{:ref :server}]]]]
    (check/check-fn-def! {:name name :parent :const :args {:value {:value payload}}})
    (is (= #{:jvm-server :browser-main} (:execution (registry/rich-type-of name))))))
