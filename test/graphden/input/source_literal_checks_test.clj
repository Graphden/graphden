(ns graphden.input.source-literal-checks-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source-literal-checks :as checks]))


(defn- read-check
  [[scope path :as address] type]
  {:id [address :value-type :field-read] :address address :scope scope :path path
   :kind :value-type :trigger :field-read :expected-type type})


(deftest literal-can-discharge-or-specialize-only-its-unchanged-read
  (let [scope [(random-uuid) []]
        literal [scope [(random-uuid)]]
        read-address [scope [(random-uuid)]]
        other (read-check [scope [(random-uuid)]] 'b)
        before-check (read-check read-address 'a)
        deferred {:status :deferred :reason :runtime-type-obligation}
        checked {:status :checked}
        expression {:kind :projection :scope scope :path (second literal)}
        read-entry {:binding-id (random-uuid) :expression expression :expected-type 'a
                    :reference {:mode :projection :source literal :actual-type 'a}}
        read-path [:scopes [] :bindings read-address]
        id (random-uuid)
        certificates [{:row {:id id :value-present true :expression-kind "value" :value "ok"}
                       :public-path [:input] :address literal}]
        before (assoc-in {:obligations [before-check other] :proofs {read-address deferred}
                          :public-input {:routes {[:input] {:completion-type 'a :address literal}}}}
                         read-path read-entry)
        after (-> before
                  (assoc :substitution {'a :text} :proofs {read-address checked literal checked})
                  (assoc-in [:scopes [] :bindings literal]
                            {:binding-id id :expected-type :text :expression {:kind :value :value "ok"}
                             :reference {:mode :literal :actual-type :text}})
                  (assoc-in (conj read-path :expected-type) :text)
                  (assoc-in (conj read-path :reference :actual-type) :text))]
    (is (= #{other} (checks/replay before after certificates)))
    (is (= #{other (read-check literal :text) (read-check read-address :text)}
           (checks/replay before (assoc after :proofs {read-address deferred literal deferred}) certificates)))
    (doseq [modified [(update after :proofs dissoc read-address)
                      (assoc-in after (conj read-path :binding-id) (random-uuid))
                      (assoc-in after (conj read-path :expression :path) (second read-address))
                      (assoc-in after [:substitution 'a] :int)
                      (assoc-in after [:scopes [] :bindings literal :expression :value] "changed")]]
      (is (= #{before-check other} (checks/replay before modified certificates))))))
