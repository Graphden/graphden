(ns graphden.executor.structural-projection-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-config :as component-config]
    [graphden.editor.config-resolution :as config-resolution]
    [graphden.executor.composition.core :as composition]
    [graphden.executor.source-contract :as source-contract]
    [graphden.executor.structural-contracts :as contracts]
    [graphden.executor.structural-projection :as projection]
    [graphden.graph.metadata :as metadata]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.types :as record-types]
    [graphden.storage.remote.core :as remote]))


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
         ["core/hof" "core.hof" #{:map}]
         ["web/graph-metadata" "web.graph-metadata" #{:find-function}]]))


(defn- graph
  ([definitions] (graph definitions namespace-path))
  ([definitions path]
   (let [records (concat (ids/boot-primitive-records)
                         (mapcat #(parse/parse-fn-def % {}) (interface-definitions))
                         (mapcat #(record-types/inline-fn-type-rows-from-form
                                    (get-in % [:args :func :type]))
                                 (filter #(= :map (:name %)) (interface-definitions)))
                         (composition/fn-defs->records
                           (mapv #(assoc % :namespace path) definitions)
                           (reduce (fn [result {:keys [name] path :namespace}]
                                     (assoc result name (ids/fn-id path name)
                                            (keyword path (clojure.core/name name)) (ids/fn-id path name)))
                                   (into (ids/primitive-fn-ids)
                                         (mapcat (fn [value]
                                                   (cond
                                                     (uuid? value) [[value value]]
                                                     (and (keyword? value) (namespace value))
                                                     [[value (ids/fn-id (namespace value) (name value))]
                                                      [(keyword (name value)) (ids/fn-id (namespace value) (name value))]]
                                                     :else [])))
                                         (tree-seq coll? seq definitions))
                                   (interface-definitions))
                           (reduce (fn [result {:keys [name] path :namespace :as definition}]
                                     (assoc result name definition (keyword path (clojure.core/name name)) definition))
                                   {} (interface-definitions))))
         grouped (group-by :kind records)]
     {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
      :bindings (:binding grouped) :list-items (:binding-list-item grouped)})))


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


(deftest packaged-editor-defaults-reference-all-shared-components-without-copying-bodies
  (let [{:keys [fns] package-path :namespace} (edn/read-string
                                                (slurp (io/resource "packages/app/ui-config/fns.edn")))
        source (graph fns package-path)
        actual (component-config/configuration source (ids/fn-id package-path :editor-defaults))
        contracts {:account-menu ["app.ui-account-menu" "account-menu" #{:initial :update :view}]
                   :fn-picker ["app.ui-fn-picker" "picker" #{:view}]
                   :recents ["app.ui-recents" "recents" #{:initial :update :view}]
                   :inspector ["app.ui-inspector" "inspector" #{:initial :update :view}]
                   :explorer ["app.ui-explorer" "explorer-toolbar" #{:initial :update :view}]}]
    (is (= (set (keys contracts)) (set (keys actual))))
    (doseq [[component [path prefix roles]] contracts]
      (is (= (into {} (map (fn [role]
                             [role (ids/fn-id path (str prefix "-" (name role)))])) roles)
             (get actual component))))
    (is (not-any? #(contains? (set (mapcat vals (vals actual))) (:id %)) (:fns source)))))


(deftest protected-resolver-contract-refuses-source-and-binding-drift
  (let [{:keys [fns] package-path :namespace} (edn/read-string
                                                (slurp (io/resource "packages/app/ui-config/fns.edn")))
        source (graph fns package-path)
        owners (set (map #(ids/fn-id package-path %) [:editor-resolver :_editor-discovery :_editor-defaults-id]))
        id (ids/fn-id package-path :editor-resolver)
        resolver (ids/fn-id package-path :editor-resolver)
        binding (first (filter #(and (= id (:fn-id %)) (not (:value-present %))) (:bindings source)))
        changed-binding (fn [key value]
                          (update source :bindings
                                  #(mapv (fn [row]
                                           (if (= (:id binding) (:id row)) (assoc row key value) row)) %)))]
    (is (some? binding) "The protected resolver has a structural reference binding to guard")
    (is (source-contract/matches? source source owners))
    (is (source-contract/matches?
          source (update source :fns #(mapv (fn [row] (assoc row :description "Edited documentation")) %)) owners))
    (doseq [[key value] [[:ref-fn-id (random-uuid)] [:type-override-fn-id (random-uuid)]
                         [:resolver-fn-id (random-uuid)] [:terminal true] [:value-present true]]]
      (is (not (source-contract/matches? source (changed-binding key value) owners))))
    (is (not (source-contract/matches?
               source (update source :fns
                              #(mapv (fn [row]
                                       (if (= resolver (:id row)) (assoc row :parent-ids [(random-uuid)]) row)) %))
               owners)))
    (is (not (source-contract/matches?
               source (update source :bindings conj
                              (assoc binding :id (random-uuid) :slot-id (random-uuid))) owners)))
    (is (not (source-contract/matches? source (update source :fns #(remove (fn [row] (= id (:id row))) %)) owners)))))


(deftest packaged-resolver-requires-closed-public-package-source-before-discovery
  (let [{:keys [fns] package-path :namespace} (edn/read-string
                                                (slurp (io/resource "packages/app/ui-config/fns.edn")))
        app-id (random-uuid)
        config-id (random-uuid)
        namespaces [{:id app-id :name "app" :parent-id nil}
                    {:id config-id :name "ui-config" :parent-id app-id}]
        source (update (graph fns package-path) :fns
                       #(mapv (fn [row]
                                (if (= package-path (:namespace-id row)) (assoc row :namespace-id config-id) row)) %))
        snapshot {:graph source :namespaces namespaces}
        scope {:namespace-path nil :name "editor" :principal-id "operator"
               :org "public" :branch-id (random-uuid)}
        capability (metadata/function-lookup (remote/from-bundle {}) (fn [& _]) scope)
        owners (set (map #(ids/fn-id package-path %) [:editor-resolver :_editor-discovery :_editor-defaults-id]))]
    (with-redefs [owned/owned-fn-id? owners]
      (is (= config-resolution/defaults-id (:id (config-resolution/resolve-identity snapshot scope capability))))
      (is (thrown? clojure.lang.ExceptionInfo
            (config-resolution/resolve-identity
              (assoc snapshot :namespaces [(assoc (first namespaces) :parent-id config-id) (second namespaces)])
              scope capability)))
      (is (thrown? clojure.lang.ExceptionInfo
            (config-resolution/resolve-identity
              (assoc snapshot :namespaces [(assoc (first namespaces) :org-id "foreign") (second namespaces)])
              scope capability)))
      (is (thrown? clojure.lang.ExceptionInfo
            (config-resolution/resolve-identity
              (update-in snapshot [:graph :bindings] conj
                         {:id (random-uuid) :fn-id config-resolution/resolver-id :slot-id (random-uuid) :value "poison"})
              scope capability))))
    (with-redefs [owned/owned-fn-id? (constantly false)]
      (is (thrown? clojure.lang.ExceptionInfo
            (config-resolution/resolve-identity snapshot scope capability))))))


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


(deftest protected-resolver-discovers-only-exact-authorized-identity
  (let [namespace-id (random-uuid)
        selected-id (random-uuid)
        default-id (random-uuid)
        scope {:namespace-path "personal" :name "editor" :principal-id "operator"
               :org "public" :branch-id (random-uuid)}
        definitions [(definition :lookup :web.graph-metadata/find-function
                       {:namespace-path {:as :namespace-path :type [:union :null :text]}
                        :name "editor" :principal-id {:as :principal-id :type :text}
                        :org {:as :org :type :text} :branch-id {:as :branch-id :type :uuid}})
                     (definition :default :core.logic/const {:value {:ref default-id :type :fn-ref}})
                     (definition :resolver :core.collections/get
                       {:coll :lookup :key {:value :fn-id} :default :default})]
        source (graph definitions)
        root (ids/fn-id namespace-path :resolver)
        storage (remote/from-bundle
                  {:ns [{:id namespace-id :name "personal" :parent-id nil :org-id "public"}]
                   :fn [{:id selected-id :namespace-id namespace-id :name "editor" :org-id "public"}]})
        capability (metadata/function-lookup storage (fn [& _]) scope)
        result (projection/project-resolver source root (constantly true) scope capability)]
    (is (= selected-id (:id result)))
    (is (= :authorized-discovery (:origin result)))
    (is (projection/identity-value? result))
    (is (= default-id
           (:id (projection/project-resolver source root (constantly true) scope
                                             (metadata/function-lookup (remote/from-bundle {}) (fn [& _]) scope)))))
    (is (thrown? clojure.lang.ExceptionInfo (projection/project source root (constantly true))))
    (is (thrown? clojure.lang.ExceptionInfo
          (projection/project-resolver source root (constantly true) (assoc scope :principal-id "foreign") capability)))
    (is (thrown? clojure.lang.ExceptionInfo
          (projection/project-resolver source root (constantly true) scope
                                       (metadata/function-lookup storage
                                                                 (fn [& _] (throw (ex-info "Denied" {:type :authz/forbidden})))
                                                                 scope))))))
