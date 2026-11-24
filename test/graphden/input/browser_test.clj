(ns graphden.input.browser-test
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.browser :as browser]
    [graphden.input.compile :as jvm]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.input.field-default :as field-default]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.module :as module]
    [graphden.input.source-primitive :as primitive]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(def ^:private definitions
  {:root {:input {:coll {:name :text :unsafe :int} :key :keyword :default :any}
          :return :any
          :bindings {[:coll :name] {:ref :name}
                     [:coll :unsafe] {:ref :unsafe}
                     [:key] {:value :name}
                     [:default] {:value nil}}}
   :name {:input {:value :text}
          :return :text
          :bindings {[:value] {:value "Ada"}}}
   :unsafe {:input {:dividend :int :divisor :int}
            :return :int
            :bindings {[:dividend] {:value 1} [:divisor] {:value 0}}}})


(def ^:private loaded (delay (:base-fn-defs (loader/load-packages ["core"]))))


(defn- checked-model
  [source]
  (let [{:keys [analysis context]} (fixture/model source :root)
        functions (into {} (map (fn [[target base-name]] [target (get-in @loaded [base-name :impl])]))
                        {:root :get :name :const :unsafe :mod})
        implementations (fixture/implementations source functions)]
    {:analysis analysis
     :options {:context context
               :snapshot-id (:snapshot-id context)
               :allow-fn? (constantly true)
               :implementations (-> implementations
                                    (assoc-in [(fixture/id :root) :base-fn-id] (ids/fn-id "core.collections" :get))
                                    (assoc-in [(fixture/id :name) :base-fn-id] (ids/fn-id "core.logic" :const))
                                    (assoc-in [(fixture/id :unsafe) :base-fn-id] (ids/fn-id "core.arithmetic" :mod)))}}))


