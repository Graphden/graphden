(ns graphden.executor.structural-projection-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.composition.core :as composition]
    [graphden.executor.structural-contracts :as contracts]
    [graphden.executor.structural-projection :as projection]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.types :as record-types]))


(def ^:private namespace-path "projection.fixture")


(defn- interface-definitions
  []
  (into []
        (mapcat (fn [[module path names]]
                  (let [definitions (:fns (edn/read-string
                                            (slurp (io/resource (str "packages/" module "/fns.edn")))))]
                    (map #(assoc % :namespace path)
                         (filter #(contains? names (:name %)) definitions)))))
        [["core/logic" "core.logic" #{:const}]
         ["core/collections" "core.collections" #{:list :get :zipmap :into :merge}]
         ["core/hof" "core.hof" #{:map}]]))


(defn- graph
  [definitions]
  (let [records (concat (ids/boot-primitive-records)
                        (mapcat #(parse/parse-fn-def % {}) (interface-definitions))
                        (mapcat #(record-types/inline-fn-type-rows-from-form
                                   (get-in % [:args :func :type]))
                                (filter #(= :map (:name %)) (interface-definitions)))
                        (composition/fn-defs->records
                          (mapv #(assoc % :namespace namespace-path) definitions)
                          (reduce (fn [result {:keys [name] path :namespace}]
                                    (assoc result name (ids/fn-id path name)
                                           (keyword path (clojure.core/name name)) (ids/fn-id path name)))
                                  (into (ids/primitive-fn-ids)
                                        (map (fn [id] [id id]))
                                        (filter uuid? (tree-seq coll? seq definitions)))
                                  (interface-definitions))
                          (reduce (fn [result {:keys [name] path :namespace :as definition}]
                                    (assoc result name definition (keyword path (clojure.core/name name)) definition))
                                  {} (interface-definitions))))
        grouped (group-by :kind records)]
    {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
     :bindings (:binding grouped) :list-items (:binding-list-item grouped)}))


(defn- definition
  [name parent args]
  {:name name :parent parent :args args})


(defn- fixture
  []
  (let [first-target (random-uuid)
        second-target (random-uuid)
        definitions [(definition :first-id :core.logic/const {:value {:ref first-target :type :fn-ref}})
                     (definition :second-id :core.logic/const {:value {:ref second-target :type :fn-ref}})
                     (definition :first-descriptor :core.collections/zipmap
                       {:keys [{:value :component-id} {:value :entries}] :vals [:first-id {:value [:initial]}]})
                     (definition :second-descriptor :core.collections/zipmap
                       {:keys [{:value :component-id} {:value :entries}] :vals [:second-id {:value [:view]}]})
                     (definition :manifest :core.collections/list {:items [:first-descriptor :second-descriptor]})
                     (definition :descriptor-key :core.collections/get
                       {:coll {:as :item} :key {:value :component-id} :default nil})
                     (definition :descriptor-value :core.collections/get
                       {:coll {:as :item} :key {:value :entries} :default nil})
                     (definition :descriptor-pair :core.collections/list {:items [:descriptor-key :descriptor-value]})
                     (definition :pairs :core.hof/map {:func :descriptor-pair :coll :manifest})
                     (definition :indexed :core.collections/into {:to {:value {}} :from :pairs})
                     (definition :override :core.collections/zipmap {:keys [:first-id] :vals [{:value [:update]}]})
                     (definition :editor :core.collections/merge {:maps [:indexed :override]})]]
    {:first-target first-target :second-target second-target :definitions definitions}))


(defn- literal?
  [value]
  (or (nil? value) (= {} value)
      (contains? #{:component-id :entries :initial :update :view} value)
      (and (vector? value) (every? #{:component-id :entries :initial :update :view} value))))


(defn- project
  [definitions name]
  (projection/project (graph definitions) (ids/fn-id namespace-path name) literal?))


(deftest callback-frames-retain-each-descriptors-identity-test
  (let [{:keys [first-target second-target definitions]} (fixture)
        result (project definitions :indexed)]
    (is (= {first-target [:initial] second-target [:view]}
           (into {} (map (fn [[key value]] [(:id key) value])) result)))
    (is (every? projection/identity-value? (keys result)))))


(deftest explicit-merge-preserves-identities-and-overrides-only-later-key-test
  (let [{:keys [first-target second-target definitions]} (fixture)
        result (project definitions :editor)]
    (is (= {first-target [:update] second-target [:view]}
           (into {} (map (fn [[key value]] [(:id key) value])) result)))))


(defn- refusal
  [definitions name]
  (try (project definitions name)
       (catch clojure.lang.ExceptionInfo error
         (or (:reason (ex-data error)) (:type (ex-data error))))))


(deftest literal-identities-and-implicit-duplicate-construction-refuse-test
  (let [{:keys [definitions]} (fixture)
        literal (assoc-in definitions [0 :args :value] {:value (random-uuid)})
        duplicate (assoc-in definitions [4 :args :items] [:first-descriptor :first-descriptor])]
    (is (= :invalid-configuration (refusal literal :editor)))
    (is (= :invalid-configuration (refusal duplicate :indexed)))))


(deftest structural-work-remains-bounded-before-materialization-test
  (let [oversized [(definition :oversized :core.collections/list
                     {:items (vec (repeat 65 {:value :view}))})]
        ;; The authoring parser already refuses cycles. Build a cycle in the
        ;; immutable rows instead to exercise projection's own source guard.
        cyclic (update (graph [(definition :first :core.logic/const {:value :second})
                               (definition :second :core.logic/const {:value {:value :view}})])
                       :bindings
                       (fn [rows]
                         (mapv (fn [row]
                                 (if (= (ids/fn-id namespace-path :second) (:fn-id row))
                                   (-> row (dissoc :value :value-present)
                                       (assoc :ref-fn-id (ids/fn-id namespace-path :first)))
                                   row)) rows)))
        deep (into [(definition :leaf :core.logic/const {:value {:value :view}})]
                   (map (fn [index]
                          (definition (keyword (str "layer-" index)) :core.logic/const
                            {:value (if (zero? index) :leaf (keyword (str "layer-" (dec index))))})))
                   (range 65))]
    (is (= :invalid-configuration (refusal oversized :oversized)))
    (is (= :invalid-configuration
           (try (projection/project cyclic (ids/fn-id namespace-path :first) literal?)
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
    (is (= :invalid-configuration (refusal deep :layer-64)))))


(deftest structural-operation-contracts-refuse-persisted-declaration-drift-test
  (let [source (graph [])
        operation (ids/fn-id "core.collections" :into)
        changed-return (update source :fns
                               #(mapv (fn [row]
                                        (if (= operation (:id row))
                                          (assoc row :return-type-fn-id ids/fn-ref-type-id) row)) %))
        changed-slot (update source :slots
                             #(mapv (fn [row]
                                      (if (= (ids/slot-id operation :from) (:id row))
                                        (assoc row :type-fn-id ids/fn-ref-type-id) row)) %))
        own-binding (update source :bindings conj
                            {:id (random-uuid) :fn-id operation
                             :slot-id (ids/slot-id operation :to) :value {}})]
    (is (contracts/matches? operation source))
    (is (contracts/matches? (ids/fn-id "core.collections" :merge) source))
    (is (not (contracts/matches? operation changed-return)))
    (is (not (contracts/matches? operation changed-slot)))
    (is (not (contracts/matches? operation own-binding)))))
