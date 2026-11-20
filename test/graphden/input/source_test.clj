(ns ^:serial graphden.input.source-test
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.input.migration :as migration]
    [graphden.input.source :as source]
    [graphden.input.syntax :as syntax]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def base
  {:name :sink :namespace "demo" :args {:x :any :items :sequence}
   :return-type :any})


(def producer
  {:name :producer :namespace "demo" :args {} :return-type :any})


(defn- prepared
  [definitions]
  (parse/prepare-module definitions {} {}))


(defn- plan
  [definitions]
  (source/plan-module definitions (prepared definitions)))


(defn- result
  [report name]
  (first (filter #(= name (get-in % [:source :name])) (:definitions report))))


(defn- codes
  [definition]
  (set (map :code (:blockers definition))))


(deftest base-declarations-preserve-rich-source-and-documentation
  (let [declaration {:name :format :namespace "demo"
                     :args {:pretty {:type :bool :required false :value false}
                            :items [:list 'a]}
                     :return-type :text :source-file "demo/fns.edn" :source-line 12}
        planned (result (plan [declaration]) :format)
        rows (get-in planned [:legacy :records])]
    (testing "documentation is retained without creating runtime defaults"
      (is (= (-> declaration (dissoc :args) (assoc :input (:args declaration)))
             (:proposed planned)))
      (is (= (:args declaration) (get-in planned [:legacy :rich-input])))
      (is (empty? (filter #(= :binding (:kind %)) rows))))
    (testing "optional remains free and structural source survives primitive rows"
      (is (false? (:required (first (filter #(= "pretty" (:name %)) rows)))))
      (is (= [:list 'a] (get-in planned [:proposed :input :items]))))))


(deftest type-definitions-remain-unchanged
  (let [declaration {:name :response :namespace "demo"
                     :type {:status :int :body {:type :text :required false}}}
        planned (result (plan [declaration]) :response)]
    (is (= :supported (:status planned)))
    (is (= declaration (:proposed planned)))))


(deftest legacy-keywords-resolve-before-new-explicit-ref-emission
  (let [cases [[:known :producer [:ref :demo/producer]]
               [:qualified :demo/producer [:ref :demo/producer]]
               [:unknown :not-a-function [:value :not-a-function]]
               [:literal {:value :producer} [:value :producer]]]
        definitions (into [base producer]
                          (map (fn [[name value]]
                                 {:name name :namespace "demo" :parent :sink
                                  :args {:x value}})) cases)
        report (plan definitions)]
    (doseq [[name _ expected] cases]
      (let [planned (result report name)]
        (is (= :supported (:status planned)))
        (is (= expected (get-in planned [:proposed :bind :x])))))))


(deftest same-bare-name-prefers-source-namespace
  (let [other (assoc producer :namespace "other")
        definitions [base producer other
                     {:name :local :namespace "demo" :parent :sink :args {:x :producer}}
                     {:name :remote :namespace "demo" :parent :sink
                      :args {:x :other/producer}}]
        report (plan definitions)]
    (is (= [:ref :demo/producer] (get-in (result report :local) [:proposed :bind :x])))
    (is (= [:ref :other/producer] (get-in (result report :remote) [:proposed :bind :x])))))


(deftest no-local-choice-is-an-explicit-preparation-failure
  (let [originals [base producer (assoc producer :namespace "other")
                   {:name :ambiguous :namespace "third" :parent :demo/sink
                    :args {:x :demo/producer}}]
        context (prepared originals)
        ambiguous (assoc (last originals) :args {:x :producer})
        report (source/plan-module [base ambiguous] context)
        planned (result report :ambiguous)]
    (is (= :supported (:status (result report :sink))))
    (is (= :packages/ambiguous-ref (get-in planned [:blockers 0 :cause-type])))
    (is (not (contains? planned :proposed)))))


(deftest literal-payloads-stay-whole-in-new-expression-parser
  (doseq [value [nil false {} [] {:ref :producer :value [:ref :producer]}
                 {:type :text :fields {}} [:ref :producer]]]
    (let [definition {:name :literal :namespace "demo" :parent :sink
                      :args {:x {:value value}}}
          planned (result (plan [base producer definition]) :literal)
          form (get-in planned [:proposed :bind])
          slot-id (ids/slot-id (ids/fn-id "demo" :sink) :x)
          binding-id (ids/binding-id (ids/fn-id "demo" :literal) slot-id)
          expression (syntax/parse-expression
                       {:type {:x :any} :fields [{:id slot-id :name :x :type :any}]} :scope form
                       {:name->id {} :binding-id (fn [_ _] binding-id)})]
      (is (= :supported (:status planned)))
      (is (= {:kind :value :value value :binding-id binding-id}
             (get-in expression [:fields slot-id]))))))


(deftest scalar-and-sequence-vectors-use-resolved-slot-contract
  (let [definitions [base
                     {:name :scalar :namespace "demo" :parent :sink :args {:x [1 2]}}
                     {:name :list :namespace "demo" :parent :sink :args {:items [1 2]}}
                     {:name :renamer :namespace "demo" :parent :sink
                      :args {:items {:as :steps}}}
                     {:name :renamed-list :namespace "demo" :parent :renamer
                      :args {:steps [3]}}]
        report (plan definitions)]
    (is (= [:value [1 2]] (get-in (result report :scalar) [:proposed :bind :x])))
    (is (= [:list-builder {:items [[:value 1] [:value 2]] :tail :open}]
           (get-in (result report :list) [:proposed :bind :items])))
    (is (contains? (codes (result report :renamed-list)) :list-builder-required))
    (is (not (contains? (result report :renamed-list) :proposed)))))


(deftest unsupported-cases-aggregate-with-safe-blocker-summaries
  (let [definitions [base producer
                     {:name :append :namespace "demo" :parent :sink
                      :args {:items {:append [:producer] :closed true}}}
                     {:name :rename :namespace "demo" :parent :sink
                      :args {:x {:as :renamed :type :text}}}
                     {:name :secret :namespace "demo" :parent :sink
                      :args {:x {:resolver :producer :value "private-payload"}}}
                     {:name :anonymous :namespace "demo" :parent :sink
                      :args {:x {:parent :producer :args {}}}}]
        report (plan definitions)]
    (is (= {:supported 4 :blocked 2} (:counts report)))
    (is (= [:list-builder {:items [[:ref :producer]] :tail :closed}]
           (get-in (result report :append) [:proposed :bind :items])))
    (is (contains? (codes (result report :rename)) :rename-projection-required))
    (is (contains? (codes (result report :secret)) :resolver-binding-required))
    (is (= [:ref {:parent :producer :bind {}}] (get-in (result report :anonymous) [:proposed :bind :x])))
    (is (not (str/includes? (pr-str (:blockers report)) "private-payload")))))


(deftest identity-and-context-mismatches-never-pick-an-arbitrary-match
  (let [context (prepared [base])
        cases [[context [base base] :duplicate-source-definition]
               [(assoc context :fn-defs []) [base] :missing-prepared-definition]
               [(update context :fn-defs into (:fn-defs context)) [base]
                :duplicate-prepared-definition]
               [(assoc-in context [:fn-defs 0 :fn-id] (random-uuid)) [base]
                :prepared-identity-mismatch]
               [(assoc-in context [:fn-defs 0 :description] "changed") [base]
                :prepared-source-mismatch]]]
    (doseq [[ctx definitions expected] cases]
      (let [planned (first (:definitions (source/plan-module definitions ctx)))]
        (is (contains? (codes planned) expected))
        (is (not (contains? planned :proposed)))))))


(deftest ancestor-occupancy-is-not-reinterpreted-as-a-default
  (let [definitions [base
                     {:name :preset :namespace "demo" :parent :sink :args {:x nil}}
                     {:name :override :namespace "demo" :parent :preset :args {:x false}}]
        report (plan definitions)]
    (is (= [:value nil] (get-in (result report :preset) [:proposed :bind :x])))
    (is (contains? (codes (result report :override)) :inherited-occupancy-required))))


(deftest ref-input-bindings-have-an-explicit-scope-blocker
  (let [reader {:name :reader :namespace "demo" :args {:deep :any} :return-type :any}
        composed {:name :outer :namespace "demo" :parent :sink
                  :args {:x :reader :deep "captured"}}
        planned (result (plan [base reader composed]) :outer)
        capture (second (get-in planned [:legacy :arguments]))]
    (is (= #{:ref-scope-required} (codes planned)))
    (is (= {:owner :reader :field :deep :type :any} (:target capture)))
    (is (= (ids/slot-id (ids/fn-id "demo" :reader) :deep)
           (get-in capture [:origin :slot-id])))))


(deftest prior-module-shapes-are-used-for-inherited-inputs
  (let [dependency (prepared [base])
        definition {:name :child :namespace "client" :parent :demo/sink :args {:x false}}
        context (parse/prepare-module [definition] (:name->id dependency) (:defs-by-name dependency))
        planned (result (source/plan-module [definition] context) :child)]
    (is (= :supported (:status planned)))
    (is (= [:value false] (get-in planned [:proposed :bind :x])))
    (is (= (ids/slot-id (ids/fn-id "demo" :sink) :x)
           (get-in planned [:legacy :arguments 0 :origin :slot-id])))))


(deftest individual-parse-failures-do-not-hide-other-definitions
  (let [definitions [base
                     {:name :bad :namespace "demo" :parent :sink :args {:missing 1}}
                     {:name :good :namespace "demo" :parent :sink :args {:x 1}}]
        report (plan definitions)]
    (is (= {:supported 2 :blocked 1} (:counts report)))
    (is (= :packages/orphan-slot-binding (get-in (result report :bad) [:blockers 0 :cause-type])))
    (is (= [:value 1] (get-in (result report :good) [:proposed :bind :x])))))


(deftest literal-maps-and-legacy-value-wrapper-are-distinct
  (let [definitions [base
                     {:name :map :namespace "demo" :parent :sink :args {:x {:data false}}}
                     {:name :wrapper :namespace "demo" :parent :sink :args {:x {:value false}}}]
        report (plan definitions)]
    (is (= [:value {:data false}] (get-in (result report :map) [:proposed :bind :x])))
    (is (= [:value false] (get-in (result report :wrapper) [:proposed :bind :x])))))


(deftest duplicate-parent-names-resolve-to-the-own-namespace
  (let [definitions [base (assoc base :namespace "other")
                     {:name :child :namespace "demo" :parent :sink :args {:x nil}}]
        planned (result (plan definitions) :child)]
    (is (= :supported (:status planned)))
    (is (= (ids/slot-id (ids/fn-id "demo" :sink) :x)
           (get-in planned [:legacy :arguments 0 :origin :slot-id])))))


(deftest declared-new-fields-and-binding-metadata-are-not-dropped
  (let [definitions [base
                     {:name :new-field :namespace "demo" :parent :sink
                      :args {:new {:type :text :required false}}}
                     {:name :annotated :namespace "demo" :parent :sink
                      :args {:x {:value nil :description "annotation" :terminal false}}}]
        report (plan definitions)]
    (is (= {:new {:type :text :required false}} (get-in (result report :new-field) [:proposed :input])))
    (is (not (contains? (:proposed (result report :new-field)) :bind)))
    (is (contains? (codes (result report :annotated)) :binding-metadata-required))
    (is (not (contains? (result report :annotated) :proposed)))))


(deftest secret-path-resolution-never-becomes-a-literal-path
  (let [resolver {:name :vault-get :namespace "web.vault" :args {:value :text}
                  :return-type :text}
        definitions [base resolver
                     {:name :secret :namespace "demo" :parent :sink
                      :args {:x {:secret-path "private/vault/path"}}}
                     {:name :data :namespace "demo" :parent :sink
                      :args {:x {:value {:secret-path "ordinary data"}}}}]
        report (plan definitions)
        secret (result report :secret)]
    (is (contains? (codes secret) :secret-path-binding-required))
    (is (not (contains? secret :proposed)))
    (is (= (ids/fn-id "web.vault" :vault-get)
           (get-in secret [:legacy :arguments 0 :policy :resolver-fn-id])))
    (is (not (str/includes? (pr-str (:blockers report)) "private/vault/path")))
    (is (= [:value {:secret-path "ordinary data"}]
           (get-in (result report :data) [:proposed :bind :x])))))


(deftest unhandled-emitted-binding-columns-block-the-proposal
  ;; Exercise schema evolution at the public legacy-parser boundary. This
  ;; namespace is serial because with-redefs changes that parser's root var.
  (let [definitions [base {:name :child :namespace "demo" :parent :sink :args {:x "stored"}}]
        context (prepared definitions)
        emit parse/parse-prepared-module]
    (doseq [[column value expected] [[:override-kind :secret-path :legacy-override-kind-required]
                                     [:future-policy nil :unsupported-binding-columns]]]
      (with-redefs [parse/parse-prepared-module
                    (fn [module]
                      (mapv #(cond-> % (= :binding (:kind %)) (assoc column value)) (emit module)))]
        (let [planned (result (source/plan-module definitions context) :child)]
          (is (contains? (codes planned) expected))
          (is (not (contains? planned :proposed)))
          (is (contains? (get-in planned [:legacy :arguments 0 :policy]) column)))))))


(defn- verified
  [definitions]
  (let [context (prepared definitions)]
    (source/verify-module (source/plan-module definitions context) context)))


(deftest verified-declarations-and-bindings-agree-with-storage-migration
  (let [options {:type {:flag {:type :bool :required false}} :required false :value {:flag false}
                 :description "Default in the implementation"}
        parent (assoc base :args {:x :any :options options :items [:list 'a]})
        child {:name :child :namespace "demo" :parent :sink :args {:x {:value nil}}}
        report (verified [parent child])
        parent-plan (result report :sink)
        child-plan (result report :child)
        owner (:identity child-plan)
        input-id (migration/derived-id :input-type owner)
        x-id (ids/slot-id (:identity parent-plan) :x)
        binding-id (ids/binding-id owner x-id)
        v2 (:v2 child-plan)
        db-mapping (->> (:definitions report)
                        (mapcat #(get-in % [:legacy :records]))
                        (group-by :kind)
                        migration/plan
                        :mapping)
        mapping (get-in v2 [:origin-ledger :mapping])
        contract (:contract v2)
        option-field (second (:fields contract))]
    (is (= {:supported 2 :blocked 0} (:counts report)))
    (is (= :authored-source-equivalent (:verification child-plan)))
    (is (= input-id (:input-type-id v2) (:type-id contract)))
    (is (= {:x :any :options {:flag :bool} :items [:list 'a]} (:type contract)))
    (is (= {:input-types (select-keys (:input-types db-mapping) [input-id])
            :memberships (select-keys (:memberships db-mapping) (keys (:memberships mapping)))
            :binding-origins (:binding-origins db-mapping)}
           mapping))
    (is (= [x-id (ids/slot-id (:identity parent-plan) :options)
            (ids/slot-id (:identity parent-plan) :items)]
           (mapv :id (:fields contract))))
    (is (= options (:declaration option-field)))
    (is (false? (:required option-field)))
    (is (= [{:name :flag :type :bool :required false}]
           (mapv #(select-keys % [:name :type :required]) (:fields option-field))))
    (is (= [[:list 'a]] (mapv :type (drop 2 (:fields contract)))))
    (is (= [{:kind :input-binding :id binding-id :fn-id owner
             :scope-path [] :target-path [x-id] :expression-kind "value"
             :value-present true :value nil}] (:bindings v2)))
    (is (empty? (get-in parent-plan [:v2 :bindings])))))


(deftest composed-declarations-preserve-free-fields-beside-inherited-bindings
  (let [field {:type {:city :text} :description "Delivery address" :required false}
        child {:name :checkout :namespace "demo" :parent :sink
               :args {:address field :x {:value nil} :count {:type :int}}}
        context (prepared [base child])
        planned (source/plan-module [child] context)
        entry (first (:definitions (source/verify-module planned context)))
        owner (:identity entry)
        free-field (first (get-in entry [:v2 :contract :fields]))]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= {:address field :count {:type :int}} (get-in entry [:proposed :input])))
    (is (= {:x [:value nil]} (get-in entry [:proposed :bind])))
    (is (= [(:address (:args child)) (ids/slot-id owner :address) false]
           [(:declaration free-field) (:id free-field) (:required free-field)]))
    (is (= [:address :count :x :items] (mapv :name (get-in entry [:v2 :contract :fields]))))
    (is (= [[(ids/slot-id (ids/fn-id "demo" :sink) :x)]]
           (mapv :target-path (get-in entry [:v2 :bindings]))))
    (doseq [[edited code] [[{:type :int} :legacy-v2-meaning-mismatch]
                           [{:value 12} :unsupported-input-declaration]]]
      (is (= code (get-in (source/verify-module
                            (assoc-in planned [:definitions 0 :proposed :input :address] edited) context)
                          [:blockers 0 :code]))))))


(deftest composed-fields-with-unhandled-policy-or-inherited-projection-remain-blocked
  (let [report (verified [base
                          {:name :unsupported :namespace "demo" :parent :sink
                           :args {:new {:type :int :unknown-policy true}}}
                          {:name :shadow :namespace "demo" :parent :sink :args {:x {:type :text}}}])]
    (is (contains? (codes (result report :unsupported)) :own-field-declaration-required))
    (is (contains? (codes (result report :shadow)) :field-projection-required))
    (is (every? #(nil? (:proposed (result report %))) [:unsupported :shadow]))))


(deftest named-type-assertions-follow-the-original-binding-identity
  (let [child {:name :typed :namespace "demo" :parent :sink
               :args {:x {:ref :producer :type :never} :items {:value nil :type :null}}}
        defs [base producer child]
        context (prepared defs)
        planned (source/plan-module defs context)
        entry (result (source/verify-module planned context) :typed)
        configs (get-in entry [:v2 :configurations])
        x (ids/slot-id (ids/fn-id "demo" :sink) :x)
        expected-id (ids/binding-id (:identity entry) x)
        binding (some #(when (= expected-id (:id %)) %) (get-in entry [:v2 :bindings]))]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= {[:x] {:type :never} [:items] {:type :null}} (get-in entry [:proposed :configure])))
    (is (= {:target-path [x] :type :never :type-override-fn-id (ids/primitive-fn-id :any)}
           (get configs [:x])))
    (is (= (select-keys binding [:target-path :type-override-fn-id]) (dissoc (get configs [:x]) :type)))
    (is (= (ids/fn-id "demo" :producer) (:ref-fn-id binding)))
    (doseq [change [#(assoc-in % [:configure [:x] :type] :any) #(dissoc % :configure)]]
      (let [altered (update-in planned [:definitions 2 :proposed] change)]
        (is (contains? (codes (result (source/verify-module altered context) :typed)) :legacy-v2-meaning-mismatch))))))


(deftest collection-assertions-preserve-rich-types-even-when-storage-ids-match
  (let [child {:name :shape :namespace "demo" :parent :sink
               :args {:x {:ref :producer :type [:list :demo/producer]}}}
        defs [base producer child]
        context (prepared defs)
        planned (source/plan-module defs context)
        entry (result (source/verify-module planned context) :shape)
        altered (assoc-in planned [:definitions 2 :proposed :configure [:x] :type] [:list :int])]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= {:type [:list :producer] :type-override-fn-id (ids/primitive-fn-id :sequence)}
           (select-keys (get-in entry [:v2 :configurations [:x]]) [:type :type-override-fn-id])))
    (is (contains? (codes (result (source/verify-module altered context) :shape))
                   :legacy-v2-meaning-mismatch))))


(deftest unresolved-and-unsupported-assertions-remain-explicit-blockers
  (let [report (verified [base producer
                          {:name :unknown :namespace "demo" :parent :sink
                           :args {:x {:ref :producer :type :unknown-type}}}
                          {:name :shape :namespace "demo" :parent :sink
                           :args {:x {:ref :producer :type [:fn {} :text]}}}])]
    (is (contains? (codes (result report :unknown)) :unresolved-binding-type))
    (is (contains? (codes (result report :shape)) :binding-annotations-required))))


(deftest verification-rejects-drift-in-rewrites
  (let [parent (assoc base :args {:x {:type :any :required false :value false}})
        child {:name :child :namespace "demo" :parent :sink :args {:x {:value [1]}}}
        definitions [parent producer child]
        context (prepared definitions)
        planned (source/plan-module definitions context)]
    (doseq [[entry name edit]
            [[0 :sink #(assoc-in % [:input :x :value] nil)]
             [0 :sink #(assoc-in % [:input :x :required] true)]
             [0 :sink #(assoc % :effects #{:io})]
             [2 :child #(dissoc % :bind)]
             [2 :child #(assoc-in % [:bind :x] [:value '(1)])]]]
      (let [changed (update-in planned [:definitions entry :proposed] edit)
            checked (result (source/verify-module changed context) name)]
        (is (= #{:legacy-v2-meaning-mismatch} (codes checked)))
        (is (= :blocked (:status checked)))
        (is (not (contains? checked :proposed)))))))


(deftest source-verification-retains-edn-number-representations
  (doseq [[original replacement] [["1" "1N"] ["0.0" "-0.0"] ["1M" "1.00M"]]]
    (let [child {:name :child :namespace "demo" :parent :sink
                 :args {:x {:value [(edn/read-string original)]}}}
          definitions [base child]
          context (prepared definitions)
          planned (source/plan-module definitions context)
          edited (assoc-in planned [:definitions 1 :proposed :bind :x 1 0] (edn/read-string replacement))
          unchanged (result (source/verify-module planned context) :child)
          changed (result (source/verify-module edited context) :child)]
      (is (= :authored-source-equivalent (:verification unchanged)))
      (is (= #{:legacy-v2-meaning-mismatch} (codes changed))))))


(deftest source-verification-ignores-map-and-set-order
  (let [value (array-map :first (sorted-set 1 2 3) :second false)
        child {:name :child :namespace "demo" :parent :sink :args {:x {:value value}}}
        definitions [base child]
        context (prepared definitions)
        planned (source/plan-module definitions context)
        reordered (array-map :second false :first (sorted-set-by #(compare %2 %1) 1 2 3))
        edited (assoc-in planned [:definitions 1 :proposed :bind :x 1] reordered)
        checked (result (source/verify-module edited context) :child)]
    (is (= :authored-source-equivalent (:verification checked)))))


(deftest verified-reference-survives-edn-wire-qualification
  (let [root-producer (assoc producer :namespace nil)
        child {:name :child :namespace "demo" :parent :sink
               :args {:x {:ref (keyword "" "producer")}}}
        report (verified [base producer root-producer child])
        planned (result report :child)]
    (is (= {:supported 4 :blocked 0} (:counts report)))
    (is (= (ids/fn-id nil :producer)
           (get-in planned [:v2 :bindings 0 :ref-fn-id])))
    (is (= [:ref (keyword "" "producer")] (get-in planned [:v2 :source :bind :x])))))


(deftest verification-cannot-introduce-a-different-known-reference
  (let [other (assoc producer :namespace "other")
        child {:name :child :namespace "demo" :parent :sink
               :args {:x {:ref :producer}}}
        definitions [base producer other child]
        context (prepared definitions)
        planned (source/plan-module definitions context)
        tampered (assoc-in planned [:definitions 3 :proposed :bind :x] [:ref :other/producer])
        checked (result (source/verify-module tampered context) :child)]
    (is (= #{:unresolved-ref} (codes checked)))
    (is (= :blocked (:status checked)))))


(deftest inherited-diamonds-deduplicate-fields-and-distinct-name-collisions-block
  (let [left {:name :left :namespace "demo" :parent :sink}
        right {:name :right :namespace "demo" :parent :sink}
        diamond {:name :diamond :namespace "demo" :parents [:left :right] :args {:x false}}
        different (assoc base :name :different :args {:x :bool})
        conflict {:name :conflict :namespace "demo" :parents [:left :different]}
        report (verified [base left right diamond different conflict])]
    (is (= [:x :items] (mapv :name (get-in (result report :diamond) [:v2 :contract :fields]))))
    (is (false? (get-in (result report :diamond) [:v2 :bindings 0 :value])))
    (is (= #{:ambiguous-inherited-input} (codes (result report :conflict))))))


(deftest indirect-parent-cycles-do-not-verify
  (let [first-definition {:name :first :namespace "demo" :parent :second}
        second-definition {:name :second :namespace "demo" :parent :first}
        report (verified [first-definition second-definition])]
    (is (= {:supported 0 :blocked 2} (:counts report)))
    (is (= #{:cyclic-parent-input} (set (map :code (:blockers report)))))))


(deftest direct-inline-ref-verifies-use-site-and-child-declaration
  (let [host (assoc base :args {:x :any :y :any})
        source {:name :child :namespace "demo" :parent :sink
                :args {:x {:parent :demo/producer} :y {:parent :producer}}}
        other (assoc source :name :sibling)
        context (prepared [host producer source other])
        planned (source/plan-module [source other] context)
        report (source/verify-module planned context)
        legacy (into {} (map (juxt :name identity)) (:fn-defs context))
        child-ids (fn [entry]
                    (mapv :ref-fn-id (get-in entry [:v2 :bindings])))]
    (is (= {:supported 2 :blocked 0} (:counts report)))
    (is (= 4 (count (set (mapcat child-ids (:definitions report))))))
    (doseq [entry (:definitions report)
            {:keys [target-path ref-fn-id]} (get-in entry [:v2 :bindings])]
      (let [owner (get-in entry [:source :name])
            field (if (= (first target-path) (ids/slot-id (ids/fn-id "demo" :sink) :x)) :x :y)
            generated (get-in legacy [owner :args field])
            inline (get-in entry [:v2 :inline-definitions target-path])]
        (is (= (:fn-id (legacy generated)) ref-fn-id (:fn-id inline)))
        (is (= (:parent (get-in entry [:source :args field])) (get-in inline [:inline-body :parent])))
        (is (= [(ids/fn-id "demo" :producer)]
               (:parent-ids (first (filter #(= ref-fn-id (:id %)) (:declaration-records inline))))))))
    (doseq [edited [[:ref :producer]
                    [:ref (get-in legacy [:child :args :x])]
                    [:ref {:parent :sink}]
                    [:ref {:parent :producer :args {}}]]]
      (let [failed (source/verify-module (assoc-in planned [:definitions 0 :proposed :bind :x] edited) context)]
        (is (= :blocked (:status (result failed :child))))
        (is (= :supported (:status (result failed :sibling))))))
    (let [id (first (child-ids (result report :child)))
          corrupted (update context :fn-defs (fn [defs]
                                               (mapv #(if (= id (:fn-id %)) (assoc % :description "drift") %) defs)))]
      (is (contains? (codes (result (source/verify-module planned corrupted) :child))
                     :inline-declaration-mismatch)))))


(deftest unsupported-inline-shapes-stay-at-their-source-field
  (doseq [body [{:parent :producer :tag :unsupported}
                [{:parent :producer}] {:append [{:parent :producer}]}]]
    (let [d {:name :child :namespace "demo" :parent :sink :args {:items body}}
          entry (result (plan [base producer d]) :child)]
      (is (= [{:code :inline-identity-or-source-mapping-required :definition ["demo" :child]
               :source-path [:args :items]}] (:blockers entry)))
      (is (nil? (:proposed entry))))))


(deftest inline-parent-normalization-honors-namespace-ambiguity
  (let [local {:name :child :namespace "demo" :parent :demo/sink :args {:x {:parent :producer}}}
        remote (assoc local :namespace "other")
        sources [local remote]
        context (prepared (into [base producer (assoc producer :namespace "other")] sources))
        verified-report (source/verify-module (source/plan-module sources context) context)
        inlines (mapv #(first (vals (get-in % [:v2 :inline-definitions]))) (:definitions verified-report))]
    (is (= {:supported 2 :blocked 0} (:counts verified-report)))
    (is (apply distinct? (map :fn-id inlines)))
    (doseq [[s child] (map vector sources inlines)]
      (is (= (keyword (:namespace s) "producer") (get-in child [:source :parent])))
      (is (= {:parent :producer} (:inline-body child))))))


(deftest inline-one-field-preserves-body-spelling-and-binding-origins
  (let [cases [[:literal false false]
               [:wrapped {:value false} [:value false]]
               [:nil nil nil]
               [:wrapped-nil {:value nil} [:value nil]]
               [:decimal {:value 1.00M} [:value 1.00M]]
               [:enum :unknown :unknown]
               [:known :demo/producer [:ref :demo/producer]]
               [:literal-name {:value :producer} [:value :producer]]
               [:dictionary {:value {:parent :producer :value [:ref :producer]}}
                [:value {:parent :producer :value [:ref :producer]}]]]]
    (doseq [[label raw expected] cases]
      (let [body {:parent :demo/sink :args {:x raw}}
            source {:namespace "demo" :name :host :parent :sink :args {:x body}}
            context (prepared [base producer source])
            authored (source/plan-module [source] context)
            report (source/verify-module authored context)
            entry (first (:definitions report))
            inline (first (vals (get-in entry [:v2 :inline-definitions])))
            [old-host] (filter #(= :host (:name %)) (:fn-defs context))
            [old-child] (filter #(= (get-in old-host [:args :x]) (:name %)) (:fn-defs context))
            [old-row] (filter #(= :binding (:kind %)) (parse/parse-prepared-module (assoc context :fn-defs [old-child])))
            [new-row] (:bindings inline)]
        (is (= :authored-source-equivalent (:verification entry)) label)
        (is (= [:ref {:parent :demo/sink :bind {:x expected}}] (get-in entry [:proposed :bind :x])) label)
        (is (= (:fn-id old-child) (:fn-id inline) (get-in entry [:v2 :bindings 0 :ref-fn-id])) label)
        (is (= (:id old-row) (:id new-row)) label)
        (is (= (:slot-id old-row) (first (:target-path new-row))) label)
        (if (= label :known)
          (is (= (ids/fn-id "demo" :producer) (:ref-fn-id old-row) (:ref-fn-id new-row)))
          (is (migration/same-edn? (:value old-row) (:value new-row)) label))))))


(deftest changing-inline-origin-or-child-binding-refuses-migration
  (let [host {:name :host :namespace "demo" :parent :sink
              :args {:x {:parent :sink :args {:x {:value false}}}}}
        ctx (prepared [base producer host])
        planned (source/plan-module [host] ctx)]
    (doseq [replacement [false [:value nil] [:ref :producer] :producer [:value {:ref :producer}]]]
      (let [changed (assoc-in planned [:definitions 0 :proposed :bind :x 1 :bind :x] replacement)]
        (is (= :blocked (get-in (source/verify-module changed ctx) [:definitions 0 :status])))))
    (let [wrapper (assoc-in host [:args :x :args :x] {:ref :producer})
          entry (result (verified [base producer wrapper]) :host)]
      (is (= :authored-source-equivalent (:verification entry)))
      (is (= {:x :ref-wrapper} (get-in entry [:proposed :bind :x 1 :legacy-forms]))))))


(deftest inline-children-preserve-inherited-occupancy-blockers
  (let [preset {:name :preset :namespace "demo" :parent :sink :args {:x nil}}
        host {:name :host :namespace "demo" :parent :sink :args {:x {:parent :preset :args {:x false}}}}
        [blocker] (:blockers (source/plan-module [host] (prepared [base preset host])))]
    (is (= :inherited-occupancy-required (:code blocker)))
    (is (= [:args :x :args :x] (:source-path blocker)))))


(deftest inline-and-host-hazards-retain-both-source-paths
  (let [preset {:name :preset :namespace "demo" :parent :sink :args {:x nil}}
        host {:name :host :namespace "demo" :parent :preset :args {:x {:parent :preset :args {:x false}}}}
        blockers (:blockers (source/plan-module [host] (prepared [base preset host])))]
    (is (= #{[[:args :x] :inherited-occupancy-required]
             [[:args :x :args :x] :inherited-occupancy-required]}
           (set (map (juxt :source-path :code) blockers))))))


(deftest changing-inline-numeric-representation-changes-the-legacy-origin
  (let [host {:name :host :namespace "demo" :parent :sink :args {:x {:parent :sink :args {:x 1}}}}
        ctx (prepared [base host])
        planned (source/plan-module [host] ctx)
        changed (assoc-in planned [:definitions 0 :proposed :bind :x 1 :bind :x] 1N)]
    (is (= :supported (get-in (source/verify-module planned ctx) [:definitions 0 :status])))
    (is (= :blocked (get-in (source/verify-module changed ctx) [:definitions 0 :status])))))


(deftest rename-origin-must-match-one-direct-parent-field
  (let [sink {:namespace "demo" :name :sink :args {:x {:type :text} :taken {:type :text}} :return-type :text}
        relay {:namespace "demo" :name :relay :parent :sink}
        child {:namespace "demo" :name :child :parent :sink :args {:x {:as :exposed}}}
        definitions [sink relay child]
        context (prepared definitions)
        planned (source/plan-module [child] context)
        foreign (-> planned
                    (assoc-in [:definitions 0 :proposed :input] {:other {:type :text :required false}})
                    (assoc-in [:definitions 0 :proposed :bind :x] [:project [:other]]))
        replay (source/verify-module planned context)
        row (first (get-in replay [:definitions 0 :v2 :bindings]))
        alias-id (ids/slot-id (ids/fn-id "demo" :child) :exposed)
        destination (ids/slot-id (ids/fn-id "demo" :sink) :x)]
    (is (= :authored-source-equivalent (get-in replay [:definitions 0 :verification])))
    (is (= [alias-id] (:projection-path row)))
    (is (= [destination] (:target-path row)))
    (is (= (ids/binding-id (ids/fn-id "demo" :child) destination) (:id row)))
    (is (= :blocked (get-in (source/verify-module foreign context) [:definitions 0 :status])))
    (doseq [[variant expected] [[(assoc-in child [:args :x :as] :taken) :rename-field-collision]
                                [(assoc child :parent :relay) :rename-projection-required]
                                [(assoc-in child [:args :x :type] :int) :rename-projection-required]]]
      (is (contains? (codes (result (plan [sink relay variant]) :child)) expected)))))


(deftest nested-inline-declarations-retain-order-and-all-use-sites
  (let [host-base (assoc base :args {:x :any :y :any :z :any})
        inner (array-map :args (array-map :y {:ref :producer} :x {:example [1 2]}) :parent :sink)
        outer (array-map :args (array-map :z inner :y {:parent :producer :args {}} :x false)
                         :return-type :any :parents [:sink])
        host {:name :host :namespace "demo" :parent :sink :args {:x outer}}
        context (prepared [host-base producer host])
        planned (source/plan-module [host] context)
        checked (result (source/verify-module planned context) :host)
        root (:v2 checked)
        nodes (rest (tree-seq :inline-definitions (comp vals :inline-definitions) root))
        legacy-owners (remove #{(ids/fn-id "demo" :host) (ids/fn-id "demo" :sink) (ids/fn-id "demo" :producer)}
                              (map :fn-id (:fn-defs context)))
        edited (update-in planned [:definitions 0 :proposed :bind :x 1 :bind]
                          #(into (array-map) (reverse %)))]
    (is (= :authored-source-equivalent (:verification checked)))
    (is (= 3 (count nodes)))
    (is (= (set legacy-owners) (set (map :fn-id nodes))))
    (is (= :blocked (:status (result (source/verify-module edited context) :host))))))


(deftest inline-type-assertions-retain-wrapper-order-and-rich-types
  (let [host-base (assoc base :args {:x :any :y :any})
        child {:parent :sink :args {:x (array-map :type [:list :int] :ref :producer)
                                    :y (array-map :value nil :type :text)}}
        host {:name :host :namespace "demo" :parent :sink :args {:x child}}
        ctx (prepared [host-base producer host])
        planned (source/plan-module [host] ctx)
        entry (result (source/verify-module planned ctx) :host)
        inline (first (vals (get-in entry [:v2 :inline-definitions])))
        rewritten (get-in entry [:proposed :bind :x 1])
        changed (update-in planned [:definitions 0 :proposed :bind :x 1 :legacy-forms] dissoc :x)]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= {[:x] {:type [:list :int]} [:y] {:type :text}} (:configure rewritten)))
    (is (= {:x :type-first} (:legacy-forms rewritten)))
    (is (= {[:x] [:list :int] [:y] :text} (update-vals (:configurations inline) :type)))
    (is (= :blocked (:status (result (source/verify-module changed ctx) :host))))))


(deftest inline-scalar-rename-preserves-the-own-alias-slot
  (let [sink (assoc-in base [:args :x] :text)
        child {:parent :sink :args {:x (array-map :type :text :as :alias)}}
        host {:name :host :namespace "demo" :parent :sink :args {:x child}}
        entry (result (verified [sink host]) :host)
        inline (first (vals (get-in entry [:v2 :inline-definitions])))
        alias-id (ids/slot-id (:fn-id inline) :alias)
        original (ids/slot-id (ids/fn-id "demo" :sink) :x)
        [binding] (:bindings inline)]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= {:alias {:type :text :required false}} (get-in entry [:proposed :bind :x 1 :input])))
    (is (= {:x [:project [:alias]]} (get-in entry [:proposed :bind :x 1 :bind])))
    (is (= [original] (:target-path binding)))
    (is (= [alias-id] (:projection-path binding)))))


(deftest nested-inline-order-survives-the-array-map-threshold
  (let [inner (array-map :args {:x (array-map :type :any :ref :producer)} :parent :sink
                         :return-type :any :return-type-wide? true :description "Metadata"
                         :effects #{} :expects-effects [] :lambda-params [])
        outer {:parent :sink :args {:x inner}}
        host {:name :host :namespace "demo" :parent :sink :args {:x outer}}
        context (prepared [base producer host])
        planned (source/plan-module [host] context)
        nested-path [:definitions 0 :proposed :bind :x 1 :bind :x 1]
        dropped (update-in planned nested-path dissoc :legacy-order)]
    (is (= (vec (keys inner)) (:legacy-order (get-in planned nested-path))))
    (is (= :authored-source-equivalent (get-in (source/verify-module planned context)
                                               [:definitions 0 :verification])))
    (is (= :blocked (get-in (source/verify-module dropped context) [:definitions 0 :status])))))


(deftest an-inline-type-pin-belongs-to-its-enclosing-binding
  (let [inner (array-map :parent :producer :type :text :args {})
        outer (array-map :type :keyword :args {:x inner} :parent :sink)
        host {:name :host :namespace "demo" :parent :sink :args {:x outer}}
        entry (result (verified [base producer host]) :host)
        outer-v2 (first (vals (get-in entry [:v2 :inline-definitions])))
        inner-v2 (first (vals (:inline-definitions outer-v2)))
        unpinned (update-in host [:args :x] dissoc :type)
        unpinned-plan (result (verified [base producer unpinned]) :host)
        unpinned-outer (first (vals (get-in unpinned-plan [:v2 :inline-definitions])))]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= {:x [:inline-type 1]} (get-in entry [:proposed :bind :x 1 :legacy-forms])))
    (is (= {:parent :producer :bind {}} (:inline-body inner-v2)))
    (is (= (:fn-id outer-v2) (:fn-id unpinned-outer)))
    (is (= (ids/primitive-fn-id :keyword) (get-in entry [:v2 :bindings 0 :type-override-fn-id])))
    (is (= (ids/primitive-fn-id :text) (get-in outer-v2 [:bindings 0 :type-override-fn-id])))))
