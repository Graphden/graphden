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
                   (assoc-in [:root :optional] #{[:coll :label]}))
        {:keys [options]} (checked-model source)
        field (peek (fixture/field-path :root [:coll :label]))
        record (fixture/id [:root :type [:coll]])
        ctx (-> (:context options)
                (assoc :snapshot-id (random-uuid))
                (assoc-in [:fns field-default/nullable-text-id] {:constraint field-default/nullable-text})
                (assoc-in [:fields field :type-fn-id] field-default/nullable-text-id)
                (update-in [:members record] (fn [rows]
                                               (mapv #(if (= field (:slot-id %)) (assoc % :input-default {:value "Ada"}) %) rows))))
        graph (analysis/analyze-effective-input ctx (fixture/id :root) {:check-expression check/check-input-binding})]
    (is (= :checked (:status graph)))
    (is (= :browser-field-default-unsupported
           (try (browser/export-named graph (assoc options :context ctx :snapshot-id (:snapshot-id ctx)))
                nil
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))
