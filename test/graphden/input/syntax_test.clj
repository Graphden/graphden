(ns graphden.input.syntax-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.input.core :as input]
    [graphden.input.syntax :as syntax]))


(def contract
  {:type :call
   :fields [{:id :user-field :name :user :type :user
             :fields [{:id :name-field :name :name :type :text}
                      {:id :email-field :name :email :type :text :required false}]}
            {:id :options-field :name :options :type [:map :keyword :any]}
            {:id :hof-field :name :callback :type [:fn [:text] :text]}
            {:id :value-field :name :value :type :any}
            {:id :ref-field :name :ref :type :any}]})


(def context
  {:name->id {:get-name :producer-id :callback :callback-id}
   :id->name {:producer-id :get-name :callback-id :callback}
   :binding-id (fn [scope path] [scope path])})


(defn- parse
  [form]
  (syntax/parse-expression contract :scope form context))


(defn- error-code
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))


(deftest partial-record-and-nested-ref
  (let [expr (parse {:user {:name [:ref :get-name]}
                     :callback [:ref :callback]})
        bindings (input/bind-input (input/input-contract contract) {} :scope expr)
        name-binding (get bindings [:scope [:user-field :name-field]])
        callback-binding (get bindings [:scope [:hof-field]])]
    (is (= {:kind :ref :fn-id :producer-id} (:expression name-binding)))
    (is (= [:scope [:user-field :name-field]] (:binding-id name-binding)))
    (is (= :text (:expected-type name-binding)))
    (is (= [:fn [:text] :text] (:expected-type callback-binding)))
    (is (= 2 (count bindings)))))


(deftest values-and-control-looking-data-stay-opaque
  (doseq [value [nil false {} [] {:ref :get-name :value [:ref :callback]}
                 [:ref :get-name] #{:ref :value} '(ref get-name)]]
    (is (= {:kind :value :value value :binding-id [:scope [:options-field]]}
           (get-in (parse {:options [:value value]}) [:fields :options-field]))))
  (testing "declared field names do not become operators"
    (let [expr (parse {:value false :ref :get-name})]
      (is (false? (get-in expr [:fields :value-field :value])))
      (is (= :get-name (get-in expr [:fields :ref-field :value]))))))


(deftest atomic-dictionary-and-whole-record
  (is (= :partial-record-required
         (error-code #(parse {:options {:key [:value 1]}}))))
  (let [expr (parse {:user [:value {:name "Alice"}]})
        bindings (input/bind-input (input/input-contract contract) {} :scope expr)]
    (is (= [[:scope [:user-field]]] (vec (keys bindings))))
    (is (= :occupied
           (try (input/bind-input (input/input-contract contract) bindings :scope
                                  (parse {:user {:email "a@example.test"}}))
                nil
                (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))


(deftest explicit-export-is-lossless-and-deterministic
  (doseq [form [{:user {:name "Alice"} :value nil}
                {:user {:name [:ref :get-name]} :callback [:ref :callback]}
                {:options [:value {:value 1 :ref "ordinary data"}]}
                {} {:user {}}]]
    (let [expr (parse form)
          exported (syntax/emit-expression contract expr context)]
      (is (= expr (parse exported)))
      (is (= exported (syntax/emit-expression contract (parse exported) context))))))


(deftest malformed-expressions-fail-with-safe-diagnostics
  (doseq [[form code] [[{:unknown "private payload"} :unknown-field]
                       [{:user {:unknown "private payload"}} :unknown-field]
                       [{:value [:ref :unknown]} :unresolved-ref]
                       [{:value [:ref nil]} :unresolved-ref]
                       [{:value [:value]} :explicit-expression-required]
                       [{:value [:unknown "private payload"]} :unknown-operator]
                       [{:value '(1 2)} :explicit-expression-required]]]
    (is (= code (error-code #(parse form)))))
  (let [error (try (parse {:user {:unknown "private payload"}})
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= {:type :input/invalid-syntax :code :unknown-field :path [:user-field]}
           (ex-data error)))))


(deftest missing-export-context-never-drops-references
  (is (= :unresolved-ref
         (error-code #(syntax/emit-expression contract
                                              (parse {:value [:ref :get-name]})
                                              {:id->name {}}))))
  (is (= :unsupported-expression
         (error-code #(syntax/emit-expression contract
                                              {:kind :projection :scope :other :path []}
                                              context)))))
