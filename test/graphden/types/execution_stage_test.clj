(ns graphden.types.execution-stage-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.capabilities :as capabilities]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check.execution :as execution]))


(defn- callable
  [profiles]
  [:fn {:item :int} :int #{} profiles])


(defn- root
  [ns-path name args]
  (let [id (ids/fn-id ns-path name)]
    {:fn-id id :args args :execution (capabilities/native-execution id (fn [_ _] nil))}))


(deftest invoked-callback-and-captured-callback-have-different-stages
  (let [browser (callable #{:browser-main})
        bindings {:func {:bound-ref :browser :type browser}}
        refs {:browser {:execution #{:browser-main} :return :int}}
        map-root (root "core.hof" :map {:func (callable :unknown) :coll [:list :int]})
        const-root (root "core.logic" :const {:value 'a})]
    (is (= #{:browser-main} (execution/derive map-root bindings refs)))
    (is (= :unknown
           (execution/derive const-root
                             {:value {:bound-ref :browser :type browser}} refs)))
    (is (= :unknown (execution/derive map-root {} refs)))))


(deftest effective-callback-rebinding-replaces-parent-stage-contribution
  (let [map-root (root "core.hof" :map {:func (callable :unknown) :coll [:list :int]})
        parent-bindings {:func {:bound-ref :server :type (callable #{:jvm-server})}}
        child-bindings (merge parent-bindings
                              {:func {:bound-ref :browser :type (callable #{:browser-main})}})
        refs {:server {:execution #{:jvm-server} :return :int}
              :browser {:execution #{:browser-main} :return :int}}]
    (is (= #{:jvm-server} (execution/derive map-root parent-bindings refs)))
    (is (= #{:browser-main} (execution/derive map-root child-bindings refs)))))


(deftest callable-construction-and-scalar-dependencies-run-in-the-current-stage
  (let [cb (callable #{:browser-main})
        refs {:constructor {:execution #{:jvm-server} :return cb}
              :server-value {:execution #{:jvm-server} :return :text}}
        map-root (root "core.hof" :map {:func (callable :unknown) :coll [:list :int]})
        const-root (root "core.logic" :const {:value 'a})]
    (is (= :unknown (execution/derive map-root
                                      {:func {:bound-ref :constructor :type cb}} refs)))
    (is (= :unknown (execution/derive const-root
                                      {:value {:ref :constructor :type cb}} refs)))
    (is (= #{:jvm-server} (execution/derive const-root
                                            {:value {:ref :server-value :type :text}} refs)))))


(deftest unsupported-stage-and-unresolved-binding-provenance-remain-unknown
  (let [unsupported (root "core.hof" :reduce {:func (callable :unknown)})
        const-root (root "core.logic" :const {:value 'a})]
    (is (= :unknown (execution/derive unsupported
                                      {:func {:bound-ref :callback :type (callable #{:jvm-server})}} {})))
    (is (= :unknown (execution/derive const-root
                                      {:renamed-value {:type :text}} {})))
    (is (= :unknown (execution/derive const-root {:value {:ref :missing}} {})))))


(deftest literal-vector-payloads-do-not-contribute-execution-references
  (let [const-root (root "core.logic" :const {:value 'a})
        refs {:red {:execution #{:jvm-server}}
              :blue {:execution :unknown}
              :server {:execution #{:jvm-server}}}]
    (doseq [payload [[:red :blue] [{:ref :server}]]]
      (is (= #{:jvm-server :browser-main}
             (execution/derive const-root
                               {:value {:type [:list :any] :value payload :value-present true}} refs))))
    (is (= #{:jvm-server}
           (execution/derive const-root
                             {:value {:type [:list :any] :value [:server] :value-present true
                                      :execution-refs [:server]}}
                             refs)))))


(deftest callable-producer-at-implemented-invocation-slot-has-no-browser-proof
  (let [cb (callable #{:jvm-server :browser-main})
        map-root (root "core.hof" :map {:func cb :coll [:list :int]})
        refs {:constructor {:execution #{:jvm-server :browser-main} :return cb}}]
    (is (= :unknown (execution/derive map-root
                                      {:func {:bound-ref :constructor :type cb}} refs)))))


(deftest unsupported-free-and-unproven-lambda-bindings-have-no-browser-proof
  (let [cb (callable #{:jvm-server :browser-main})
        map-root (root "core.hof" :map {:func cb :coll [:list :int]})
        binding {:func {:bound-ref :callback :type cb}}]
    (is (= :unknown (execution/derive map-root {:func {:type cb}} {})))
    (doseq [target [{:args {:left :any :right :any} :lambda-params [:left :right]}
                    {:args {:left :any :right :any}}
                    {:args {:value :int}}
                    {:args {:value :int} :lambda-params [:missing]}]]
      (is (= :unknown (execution/derive map-root binding
                                        {:callback (assoc target :return :int
                                                          :execution #{:jvm-server :browser-main})}))))
    (doseq [target [{:args {:item :int :captured :text}}
                    {:args {:value :int} :lambda-params [:value]}]]
      (is (= #{:jvm-server :browser-main}
             (execution/derive map-root binding
                               {:callback (assoc target :return :int
                                                 :execution #{:jvm-server :browser-main})}))))))
