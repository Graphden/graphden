(ns graphden.input.ir-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.arguments :as arguments]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.input.ir :as ir]))


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
