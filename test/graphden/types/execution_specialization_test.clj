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


(deftest return-annotations-cannot-manufacture-execution-proof
  (doseq [wrap [identity #(vector :list %) #(vector :map :keyword %)
                #(hash-map :handler % :label :text)
                #(vector :union :null %) #(vector :secret %)]
          execution [:unknown #{:jvm-server} [:execution-var 'Unsolved]]]
    (registry/record-rich-types-raw! :producer
                                     {:args {} :return (wrap (callback execution))})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"computed return is not a subtype"
          (check-entry! {:name :claimed-browser :parent :producer
                         :return-type (wrap (callback #{:browser-main}))})))))


(deftest return-value-assertions-retain-existing-narrowing-when-execution-is-proven
  (doseq [wrap [identity #(vector :list %) #(vector :map :keyword %)
                #(hash-map :handler % :label :text)]]
    (registry/record-rich-types-raw! :producer
                                     {:args {} :return (wrap [:fn {} :any :any portable])})
    (let [declared (wrap [:fn {} :int #{} portable])]
      (is (= declared (:return (check-entry! {:name :narrowed :parent :producer
                                              :return-type declared})))))))


(deftest return-annotation-respects-higher-order-execution-contravariance
  (registry/record-rich-types-raw! :producer
                                   {:args {} :return [:fn {:handler (callback portable)} :int #{} portable]})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"computed return is not a subtype"
        (check-entry! {:name :changed-input :parent :producer
                       :return-type [:fn {:handler (callback #{:browser-main})} :int #{} portable]}))))


(deftest generic-return-annotations-cannot-uplift-unproven-execution
  (doseq [wrap [identity #(vector :list %) #(vector :map :keyword %)
                #(hash-map :handler %)]
          execution [:unknown #{} #{:jvm-server}]]
    (registry/record-rich-types-raw! :producer {:args {} :return (wrap (callback execution))})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"computed return is not a subtype"
          (check-entry! {:name :claimed-generic :parent :producer
                         :return-type (wrap (callback e))})))))


(deftest generic-return-annotations-retain-proven-correlation
  (doseq [shape [(callback e) [:list (callback e)] [:map :keyword (callback e)]]]
    (registry/record-rich-types-raw! :producer {:args {} :return shape})
    (is (= shape (:return (check-entry! {:name :same-generic :parent :producer :return-type shape}))))))


(deftest generic-execution-inputs-follow-contravariance
  (doseq [required [:unknown #{} #{:jvm-server} portable]]
    (let [actual [:fn {:handler (callback required)} :int #{} portable]
          declared [:fn {:handler (callback e)} :int #{} portable]]
      (registry/record-rich-types-raw! :producer {:args {} :return actual})
      (if (#{:unknown #{}} required)
        (is (= declared (:return (check-entry! {:name :generic-input :parent :producer
                                                :return-type declared}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"computed return is not a subtype"
              (check-entry! {:name :generic-input :parent :producer :return-type declared})))))))


(deftest unknown-producer-cannot-reach-browser-consumer-through-generic-return
  (registry/record-rich-types-raw! :unknown-producer
                                   {:args {} :return [:fn {} :int #{} :unknown]})
  (registry/record-rich-types-raw! :browser-consumer
                                   {:args {:func [:fn {} :int #{} #{:browser-main}]} :return :int})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"computed return is not a subtype"
        (do (check-entry! {:name :generic-child :parent :unknown-producer
                           :return-type [:fn {} :int #{} e]})
            (check-entry! {:name :browser-call :parent :browser-consumer
                           :args {:func :generic-child}}))))
  (is (nil? (registry/rich-type-of :generic-child))))


(deftest ref-override-cannot-turn-an-unknown-producer-into-a-browser-callable
  (registry/record-rich-types-raw! :unknown-producer
                                   {:args {} :return [:fn {} :int #{} :unknown]})
  (registry/record-rich-types-raw! :browser-consumer
                                   {:args {:func [:fn {} :int #{} #{:browser-main}]} :return :int})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"contradicts the ref-return"
        (check-entry! {:name :claimed-call :parent :browser-consumer
                       :args {:func {:ref :unknown-producer :type [:fn {} :int #{} e]}}}))))


(deftest execution-claims-require-proof-on-ref-value-and-item-overrides
  (registry/record-rich-types-raw! :claim-root {:args {:input :any :items [:list :any]} :return :int})
  (doseq [wrap [identity #(vector :list %) #(vector :map :keyword %)]
          support [:unknown #{} #{:jvm-server}]
          item? [false true]]
    (registry/record-rich-types-raw! :producer {:args {} :return (wrap (callback support))})
    (let [claim {:ref :producer :type (wrap (callback e))}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"contradicts the ref-return"
            (check-entry! {:name :claim :parent :claim-root
                           :args (if item? {:items [claim]} {:input claim})})))))
  (doseq [claim [e #{:browser-main}]
          item? [false true]]
    (let [binding {:value 1 :type (callback claim)}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"contradicts the literal-value"
            (check-entry! {:name :literal-claim :parent :claim-root
                           :args (if item? {:items [binding]} {:input binding})}))))))


(deftest authored-value-and-contravariant-claims-retain-safe-narrowing
  (registry/record-rich-types-raw! :claim-root {:args {:input :any} :return :int})
  (types/register-type-alias! :positive [:refine :int [:> 0]])
  (is (= [:refine :int [:> 0]]
         (get-in (check-entry! {:name :value-claim :parent :claim-root
                                :args {:input {:value 1 :type :positive}}})
                 [:resolved-bindings :input :type])))
  (doseq [required [:unknown #{} #{:jvm-server}]]
    (let [actual [:fn {:handler (callback required)} :int #{} portable]
          declared [:fn {:handler (callback e)} :int #{} portable]]
      (registry/record-rich-types-raw! :producer {:args {} :return actual})
      (if (#{:unknown #{}} required)
        (is (= declared
               (get-in (check-entry! {:name :negative-claim :parent :claim-root
                                      :args {:input {:ref :producer :type declared}}})
                       [:resolved-bindings :input :type])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"contradicts the ref-return"
              (check-entry! {:name :negative-claim :parent :claim-root
                             :args {:input {:ref :producer :type declared}}})))))))


(deftest correlated-execution-variables-do-not-authorize-stripping-a-known-marker
  (let [marked [:secret (callback e)]]
    (registry/record-rich-types-raw! :secret-producer {:args {} :return marked})
    (registry/record-rich-types-raw! :plain-root {:args {:input :any} :return :int})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strips a hide-result marker"
          (check-entry! {:name :stripped :parent :plain-root
                         :args {:input {:ref :secret-producer :type (callback e)}}})))
    (registry/record-rich-types-raw! :marked-root {:args {:input marked} :return :int})
    (is (= marked
           (get-in (check-entry! {:name :kept :parent :marked-root
                                  :args {:input {:ref :secret-producer :type marked}}})
                   [:resolved-bindings :input :type])))))


(deftest authored-claims-cannot-relocate-secret-to-a-sibling-path
  (registry/record-rich-types-raw! :claim-root {:args {:input :any} :return :int})
  (let [actual {:s [:secret (callback e)] :decoy :int}
        relocated {:s (callback e) :decoy [:secret :int]}]
    (doseq [wrap [identity #(vector :list %) #(vector :map :keyword %)
                  #(hash-map :nested %)
                  #(vector :union :null %)
                  #(vector :refine % [:not= nil])
                  #(vector :fn {:arg %} :int #{} portable)
                  #(vector :fn {} % #{} portable)
                  #(vector :tuple :int %)]]
      (registry/record-rich-types-raw! :secret-producer {:args {} :return (wrap actual)})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strips a hide-result marker"
            (check-entry! {:name :relocated :parent :claim-root
                           :args {:input {:ref :secret-producer :type (wrap relocated)}}})))
      (is (= (wrap actual)
             (get-in (check-entry! {:name :correlated :parent :claim-root
                                    :args {:input {:ref :secret-producer :type (wrap actual)}}})
                     [:resolved-bindings :input :type]))))))


(deftest secret-field-cannot-be-extracted-through-a-relocated-any-sink-claim
  (let [actual {:s [:secret (callback e)] :decoy :int}
        relocated {:s (callback e) :decoy [:secret :int]}]
    (registry/record-rich-types-raw! :secret-producer {:args {} :return actual})
    (registry/record-rich-types-raw! :copy {:args {:input 'a} :return 'a})
    (registry/record-rich-types-raw! :extract {:args {:record {:s 'a}} :return 'a})
    (registry/record-rich-types-raw! :browser-consumer
                                     {:args {:func (callback #{:browser-main})} :return :int})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strips a hide-result marker"
          (do (check-entry! {:name :plain-copy :parent :copy
                             :args {:input {:ref :secret-producer :type relocated}}})
              (check-entry! {:name :extracted :parent :extract :args {:record :plain-copy}})
              (check-entry! {:name :leaked :parent :browser-consumer :args {:func :extracted}}))))
    (is (nil? (registry/rich-type-of :plain-copy)))))


(deftest union-arms-and-missing-fields-cannot-conceal-known-secret-paths
  (registry/record-rich-types-raw! :claim-root {:args {:input :any} :return :int})
  (doseq [[actual declared]
          [[{:s [:secret (callback e)] :decoy :int} {:decoy [:secret :int]}]
           [[:secret (callback e)] [:union [:secret (callback e)] (callback e)]]
           [[:union :null [:secret (callback e)]] [:union :null (callback e)]]]]
    (registry/record-rich-types-raw! :secret-producer {:args {} :return actual})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strips a hide-result marker"
          (check-entry! {:name :hidden-loss :parent :claim-root
                         :args {:input {:ref :secret-producer :type declared}}})))))


(deftest backward-return-refinement-does-not-narrow-a-free-scalar-ingress-pin
  (types/register-type-alias! :validated-text [:refine :text [:matches "^ok$"]])
  (registry/record-rich-types-raw! :narrower-template {:args {:value 'a} :return 'a})
  (doseq [pin [{:type :text} {:as :value :type :text} {:as :input :type :text}]]
    (let [entry (check-entry! {:name :narrower :parent :narrower-template
                               :args {:value pin} :return-type :validated-text})]
      (is (= :text (get-in entry [:args (or (:as pin) :value)])))
      (is (= (types/resolve-alias :validated-text) (:return entry)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
        (check-entry! {:name :wrong-free-input :parent :narrower-template
                       :args {:value {:type :int}} :return-type :validated-text})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refinement constraint failed"
        (check-entry! {:name :wrong-literal-input :parent :narrower-template
                       :args {:value {:value "bad" :type :text}} :return-type :validated-text})))
  (registry/record-rich-types-raw! :concrete-input {:args {:value :int} :return :int})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
        (check-entry! {:name :wrong-concrete-input :parent :concrete-input :args {:value {:type :text}}})))
  (let [required (callback #{:browser-main})]
    (doseq [execution [:unknown #{} #{:jvm-server}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
            (check-entry! {:name :wrong-callable-input :parent :narrower-template
                           :return-type required :args {:value {:type (callback execution)}}}))))))
