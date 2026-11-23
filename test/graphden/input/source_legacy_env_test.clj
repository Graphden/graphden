(ns graphden.input.source-legacy-env-test
  "Migration boundary for legacy env bindings that feed same-name root inputs."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.registry.core :as registry]
    [graphden.input.migration :as migration]
    [graphden.input.source-package-fixtures :as fixtures]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.sync :as sync]
    [graphden.types.core :as types]))


(def ^:private loaded-core (delay (loader/load-packages ["core"])))


(def ^:private env-slot (ids/slot-id (ids/fn-id "core.logic" :const) :value))


(def ^:private reader-slot (ids/slot-id (ids/fn-id "core.logic" :is-a?) :value))


(def ^:private inline-owner #uuid "668ff893-ec30-51dc-aa1c-bd668239a546")


(defn- prepared-graph
  [extra]
  (let [declarations (vec (concat (fixtures/declarations "packages/core/logic/fns.edn" #{:const :is-a? :if :not})
                                  (fixtures/declarations "packages/core/strings/fns.edn" #{:blank?})
                                  (fixtures/declarations "packages/core/refinements/fns.edn" #{:nullable-text})
                                  (fixtures/declarations "packages/app/branches/fns.edn" #{:_branch-ref-input :_branch-ref-named?})
                                  extra))
        prepared (parse/prepare-module declarations {} {})
        rows (group-by :kind (parse/parse-prepared-module prepared))]
    {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
     :bindings (:binding rows) :list-items (:binding-list-item rows)}))


(defn- compile-graph
  [graph]
  (let [{:keys [fn-defs base-fn-defs]} @loaded-core]
    (sync/register-type-aliases! fn-defs)
    (doseq [[name {module-ns :namespace :as definition}] base-fn-defs]
      (registry/record-rich-types! (ids/fn-id module-ns name) name definition))
    (eager/compile-all (assoc (lookups/build-lookups graph)
                              :base-fns (registry/compute-base-fns-map base-fn-defs)))))


(defn- remap-root
  "Deliberately unsafe candidate: keep the UUID but replace its target slot."
  [graph owner]
  (update graph :bindings
          #(mapv (fn [row]
                   (if (and (= owner (:fn-id row)) (= env-slot (:slot-id row)))
                     (assoc row :slot-id reader-slot)
                     row)) %)))


(deftest actual-branch-ref-input-has-an-env-origin-and-a-distinct-free-reader
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
    (let [graph (prepared-graph [])
          index (lookups/build-lookups graph)
          direct (bindings/collect-bindings inline-owner index)
          [environment] (bindings/collect-env-bindings inline-owner index)
          origin (some #(when (= (ids/binding-id inline-owner env-slot) (:id %)) %) (:bindings graph))
          mapping {:mapping (migration/identity-mapping {} [origin])}]
      (is (= #uuid "c8c410ff-d87b-54bd-9de0-aa2e588895fe" (:id origin)))
      (is (= [:free :value] (mapv :kind direct)))
      (is (= reader-slot (:slot-id (first direct))))
      (is (= {:kind :ref :env-name :value :slot-id env-slot
              :type-override-fn-id nil :ref-id (ids/fn-id "app.branches" :_branch-ref-input)
              :is-fn false :produces-callable? false}
             environment))
      (is (= [env-slot reader-slot]
             (lookups/env-binding-route-ids inline-owner environment direct
                                            (lookups/fn-owned-slot-ids inline-owner index) index)))
      (is (= (:id origin) (migration/binding-origin mapping [inline-owner []] [env-slot])))
      (is (nil? (migration/binding-origin mapping [inline-owner []] [reader-slot]))))))


(deftest actual-branch-ref-runtime-shadows-both-the-public-name-and-reader-uuid
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [compiled (compile-graph (prepared-graph []))
          owner (ids/fn-id "app.branches" :_branch-ref-named?)
          invoke #((compiled owner) % {})]
      (doseq [[arguments expected] [[{} false] [{:ref nil} false] [{:ref ""} false]
                                    [{:ref "main"} true] [{:value "direct"} false]
                                    [{:ref nil :value "direct"} false]
                                    [{:ref nil reader-slot "direct" env-slot "direct"} false]
                                    [{:ref "main" :value false reader-slot false env-slot false} true]]]
        (is (= expected (invoke arguments)) (pr-str arguments))))))


(deftest preserving-the-env-binding-uuid-does-not-prove-an-inherited-root-remap
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [host (ids/fn-id "fixture" :host)
          derived (ids/fn-id "fixture" :derived)
          graph (prepared-graph [{:namespace "fixture" :name :host :parent :core.logic/is-a?
                                  :args {:value :app.branches/_branch-ref-input :type {:value :text}}}
                                 {:namespace "fixture" :name :derived :parent :fixture/host
                                  :args {:value {:value false}}}])
          remapped (remap-root graph host)
          original-binding (some #(when (= (ids/binding-id host env-slot) (:id %)) %) (:bindings graph))
          candidate-binding (some #(when (= (:id original-binding) (:id %)) %) (:bindings remapped))
          child-binding (some #(when (= derived (:fn-id %)) %) (:bindings graph))
          before (compile-graph graph)
          after (compile-graph remapped)]
      (testing "The exact original row identity survives this unsafe candidate"
        (is (= env-slot (:slot-id original-binding)))
        (is (= reader-slot (:slot-id candidate-binding)))
        (is (= (dissoc original-binding :slot-id) (dissoc candidate-binding :slot-id)))
        (is (= env-slot (:slot-id child-binding)))
        (is (= false (:value child-binding))))
      (testing "Standalone positive and negative samples cannot prove inheritance"
        (is (= true ((before host) {:ref "main"} {}) ((after host) {:ref "main"} {})))
        (is (= false ((before host) {:value "direct"} {}) ((after host) {:value "direct"} {}))))
      (testing "The inherited env literal wins only before the root remap"
        (is (= false ((before derived) {:ref "main"} {})))
        (is (= true ((after derived) {:ref "main"} {})))))))
