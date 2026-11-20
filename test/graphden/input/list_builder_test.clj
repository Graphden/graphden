(ns graphden.input.list-builder-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.list-builder :as builder]
    [graphden.input.source :as source]
    [graphden.input.source-list :as source-list]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  [start end]
  (let [base {:namespace "demo" :name :consume :args {:xs {:type [:list :any]}} :return-type :any}
        source [{:namespace "demo" :name :source :args {} :return-type :int}
                base {:namespace "demo" :name :first-step :parent :consume :args {:xs start}}
                {:namespace "demo" :name :last-step :parent :first-step :args {:xs end}}]
        context (parse/prepare-module source {} {})
        rows (parse/parse-prepared-module context)
        fragments (for [[n form] [[:first-step start] [:last-step end]]
                        :let [binding (some #(when (and (= :binding (:kind %)) (= (ids/fn-id "demo" n) (:fn-id %))) %) rows)
                              items (filterv #(= (:id binding) (:binding-id %)) rows)]]
                    (builder/source-fragment binding items form (:name->id context)))]
    (vec fragments)))


(defn- reason
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))


(deftest ordered-fragments-use-separate-continuations
  (let [[head tail :as fragments] (fixture [:source {:value :source} nil false] {:append [:source] :closed true})
        source-id (ids/fn-id "demo" :source)
        first-id (:binding-id head)
        {:keys [bindings open-continuation]} (builder/link-fragments fragments)
        [initial next-fragment seal] bindings
        form (builder/emit-fragment head {source-id :demo/source})]
    (is (= [:list-builder {:tail :open :items [[:ref :demo/source] [:value :source] [:value nil] [:value false]]}] form))
    (is (= (dissoc head :owner-id :target-id) (builder/parse-fragment first-id form {:demo/source source-id})))
    (is (= (mapv #(ids/binding-list-item-id first-id %) (range 4)) (mapv :id (:items head))))
    (is (= [(:target-id head)] (:target-path initial)))
    (is (= [first-id] (:scope-path next-fragment)))
    (is (= [(:tail-id head)] (:target-path next-fragment)))
    (is (= (:binding-id tail) (:id next-fragment)))
    (is (= [(:tail-id tail)] (:target-path seal)))
    (is (= [] (:value seal)))
    (is (nil? open-continuation))
    (is (= [[first-id] [(:tail-id head)]] (:open-continuation (builder/link-fragments [head]))))))


(deftest explicit-blockers-for-unmapped-or-closed-routes
  (is (= :positional-list-input-required (reason #(fixture [{:value {:as :input}}] []))))
  (is (= :unsupported-list-binding (reason #(fixture {:append [] :closed? true} []))))
  (is (= :closed-list-continuation (reason #(builder/link-fragments (fixture {:append [] :closed true} [])))))
  (is (= :invalid-list-chain (reason #(let [[head] (fixture [] [])] (builder/link-fragments [head head]))))))


(deftest source-planner-records-the-authored-chain-only
  (let [base {:namespace "demo" :name :consume :args {:xs {:type [:list :any]}} :return-type :any}
        head {:namespace "demo" :name :head :parent :consume :args {:xs [1]}}
        tail {:namespace "demo" :name :tail :parent :head :args {:xs [2]}}
        originals [base head tail]
        ctx (parse/prepare-module originals {} {})
        trace (fn [forms]
                (-> (source/plan-module forms ctx) :definitions last :legacy :arguments first :list-builder))
        linked (trace originals)]
    (is (= [[1] [2]] (mapv #(mapv :value (:items %)) (:fragments linked))))
    (is (= (mapv :binding-id (:fragments linked)) (mapv :id (:bindings linked))))
    (is (= :list-source-origin-required (:blocker (trace [tail]))))))


(deftest compiler-receives-ordered-refs-and-opaque-literals
  (let [owner (ids/fn-id "demo" :consumer)
        target (ids/fn-id "demo" :target)
        origin (ids/binding-id owner (ids/slot-id owner :items))
        fragment (builder/parse-fragment origin [:list-builder {:items [[:ref :target] [:value {:as :data}] [:value nil]]
                                                                :tail :open}] {:target target})
        [ref-row literal nil-row] (builder/compiler-items fragment)]
    (is (= [target 0 origin] ((juxt :ref-fn-id :position :binding-id) ref-row)))
    (is (= [{:as :data} true] ((juxt :value :literal) literal)))
    (is (= [nil nil 2] ((juxt :value :ref-fn-id :position) nil-row)))
    (is (= :list-item-origin-mismatch
           (reason #(builder/compiler-items (assoc-in fragment [:items 1 :id] (:id ref-row))))))))


(deftest ambiguous-bare-refs-use-their-authoring-namespace
  (let [alpha {:namespace "alpha" :name :read :args {} :return-type :int}
        beta (assoc alpha :namespace "beta")
        names (:name->id (parse/prepare-module [alpha beta] {} {}))
        original [:read {:value :read} {:ref :read} {:value {:ref :read}}]
        scoped (source-list/field-names names "alpha" original)
        form (builder/source-form original scoped)
        fragment (builder/parse-fragment (ids/fn-id "demo" :test) form scoped)]
    (is (= [(ids/fn-id "alpha" :read) :read (ids/fn-id "alpha" :read) {:ref :read}]
           (mapv #(or (:fn-id %) (:value %)) (:items fragment))))))
