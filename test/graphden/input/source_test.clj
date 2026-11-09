(ns graphden.input.source-test
  (:require
    [clojure.test :refer [deftest is testing]]
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
      (is (= false (:required (first (filter #(= "pretty" (:name %)) rows)))))
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
    (doseq [name [:list :renamed-list]]
      (let [planned (result report name)]
        (is (contains? (codes planned) :list-builder-required))
        (is (not (contains? planned :proposed)))))))


(deftest unsupported-cases-aggregate-with-safe-blocker-summaries
  (let [definitions [base producer
                     {:name :append :namespace "demo" :parent :sink
                      :args {:items {:append [:producer] :closed true}}}
                     {:name :rename :namespace "demo" :parent :sink
                      :args {:x {:as :renamed :type :text}}}
                     {:name :secret :namespace "demo" :parent :sink
                      :args {:x {:resolver :producer :value "private-payload"}}}
                     {:name :anonymous :namespace "demo" :parent :sink
                      :args {:x {:parent :producer}}}]
        report (plan definitions)]
    (is (= {:supported 2 :blocked 4} (:counts report)))
    (is (contains? (codes (result report :append)) :list-builder-required))
    (is (contains? (codes (result report :rename)) :rename-projection-required))
    (is (contains? (codes (result report :secret)) :resolver-binding-required))
    (is (= #{:inline-identity-or-source-mapping-required}
           (codes (result report :anonymous))))
    (is (not (.contains (pr-str (:blockers report)) "private-payload")))))


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
    (is (contains? (codes (result report :new-field)) :own-field-declaration-required))
    (is (contains? (codes (result report :annotated)) :binding-metadata-required))
    (is (not (contains? (result report :annotated) :proposed)))))
