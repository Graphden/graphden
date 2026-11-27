(ns graphden.input.metadata-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.metadata :as metadata]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[base child root empty-root field] (repeatedly 5 random-uuid)]
    {:base base :child child :field field
     :context (context/build-context
                {:fns {base {:input-type-fn-id root}
                       child {:input-type-fn-id empty-root :parent-ids [base]}}
                 :members {root [{:slot-id field :position 0}] empty-root []}
                 :fields {field {:id field :name "value" :required false
                                 :type-fn-id (get (ids/primitive-fn-ids) :any)}}})}))


(defn- row
  [owner field policy]
  (merge {:id (random-uuid) :fn-id owner :scope-path [] :target-path [field]} policy))


(defn- with-rows
  [ctx rows]
  (assoc ctx :bindings (into {} (map (juxt :id identity)) rows)))


(deftest required-policy-strengthens-without-mutating-declared-contract-test
  (let [{:keys [base child field context]} (fixture)
        ctx (with-rows context [(row base field {:required true})])
        result (analysis/analyze-input ctx child)]
    (is (= [[[child []] [field]]] (:required-addresses result)))
    (is (false? (get-in result [:declared-contract :fields 0 :required])))
    (is (true? (get-in result [:configured-contract :fields 0 :required])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-input
            (update ctx :bindings assoc (random-uuid) (row child field {:required false})) child)))))


(deftest concrete-type-overrides-must-narrow-even-without-checker-test
  (let [{:keys [base child field context]} (fixture)
        int-type (get (ids/primitive-fn-ids) :int)
        any-type (get (ids/primitive-fn-ids) :any)
        narrowed (row base field {:type-override-fn-id int-type})
        result (analysis/analyze-input (with-rows context [narrowed]) child)]
    (is (= :int (get-in result [:remaining-contract :fields 0 :type])))
    (is (= :incomplete (:status result)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-input
            (with-rows context [narrowed (row child field {:type-override-fn-id any-type})]) child)))))


(deftest exact-source-annotation-retains-the-erased-collection-type-test
  (let [{:keys [base child field context]} (fixture)
        sequence-id (get (ids/primitive-fn-ids) :sequence)
        input-id (get-in context [:fns base :input-type-fn-id])
        rich [:list :text]
        annotation (row child field {:expression-kind nil :value-present false :type-override-fn-id sequence-id
                                     :description "Authored head elements"})
        ctx (-> context
                (assoc-in [:fields field :type-fn-id] sequence-id)
                (assoc-in [:type-bodies input-id] {:value rich})
                (assoc :source-field-types {[field sequence-id] rich})
                (with-rows [annotation]))
        frozen (assoc ctx :source-binding-types {[(:id annotation) sequence-id] rich})
        analyzed (analysis/analyze-input frozen child)]
    (is (= rich (get-in analyzed [:configured-contract :fields 0 :type])))
    (is (= "Authored head elements" (get-in analyzed [:configured-contract :fields 0 :description])))
    (is (empty? (:bindings analyzed)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-input ctx child)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-input (assoc frozen :source-binding-types {[(:id annotation) sequence-id] [:list :int]}) child)))))


