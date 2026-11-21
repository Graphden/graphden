(ns graphden.input.stored-implementations-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.stored-implementations :as implementations]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[base child base-type child-type field] (repeatedly 5 random-uuid)
        text (ids/primitive-fn-id :text)
        ctx (context/build-context
              {:fns {base {:id base :name "encode" :return-type-fn-id text :input-type-fn-id base-type}
                     child {:id child :name "encode-body" :parent-ids [base] :input-type-fn-id child-type}}
               :members {base-type [{:slot-id field :position 0}] child-type []}
               :fields {field {:id field :name "body" :type-fn-id text :required true}}})
        signature {:name :encode :fn-id base :args {:body :text} :return :text :effects #{}}
        impl (fn [_ _] (throw (ex-info "Resolution must not invoke native code" {})))]
    {:context ctx :base base :child child :field field
     :trusted {base {:signature signature :impl impl}}}))


(defn- rejection
  [f]
  (try (f) (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))


(deftest stored-descendant-inherits-the-exact-trusted-argument-ids
  (let [{:keys [context base child field trusted]} (fixture)
        resolved (implementations/resolve-closure context child trusted)]
    (is (= #{base child} (set (keys resolved))))
    (is (= (get resolved base) (get resolved child)))
    (is (= {:base-fn-id base :argument-paths {:body [field]}
            :root-entry (get-in trusted [base :signature])}
           (dissoc (get resolved child) :impl)))
    (is (identical? (get-in trusted [base :impl]) (get-in resolved [child :impl])))))


(deftest stored-abi-must-match-the-provided-identity-and-contract
  (let [{:keys [context base child trusted]} (fixture)
        resolve-with (partial implementations/resolve-closure context child)]
    (is (= :implementation-signature-required (rejection #(resolve-with {}))))
    (is (= :implementation-signature-required
           (rejection #(resolve-with (assoc-in trusted [base :signature :fn-id] child)))))
    (is (= :implementation-signature-mismatch
           (rejection #(resolve-with (assoc-in trusted [base :signature :args :body] :any)))))
    (is (= :implementation-signature-mismatch
           (rejection #(resolve-with (assoc-in trusted [base :signature :return] :bool)))))
    (is (= :rule-signature-view-required
           (rejection #(resolve-with (assoc-in trusted [base :signature :return-type-rule] identity)))))))
