(ns ^:serial graphden.input.lesson-capture-test
  "Lesson 47 diagnostics: saved configuration, external input and exact capture routes."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.structured-input-bind :as bind]
    [graphden.executor.registry.core :as registry]
    [graphden.input.analysis :as analysis]
    [graphden.input.compile :as compile]
    [graphden.input.context :as context]
    [graphden.input.source-primitive :as primitive]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.test-infra.lesson-compute :as lesson]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(use-fixtures :each
  (fn [run-test]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              types/*type-aliases-override* (atom {})]
      (run-test))))


(def ^:private native
  (delay (:base-fn-defs (loader/load-packages ["lesson-compute"] {:primitive-inputs lesson/admissions}))))


(def ^:private callback-base (ids/fn-id "lesson-compute.callback" :decorate-greeting-input))


(defn- source-module
  [module]
  (edn/read-string (slurp (io/resource (str "packages/lesson-compute/" module "/fns.edn")))))


(defn- field
  [contract name]
  (some #(when (= name (:name %)) %) (:fields contract)))


(defn- type-bodies
  [node]
  (into {(:type-id node) (:type node)} (mapcat type-bodies (:fields node))))


(defn- fixture
  "The outer-mark candidate is in-memory diagnostic data, never package admission."
  [{:keys [free-mark? outer-mark? projection?]}]
  (let [source (cond-> (source-module "structured")
                 outer-mark? (assoc-in [:fns 0 :input :style :type :mark] :text))
        style-type (cond-> {:suffix :text :callback [:fn {:string :text} :text #{}]}
                     outer-mark? (assoc :mark :text))
        root (primitive/prepare source lesson/root {:profile {:name :text} :style style-type})
        builder (primitive/prepare (source-module "builder") lesson/builder {:profile {:name :text}})
        callback (primitive/prepare (source-module "callback") callback-base)
        child (second (primitive/prepare-literal-root (source-module "callback") lesson/callback
                                                      #{callback-base lesson/callback}))
        parsed [root builder callback child]
        grouped (group-by :kind (distinct (mapcat #(concat (:declaration-records %) (:bindings %)) parsed)))
        style (field (:contract root) :style)
        profile (field (:contract root) :profile)
        callable (field style :callback)
        outer-mark (field style :mark)
        mark (field (:contract callback) :mark)
        edge (ids/binding-id lesson/root (:id callable))
        address [[lesson/root []] [(:id style) (:id callable)]]
        origins (into {} (map (juxt :id identity)) (:input-binding grouped))
        bindings (cond-> origins
                   free-mark? (dissoc (ids/binding-id lesson/callback (:id mark))))
        links {edge {:id edge :fn-id lesson/root :scope-path [] :target-path (second address)
                     :expression-kind "ref" :ref-fn-id lesson/callback}
               (ids/binding-id lesson/root (:id profile))
               {:id (ids/binding-id lesson/root (:id profile)) :fn-id lesson/root :scope-path []
                :target-path [(:id profile)] :expression-kind "ref" :ref-fn-id lesson/builder}}
        projection-id (ids/binding-id lesson/root (:id mark))
        bindings (cond-> (merge bindings links)
                   projection? (assoc projection-id {:id projection-id :fn-id lesson/root :scope-path [edge]
                                                     :target-path [(:id mark)] :expression-kind "projection"
                                                     :projection-scope-path []
                                                     :projection-path [(:id style) (:id outer-mark)]}))
        implementations (into {}
                              (for [[owner base implementation] [[root root :deliver-greeting]
                                                                 [builder builder :build-greeting-record]
                                                                 [callback callback :decorate-greeting]
                                                                 [child callback :decorate-greeting]]]
                                [(:fn-id owner) {:base-fn-id (:fn-id base) :impl (get-in @native [implementation :impl])
                                                 :root-entry {:args (get-in base [:contract :type])
                                                              :return (get-in base [:source :return-type])}
                                                 :argument-paths (into {} (map #(vector (:name %) [(:id %)]))
                                                                       (get-in base [:contract :fields]))}]))
        frozen (context/build-context
                 {:fns (into {} (map (juxt :id identity)) (:fn grouped))
                  :fields (into {} (map (juxt :id identity)) (:slot grouped))
                  :members (group-by :fn-id (:fn-slot grouped))
                  :type-bodies (into {} (mapcat #(type-bodies (:contract %))) parsed)
                  :signatures (into {} (map #(vector (:fn-id %) {:args (get-in % [:contract :type])
                                                                 :return (or (get-in % [:source :return-type]) :text)
                                                                 :effects #{}})) parsed)
                  :bindings bindings})
        checked (analysis/analyze-effective-input frozen lesson/root
                                                  {:check-expression check/check-input-binding})]
    {:context frozen :analysis checked :implementations implementations :address address :edge edge
     :mark (:id mark) :outer-mark (:id outer-mark) :style (:id style)}))


(defn- compile-fixture
  [{:keys [analysis context implementations]}]
  (compile/compile-named analysis {:context context :implementations implementations}))


(defn- rejection
  [operation]
  (try (operation) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn- arguments
  [mark]
  {:profile {:profile {:name "Ada"}} :style {:suffix "team" :mark mark}})


(deftest the-published-lesson-has-saved-configuration-without-a-capture
  (let [{:keys [analysis address] :as sample} (fixture {})
        plan (get-in analysis [:closures address])
        invoke (compile-fixture sample)]
    (is (= :checked (:status analysis)))
    (is (= [:string] (:parameters plan)))
    (is (= [] (:captures plan) (:external-captures plan)))
    (is (= "Hello Ada! · team" (invoke (update (arguments "?") :style dissoc :mark) {})))))


(deftest freeing-the-callback-mark-does-not-invent-a-lexical-source
  (let [{:keys [analysis address edge mark] :as sample} (fixture {:free-mark? true})
        plan (get-in analysis [:closures address])
        refused (rejection #(compile-fixture sample))]
    (is (= :checked (:status analysis)))
    (is (= [:string] (:parameters plan)))
    (is (= [] (:captures plan)))
    (is (= [{:target [[lesson/root [edge]] [mark]] :value-path []}] (:external-captures plan)))
    (is (= :unsupported-closure-scope (:code refused)))
    (is (= address (:address refused)))))


(deftest a-same-name-outer-field-is-not-the-callback-mark-origin
  (let [{:keys [analysis address edge mark outer-mark style] :as sample}
        (fixture {:free-mark? true :outer-mark? true})
        plan (get-in analysis [:closures address])]
    (is (not= mark outer-mark))
    (is (= [] (:captures plan)))
    (is (= [{:target [[lesson/root [edge]] [mark]] :value-path []}] (:external-captures plan)))
    (is (contains? (set (:required-addresses analysis)) [[lesson/root []] [style outer-mark]]))
    (is (= :unsupported-closure-scope (:code (rejection #(compile-fixture sample)))))))


(deftest an-explicit-projection-proves-the-runtime-connection-but-not-package-admission
  (let [{:keys [analysis address edge mark outer-mark style] :as sample}
        (fixture {:free-mark? true :outer-mark? true :projection? true})
        invoke (compile-fixture sample)
        plan (get-in analysis [:closures address])
        expression (get-in analysis [:scopes [edge] :bindings [[lesson/root [edge]] [mark]] :expression])]
    (is (= :checked (:status analysis)))
    (is (= [] (:external-captures plan)))
    (is (= [:string] (:parameters plan)))
    (is (= {:kind :projection :scope [lesson/root []] :path [style outer-mark]} expression))
    (is (= "Hello Ada! · team" (invoke (arguments "!") {})))
    (is (= "Hello Ada? · team" (invoke (arguments "?") {})))
    (is (= "Hello Ada! · team" (invoke (arguments "!") {})))))


(deftest the-existing-editor-and-pinned-package-do-not-admit-that-projection
  (let [command {:fn-id lesson/root :path ["style" "callback" "mark"]
                 :projection-path ["style" "mark"]}
        source (assoc-in (source-module "callback") [:fns 1 :bind :mark] [:project [:style :mark]])]
    ;; No storage is supplied: a projection command must refuse before looking
    ;; up or writing an owner. The existing lesson cannot ask a user to create it.
    (is (= :input/invalid-command (:type (rejection #(bind/preview {} command)))))
    (is (= :staged-literal-binding-unsupported
           (:code (rejection #(primitive/prepare-literal-root source lesson/callback
                                                              #{callback-base lesson/callback})))))))
