(ns ^:serial graphden.input.source-scope-test
  "Scope proof tests replace the shared lineage reader."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.source :as source]
    [graphden.input.source-context :as source-context]
    [graphden.input.source-rename :as rename]
    [graphden.input.source-scope :as scope]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.check :as check]))


(defn- demo-id
  [name]
  (ids/fn-id "demo" name))


(defn- fixture
  ([remote-value duplicate?] (fixture remote-value duplicate? :text))
  ([remote-value duplicate? result]
   (let [owner (demo-id :host)
         producer (demo-id :producer)
         sources [{:name :returned :type {:deep :text}}
                  {:name :sink :args {:left result :right result} :return-type :text}
                  {:name :reader :args {:deep :text} :return-type result}
                  {:name :producer :args {} :return-type :text}
                  {:name :fixed-reader :parent :reader :args {:deep "already bound"}}
                  {:name :host :parent :sink :args (cond-> {:left :reader :deep remote-value}
                                                     duplicate? (assoc :right (if (= :occupied duplicate?) :fixed-reader :reader)))}]
         sources (mapv #(assoc % :namespace "demo") sources)
         original-context (parse/prepare-module sources {} {})
         [entry] (:definitions (source/plan-module [(peek sources)] original-context))
         baseline (update-in sources [(dec (count sources)) :args] dissoc :deep)
         prepared (parse/prepare-module baseline {} {})
         validated (source/verify-module (source/plan-module baseline prepared) prepared)
         _ (assert (= {:supported 6 :blocked 0} (:counts validated)))
         records (->> (:definitions validated) (map :v2) (mapcat #(concat (:declaration-records %) (:bindings %))) distinct)
         {fns :fn fields :slot members :fn-slot bindings :input-binding} (group-by :kind records)
         snapshot (context/build-context
                    {:fns (into {} (map (juxt :id identity)) fns)
                     :fields (into {} (map (juxt :id identity)) fields)
                     :aliases {:returned {:deep :text}} :alias-ids {:returned (demo-id :returned)}
                     :type-bodies (into {} (map (fn [{:keys [v2]}] [(:input-type-id v2) (get-in v2 [:contract :type])])) (:definitions validated))
                     :members (merge (into {} (map #(vector (get-in % [:v2 :input-type-id]) [])) (:definitions validated))
                                     (group-by :fn-id members))
                     :bindings (into {} (map (juxt :id identity)) bindings)
                     :signatures {(demo-id :reader) {:return (if (= result :returned) {:deep :text} result) :args {:deep :text} :effects #{}}
                                  (demo-id :fixed-reader) {:return :text :args {} :effects #{}}
                                  producer {:return :text :args {} :effects #{}}}})]
     {:owner owner :producer producer :entry entry :snapshot snapshot :original-context original-context
      :edge (ids/binding-id owner (ids/slot-id (demo-id :sink) :left))
      :slot (ids/slot-id (demo-id :reader) :deep)})))


(defn- analyze
  [snapshot owner]
  (analysis/analyze-effective-input snapshot owner {:check-expression check/check-input-binding}))


(defn- code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (or (:code (ex-data e)) (:type (ex-data e))))))


(deftest source-records-survive-the-stage-four-occurrence
  (doseq [value ["yes" :producer]]
    (let [{:keys [entry owner slot edge snapshot producer]} (fixture value false)
          certificate (scope/verify-binding entry [:args :deep] snapshot analyze)
          row (:row certificate)]
      (is (= :blocked (:status entry)))
      (is (= [:left :deep] (:public-path certificate)))
      (is (= [[owner [edge]] [slot]] (:address certificate)))
      (is (= (ids/binding-id owner slot) (:id row)))
      (is (= owner (:fn-id row)))
      (is (= [edge] (:scope-path row)))
      (is (= [slot] (:target-path row)))
      (if (keyword? value)
        (is (= producer (:ref-fn-id row)))
        (is (= value (:value row))))
      (is (not (contains? (:bindings snapshot) (:id row)))))))


(deftest referenced-field-annotation-retains-its-identity-and-free-state
  (let [annotation {:as :deep :type :text :description "Reader input"}
        {:keys [entry owner slot edge snapshot original-context]} (fixture annotation false)
        certificate (scope/verify-binding entry [:args :deep] snapshot analyze)
        row (:row certificate)
        candidate (-> entry
                      (assoc :status :supported :blockers []
                             :proposed {:name :host :namespace "demo" :parent :sink
                                        :bind {:left [:ref :demo/reader]}
                                        :configure {[:left :deep] (:configuration certificate)}})
                      (update-in [:legacy :arguments]
                                 (fn [arguments]
                                   (mapv #(cond-> % (= [:args :deep] (:source-path %))
                                                  (assoc :scope-certificate certificate))
                                         arguments))))
        [verified] (:definitions (source/verify-module {:definitions [candidate]} original-context))
        duplicate (fixture annotation true)]
    (is (= {:type :text :description "Reader input"} (:configuration certificate)))
    (is (= [[owner [edge]] [slot]] (:address certificate)))
    (is (= (ids/binding-id owner slot) (:id row)))
    (is (= [edge] (:scope-path row)))
    (is (= (ids/primitive-fn-id :text) (:type-override-fn-id row)))
    (is (= [nil false "Reader input"] ((juxt :expression-kind :value-present :description) row)))
    (is (= :authored-source-equivalent (:verification verified)) (pr-str (:blockers verified)))
    (is (= row (get-in verified [:v2 :configurations [:left :deep] :row])))
    (is (= 1 (count (get-in verified [:v2 :binding-state]))))
    (is (= :nonunique-reference-input
           (code #(scope/verify-binding (:entry duplicate) [:args :deep] (:snapshot duplicate) analyze))))))


(deftest result-fields-and-constructor-inputs-keep-distinct-addresses
  (let [{:keys [entry owner snapshot edge slot]} (fixture "data" false :returned)
        graph (analyze snapshot owner)
        old (some #(when (= [:args :deep] (:source-path %)) %) (get-in entry [:legacy :arguments]))]
    (is (= [:returned :deep] ((juxt :owner :field) (:target old))))
    (is (= (ids/slot-id (demo-id :returned) :deep) (get-in old [:origin :slot-id])))
    (is (= [[owner [edge]] [slot]] (get-in graph [:public-input :routes [:left :deep] :address])))
    (is (= :missing-reference-input (code #(scope/verify-binding entry [:args :deep] snapshot analyze))))))


(deftest a-single-free-route-cannot-hide-a-second-occupied-occurrence
  (let [{:keys [entry owner snapshot]} (fixture "text" :occupied)
        graph (analyze snapshot owner)
        routes (get-in graph [:public-input :routes])]
    (is (contains? routes [:left :deep]))
    (is (not (contains? routes [:right :deep])))
    (is (= :nonunique-reference-input (code #(scope/verify-binding entry [:args :deep] snapshot analyze))))))


(deftest ambiguous-occupied-stale-and-incompatible-origins-are-refused
  (let [{:keys [entry snapshot]} (fixture "text" true)]
    (is (= :nonunique-reference-input (code #(scope/verify-binding entry [:args :deep] snapshot analyze)))))
  (let [{:keys [entry snapshot owner edge slot]} (fixture "text" false)
        verify (fn [s analyzer] (scope/verify-binding entry [:args :deep] s analyzer))
        stale (fn [s id] (assoc-in (analyze s id) [:public-input :snapshot-id] :different))
        extra-binding (fn [s id]
                        (let [graph (analyze s id)]
                          (if (= (:snapshot-id s) (:snapshot-id snapshot))
                            graph
                            (assoc-in graph [:public-input :bindings [[owner []] [slot]]]
                                      {:binding-id (random-uuid) :expression {:kind :value :value "extra"}}))))
        ignored-overlay (fn [s id] (assoc-in (analyze snapshot id) [:public-input :snapshot-id] (:snapshot-id s)))
        incomplete (fn [s id] (assoc (analyze s id) :status :incomplete))
        occupied-id (ids/binding-id (demo-id :reader) slot)
        occupied {:id occupied-id :fn-id (demo-id :reader) :scope-path [] :target-path [slot]
                  :expression-kind "value" :value-present true :value "already bound"}]
    (is (= :stale-input-surface (code #(verify snapshot stale))))
    (doseq [replay [ignored-overlay extra-binding]]
      (is (= :scope-replay-mismatch (code #(verify snapshot replay)))))
    (is (= :incomplete-reference-analysis (code #(verify snapshot incomplete))))
    (is (= :source-snapshot-mismatch
           (code #(verify (assoc-in snapshot [:bindings edge :description] "different policy") analyze))))
    (is (= :source-snapshot-mismatch (code #(verify (update snapshot :bindings dissoc edge) analyze))))
    (is (= :source-snapshot-mismatch
           (code #(verify (assoc-in snapshot [:fns owner :parent-ids] []) analyze))))
    (is (= :unavailable-reference-input
           (code #(verify (assoc-in snapshot [:bindings occupied-id] occupied) analyze)))))
  (let [{:keys [entry snapshot]} (fixture 4 false)]
    (is (= :types/check-failed (code #(scope/verify-binding entry [:args :deep] snapshot analyze))))))


(deftest binding-replay-retains-exact-field-checks-without-discharging-them
  (let [{:keys [entry owner snapshot]} (fixture "text" false)
        address [[owner []] [(ids/slot-id (demo-id :sink) :right)]]
        obligation {:id [address :value-type :field-read] :address address :scope (first address) :path (second address)
                    :kind :value-type :trigger :field-read :expected-type :text}
        with-checks (fn [s id] (assoc (analyze s id) :obligations [obligation]))
        certificate (scope/verify-binding entry [:args :deep] snapshot with-checks)]
    (is (= [obligation] (:obligations certificate)))
    (doseq [transform [empty
                       #(conj % (assoc obligation :expected-type :int))
                       #(mapv (fn [check] (assoc check :expected-type :int)) %)]]
      (let [tampered (fn [s id]
                       (cond-> (with-checks s id)
                         (not= (:snapshot-id s) (:snapshot-id snapshot)) (update :obligations transform)))]
        (is (= :scope-replay-mismatch
               (code #(scope/verify-binding entry [:args :deep] snapshot tampered))))))
    (let [container (fn [s id]
                      (assoc (analyze s id) :obligations [(assoc obligation :kind :container-type
                                                                 :trigger :container-materialization)]))]
      (is (= :incomplete-reference-analysis
             (code #(scope/verify-binding entry [:args :deep] snapshot container)))))))


(deftest scoped-binding-preserves-verified-container-materialization-checks
  (let [{:keys [entry owner snapshot]} (fixture "text" false)
        address [[owner []] []]
        expected (get-in (analyze snapshot owner) [:scopes [] :configured-contract :type])
        obligation {:id [address :container-type :container-materialization] :address address
                    :scope (first address) :path [] :expected-type expected
                    :kind :container-type :trigger :container-materialization}
        replay (fn [before after]
                 (scope/verify-binding entry [:args :deep] snapshot
                                       (fn [s id]
                                         (assoc (analyze s id) :obligations
                                                (if (= (:snapshot-id s) (:snapshot-id snapshot)) before after)))))]
    (is (= [obligation] (:obligations (replay [obligation] [obligation]))))
    (is (= :scope-replay-mismatch (code #(replay [obligation] []))))
    (is (= :scope-replay-mismatch (code #(replay [] [obligation]))))
    (doseq [bad [(assoc obligation :expected-type :text)
                 (assoc obligation :id [address :value-type :field-read])
                 (assoc obligation :scope [(random-uuid) []])]]
      (is (= :incomplete-reference-analysis (code #(replay [bad] [bad])))))))


(deftest unary-graph-callable-replay-keeps-its-exact-parameter-contract
  (let [owner (demo-id :host)
        target (demo-id :handler)
        row-id (demo-id :row)
        address [[owner []] [(demo-id :field)]]
        public-path [:outer :handler]
        original '[:fn {:arg b} c :any :unknown]
        actual '[:fn {:request a} a #{} #{:jvm-server :browser-main}]
        expected '[:fn {:arg a} a :any :unknown]
        certificate {:row {:ref-fn-id target :id row-id} :address address :public-path public-path}
        before {:public-input {:routes {public-path {:completion-type original}}}}
        after {:substitution '{b a c a}
               :dependencies [{:from address :to [owner [row-id]] :binding-id row-id :target-fn-id target :mode :graph-callable}]
               :scopes {[] {:bindings {address {:dispatch-type original :expected-type expected
                                                :reference {:mode :graph-callable :capture-resolved? true :actual-type actual :callable-type actual}}}}
                        [row-id] {:fn-id target :signature {:lambda-params ["request"] :args '{:request a}
                                                            :return 'a :effects #{} :execution #{:jvm-server :browser-main}}}}
               :closures {address {:parameters [:request] :captures [] :external-captures [] :callable-type actual}}}
        prove (fn [g] (#'scope/scoped-unary-callable (context/build-context {}) before g certificate))]
    (is (= expected (prove after)))
    (let [raw '[:fn {} a #{} #{:jvm-server :browser-main}]
          positional '[:fn {:arg a} a #{} #{:jvm-server :browser-main}]
          ignored (-> after
                      (assoc-in [:scopes [row-id] :signature :lambda-params] nil)
                      (assoc-in [:scopes [row-id] :signature :args] {})
                      (assoc-in [:closures address :parameters] [])
                      (assoc-in [:closures address :callable-type] positional)
                      (assoc-in [:scopes [] :bindings address :reference :actual-type] raw)
                      (assoc-in [:scopes [] :bindings address :reference :callable-type] positional))]
      (is (= expected (prove ignored)))
      (is (nil? (prove (assoc-in ignored [:closures address :parameters] [:request])))))
    (doseq [changed [(assoc-in after [:dependencies 0 :mode] :value)
                     (assoc-in after [:scopes [row-id] :signature :lambda-params] [])
                     (assoc-in after [:scopes [row-id] :signature :effects] #{:io})
                     (assoc-in after [:closures address :external-captures] [{:target address}])
                     (assoc-in after [:substitution 'c] :text)
                     (assoc-in after [:scopes [] :bindings address :reference :capture-resolved?] false)]]
      (is (nil? (prove changed))))))


(deftest generic-reference-field-requires-its-own-declaration-and-use-scope
  (let [{:keys [entry owner edge slot snapshot]} (fixture "text" false)
        [[arg binding]] (#'scope/legacy-omissions entry [[:args :deep]] snapshot [])
        original (analyze snapshot owner)
        address [[owner [edge]] [slot]]
        public-path [:left :deep]
        source-type '[:list a]
        arg (assoc-in arg [:target :type] source-type)
        scoped (variables/type-in-scope (first address) source-type)
        graph (-> original
                  (update-in [:scopes [edge] :declared-contract] descriptor/update-node [slot] #(assoc % :type source-type))
                  (assoc-in [:public-input :routes public-path :completion-type] scoped))
        prove (fn [g] (#'scope/public-route g snapshot arg binding address))]
    (is (= [public-path address] (take 2 (prove graph))))
    (doseq [wrong [(assoc-in graph [:public-input :routes public-path :completion-type]
                             (variables/type-in-scope [owner []] source-type))
                   (assoc-in graph [:public-input :routes public-path :completion-type] [:list :text])
                   (assoc-in graph [:scopes [edge] :declared-contract]
                             (get-in original [:scopes [edge] :declared-contract]))]]
      (is (= :unsupported-reference-use (code #(prove wrong)))))))


(deftest generic-list-reference-needs-the-same-independent-return
  (let [owner (demo-id :host)
        target (demo-id :items)
        id (demo-id :row)
        address [[owner []] [(demo-id :slot)]]
        certificate {:address address :public-path [:items] :row {:id id :ref-fn-id target}}
        snapshot {:snapshot-id (random-uuid)}
        expected [:list :any]
        before {:public-input {:routes {[:items] {:completion-type '[:list a]}}}}
        target-graph {:fn-id target :status :checked :public-input (assoc snapshot :fn-id target)
                      :coverage {:recursive-availability true :type-checker true}
                      :scopes {[] {:signature {:return expected}}}}
        after {:substitution '{a :any}
               :dependencies [{:from address :to [owner [id]] :mode :value :binding-id id :target-fn-id target}]
               :scopes {[] {:bindings {address {:expected-type expected :reference {:mode :value :actual-type expected}}}}
                        [id] {:fn-id target :signature {:return expected}}}}
        prove (fn [g result] (#'scope/scoped-value-reference snapshot (constantly result) before g certificate))]
    (is (= [:checked-list {:type expected :original '[:list a] :graph target-graph}] (prove after target-graph)))
    (let [open-before (assoc-in before [:public-input :routes [:items] :completion-type] 'a)
          open-after (-> after (assoc-in [:scopes [] :bindings address :expected-type] :any)
                         (assoc-in [:scopes [] :bindings address :reference :actual-type] :any)
                         (assoc-in [:scopes [id] :signature :return] :any))
          independent (assoc-in target-graph [:scopes [] :signature :return] :any)
          open (fn [b result] (#'scope/scoped-value-reference snapshot (constantly result) b open-after certificate))]
      (is (= [:checked-open {:type :any :original 'a :graph independent}] (open open-before independent)))
      (is (nil? (open (assoc open-before :substitution '{a :text}) independent)))
      (is (nil? (open open-before target-graph))))
    (is (nil? (prove after (assoc-in target-graph [:scopes [] :signature :return] [:list :text]))))
    (is (nil? (prove (assoc-in after [:scopes [id] :signature :return] :any) target-graph)))
    (is (nil? (prove (assoc-in after [:dependencies 0 :mode] :graph-callable) target-graph)))
    (is (nil? (prove (assoc after :substitution {}) target-graph)))
    (is (= :incomplete-reference-analysis (code #(prove after (assoc target-graph :status :incomplete)))))))


(deftest an-unchanged-rename-retains-its-authored-pin-and-field-origin
  (let [owner (demo-id :reader)
        alias-id (ids/slot-id owner :registry)
        field-id (ids/slot-id (demo-id :parent) :coll)
        pin (random-uuid)
        id (ids/binding-id owner field-id)
        argument {:origin {:fn-id owner :binding-id id :slot-id field-id}
                  :rename {:alias :registry :alias-id alias-id :field-id field-id :pin-id pin}
                  :policy {:type-override-fn-id pin} :expression [:project [:registry]]}
        snapshot {:fields {alias-id {:kind :slot :id alias-id :name "registry" :source-slot-id field-id :type-fn-id pin}}
                  :bindings {id {:fn-id owner :id id :target-path [field-id] :projection-path [alias-id] :scope-path []
                                 :projection-scope-path [] :expression-kind "projection" :type-override-fn-id pin}}}]
    (is (rename/preserved-pin? snapshot owner argument))
    (doseq [changed [(assoc-in snapshot [:fields alias-id :source-slot-id] (random-uuid))
                     (assoc-in snapshot [:fields alias-id :type-fn-id] (random-uuid))
                     (assoc-in snapshot [:bindings id :projection-scope-path] [(random-uuid)])
                     (assoc-in snapshot [:bindings id :type-override-fn-id] (random-uuid))]]
      (is (not (rename/preserved-pin? changed owner argument))))
    (is (not (rename/preserved-pin? snapshot owner (assoc-in argument [:policy :terminal] true))))))


(deftest a-nested-reference-keeps-its-declaring-scope-and-legacy-certificate
  (let [[root owner target outer inner field] (repeatedly 6 random-uuid)
        binding-id (ids/binding-id owner field)
        row {:id binding-id :fn-id owner :target-path [field] :scope-path [inner] :expression-kind "ref" :ref-fn-id target}
        original [[owner [inner]] [field]]
        certificate {:snapshot-id (random-uuid) :row row :address original :edges [{:binding-id inner}]}
        graph {:fn-id root :scopes {[outer] {:declaration-order [owner]}}}
        snapshot {:source-scoped-references {binding-id {:row row :address original}}}
        frozen (fn [g s r] (#'scope/source-scope-path s g [root [outer inner]] r))
        env {:inline-proofs {owner {:arguments [{:scope-certificate certificate}]}}}
        edge {:from [[root [outer inner]] [field]] :origin-fn-id owner :binding-id binding-id :target-fn-id target :source-scope-path [inner]}
        lineage (fn [environment reference]
                  (with-redefs-fn {#'source-context/certified-lineage (fn [_ id] id)}
                    #(#'source-context/scoped-origin environment [{:target-fn-id owner}] reference [:input])))]
    (is (= [inner] (frozen graph snapshot row)))
    (is (nil? (frozen (assoc-in graph [:scopes [outer] :declaration-order] [root]) snapshot row)))
    (is (nil? (frozen graph (assoc-in snapshot [:source-scoped-references binding-id :address] [[root [inner]] [field]]) row)))
    (is (nil? (frozen graph snapshot (assoc row :ref-fn-id (random-uuid)))))
    (is (= owner (lineage env edge)))
    (doseq [changed [(assoc edge :source-scope-path [outer]) (assoc edge :target-fn-id (random-uuid))
                     (assoc edge :binding-id (random-uuid))]]
      (is (= :reference-use-drift (code #(lineage env changed)))))
    (is (= :reference-use-drift (code #(lineage
                                         (update-in env [:inline-proofs owner :arguments] conj {:scope-certificate certificate})
                                         edge))))))


(deftest generated-reader-specialization-requires-the-same-declared-binder-and-reader
  (let [owner (random-uuid) builder (random-uuid) scope [owner [(random-uuid)]]
        slot (ids/slot-id builder :item-0) path [slot] address [scope path]
        declared '[:union :null [:map key :any]]
        key-var (variables/type-in-scope scope 'key)
        check {:address address :expected-type (variables/type-in-scope scope declared) :kind :value-type :trigger :field-read}
        source {:generated-list {:operands [:item-0]} :base-fn-id builder :argument-paths {:item-0 path}}
        binding {:binding-id (random-uuid) :origin-fn-id builder :expression {:kind :value :value nil}}
        fields [{:id slot :name :item-0 :type declared}]
        node {:fn-id builder :signature-source source :declared-contract {:fields fields} :bindings {address binding}}
        before {:scopes {(second scope) node}}
        after (assoc before :substitution {key-var :keyword})
        replay (fn [new c] (#'scope/specialize-generated-read before new c))]
    (is (= [:union :null [:map :keyword :any]] (:expected-type (replay after check))))
    (is (= check (replay (assoc-in after [:scopes (second scope) :bindings address :binding-id] (random-uuid)) check)))
    (is (= check (replay (assoc-in after [:scopes (second scope) :declared-contract :fields 0 :type] 'other) check)))
    (let [unrelated (assoc check :expected-type (variables/type-in-scope [owner []] declared))]
      (is (= unrelated (replay after unrelated))))
    (let [conjunction (assoc check :additional-types [:jsonb])]
      (is (= conjunction (replay after conjunction))))))
