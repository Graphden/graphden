(ns graphden.types.view-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.types.view :as view]))


(use-fixtures :each
  (fn [t]
    (binding [shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
      (types/register-marker! :ui-view {:monotone? true})
      (t))))


(deftest view-does-not-subtype-precise-value-sinks
  (is (false? (types/subtype? [:ui-view :text] :text)))
  (is (false? (types/subtype? [:ui-view [:refine :text [:matches "^#"]]]
                              [:refine :text [:matches "^#"]])))
  (is (true? (types/subtype? [:ui-view :text] :any))))


(deftest explicit-encoding-retains-all-other-contracts
  (doseq [[actual expected]
          [[[:secret [:ui-view :text]] [:secret :text]]
           [[:ui-view [:secret :text]] [:secret :text]]
           [{:items [:list [:ui-view :text]]} {:items [:list :text]}]
           [[:fn {:x [:ui-view :int]} [:secret [:ui-view :text]] #{:db} [:execution-var 'E]]
            [:fn {:x [:ui-view :int]} [:secret [:ui-view :text]] #{:db} [:execution-var 'E]]]
           [[:refine :text [:= [:ui-view :text]]] [:refine :text [:= [:ui-view :text]]]]]]
    (is (= expected (view/encoded-type actual)))))


(deftest broad-jsonb-tolerates-view-representation-without-removing-secrecy
  (doseq [wrap [identity #(vector :list %) #(vector :map :keyword %) #(hash-map :color %)]]
    (is (true? (types/subtype? (wrap [:ui-view :text]) :jsonb)))
    (is (false? (types/subtype? (wrap [:ui-view [:secret :text]]) :jsonb)))
    (is (false? (types/subtype? (wrap [:secret [:ui-view :text]]) :jsonb)))))


(deftest broad-sinks-get-a-hint-while-precise-connections-keep-the-view-type
  (let [t {:colors [:list [:ui-view :text]]}
        bindings {:value {:type t}}]
    (is (= [:types/view-to-broad-sink]
           (mapv :code (view/broad-sink-hints {:value :jsonb} bindings))))
    (is (= [:types/view-to-broad-sink]
           (mapv :code (view/broad-sink-hints {:value :any} bindings))))
    (is (= [] (view/broad-sink-hints {:value {:colors [:list :text]}} bindings)))
    (is (= t (:type (:value bindings))))
    (is (= {:colors [:list :text]} (view/encoded-type t)))))


(deftest value-producing-marker-propagation-distinguishes-view-from-secrecy
  (is (= :bool (types/taint-with-secret-if-tainted {:x {:type [:ui-view :text]}} :bool)))
  (is (= [:secret :bool]
         (types/taint-with-secret-if-tainted {:x {:type [:ui-view [:secret :text]]}} :bool))))


(deftest a-hidden-marker-cannot-use-the-view-name-to-bypass-content-protection
  (types/register-marker! :ui-view {:monotone? true :hide-result? true})
  (is (= [:ui-view :text] (view/encoded-type [:ui-view :text])))
  (is (false? (types/subtype? [:ui-view :text] :jsonb)))
  (is (= [:ui-view :bool]
         (types/taint-with-secret-if-tainted {:value {:type [:ui-view :text]}} :bool))))


(deftest nested-broad-sinks-carry-their-value-paths
  (doseq [[a e path]
          [[{:payload [:ui-view :text]} {:payload :jsonb} [:payload]]
           [[:list [:ui-view :text]] [:list :any] [:element]]
           [[:map :keyword [:ui-view :text]] [:map :keyword :jsonb] [:value]]]]
    (is (= [{:arg-name :input :path path :code :types/view-to-broad-sink
             :message "This broad data input accepts a view representation; connect the field value to save data, or use encode-view to encode the view explicitly."}]
           (view/broad-sink-hints {:input e} {:input {:type a}})))))
