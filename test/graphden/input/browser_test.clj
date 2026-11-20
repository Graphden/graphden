(ns graphden.input.browser-test
  (:require
    [cheshire.core :as json]
    [clojure.java.shell :as sh]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.browser-plan :as wire]
    [graphden.input.browser :as browser]
    [graphden.input.compile :as jvm]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]))


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
  [plan]
  (let [result (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                      :in (json/generate-string {:plan plan :cases [{:entry "root" :inputs {}}]}))]
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
           (run-browser plan)))))


(deftest browser-lowers-the-same-checked-nested-input-as-jvm
  (agrees-with-browser definitions "Ada"))


(deftest browser-omits-unconfigured-empty-record-fields
  (agrees-with-browser (-> definitions
                           (assoc-in [:root :input :coll :empty] {})
                           (assoc-in [:root :optional] #{[:coll :empty]})
                           (assoc-in [:root :bindings [:key] :value] :empty)
                           (assoc-in [:root :bindings [:default] :value] "missing"))
                       "missing"))


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
