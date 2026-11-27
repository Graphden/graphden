(ns graphden.input.source-reference-alias-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.runtime :as runtime]
    [graphden.input.source :as source]
    [graphden.input.source-package-fixtures :as fixtures]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- definitions
  []
  (vec (concat (fixtures/declarations "packages/core/collections/fns.edn" #{:assoc :assoc-fn :assoc-empty})
               (fixtures/declarations "packages/app-base/common/fns.edn" #{:method-map :assoc-handler}))))


(deftest occupied-reference-keeps-the-public-reader-and-the-dormant-identity
  (let [defs (definitions)
        original (some #(when (= :method-map (:name %)) %) defs)
        context (:prepared (snapshot/package-index (parse/prepare-module defs {} {}) defs))
        verify #(first (:definitions (source/verify-module {:definitions [%]} context)))
        entry (verify (first (:definitions (source/plan-module [original] context))))
        owner (get-in context [:name->id :method-map])
        callee (get-in context [:name->id :assoc-handler])
        outer (ids/slot-id owner :handler)
        inner (ids/slot-id callee :handler)
        destination (ids/slot-id (get-in context [:name->id :assoc]) :value)
        ref-edge (ids/binding-id owner destination)
        origin (get-in entry [:v2 :origin-ledger :mapping :occupied-reference-aliases outer])]
    (is (= :authored-source-equivalent (:verification entry)) (pr-str (:blockers entry)))
    (is (= {:value [:ref :app.common/assoc-handler] :key [:project [:method]]} (get-in entry [:proposed :bind])))
    (is (= [[owner [ref-edge]] [inner]] (:reader-address origin)))
    (is (= [[owner []] [destination]] (:occupied-address origin)))
    (is (= [outer destination 0] [(get-in origin [:alias-slot :id]) (get-in origin [:alias-slot :source-slot-id])
                                  (get-in origin [:membership :position])]))
    (is (not-any? #(= outer (:id %)) (get-in entry [:v2 :contract :fields])))
    (is (= :blocked (:status (verify (assoc-in entry [:legacy :occupied-reference-alias :reader-address] [[owner []] [outer]])))))
    (is (= :blocked (:status (verify (assoc-in entry [:proposed :bind :value] [:ref :assoc-empty])))))
    (let [changed (assoc-in original [:args :value :as] :different)
          changed-defs (mapv #(if (= original %) changed %) defs)
          ctx (:prepared (snapshot/package-index (parse/prepare-module changed-defs {} {}) changed-defs))]
      (is (= :blocked (:status (first (:definitions (source/plan-module [changed] ctx)))))))))


(deftest a-persisted-outer-alias-override-has-a-different-result-from-the-authored-inner-binding
  (let [derived {:namespace "fixture" :name :derived :parent :method-map :args {:handler {:value "override"}}}
        prepared (parse/prepare-module (conj (definitions) derived) {} {})
        rows (group-by :kind (parse/parse-prepared-module prepared))
        owner (get-in prepared [:name->id :method-map])
        child (get-in prepared [:name->id :derived])
        outer (ids/slot-id owner :handler)
        reroute #(if (= child (:fn-id %)) (assoc % :slot-id outer :id (ids/binding-id child outer)) %)
        assoc-impl (fn [args _]
                     (assoc (runtime/resolve-arg args :map) (runtime/resolve-arg args :key)
                            (runtime/resolve-arg args :value)))
        compile-rows (fn [bindings]
                       (let [index (lookups/build-lookups {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
                                                           :bindings bindings :list-items []})]
                         (eager/compile-all (assoc index :base-fns {:assoc assoc-impl}))))
        compiled (compile-rows (:binding rows))
        direct (compile-rows (mapv reroute (:binding rows)))
        handler (fn [request] [:called request])]
    (is (= [:called :request] ((get-in ((compiled owner) {:method "get" :handler handler} {}) ["get" "handler"]) :request)))
    (is (= {"get" {"handler" "override"}} ((compiled child) {:method "get"} {})))
    (is (= {"get" "override"} ((direct child) {:method "get"} {})))))
