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


(deftest a-scoped-binding-may-consume-only-its-own-external-capture
  (let [[owner edge-id field target parameter capture sibling] (repeatedly 7 random-uuid)
        from [[owner []] [field]]
        to [owner [edge-id]]
        supplied [to [capture]]
        other [to [sibling]]
        positional [to [parameter]]
        contract [:fn {:item :any} :bool #{} :unknown]
        edge {:from from :to to :target-fn-id target :binding-id edge-id :mode :graph-callable}
        closure {:parameters [:item] :call-sites [{:target positional :parameter :item :value-path []}] :captures []
                 :external-captures [{:target supplied :value-path [:constraint]} {:target other :value-path [:tail]}]
                 :callable-type contract}
        snapshot {:fns {target {:lambda-params ["item"]}}}
        bound {:expected-type contract :reference {:mode :graph-callable :callable-type contract :capture-resolved? true}}
        before {:scopes {(second to) {:fn-id target}} :closures {from closure} :public-input {:bindings {from bound}}}
        after (update-in before [:closures from :external-captures] subvec 1)
        certs [{:address supplied :row {:expression-kind "value"} :edges [(assoc edge :captured-input supplied)]}]]
    (is (external/captured-input? before snapshot edge supplied))
    (is (not (external/captured-input? before snapshot edge positional)))
    (is (not (external/captured-input? before {} edge supplied)))
    (is (external/preserves-capture-inputs? before after certs (constantly false)))
    (is (not (external/preserves-capture-inputs? before after [(assoc (first certs) :address positional)] (constantly true))))
    (let [reference (assoc-in (first certs) [:row :ref-fn-id] (random-uuid))
          additional {:target [to [(random-uuid)]] :value-path [:constraint :spec]}
          installed (update-in after [:closures from :external-captures] conj additional)
          verified (fn [c path captures] (= [reference [:constraint] [additional]] [c path (vec captures)]))]
      (is (external/preserves-capture-inputs? before installed [reference] verified))
      (is (not (external/preserves-capture-inputs? before installed [reference] (constantly false)))))
    (doseq [changed [(assoc-in after [:closures from :call-sites] [])
                     (assoc-in after [:closures from :external-captures] [])
                     (assoc-in after [:closures from :captures] [{:from supplied :source other}])
                     (assoc-in after [:public-input :bindings from :reference :capture-resolved?] false)]]
      (is (not (external/preserves-capture-inputs? before changed certs (constantly false)))))))
