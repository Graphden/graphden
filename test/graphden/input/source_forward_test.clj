(ns graphden.input.source-forward-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.scoped-source :as scoped-source]
    [graphden.input.source :as source]
    [graphden.input.source-forward :as forward]
    [graphden.input.source-legacy-readers :as readers]
    [graphden.input.source-scope :as scope]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  ([args] (fixture args []))
  ([args extensions]
   (let [forms [{:name :combine :args {:left {:type :any} :right {:type :any}} :return-type :any}
                {:name :encode :args {:data {:type :text :required true}} :return-type :text}
                {:name :encode-body :parent :encode}
                {:name :response :parent :combine :args args}]
         declarations (mapv #(assoc % :namespace "fixture") (into forms extensions))
         prepared (parse/prepare-module declarations {} {})
         scope (source/plan-module declarations prepared)]
     [prepared (some #(when (= :response (get-in % [:source :name])) %) (:definitions scope))])))


(defn- rejection
  [prepared entry]
  (try
    (forward/verify-forwarded-field prepared entry :data)
    (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(deftest forwarded-field-keeps-its-own-id-and-the-exact-reference-use
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        owner (ids/fn-id "fixture" :response)
        source-id (ids/slot-id owner :data)
        target-id (ids/slot-id (ids/fn-id "fixture" :encode) :data)
        edge (ids/binding-id owner (ids/slot-id (ids/fn-id "fixture" :combine) :left))
        proof (forward/verify-forwarded-field prepared entry :data)
        tampered (update-in entry [:legacy :arguments]
                            (fn [arguments]
                              (mapv #(cond-> % (:source-slot-id %)
                                             (assoc :source-slot-id source-id)) arguments)))]
    (is (= :blocked (:status entry)))
    (is (= source-id (:field-id proof)))
    (is (= (ids/fn-slot-id owner source-id) (:membership-id proof)))
    (is (= (ids/fn-id "fixture" :encode-body) (:target-fn-id proof)))
    (is (= [:text :text] ((juxt :source-type :target-type) proof)))
    (is (= [[owner []] [source-id]] (:source-address proof)))
    (is (= [[owner [edge]] [target-id]] (:target-address proof)))
    (is (= proof (forward/verify-forwarded-field prepared entry :data)))
    (is (= :unproved-field-origin (rejection prepared tampered)))))


(deftest legacy-dispatch-distinguishes-generic-value-uses-from-callable-and-identity-uses
  (let [check (fn [expected]
                (let [forms (mapv #(assoc % :namespace "fixture")
                                  [{:name :consumer :args {:input {:type expected}} :return-type :any}
                                   {:name :producer :args {:data :text} :return-type :text}
                                   {:name :owner :parent :consumer :args {:input :producer :data {:type :text}}}])
                      prepared (parse/prepare-module forms {} {})
                      entry (last (:definitions (source/plan-module forms prepared)))
                      owner (:identity entry)
                      checked (::forward/value-edge? (readers/prepare prepared owner {(ids/fn-id "fixture" :producer) {:return :text}}))
                      row (first (filter #(= :binding (:kind %)) (get-in entry [:legacy :records])))]
                  [(checked owner row)
                   (checked owner (assoc row :ref-fn-id (random-uuid)))]))]
    (is (= [true false] (check 'a)))
    (is (= [false false] (check :fn-ref)))
    (is (= [false false] (check [:fn {} :text])))))


(deftest forwarding-does-not-conflate-two-uses-of-one-function
  (let [[prepared entry] (fixture {:left :encode-body :right :encode-body :data {:type :text}})]
    (is (= :nonunique-reference-use (rejection prepared entry)))))


(deftest forwarding-through-a-list-uses-the-stored-operand-position
  (let [forms [{:name :collect :args {:items {:type '[:list a]}} :return-type '[:list a]}
               {:name :encode :args {:data :text} :return-type :text}
               {:name :response :parent :collect :args {:items ["prefix" :encode] :data {:type :text}}}]
        declarations (mapv #(assoc % :namespace "fixture") forms)
        prepared (parse/prepare-module declarations {} {})
        entry (last (:definitions (source/plan-module declarations prepared)))
        owner (ids/fn-id "fixture" :response)
        target (ids/fn-id "fixture" :encode)
        field (ids/slot-id target :data)
        {:keys [binding-id builder-id]} (list-builder/origin owner (ids/slot-id (ids/fn-id "fixture" :collect) :items))
        operand (ids/binding-list-item-id binding-id 1)
        proof (forward/verify-forwarded-field prepared entry :data)
        tampered (update-in entry [:legacy :records]
                            (fn [records] (mapv #(cond-> % (= operand (:id %)) (assoc :position 0)) records)))]
    (is (= builder-id (:target-fn-id proof)))
    (is (= [[owner [binding-id operand]] [field]] (:target-address proof)))
    (is (= {:binding-id binding-id :position 1 :item-id operand :ref-fn-id target} (:list-operand proof)))
    (is (= :unproved-reference-binding (rejection prepared tampered)))))


(deftest multiple-forwarded-fields-must-be-lowered-as-a-complete-group
  (let [definitions (mapv #(assoc % :namespace "fixture")
                          [{:name :combine :args {:left :any :right :any} :return-type :any}
                           {:name :encode :args {:data :text :other {:type [:list :text]}} :return-type :text}
                           {:name :middle :parent :combine :args {:left :encode}}
                           {:name :response :parent :combine
                            :args {:left :middle :data {:type :text}
                                   :other {:type [:list :text] :description "Retain collection metadata"}}}])
        prepared (parse/prepare-module definitions {} {})
        entry (last (:definitions (source/plan-module definitions prepared)))
        {:keys [proofs entry] :as baseline} (source/forwarded-fields-baseline prepared entry [:data :other])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"proved together"
          (source/forwarded-field-baseline prepared (last (:definitions (source/plan-module definitions prepared))) :data)))
    (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
    (is (= [nil nil] (mapv :target-address proofs)))
    (is (= [:text [:list :text]] (mapv :source-type proofs)))
    (is (= {:type [:list :text] :description "Retain collection metadata"}
           (get-in baseline [:entry :proposed :input :other])))))


(defn- checked-snapshot
  [{:keys [owner-id target-fn-id field-id target-address]}]
  (let [[[_ [edge]] [target-id]] target-address
        parent (ids/fn-id "fixture" :combine)
        reference-slot (ids/slot-id parent :left)
        sibling-slot (ids/slot-id parent :right)
        declaring-id (ids/fn-id "fixture" :encode)
        local-type (random-uuid)
        parent-type (random-uuid)
        remote-type (random-uuid)
        declaring-type (random-uuid)
        primitive-ids (ids/primitive-fn-ids)
        text (:text primitive-ids)
        any-type (:any primitive-ids)]
    (context/build-context
      {:fns {owner-id {:id owner-id :input-type-fn-id local-type :parent-ids [parent]}
             parent {:id parent :input-type-fn-id parent-type}
             target-fn-id {:id target-fn-id :input-type-fn-id remote-type :parent-ids [declaring-id]}
             declaring-id {:id declaring-id :input-type-fn-id declaring-type}}
       :members {local-type [{:slot-id field-id :position 0}]
                 parent-type [{:slot-id reference-slot :position 0} {:slot-id sibling-slot :position 1}]
                 remote-type []
                 declaring-type [{:slot-id target-id :position 0}]}
       :fields {reference-slot {:id reference-slot :name "left" :required false :type-fn-id any-type}
                sibling-slot {:id sibling-slot :name "right" :required false :type-fn-id any-type}
                field-id {:id field-id :name "data" :required false :type-fn-id text}
                target-id {:id target-id :name "data" :required true :type-fn-id text}}
       :signatures {target-fn-id {:args {:data :text} :return :text :effects #{}}}
       :bindings {edge {:id edge :fn-id owner-id :scope-path [] :target-path [reference-slot]
                        :expression-kind "ref" :ref-fn-id target-fn-id}}})))


(defn- analyze
  [ctx owner]
  (analysis/analyze-effective-input ctx owner
                                    {:check-expression (fn [{:keys [substitution]}]
                                                         {:status :checked :substitution substitution})}))


(deftest exact-forwarded-origin-survives-checked-replay
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [owner-id projection-id source-address target-address] :as proof}
        (forward/verify-forwarded-field prepared entry :data)
        snapshot (checked-snapshot proof)
        {:keys [row source-names public-path]} (scope/verify-forwarded-projection snapshot proof analyze)
        replay (-> snapshot (assoc :snapshot-id (random-uuid)) (assoc-in [:bindings projection-id] row))
        result (analyze replay owner-id)]
    (is (= [:data] source-names))
    (is (= [:left :data] public-path))
    (is (= projection-id (:id row)))
    (is (= [source-address] (:required-addresses result)))
    (is (= source-address (get-in result [:public-input :bindings target-address :reference :source])))
    (is (thrown? clojure.lang.ExceptionInfo (scope/verify-forwarded-projection replay proof analyze)))))


(deftest deep-forwarding-uses-every-edge-and-refuses-a-repeated-leaf
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [owner-id target-fn-id root-use-id] :as proof} (forward/verify-forwarded-field prepared entry :data)
        snapshot (checked-snapshot proof)
        middle (ids/fn-id "fixture" :middle)
        parent (ids/fn-id "fixture" :combine)
        field (ids/slot-id parent :left)
        edge (ids/binding-id middle field)
        middle-input (random-uuid)
        snapshot (-> snapshot
                     (assoc-in [:fns middle] {:id middle :input-type-fn-id middle-input :parent-ids [parent]})
                     (assoc-in [:members middle-input] [])
                     (assoc-in [:signatures middle] {:args {} :return :text :effects #{}})
                     (assoc-in [:bindings root-use-id :ref-fn-id] middle)
                     (assoc-in [:bindings edge] {:id edge :fn-id middle :scope-path [] :target-path [field]
                                                 :expression-kind "ref" :ref-fn-id target-fn-id}))
        proof (assoc proof :target-fn-id middle :target-address nil)
        {:keys [row edges public-path] :as certificate} (scope/verify-forwarded-projection snapshot proof analyze)
        before (analyze snapshot owner-id)
        ctx {:scope [owner-id []] :contract (get-in before [:scopes [] :declared-contract])
             :declaration-records [{:kind :fn :id owner-id :parent-ids (:owner-parents certificate)}]
             :scoped-inputs {public-path certificate}}
        right (ids/slot-id parent :right)
        repeat-id (ids/binding-id middle right)
        repeated (assoc-in snapshot [:bindings repeat-id] {:id repeat-id :fn-id middle :scope-path [] :target-path [right]
                                                           :expression-kind "ref" :ref-fn-id target-fn-id})]
    (is (= [root-use-id edge] (:scope-path row) (mapv :binding-id edges)))
    (is (= [:left :left :data] public-path))
    (is (= row (get-in (scoped-source/parse-bindings ctx (get-in before [:public-input :bindings])
                                                     {public-path [:project [:data]]}) [public-path :row])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reference input occurrence"
          (scope/verify-forwarded-projection repeated proof analyze)))))


(deftest forwarding-multiple-readers-requires-the-complete-reader-set
  (let [[prepared entry] (fixture {:left :encode-body :right :encode-other :data {:type :text}}
                                  [{:name :encode-other :parent :encode}])
        [left right :as readers] (forward/verify-forwarded-uses prepared entry :data)
        snapshot (checked-snapshot left)
        left-id (:target-fn-id left)
        right-id (:target-fn-id right)
        right-use (:root-use-id right)
        owner (:owner-id left)
        slot (ids/slot-id (ids/fn-id "fixture" :combine) :right)
        snapshot (-> snapshot
                     (assoc-in [:fns right-id] (assoc (get-in snapshot [:fns left-id]) :id right-id))
                     (assoc-in [:signatures right-id] (get-in snapshot [:signatures left-id]))
                     (assoc-in [:bindings right-use] {:id right-use :fn-id owner :scope-path [] :target-path [slot]
                                                      :expression-kind "ref" :ref-fn-id right-id}))]
    (is (= #{[:left :data] [:right :data]}
           (set (map :public-path (scope/verify-forwarded-projections snapshot readers analyze)))))
    (is (thrown? clojure.lang.ExceptionInfo
          (scope/verify-forwarded-projections snapshot [left] analyze)))))


(deftest forwarding-cannot-discard-retained-field-read-checks
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [owner-id field-id target-field-id target-fn-id projection-id] :as proof}
        (forward/verify-forwarded-field prepared entry :data)
        snapshot (checked-snapshot proof)
        rich [:list :any]
        sequence-id (ids/primitive-fn-id :sequence)
        declaring (ids/fn-id "fixture" :encode)
        snapshot (-> snapshot
                     (assoc-in [:fields field-id :type-fn-id] sequence-id)
                     (assoc-in [:fields target-field-id :type-fn-id] sequence-id)
                     (assoc-in [:type-bodies (get-in snapshot [:fns owner-id :input-type-fn-id])] {:data rich})
                     (assoc-in [:type-bodies (get-in snapshot [:fns declaring :input-type-fn-id])] {:data rich})
                     (assoc-in [:signatures target-fn-id :args :data] rich)
                     (assoc :source-field-types {[field-id sequence-id] rich [target-field-id sequence-id] rich}))
        proof (assoc proof :source-type rich :target-type rich)
        source (:source-address proof)
        target (:target-address proof)
        with-checks (fn [ctx owner]
                      (let [graph (analyze ctx owner)
                            free (set (map :address (vals (get-in graph [:public-input :routes]))))]
                        (assoc graph :obligations
                               (mapv #(hash-map :address % :kind :value-type :trigger :field-read :expected-type rich)
                                     (filter free [source target])))))
        certificate (scope/verify-forwarded-projection snapshot proof with-checks)
        with-extra-check (fn [address type]
                           (fn [ctx owner]
                             (cond-> (with-checks ctx owner)
                               (get-in ctx [:bindings projection-id])
                               (update :obligations conj
                                       {:address address :kind :value-type :trigger :field-read :expected-type type}))))
        unchecked (fn [ctx owner]
                    (cond-> (with-checks ctx owner) (get-in ctx [:bindings projection-id]) (assoc :obligations [])))]
    (is (= [{:address [[owner-id []] [field-id]] :kind :value-type :trigger :field-read :expected-type rich}]
           (mapv #(select-keys % [:address :kind :trigger :expected-type]) (:obligations certificate))))
    (is (= #{rich :any}
           (into #{} (map :expected-type)
                 (:obligations (scope/verify-forwarded-projection snapshot proof (with-extra-check target :any))))))
    (doseq [[address type] [[target :text] [source :any]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reference input occurrence"
            (scope/verify-forwarded-projection snapshot proof (with-extra-check address type)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reference input occurrence"
          (scope/verify-forwarded-projection snapshot proof unchecked)))))


(deftest forwarded-path-can-be-emitted-and-read-only-with-its-exact-certificate
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [owner-id] :as proof} (forward/verify-forwarded-field prepared entry :data)
        snapshot (checked-snapshot proof)
        {:keys [public-path row] :as certificate} (scope/verify-forwarded-projection snapshot proof analyze)
        graph (analyze snapshot owner-id)
        local (get-in graph [:public-input :bindings])
        reader-context {:scope [owner-id []] :contract (get-in graph [:scopes [] :declared-contract])
                        :declaration-records [{:kind :fn :id owner-id :parent-ids (:owner-parents certificate)}]
                        :scoped-inputs {public-path certificate}}
        bind {public-path [:project [:data]]}
        parsed (scoped-source/parse-bindings reader-context local bind)]
    (is (= row (get-in parsed [public-path :row])))
    (is (= bind (scoped-source/emit-bindings {} parsed {})))
    (is (thrown? clojure.lang.ExceptionInfo
          (scoped-source/parse-bindings reader-context local {public-path [:project [:left]]})))
    (is (thrown? clojure.lang.ExceptionInfo
          (scoped-source/parse-bindings (dissoc reader-context :scoped-inputs) local bind)))))


(deftest verified-owner-keeps-its-authored-input-and-serializes-the-forwarding
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [field-id projection-id] :as proof} (forward/verify-forwarded-field prepared entry :data)
        baseline (:entry (source/forwarded-field-baseline prepared entry :data))
        certificate (scope/verify-forwarded-projection (checked-snapshot proof) proof analyze)
        migrated (source/rewrite-forwarded-field prepared entry :data certificate)]
    (is (= :authored-source-equivalent (:verification baseline)) (:blockers baseline))
    (is (= {:left [:ref :fixture/encode-body]} (get-in baseline [:proposed :bind])))
    (is (= :authored-source-equivalent (:verification migrated)) (:blockers migrated))
    (is (= {:data {:type :text}} (get-in migrated [:proposed :input])))
    (is (= [:project [:data]] (get-in migrated [:proposed :bind [:left :data]])))
    (is (= field-id (some #(when (= :data (:name %)) (:id %)) (get-in migrated [:v2 :contract :fields]))))
    (is (= #{projection-id} (into #{} (keep #(when (= "projection" (:expression-kind %)) (:id %)))
                                  (get-in migrated [:v2 :bindings]))))
    (is (thrown? clojure.lang.ExceptionInfo
          (source/rewrite-forwarded-field prepared entry :data (assoc-in certificate [:row :id] (random-uuid)))))))


(deftest derived-source-reuses-only-its-exact-verified-parent-link
  (let [child {:namespace "fixture" :name :json-result :parent :response :args {:data "ready"}}
        [prepared entry] (fixture {:left :encode-body :data {:type :text}} [child])
        {:keys [field-id] :as proof} (forward/verify-forwarded-field prepared entry :data)
        certificate (scope/verify-forwarded-projection (checked-snapshot proof) proof analyze)
        parent (source/rewrite-forwarded-field prepared entry :data certificate)
        certified (source/with-forwarded-parent prepared parent)
        child-plan (fn [context] (first (:definitions (source/verify-module (source/plan-module [child] context) context))))
        unproved (child-plan prepared)
        verified (child-plan certified)]
    (is (= [:inherited-projection-required] (mapv :code (:blockers unproved))))
    (is (= :authored-source-equivalent (:verification verified)) (:blockers verified))
    (is (= [:value "ready"] (get-in verified [:proposed :bind :data])))
    (is (= [field-id] (get-in verified [:v2 :bindings 0 :target-path])))
    (is (thrown? clojure.lang.ExceptionInfo
          (source/with-forwarded-parent prepared (assoc parent :verification nil))))))