(deftest rich-pin-witness-restores-only-the-selected-binding-and-keeps-checker-coverage-explicit
  (let [{:keys [base child field context]} (fixture)
        sequence-id (ids/primitive-fn-id :sequence)
        input-id (get-in context [:fns base :input-type-fn-id])
        other (random-uuid)
        annotation (row child field {:type-override-fn-id sequence-id})
        ctx (-> context
                (assoc-in [:fields field :type-fn-id] sequence-id)
                (assoc-in [:fields other] {:id other :name "other" :required false :type-fn-id sequence-id})
                (update-in [:members input-id] conj {:slot-id other :position 1})
                (assoc-in [:type-bodies input-id] {:value [:list 'a] :other [:list :text]})
                (assoc :source-field-types {[field sequence-id] [:list 'a] [other sequence-id] [:list :text]}
                       :source-binding-types {[(:id annotation) sequence-id] [:list :uuid]})
                (with-rows [annotation]))
        result (analysis/analyze-input ctx child)]
    (is (= [[:list :uuid] [:list :text]] (mapv :type (get-in result [:configured-contract :fields]))))
    (is (= [:list 'a] (get-in result [:declared-contract :fields 0 :type])))
    (is (not (contains? (:type-bodies ctx) sequence-id)))
    (is (= :incomplete (:status result)))
    (is (false? (get-in result [:coverage :type-checker])))
    (doseq [witness [[:list :missing-type] [:map :text :uuid]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
            (analysis/analyze-input (assoc ctx :source-binding-types {[(:id annotation) sequence-id] witness}) child))))))


(deftest terminal-locks-descendant-but-does-not-retroactively-reject-ancestor-test
  (let [{:keys [base child field context]} (fixture)
        value {:expression-kind "value" :value-present true :value 1}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-input
            (with-rows context [(row base field {:terminal true}) (row child field value)]) child)))
    (is (= [] (:required-addresses
                (analysis/analyze-input
                  (with-rows context [(row base field value) (row child field {:terminal true})]) child))))))


(deftest metadata-flags-do-not-coerce-non-booleans-test
  (let [{:keys [base field context]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-input (with-rows context [(row base field {:required "false"})]) base)))))


(deftest nested-type-update-retains-parent-marker-and-refinement-test
  (let [[outer inner] (repeatedly 2 random-uuid)
        node {:type [:secret [:refine {:user {:age :numeric}} [:present :user]]]
              :fields [{:id outer :name :user :type {:age :numeric}
                        :fields [{:id inner :name :age :type :numeric :required true}]}]}
        updated (descriptor/update-node node [outer inner] #(assoc % :type :int))]
    (is (= [:secret [:refine {:user {:age :int}} [:present :user]]] (:type updated)))
    (is (= :int (:type (descriptor/node-at updated [outer inner]))))))


(deftest terminal-target-policy-blocks-a-caller-use-overlay-test
  (let [{:keys [base field context]} (fixture)
        [caller caller-type ref-field edge] (repeatedly 4 random-uuid)
        seal (row base field {:terminal true})
        overlay (assoc (row caller field {:expression-kind "value" :value-present true :value 1})
                       :scope-path [edge])
        ctx (-> context
                (assoc-in [:fns caller] {:input-type-fn-id caller-type})
                (assoc-in [:members caller-type] [{:slot-id ref-field :position 0}])
                (assoc-in [:fields ref-field] {:id ref-field :name "result" :required true
                                               :type-fn-id (get (ids/primitive-fn-ids) :any)})
                (assoc-in [:signatures base] {:return :any})
                (with-rows [seal overlay
                            (assoc (row caller ref-field {:expression-kind "ref" :ref-fn-id base}) :id edge)]))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (analysis/analyze-effective-input ctx caller)))))


(deftest record-type-narrowing-cannot-remap-an-inherited-child-binding-by-name-test
  (let [{:keys [base child field context]} (fixture)
        [old-record new-record old-child new-child] (repeatedly 4 random-uuid)
        numeric (:numeric (ids/primitive-fn-ids))
        integer (:int (ids/primitive-fn-ids))
        bound (assoc (row base field {:expression-kind "value" :value-present true :value 1})
                     :target-path [field old-child])
        narrowed (row child field {:type-override-fn-id new-record})
        ctx (-> context
                (assoc-in [:fields field :type-fn-id] old-record)
                (assoc-in [:members old-record] [{:slot-id old-child :position 0}])
                (assoc-in [:members new-record] [{:slot-id new-child :position 0}])
                (assoc-in [:fields old-child] {:id old-child :name "number" :type-fn-id numeric :required true})
                (assoc-in [:fields new-child] {:id new-child :name "number" :type-fn-id integer :required true})
                (with-rows [bound narrowed]))
        error (try (analysis/analyze-input ctx child) (catch clojure.lang.ExceptionInfo e e))]
    (is (= :unknown-field (:code (ex-data error))))
    (is (= [field old-child] (:path (ex-data error))))))


(deftest subtype-promotion-does-not-authorize-removing-an-inherited-marker-test
  (doseq [marker [:secret :ui-view]]
    (let [{:keys [base child field context]} (fixture)
          marked-type (random-uuid)
          text (:text (ids/primitive-fn-ids))
          ctx (-> context
                  (assoc-in [:markers :ui-view] {:hide-result? true})
                  (assoc-in [:type-bodies marked-type] [marker :text])
                  (assoc-in [:fields field :type-fn-id] marked-type)
                  (with-rows [(row base field {:required true})
                              (row child field {:type-override-fn-id text})]))
          error (try (analysis/analyze-input ctx child) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :marker-stripping (:code (ex-data error)))))))


(deftest reference-assertions-need-the-checked-overlay-at-the-original-address
  (let [id (random-uuid)
        address [[(random-uuid) []] [(random-uuid)]]
        status [:refine :int {:min 100 :max 599}]
        binding {:binding-id id :address address :expression {:kind :ref} :reference {:mode :value} :expected-type status}
        proof {:status :checked :address address :binding-id id}
        node {:metadata-proofs {id proof}}
        asserted metadata/asserted-read-type]
    (is (= status (asserted node binding :any)))
    (is (nil? (asserted node binding :text)))
    (is (nil? (asserted node (assoc-in binding [:reference :mode] :projection) :any)))
    (doseq [changed [(assoc proof :address [[(random-uuid) []] (second address)])
                     (assoc proof :status :deferred) (assoc proof :binding-id (random-uuid))]]
      (is (nil? (asserted {:metadata-proofs {id changed}} binding :any))))
    (let [binding (assoc binding :expected-type [:map :text :text])
          branch [:map [:union :text 'k] [:union :text 'v]]]
      (is (= [:map :text :text]
             (asserted node binding [:union branch [:map [:union :text 'x] [:union :text 'y]]])))
      (is (nil? (asserted node binding [:union branch [:map :int :text]]))))))
