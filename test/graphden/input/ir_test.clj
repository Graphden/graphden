(ns graphden.input.ir-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.input.ir :as ir]))


(def definitions
  {:root {:input {:user {:name :text :unused :text} :result :text}
          :return :text :bindings {[:user :name] {:value "Ada"} [:result] {:ref :child :id :use}}}
   :child {:input {:x :text} :return :text}})


(defn- structural-model
  ([] (structural-model definitions))
  ([definitions]
   ;; A deliberately explicit checker seam for pure BB lowering tests. These
   ;; assertions prove routing, not typing; compile-test uses the real checker.
   (fixture/model definitions :root
                  (fn [{:keys [substitution]}]
                    {:status :checked :substitution substitution}))))


(defn- lower
  ([model] (lower model definitions))
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


(deftest special-base-semantics-and-missing-abi-provenance-reject
  (let [{:keys [analysis context]} (structural-model)
        impls (fixture/implementations definitions {:root (fn [_ _]) :child (fn [_ _])})
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
