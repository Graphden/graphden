(ns graphden.editor.component-bundle
  "Pure parsing of the fixed personal UI template bundle. Existing tenant
   definitions and their literals are never a parser input."
  (:require
    [clojure.set :as set]
    [graphden.editor.component-templates :as templates]
    [graphden.executor.composition.core :as composition]
    [graphden.packages.records.ids :as ids]
    [graphden.tenancy.context :as tenancy]))


(defn- indexed-interfaces
  [definitions value-fn]
  (reduce (fn [index {:keys [name] fn-namespace :namespace :as definition}]
            (let [value (value-fn definition)]
              (assoc index name value (keyword fn-namespace (clojure.core/name name)) value)))
          {} definitions))


(defn parse
  "Parse every new body against fixed installed operation/type interfaces.
   Canonical public inline types keep their boot identities; ordinary named
   copies derive identities from the fresh personal namespace path."
  ([root] (parse root nil nil nil {}))
  ([root group retained legacy retained-names]
   (let [definitions (if group (templates/selected-definitions root group retained legacy) (templates/definitions root))
         interfaces (templates/interface-definitions)
         fixed-names (merge (ids/primitive-fn-ids)
                            (indexed-interfaces interfaces #(ids/fn-id (:namespace %) (:name %))))
         ;; The module parser normalizes qualified refs to bare names before
         ;; parsing list items. Dual-key these fresh UUID aliases just like
         ;; shipped interfaces; neither spelling is a persisted function.
         retained-names (into retained-names
                              (map (fn [[reference id]] [(keyword (name reference)) id]))
                              retained-names)
         _ (doseq [[reference id] retained-names]
             (when (and (contains? fixed-names reference) (not= id (get fixed-names reference)))
               (throw (ex-info "Retained component metadata conflicts with the installed interface"
                               {:type :browser-plan/unsupported :reason :retained-interface-conflict}))))
         names (merge fixed-names retained-names)
         shapes (indexed-interfaces interfaces identity)
         records (tenancy/with-org tenancy/public-org
                                   (composition/fn-defs->records definitions names shapes))
         expected (into #{} (map #(ids/fn-id (:namespace %) (:name %))) definitions)
         functions (filter #(= :fn (:kind %)) records)
         actual (into #{} (map :id) functions)
         owned-paths (set (map :namespace definitions))
         local-anonymous? (fn [{:keys [id name namespace-id]}]
                            (and (contains? owned-paths namespace-id)
                                 (string? name)
                                 (re-matches #"_anon-[0-9a-f]{16}" name)
                                 (= id (ids/fn-id namespace-id (keyword name)))))
         additional (remove #(contains? expected (:id %)) functions)]
     ;; Inline operations belong to the fresh personal namespace and have a
     ;; stable parser identity. Refuse global/shared anonymous shape rows or
     ;; any unexpected identity before an exact create/delete receipt exists.
     (when-not (and (set/subset? expected actual)
                    (= (count functions) (count actual))
                    (every? local-anonymous? additional))
       (throw (ex-info "Installed UI templates have unsupported anonymous dependencies"
                       {:type :browser-plan/unsupported :reason :template-identity-mismatch})))
     {:definitions definitions :records records})))


(defn external-identities
  "Only references outside this fresh bundle need source authorization."
  [records]
  (let [owned (into #{} (comp (filter #(= :fn (:kind %))) (map :id)) records)]
    (into #{}
          (comp (mapcat #(keep % [:base-fn-id :return-type-fn-id :element-fn-id
                                  :type-fn-id :ref-fn-id :type-override-fn-id :resolver-fn-id]))
                (remove owned))
          (concat records (mapcat (fn [row] (for [parent-id (:parent-ids row)] {:ref-fn-id parent-id})) records)))))


(defn identity-targets
  "Identity-only targets from the fixed template's exact const/value fn-ref
   bindings. An ordinary reference to the same target still requires its body."
  [records]
  (let [identity? #(and (= :binding (:kind %))
                        (= (:slot-id %) (ids/slot-id (ids/fn-id "core.logic" :const) :value))
                        (= ids/fn-ref-type-id (:type-override-fn-id %))
                        (not (:value-present %)) (not (:resolver-fn-id %)))
        ordinary (external-identities (map #(if (identity? %) (dissoc % :ref-fn-id) %) records))]
    (into #{} (comp (filter identity?) (map :ref-fn-id) (remove ordinary)) records)))


(defn graph
  "The source graph view of fixed prepared records, for bounded projection."
  [records]
  (let [grouped (group-by :kind records)]
    {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
     :bindings (:binding grouped) :list-items (:binding-list-item grouped)}))