(defn- rejection
  [analysis options]
  (try (browser/export-closed analysis options)
       nil
       (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))


(defn- run-browser
  [plan inputs]
  (let [cases (mapv (fn [input] {:entry "root" :inputs (update-vals input wire/encode-value)}) inputs)
        result (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                      :in (json/generate-string {:plan plan :cases cases}))]
    (is (zero? (:exit result)) (:err result))
    (json/parse-string (:out result) true)))


(defn- agrees-with-browser
  [source expected]
  (let [{:keys [analysis options]} (checked-model source)
        invoke (jvm/compile-checked analysis options)
        plan (browser/export-closed analysis options)]
    (is (= expected (invoke {} nil)))
    (is (= 2 (:format plan)))
    (is (= plan (browser/export-closed analysis options)))
    (is (= [{:value (wire/encode-value expected) :sequence false}]
           (run-browser plan [{}])))))


(deftest browser-lowers-the-same-checked-nested-input-as-jvm
  (agrees-with-browser definitions "Ada"))


(deftest browser-omits-unconfigured-empty-record-fields
  (agrees-with-browser (-> definitions
                           (assoc-in [:root :input :coll :empty] {})
                           (assoc-in [:root :optional] #{[:coll :empty]})
                           (assoc-in [:root :bindings [:key] :value] :empty)
                           (assoc-in [:root :bindings [:default] :value] "missing"))
                       "missing"))


(defn- invocation-error
  [invoke args]
  (try (invoke args nil)
       nil
       (catch clojure.lang.ExceptionInfo error
         (:code (ex-data error)))))


(deftest explicitly-supplied-empty-records-are-present
  (let [base (-> definitions
                 (assoc-in [:root :input :coll :empty] {})
                 (assoc-in [:root :bindings [:key] :value] :empty)
                 (assoc-in [:root :bindings [:default] :value] "missing"))
        given {:coll {:empty {}}}]
    (doseq [optional? [false true]]
      (let [source (cond-> base optional? (assoc-in [:root :optional] #{[:coll :empty]}))
            {:keys [analysis options]} (checked-model source)
            invoke (jvm/compile-named analysis options)
            [absent present] (run-browser (browser/export-named analysis options) [{} given])]
        (is (= {} (invoke given nil)))
        (is (= {:value (wire/encode-value {}) :sequence false} present))
        (if optional?
          (do (is (= "missing" (invoke {} nil)))
              (is (= {:value (wire/encode-value "missing") :sequence false} absent)))
          (do (is (= :missing-required-input (invocation-error invoke {})))
              (is (= "Missing graph input field" (:error absent)))
              (is (= :browser-input-validation-required (rejection analysis options)))))))))


(deftest supplied-optional-records-require-their-own-required-fields
  (let [source (-> definitions
                   (assoc-in [:root :input :coll :profile] {:name :text :note :text})
                   (assoc-in [:root :optional] #{[:coll :profile] [:coll :profile :note]})
                   (assoc-in [:root :bindings [:key] :value] :profile)
                   (assoc-in [:root :bindings [:default] :value] "missing"))
        {:keys [analysis options]} (checked-model source)
        invoke (jvm/compile-named analysis options)
        incomplete {:coll {:profile {:note "gift"}}}
        present {:coll {:profile {:name "Ada"}}}
        [absent refused success] (run-browser (browser/export-named analysis options) [{} incomplete present])]
    (is (= "missing" (invoke {} nil)))
    (is (= {:value (wire/encode-value "missing") :sequence false} absent))
    (is (= :missing-required-input (invocation-error invoke incomplete)))
    (is (= "Missing graph input field" (:error refused)))
    (is (= {:name "Ada"} (invoke present nil)))
    (is (= {:value (wire/encode-value {:name "Ada"}) :sequence false} success))))


(deftest export-refuses-unproved-browser-execution
  (let [{:keys [analysis options]} (checked-model definitions)
        restricted (fixture/id :unsafe)]
    (is (= :browser-visibility-policy-required (rejection analysis (dissoc options :allow-fn?))))
    (is (= :browser-visibility-denied (rejection analysis (assoc options :allow-fn? #(not= restricted %)))))
    (is (= :unsupported-browser-primitive
           (rejection analysis (assoc-in options [:implementations restricted :base-fn-id] restricted))))
    (testing "unvalidated input never becomes an untyped read"
      (let [{:keys [analysis options]} (checked-model (update-in definitions [:name :bindings] dissoc [:value]))]
        (is (= :browser-input-validation-required (rejection analysis options)))))))


(deftest browser-routes-required-reference-inputs-through-nested-groups
  (let [source (update-in definitions [:name :bindings] dissoc [:value])
        {:keys [analysis options]} (checked-model source)
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        args {:coll {:name {:value "Grace"}}}
        [success missing wrong unknown bad-record]
        (run-browser plan [args {} {:coll {:name {:value 1}}}
                           {:coll {:name {:value "Grace" :extra nil}}} {:coll false}])]
    (is (= "Grace" (invoke args nil)))
    (is (= {:value (wire/encode-value "Grace") :sequence false} success))
    (is (= "Missing graph input field" (:error missing)))
    (is (= "Value does not match the required graph input type" (:error wrong)))
    (is (= "Unknown graph input field" (:error unknown)))
    (is (= "Expected a graph input record" (:error bad-record)))))


(deftest named-browser-fields-distinguish-absence-nil-and-false
  (let [source (-> definitions
                   (assoc-in [:root :input :coll :flag] :any)
                   (assoc-in [:root :optional] #{[:coll :flag]})
                   (assoc-in [:root :bindings [:key] :value] :flag)
                   (assoc-in [:root :bindings [:default] :value] "missing"))
        {:keys [analysis options]} (checked-model source)
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        inputs [{} {:coll {:flag nil}} {:coll {:flag false}}]
        expected ["missing" nil false]]
    (is (= expected (mapv #(invoke % nil) inputs)))
    (is (= (mapv #(hash-map :value (wire/encode-value %) :sequence false) expected)
           (run-browser plan inputs)))))


(deftest browser-checks-primitive-reference-obligations-only-when-read
  (let [source (-> definitions
                   (assoc-in [:name :return] :any)
                   (assoc-in [:name :input :value] :any)
                   (assoc-in [:name :bindings [:value] :value] 42))
        {:keys [analysis options]} (checked-model source)
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        [result] (run-browser plan [{}])]
    (is (= [:field-read] (mapv :trigger (:obligations analysis))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match" (invoke {} nil)))
    (is (= "Value does not match the required graph input type" (:error result)))
    (agrees-with-browser (-> source
                             (assoc-in [:root :bindings [:key] :value] :missing)
                             (assoc-in [:root :bindings [:default] :value] "fallback"))
                         "fallback")))


(deftest named-browser-input-checks-a-union-of-wire-primitives
  (let [source {:root {:input {:value [:union :null :text]} :return :any}}
        {:keys [analysis context]} (fixture/model source :root)
        implementations (fixture/implementations source {:root (get-in @loaded [:const :impl])})
        options {:context context :snapshot-id (:snapshot-id context) :allow-fn? (constantly true)
                 :implementations (assoc-in implementations [(fixture/id :root) :base-fn-id]
                                            (ids/fn-id "core.logic" :const))}
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        args (fn [value] {:value value})
        values [nil "Ada"]
        [null-result text-result invalid] (run-browser plan (mapv args (conj values false)))]
    (is (= values (mapv #(invoke (args %) nil) values)))
    (is (= [{:value ["nil"] :sequence false} {:value ["string" "Ada"] :sequence false}]
           [null-result text-result]))
    (is (= "Value does not match the required graph input type" (:error invalid)))))


(deftest browser-and-jvm-run-the-same-checked-callback-plan
  (let [source {:root {:input {:func [:fn {:item :text} :text #{}] :coll [:list :text]}
                       :return [:list :text]
                       :bindings {[:func] {:ref :callback}}}
                :callback {:input {:string :text} :return :text}}
        {:keys [analysis context]} (fixture/model source :root)
        functions {:root (get-in @loaded [:map :impl]) :callback (get-in @loaded [:str-lower :impl])}
        implementations (-> (fixture/implementations source functions)
                            (assoc-in [(fixture/id :root) :base-fn-id] (ids/fn-id "core.hof" :map))
                            (assoc-in [(fixture/id :callback) :base-fn-id] (ids/fn-id "core.strings" :str-lower)))
        options {:context context :snapshot-id (:snapshot-id context)
                 :implementations implementations :allow-fn? (constantly true)}
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        args {:coll ["ONE" "TWO" "ONE"]}
        expected (invoke args nil)
        [result bad-member] (run-browser plan [args {:coll ["one" 2]}])]
    (is (= ["one" "two" "one"] expected))
    (is (= {:value (wire/encode-result expected) :sequence true} result))
    (is (= "Value does not match the required graph input type" (:error bad-member)))))


(deftest exposed-callback-captures-match-in-browser-and-jvm
  (let [source {:root {:input {:func [:fn {:a :text} :bool #{}] :coll [:list :text]}
                       :return [:list :bool] :bindings {[:func] {:ref :callback}}}
                :callback {:input {:a :text :b :text} :return :bool}}
        {:keys [analysis context]} (fixture/model source :root)
        functions {:root (get-in @loaded [:map :impl]) :callback (get-in @loaded [:equal? :impl])}
        implementations (-> (fixture/implementations source functions)
                            (assoc-in [(fixture/id :root) :base-fn-id] (ids/fn-id "core.hof" :map))
                            (assoc-in [(fixture/id :callback) :base-fn-id] (ids/fn-id "core.logic" :equal?)))
        options {:context context :snapshot-id (:snapshot-id context)
                 :implementations implementations :allow-fn? (constantly true)}
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        args (fn [capture] {:coll ["Ada" "Bea" "Ada"] :func {:b capture}})
        cases (mapv args ["Ada" "Bea" "Ada"])
        expected [[true false true] [false true false] [true false true]]
        results (vec (run-browser plan (conj cases {:coll ["Ada"]})))]
    (is (= expected (mapv #(invoke % nil) cases)))
    (is (= (mapv #(hash-map :value (wire/encode-result %) :sequence true) expected)
           (pop results)))
    (is (some? (:error (peek results))))))


(deftest browser-and-jvm-route-the-same-record-callback-argument
  (let [item {:value :any :details {:flag :any}}
        source {:root {:input {:func [:fn {:coll item} :any #{}] :coll [:list :any]}
                       :return [:list :any] :bindings {[:func] {:ref :callback}}}
                :callback {:input {:coll item :key :keyword :default :any}
                           :optional #{[:coll :details]} :return :any
                           :bindings {[:key] {:value :value} [:default] {:value "missing"}}}}
        {:keys [analysis context]} (fixture/model source :root)
        functions {:root (get-in @loaded [:map :impl]) :callback (get-in @loaded [:get :impl])}
        implementations (-> (fixture/implementations source functions)
                            (assoc-in [(fixture/id :root) :base-fn-id] (ids/fn-id "core.hof" :map))
                            (assoc-in [(fixture/id :callback) :base-fn-id] (ids/fn-id "core.collections" :get)))
        options {:context context :snapshot-id (:snapshot-id context)
                 :implementations implementations :allow-fn? (constantly true)}
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        valid {:coll [{:value nil} {:value false :details {:flag nil}} {:value "next"}]}
        missing {:coll [{:value nil :details {}}]}
        unknown {:coll [{:value nil :extra false}]}
        expected [nil false "next"]
        [result missing-member unknown-member] (run-browser plan [valid missing unknown])]
    (is (= expected (invoke valid nil)))
    (is (= :missing-required-input (invocation-error invoke missing)))
    (is (= :unknown-input-field (invocation-error invoke unknown)))
    (is (= {:value (wire/encode-result expected) :sequence true} result))
    (is (= "Missing graph input field" (:error missing-member)))
    (is (= "Unknown graph input field" (:error unknown-member)))))


(deftest named-browser-map-input-is-an-opaque-value
  (let [source (-> definitions
                   (assoc-in [:root :input :coll] [:map :keyword :any])
                   (update-in [:root :bindings] select-keys [[:key] [:default]]))
        {:keys [analysis options]} (checked-model source)
        invoke (jvm/compile-named analysis options)
        plan (browser/export-named analysis options)
        expected {:ref "data" :value false}
        args {:coll {:name expected}}
        [result invalid] (run-browser plan [args {:coll {"name" expected}}])]
    (is (= expected (invoke args nil)))
    (is (= {:value (wire/encode-value expected) :sequence false} result))
    (is (= "Value does not match the required graph input type" (:error invalid)))))


(defn- shipped-declaration
  [module name]
  (let [source (edn/read-string (slurp (io/resource (str "packages/core/" module "/fns.edn"))))]
    (assoc (some #(when (= name (:name %)) %) (:fns source)) :namespace (:namespace source))))


(defn- default-graph
  [fallback]
  (let [outer (shipped-declaration "collections" :assoc)
        outer-id (ids/fn-id (:namespace outer) :assoc)
        predicate (shipped-declaration "strings" :str-starts-with?)
        pred-id (ids/fn-id (:namespace predicate) :str-starts-with?)
        input (assoc (:args predicate) :string {:required false :type field-default/nullable-text :default fallback})
        staged {:input-format 2 :namespace (:namespace predicate)
                :fns [{:name (:name predicate) :input input :return-type (:return-type predicate)}]}
        parsed (primitive/prepare staged pred-id)
        union (shipped-declaration "refinements" :nullable-text)
        rows (group-by :kind (into (:declaration-records parsed) (parse/parse-module [union])))
        package (snapshot/package-index (parse/prepare-module [outer] {} {}) [outer])
        ctx (:context (snapshot/freeze-declarations package [outer-id]))
        child (ids/fn-id "test.input" :starts-with-a)
        wrapper (ids/fn-id "test.input" :assoc-result)
        value-row (fn [id base key value]
                    (let [slot (ids/slot-id base key)]
                      {:id (ids/binding-id id slot) :fn-id id :scope-path [] :target-path [slot]
                       :value-present true :expression-kind "value" :value value}))
        target (ids/slot-id outer-id :value)
        bindings [(value-row child pred-id :prefix "A") (value-row wrapper outer-id :map {})
                  (value-row wrapper outer-id :key "answer")
                  {:id (ids/binding-id wrapper target) :fn-id wrapper :scope-path [] :target-path [target]
                   :expression-kind "ref" :ref-fn-id child :value-present false}]
        index (fn [rs] (into {} (map (juxt :id identity)) rs))
        ctx (-> ctx
                (update :fns merge (index (:fn rows)))
                (assoc-in [:fns child] {:id child :parent-ids [pred-id] :input-type-fn-id (:input-type-id parsed)})
                (assoc-in [:fns wrapper] {:id wrapper :parent-ids [outer-id] :input-type-fn-id (get-in ctx [:fns outer-id :input-type-fn-id])})
                (update :fields merge (index (:slot rows)))
                (update :members merge (group-by :fn-id (:fn-slot rows)))
                (update :bindings merge (index bindings)))
        trusted (binding [registry/*rich-types-override* (atom {})
                          types/*type-aliases-override* (atom {:nullable-text field-default/nullable-text})]
                  (into {} (for [[id name] [[pred-id :str-starts-with?] [outer-id :assoc]]
                                 :let [raw (get @loaded name)
                                       registered (registry/record-rich-types! id name raw)]]
                             [id {:impl (:impl raw) :signature (get-in registered [:by-id id])}])))]
    {:context ctx :trusted trusted :child child :wrapper wrapper
     :visible #{outer-id pred-id child wrapper}}))


(deftest browser-defaults-use-checked-public-field-presence
  (doseq [fallback ["Ada" nil]
          :let [{:keys [context trusted child wrapper visible]} (default-graph fallback)]
          [root value wrap] [[child identity identity] [wrapper #(hash-map :value %) #(hash-map "answer" %)]]]
    (let [{:keys [analysis implementations]} (jvm/analyze-stored context root trusted)
          options {:snapshot-id (:snapshot-id context) :context context :implementations implementations
                   :allow-fn? #(contains? visible %)}
          plan (browser/export-named analysis options)
          inputs [{} (value {:string nil}) (value {:string "Ada"}) (value {:string "Bea"})]
          expected (mapv wrap [(some? fallback) false true false])
          invoke (jvm/compile-named analysis options)
          results (vec (run-browser plan (conj inputs (value {:string false}))))]
      (is (= :checked (:status analysis)))
      (is (= expected (mapv #(invoke % nil) inputs)))
      (is (= (mapv wire/encode-value expected) (mapv :value (pop results))))
      (is (= "Value does not match the required graph input type" (:error (peek results))))
      (is (thrown? clojure.lang.ExceptionInfo (invoke (value {:string false}) nil)))
      (is (= :browser-input-validation-required (rejection analysis options)))
      (is (= :browser-visibility-denied
             (try (browser/export-named analysis (assoc options :allow-fn? (constantly false)))
                  nil
                  (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))))


(deftest browser-keeps-nested-record-member-defaults-closed
  (let [source (-> definitions
                   (assoc-in [:root :input :coll :label] field-default/nullable-text)
                   (assoc-in [:root :optional] #{[:coll] [:coll :label]})
                   (update-in [:root :bindings] select-keys [[:key] [:default]])
                   (assoc-in [:root :bindings [:key] :value] :label))
        {:keys [options]} (checked-model source)
        field (peek (fixture/field-path :root [:coll :label]))
        record (fixture/id [:root :type [:coll]])
        ctx (-> (:context options)
                (assoc :snapshot-id (random-uuid))
                (assoc-in [:fns field-default/nullable-text-id] {:constraint field-default/nullable-text})
                (assoc-in [:fields field :type-fn-id] field-default/nullable-text-id)
                (update-in [:members record] (fn [rows]
                                               (mapv #(if (= field (:slot-id %)) (assoc % :input-default {:value "Ada"}) %) rows))))
        analyze #(analysis/analyze-effective-input % (fixture/id :root) {:check-expression check/check-input-binding})
        graph (analyze ctx)
        options (assoc options :context ctx :snapshot-id (:snapshot-id ctx))
        plain (update-in ctx [:members record] #(mapv (fn [row] (dissoc row :input-default)) %))
        invoke (jvm/compile-named graph options)
        siblings {:name "other" :unsafe 3}]
    (is (= :checked (:status graph)))
    (is (nil? ((jvm/compile-named (analyze plain) (assoc options :context plain)) {} nil)))
    (is (= "Ada" (invoke {:coll siblings} nil)))
    (is (nil? (invoke {:coll (assoc siblings :label nil)} nil)))
    (is (= "given" (invoke {:coll (assoc siblings :label "given")} nil)))
    (is (thrown? clojure.lang.ExceptionInfo (invoke {:coll (assoc siblings :label false)} nil)))
    (is (nil? (invoke {} nil)))
    (is (= :missing-required-input
           (try (invoke {:coll {}} nil) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))
    (is (= :browser-field-default-unsupported
           (try (browser/export-named graph options)
                nil
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))


(def ^:private loaded-hiccup (delay (:base-fn-defs (loader/load-packages ["core" "web"]))))


(defn- stored-default-projection
  "A stored child of canonical hiccup projects an authored record into its
   optional attributes. Only the shipped primitive carries native code."
  [selected fallback]
  (let [web-source (edn/read-string (slurp (io/resource "packages/web/html/fns.edn")))
        web-def (fn [name] (assoc (some #(when (= name (:name %)) %) (:fns web-source)) :namespace (:namespace web-source)))
        base (web-def :hiccup)
        base-id (ids/fn-id (:namespace base) :hiccup)
        owner (ids/fn-id "test.input" :render-record)
        source (edn/read-string (slurp (io/resource "packages/nesteddefaultfixture/structured/fns.edn")))
        source (-> source (assoc :namespace "test.input") (assoc-in [:fns 0 :name] :render-record)
                   (assoc-in [:fns 0 :input :left :type :label :default] fallback))
        shape {:id :text :label field-default/nullable-text :hint field-default/nullable-text}
        parsed (primitive/prepare source owner {:left shape :right shape})
        union (shipped-declaration "refinements" :nullable-text)
        rows (group-by :kind (into (:declaration-records parsed) (parse/parse-module [union])))
        declarations [(shipped-declaration "refinements" :keyword-or-text) (web-def :hiccup-node) base]
        package (snapshot/package-index (parse/prepare-module declarations {} {}) declarations)
        ctx (:context (snapshot/freeze-declarations package [base-id]))
        definition {:name :render-record :namespace "test.input" :parent :hiccup
                    :input (get-in source [:fns 0 :input])
                    :bind {:tag [:value "span"] :children [:value []] :attrs [:project [selected]]}}
        input-id (:input-type-id parsed)
        owner-row (assoc (parse/composed-declaration-record definition [base-id] nil) :input-type-fn-id input-id)
        index (fn [rs] (into {} (map (juxt :id identity)) rs))
        ctx (-> ctx
                (update :fns merge (index (:fn rows)))
                (assoc-in [:fns owner] owner-row)
                (update :fields merge (index (:slot rows)))
                (update :members merge (group-by :fn-id (:fn-slot rows))))
        binding-ctx {:fn-id owner :scope [owner []] :input-type-id input-id
                     :contract (:contract (inheritance/declared-view ctx owner))
                     :binding-id (fn [_ path] (ids/binding-id owner (peek path)))
                     :name->id {:hiccup base-id} :origin-ledger {} :declaration-records [owner-row]}
        authored (module/parse-module-v2 {:input-format 2 :namespace "test.input" :fns [definition]}
                                         {:context-for (constantly binding-ctx)})
        ctx (update ctx :bindings merge (index (get-in authored [:definitions 0 :bindings])))
        native (get @loaded-hiccup :hiccup)
        signature (binding [registry/*rich-types-override* (atom {})
                            types/*type-aliases-override* (atom (:aliases ctx))]
                    (get-in (registry/record-rich-types! base-id :hiccup native) [:by-id base-id]))
        {:keys [analysis implementations]} (jvm/analyze-stored ctx owner {base-id {:signature signature :impl (:impl native)}})]
    {:analysis analysis :context ctx :snapshot-id (:snapshot-id ctx) :implementations implementations
     :allow-fn? #(contains? (:fns ctx) %) :owner owner :input (:input-type-id parsed)}))


(deftest browser-checks-defaults-at-their-stored-occurrences
  (doseq [[selected fallback] [[:left "Ada"] [:left nil] [:right "Ada"]]]
    (let [{:keys [analysis] :as options} (stored-default-projection selected fallback)
          invoke (jvm/compile-named analysis options)
          plan (browser/export-named analysis options)
          other (if (= selected :left) :right :left)
          attrs #(cond-> {:id "S" :label %} (= selected :left) (assoc :hint nil))
          defaulted [:span (attrs (if (= selected :right) "Bea" fallback))]
          inputs [{} {selected {:id "S"}} {selected {:id "S" :label nil}} {selected {:id "S" :label "given"}}
                  {other {:id "T"}} {selected {:id "S"} other {:id "T" :label "unrelated"}}
                  {selected {:id "S" :hint "supplied"}}]
          expected [[:span] defaulted [:span (attrs nil)] [:span (attrs "given")]
                    [:span] defaulted [:span (assoc (peek defaulted) :hint "supplied")]]
          invalid [{selected {}} {selected {:id "S" :label false}} {selected {:id "S" :hint false}}]
          results (run-browser plan (into inputs invalid))]
      (is (= :checked (:status analysis)))
      (is (= expected (mapv #(invoke % nil) inputs)))
      (is (= (mapv wire/encode-value expected) (mapv :value (take (count inputs) results))))
      (doseq [[bad result] (map vector invalid (drop (count inputs) results))]
        (is (:error result))
        (is (thrown? clojure.lang.ExceptionInfo (wire/encode-value (invoke bad nil))))))))


(deftest browser-rechecks-occurrence-ownership-and-disclosure
  (let [{:keys [analysis owner input context] :as options} (stored-default-projection :left "Ada")
        left (ids/slot-id owner :left)
        record (get-in context [:fields left :type-fn-id])
        leaf (ids/slot-id record :label)
        change-member (fn [opts type slot f]
                        (update-in opts [:context :members type]
                                   (fn [members] (mapv #(if (= slot (:slot-id %)) (f %) %) members))))
        refusal (fn [opts]
                  (try (browser/export-named analysis opts) nil
                       (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (doseq [id [owner input record field-default/nullable-text-id]]
      (is (= :browser-field-default-unsupported (refusal (assoc options :allow-fn? #(not= id %)))))
      (is (= :browser-field-default-unsupported (refusal (update-in options [:context :fns] dissoc id)))))
    (doseq [opts [(assoc-in options [:context :snapshot-id] (random-uuid))
                  (change-member options input left #(assoc-in % [:input-default :members 0 :value] "different"))
                  (change-member options input left #(assoc-in % [:input-default :members 0 :target-path] [(str left)]))
                  (change-member options input left #(assoc % :id (random-uuid)))
                  (change-member options record leaf #(assoc % :input-default {:value "shared"}))
                  (assoc-in options [:context :fns field-default/nullable-text-id :constraint] :any)
                  (assoc-in options [:context :fields leaf :required] true)]]
      (is (= :browser-field-default-unsupported (refusal opts))))))
