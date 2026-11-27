(ns graphden.input.ir-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.arguments :as arguments]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.input.ir :as ir]
    [graphden.input.lookup-abi :as lookup]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.types.validator :as validator]))


(deftest materializing-a-projected-native-record-requires-its-exact-argument-identity
  (let [owner (random-uuid)
        native (ids/fn-id "fixture" :inspect)
        use-id (random-uuid)
        argument [(ids/slot-id native :request)]
        source [[owner []] [(random-uuid)]]
        address [[owner [use-id]] argument]
        leaf [(first source) (conj (second source) (random-uuid))]
        type {:uri :text}
        expressions {source {:op :record :fields [[:uri leaf]] :type type}
                     leaf {:op :supplied :type :text}
                     address {:op :read :source source}}
        entry {:fn-id native :namespace "fixture" :name :inspect :args {:request type}}
        abi {:base-fn-id native :root-entry entry :argument-paths {:request argument}}
        binding {:expression {:kind :projection} :expected-type type
                 :reference {:mode :projection :actual-type type}}
        analysis {:scopes {[use-id] {:bindings {address binding}}}}
        check (fn [impl]
                (try (#'ir/container-validator analysis expressions {(first address) impl}
                                               {:address address :expected-type type :trigger :container-materialization})
                     (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= {:source source :required [:uri] :key-type :any} (check abi)))
    (doseq [other [(assoc-in abi [:root-entry :args :request] {:uri :any})
                   (assoc-in abi [:root-entry :name] :other)
                   (assoc-in abi [:argument-paths :request] [(random-uuid)])]]
      (is (= :runtime-obligations-required (check other))))))


(deftest a-fixed-keyword-list-producer-requires-the-complete-zipmap-key-list
  (let [abi (fn [module name args result]
              (let [id (ids/fn-id module name)]
                {:base-fn-id id :root-entry {:fn-id id :namespace module :name name :args args :return result :effects #{}}
                 :argument-paths (into {} (map #(vector % [(ids/slot-id id %)])) (keys args))}))
        filter-abi (abi "core.hof" :filter '{:coll [:list a] :pred [:fn {:item a} :bool #{} :unknown]} '[:list a])
        map-abi (abi "core.hof" :map '{:coll [:list a] :func [:fn {:item a} b :any :unknown]} '[:list b])
        zip-abi (abi "core.collections" :zipmap '{:keys [:list k] :vals [:list v]} '[:map k v])
        root [(random-uuid) []]
        rows [(first root) [(random-uuid)]]
        item [(first root) (conj (second rows) (random-uuid))]
        address [root (get-in filter-abi [:argument-paths :coll])]
        expected [:list [:map :keyword :any]]
        type {:label :text}
        evidence [{:value-present true :type :keyword :value :label}]
        scopes {root filter-abi rows map-abi item zip-abi}
        graph {:scopes {[] {:signature-source filter-abi
                            :bindings {address {:expected-type expected :reference {:actual-type [:list type]}}}}
                        (second rows) {:signature-source map-abi :signature {:return [:list type]}}
                        (second item) {:signature-source zip-abi :signature {:return type :resolved-bindings {:keys {:list-elements evidence}}}}}}
        expressions {address {:op :invoke :scope rows}}
        closures {[root (get-in filter-abi [:argument-paths :pred])] {:parameters [:item] :type [:fn {:item (second expected)} :bool #{} :unknown]}
                  [rows (get-in map-abi [:argument-paths :func])] {:scope item :parameters [:item] :type [:fn {:item :any} type :any :unknown]}}
        validate #(lookup/lookup-item-list-type %1 expressions %2 closures address expected)
        proof [:scopes (second item) :signature :resolved-bindings :keys :list-elements]]
    (is (= expected (validate graph scopes)))
    (doseq [elements [nil [] [{:value-present false :type :keyword}] [{:value-present true :value "label" :type :text}]]]
      (is (nil? (validate (assoc-in graph proof elements) scopes))))
    (is (nil? (validate graph (assoc-in scopes [item :root-entry :return] :any))))))


(deftest computed-native-record-validates-the-full-declared-value-at-the-invocation
  (let [owner (random-uuid) binding (random-uuid) field (random-uuid)
        root [owner []] source [owner [binding]] address [root [field]]
        target (ids/fn-id "fixture" :receipt)
        shape {:ok :bool :value [:union :null [:map :keyword :any]]}
        native {:base-fn-id target :root-entry {:fn-id target :namespace "fixture" :name :receipt :return shape}}
        bound {:binding-id binding :expression {:kind :ref :fn-id target} :expected-type shape
               :reference {:mode :value :actual-type shape}}
        expr {:op :invoke :scope source :binding-id binding :target-id target :type shape}
        obligation {:kind :container-type :trigger :container-materialization :address address :expected-type shape}
        analysis {:scopes {[] {:bindings {address bound}}
                           [binding] {:fn-id target :signature-source native :signature {:return shape}}}
                  :obligations [obligation]}
        plan (#'ir/read-obligations analysis {address expr} {source native} {})
        [read-type] (get-in plan [:value-obligations address])
        lookup (ids/fn-id "core.collections" :get)
        call [owner [(random-uuid)]]
        alias-field [call [(random-uuid)]]
        native-path [(ids/slot-id lookup :coll)]
        input [call native-path]
        lookup-abi {:base-fn-id lookup :argument-paths {:coll native-path}
                    :root-entry {:fn-id lookup :namespace "core.collections" :name :get :return :any
                                 :args '{:coll [:union :null [:list :any] [:map a :any]] :key :any :default :any}}}
        project (fn [[scope path :as addr]] {:expected-type shape :expression {:kind :projection :scope scope :path path}
                                             :reference {:source addr :mode :projection :actual-type shape}})
        analysis (assoc-in analysis [:scopes (second call)] {:signature-source lookup-abi
                                                             :bindings {input (project alias-field) alias-field (project address)}})
        expressions {input {:op :read :source alias-field :type shape} alias-field {:op :read :source address :type shape} address expr}
        materialize #(#'ir/lookup-native-record? analysis % {call lookup-abi source native} (assoc obligation :address %2))]
    (is (= shape read-type))
    (is (materialize expressions input))
    (is (materialize expressions alias-field))
    (is (not (materialize (assoc-in expressions [alias-field :source] input) input)))
    (is (not (materialize (assoc-in expressions [address :op] :record) input)))
    (is (= {:ok false :value nil :extra 1} (validator/validate! read-type {:ok false :value nil :extra 1})))
    (is (thrown? clojure.lang.ExceptionInfo (validator/validate! read-type {:ok true})))
    (is (thrown? clojure.lang.ExceptionInfo (validator/validate! read-type {:ok true :value {"bad" 1}})))
    (doseq [[changed-expr changed-native changed-check]
            [[(assoc expr :op :record) native obligation]
             [(assoc expr :binding-id (random-uuid)) native obligation]
             [expr (assoc-in native [:root-entry :return] :any) obligation]
             [expr native (assoc obligation :additional-types [:jsonb])]]]
      (is (not (#'ir/computed-native-record? analysis {address changed-expr} {source changed-native} changed-check))))))


(deftest computed-map-checks-retain-their-native-read-boundary
  (let [owner (random-uuid)
        read-type (fn [module native field expected actual]
                    (let [id (ids/fn-id module native)
                          path [(ids/slot-id id field)]
                          address [[owner []] path]
                          source {:base-fn-id id :root-entry {:fn-id id :namespace module :name native}
                                  :argument-paths {field path}}
                          node {:signature-source source :bindings {address {:reference {:mode :value :actual-type actual}}}}]
                      (#'ir/field-read-type {:scopes {[] node}} address expected :invoke)))
        record {:name :text :required :bool}
        dictionary [:map [:union :keyword :text] :any]
        lookup [:union :null [:list :any] dictionary]
        asserted (read-type "core.logic" :assert-eq :actual record [:map :any :any])]
    (is (= record asserted))
    (is (= {:name "one" :required false :extra nil}
           (validator/validate! asserted {:name "one" :required false :extra nil})))
    (is (thrown? clojure.lang.ExceptionInfo (validator/validate! asserted {:name "one"})))
    (is (nil? (read-type "core.logic" :assert-eq :expected record [:map :any :any])))
    (doseq [branch [:then :else]]
      (is (= [:map :any :any] (read-type "core.logic" :if branch [:map :any :any] [:map :any :any]))))
    (is (nil? (read-type "core.logic" :if :test [:map :any :any] [:map :any :any])))
    (doseq [native [:get :contains?]]
      (is (= lookup (read-type "core.collections" native :coll lookup dictionary))))
    (is (nil? (read-type "core.collections" :get :key lookup dictionary)))))


(deftest computed-assoc-input-keeps-its-checked-key-domain
  (let [native (ids/fn-id "core.collections" :assoc)
        scope [(random-uuid) []]
        path [(ids/slot-id native :map)] address [scope path]
        args '{:map [:union :null [:map a :any]] :key :any :value :any}
        declared (variables/type-in-scope scope (:map args))
        actual [:union :null [:map :keyword :any]]
        node {:signature-source {:base-fn-id native :argument-paths {:map path}
                                 :root-entry {:fn-id native :name :assoc :namespace "core.collections" :args args :return :any}}
              :bindings {address {:reference {:mode :value :actual-type actual}}}}
        domain #(lookup/collection-read-domain {:scopes {[] %}} address declared %2)]
    (is (= actual (domain node :invoke)))
    (is (nil? (domain node :read)))
    (is (nil? (domain (assoc-in node [:signature-source :root-entry :args :key] 'a) :invoke)))
    (is (nil? (domain (assoc-in node [:signature :return] (variables/type-in-scope scope 'a)) :invoke)))
    (is (nil? (domain (assoc-in node [:bindings address :reference :mode] :callable) :invoke)))))


(deftest generated-merge-operands-require-the-private-map-key-contract
  (let [owner (random-uuid) binding (random-uuid)
        merge-id (ids/fn-id "core.collections" :merge)
        input [(ids/slot-id merge-id :maps)] item [(random-uuid)]
        scope [owner [binding]] operand [scope item]
        args '{:maps [:list [:union :null [:map a :any]]]}
        contract {:fn-id merge-id :namespace "core.collections" :name :merge :args args :return [:map :any :any]}
        graph {:scopes {[] {:signature-source {:base-fn-id merge-id :root-entry contract :argument-paths {:maps input}}}
                        [binding] {:signature-source {:generated-list {:operands [:item-0]} :argument-paths {:item-0 item}}
                                   :bindings {operand {:reference {:mode :value :actual-type [:union :empty-map {:name :text}]}}}}}
               :dependencies [{:from [[owner []] input] :to scope :mode :value}]}
        validator #(when-let [t (#'ir/field-read-type % operand '[:union :null [:map a$builder :any]] :invoke)] t)]
    (is (= [:union :null [:map :any :any]] (validator graph)))
    (is (nil? (validator (assoc-in graph [:scopes [] :signature-source :root-entry :return] '[:map a :any]))))
    (is (nil? (validator (assoc graph :dependencies []))))
    (is (nil? (validator (assoc-in graph [:scopes [binding] :bindings operand :reference :actual-type] :text))))))


(def ^:private base-definitions
  {:root {:input {:user {:name :text :unused :text} :result :text}
          :return :text :bindings {[:user :name] {:value "Ada"} [:result] {:ref :child :id :use}}}
   :child {:input {:x :text} :return :text}})


(defn- structural-model
  ([] (structural-model base-definitions))
  ([definitions]
   ;; A deliberately explicit checker seam for pure BB lowering tests. These
   ;; assertions prove routing, not typing; compile-test uses the real checker.
   (fixture/model definitions :root
                  (fn [{:keys [substitution]}]
                    {:status :checked :substitution substitution}))))


(defn- lower
  ([model] (lower model base-definitions))
  ([{:keys [analysis context]} definitions]
   (ir/lower-checked analysis {:snapshot-id (:snapshot-id context)
                               :implementations (fixture/implementations definitions
                                                                         {:root (fn [_ _]) :child (fn [_ _])})})))


(deftest nullable-unconstrained-map-operands-keep-their-structural-check
  (let [check #(#'ir/field-read-type {} [[(random-uuid) []] []] % :read)]
    (doseq [shape [[:union :null [:map :keyword :any]]
                   [:union :null [:map :any :any]]
                   [:union [:map :any :any] [:list :any] :null]]]
      (is (= shape (check shape))))
    (is (nil? (check '[:union :null [:map a :any]])))
    (is (nil? (check [:union :null [:map :text :int]])))))


(deftest checked-plan-seam-lowers-with-free-required-parameters
  (let [model (structural-model)
        plan (lower model)]
    (is (= :checked (get-in model [:analysis :status])))
    (is (= :literal (get-in plan [:expressions (fixture/address :root [] :root [:user :name]) :op])))
    (is (= :invoke (get-in plan [:expressions (fixture/address :root [] :root [:result]) :op])))
    (is (= #{(fixture/address :root [] :root [:user :unused])
             (fixture/address :root [:use] :child [:x])} (:parameters plan)))
    (is (= (:parameters plan) (:required-addresses plan)))))


(deftest incomplete-checking-and-runtime-obligations-are-never-dropped
  (let [model (structural-model)
        code (fn [model] (try (lower model) (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= :unchecked-plan (code (assoc-in model [:analysis :status] :incomplete))))
    (is (= :unchecked-plan (code (assoc-in model [:analysis :coverage :type-checker] false))))
    (is (= :snapshot-mismatch (code (assoc-in model [:context :snapshot-id] (random-uuid)))))
    (is (= :runtime-obligations-required (code (assoc-in model [:analysis :obligations] [{:kind :value-type}]))))
    (is (= :runtime-obligations-required (code (assoc-in model [:analysis :scopes [] :obligations] [{}]))))
    (is (= :closure-plan-mismatch (code (assoc-in model [:analysis :closures] {:x {}}))))))


(deftest checked-closure-partitions-public-inputs-from-invocation-parameters
  (let [definitions {:root {:input {:callback [:fn {:item :any} :any #{}] :config :any} :return :any
                            :bindings {[:callback] {:ref :child :id :callback-use}}}
                     :child {:input {:item :any :config :any} :return :any
                             :field-origins {[:config] :root}}}
        model (structural-model definitions)
        plan (lower model definitions)
        callback (fixture/address :root [] :root [:callback])
        configuration (fixture/address :root [] :root [:config])
        item (fixture/address :root [:callback-use] :child [:item])
        capture (fixture/address :root [:callback-use] :root [:config])
        closure (get-in plan [:closures callback])
        read-check {:address callback :kind :value-type :trigger :field-read :expected-type (:type closure)}
        code (fn [changed]
               (try (lower changed definitions)
                    (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= :closure (get-in plan [:expressions callback :op])))
    (is (= [[:item] [{:name :item :address item :required? true}]]
           ((juxt :parameters :call-sites) closure)))
    (is (= #{configuration} (:parameters plan)))
    (is (= #{item} (get-in plan [:frame-layouts (:scope closure) :required-addresses])))
    (is (= configuration (get-in plan [:expressions capture :source])))
    (is (= :callback-parameter-shape-required
           (code (assoc-in model [:analysis :closures callback :call-sites 0 :value-path] [:nested]))))
    (is (= :closure-capture-route-mismatch
           (code (assoc-in model [:analysis :closures callback :captures 0 :source] item))))
    (is (= :unsupported-closure-scope
           (code (assoc-in model [:analysis :closures callback :external-captures] [{:target item}]))))
    (is (= {} (:value-obligations (lower (assoc-in model [:analysis :obligations] [read-check]) definitions))))
    (is (= :runtime-obligations-required
           (code (assoc-in model [:analysis :obligations] [(assoc read-check :expected-type :any)]))))
    (is (= :unproved-closure-contract
           (code (assoc-in model [:analysis :closures callback :callable-type] [:fn {} :bool #{}]))))
    (is (= :cross-frame-expression
           (code (assoc-in model [:analysis :scopes [] :bindings configuration]
                           {:reference {:mode :projection :source item}}))))))


(deftest polymorphic-closure-contract-must-agree-for-every-substitution
  (let [actual '[:fn {:value b} [:union :null a b] #{} #{:jvm-server}]
        expected (assoc actual 3 :any 4 :unknown)
        fits? #'ir/checked-closure-subtype?]
    (is (fits? actual expected))
    (is (not (fits? actual (assoc-in expected [1 :value] 'a))))
    (is (not (fits? actual (assoc expected 2 'w))))
    (is (not (fits? (assoc actual 3 #{:io}) (assoc expected 3 #{}))))
    (is (not (fits? actual (assoc expected 4 #{:browser-main}))))))


(deftest explicit-zero-parameter-closure-has-only-lexical-captures
  (let [definitions {:root {:input {:callback [:fn {} :any #{}] :config :any} :return :any
                            :bindings {[:callback] {:ref :child :id :callback-use} [:config] {:value nil}}}
                     :child {:input {:config :any} :return :any :lambda-params []
                             :field-origins {[:config] :root}}}
        plan (lower (structural-model definitions) definitions)
        callback (fixture/address :root [] :root [:callback])
        closure (get-in plan [:closures callback])
        capture (fixture/address :root [:callback-use] :root [:config])]
    (is (= [] (:parameters closure) (:call-sites closure)))
    (is (= #{} (get-in plan [:frame-layouts (:scope closure) :parameters])))
    (is (= (fixture/address :root [] :root [:config]) (get-in plan [:expressions capture :source])))))


(deftest exposed-callback-captures-are-supplied-by-the-enclosing-frame
  (let [definitions {:root {:input {:style {:callback [:fn {:item :text} :text #{}]}} :return :text
                            :bindings {[:style :callback] {:ref :child :id :callback-use}}}
                     :child {:input {:item :text :mark :text} :return :text}}
        model (structural-model definitions)
        plan (lower model definitions)
        style (fixture/address :root [] :root [:style])
        callback (fixture/address :root [] :root [:style :callback])
        item (fixture/address :root [:callback-use] :child [:item])
        mark (fixture/address :root [:callback-use] :child [:mark])
        closure (get-in plan [:closures callback])
        fail (fn [changed]
               (try (lower changed definitions)
                    (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))]
    (is (= [:item] (:parameters closure)))
    (is (= #{mark} (:external-addresses closure) (:parameters plan) (:required-addresses plan)))
    (is (= {style {} mark "!"}
           (arguments/route-values (get-in model [:analysis :public-input]) {:style {:callback {:mark "!"}}})))
    (is (= #{item} (get-in plan [:frame-layouts (:scope closure) :parameters])))
    (is (= #{item} (get-in plan [:frame-layouts (:scope closure) :required-addresses])))
    (is (true? (get (ir/present-addresses (get-in plan [:frame-layouts (:root-scope plan) :presence-program])
                                          {mark "!"})
                    mark)))
    (is (= :unsupported-closure-scope
           (fail (assoc-in model [:analysis :closures callback :external-captures]
                           [{:target mark :value-path [:nested]}]))))
    (is (= :unsupported-closure-scope
           (fail (update-in model [:analysis :public-input :routes] dissoc [:style :callback :mark]))))))


(deftest special-base-semantics-and-missing-abi-provenance-reject
  (let [{:keys [analysis context]} (structural-model)
        impls (fixture/implementations base-definitions {:root (fn [_ _]) :child (fn [_ _])})
        target (fixture/id :child)
        code (fn [impls]
               (try (ir/lower-checked analysis {:snapshot-id (:snapshot-id context) :implementations impls})
                    (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= :resolved-implementation-required (code (update impls target dissoc :root-entry))))
    (is (= :implementation-abi-mismatch (code (assoc-in impls [target :argument-paths] {}))))
    (is (= :compile-time-value-compilation-required
           (code (assoc-in impls [target :root-entry :compile-time-value?] true))))
    (is (= :lazy-sequence-abi-required
           (code (assoc-in impls [target :root-entry :lazy-seq-args] #{:x}))))))


(deftest callback-record-uses-identity-stable-routes-and-explicit-presence
  (let [item {:value :any :details {:flag :any}}
        definitions {:root {:input {:callback [:fn {:item item} :any #{}]} :return :any
                            :bindings {[:callback] {:ref :child :id :use}}}
                     :child {:input {:item item} :optional #{[:item :details]} :return :any}}
        model (structural-model definitions)
        callback (fixture/address :root [] :root [:callback])
        address (partial fixture/address :root [:use] :child)
        item-address (address [:item])
        detail-address (address [:item :details])
        value-address (address [:item :value])
        flag-address (address [:item :details :flag])
        plan (lower model definitions)
        closure (get-in plan [:closures callback])
        layout (get-in plan [:frame-layouts (:scope closure)])]
    (is (= #{item-address} (:required-addresses layout)))
    (is (= #{item-address detail-address} (:record-inputs layout)))
    (is (= #{value-address flag-address} (:parameters layout)))
    (is (= {item-address {} value-address nil detail-address {} flag-address false}
           (arguments/route-values (:parameter-surface closure) {:item {:value nil :details {:flag false}}})))
    (is (= {item-address {} value-address nil}
           (arguments/route-values (:parameter-surface closure) {:item {:value nil}})))
    (is (false? (get (ir/present-addresses (:presence-program layout) {item-address {} value-address false})
                     detail-address)))
    (is (= :callback-parameter-shape-required
           (try
             (lower (update-in model [:analysis :closures callback :call-sites] pop) definitions)
             (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure))))))))
