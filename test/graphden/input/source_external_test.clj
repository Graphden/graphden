(ns graphden.input.source-external-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source-external :as external]))


(deftest ignored-handler-argument-keeps-the-complete-independent-input-surface
  (let [target (random-uuid)
        owner (random-uuid)
        ref (random-uuid)
        edge (random-uuid)
        id (random-uuid)
        public-path [:route :handler]
        path [:body :tail]
        source-address [[target [edge]] [id]]
        destination [[owner [ref edge]] [id]]
        route {:name :tail :kind :input-field :origin-field-id id :whole-bindable? true
               :required true :required-now? true :type 'a :completion-type 'a :address source-address}
        nested (assoc route :type 'b :completion-type 'b :address destination)
        original {:fn-id target :public-input {:routes {path route}}}
        installed-path (into public-path path)
        after {:public-input {:routes {installed-path nested}}}
        capture {:target destination :value-path [:tail]}
        prove (fn [source installed captures]
                (external/same-inputs? source installed [owner [ref]] public-path captures {'a 'b}))]
    (is (prove original after [capture]))
    (doseq [changed [(assoc nested :required-now? false)
                     (assoc nested :whole-bindable? false)
                     (assoc nested :origin-field-id (random-uuid))
                     (assoc nested :type 'a)
                     (assoc nested :completion-type 'a)
                     (assoc nested :address [[owner [edge]] [id]])]]
      (is (not (prove original (assoc-in after [:public-input :routes installed-path] changed) [capture]))))
    (is (not (prove original (assoc-in after [:public-input :routes (conj public-path :other)] nested) [capture])))
    (is (not (prove original after [capture capture])))
    (is (not (prove original after [(assoc capture :value-path [])])))
    (is (not (prove (assoc original :fn-id (random-uuid)) after [capture])))))
