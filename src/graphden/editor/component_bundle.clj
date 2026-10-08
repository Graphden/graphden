(ns graphden.editor.component-bundle
  "Pure parsing of the fixed personal UI template bundle. Existing tenant
   definitions and their literals are never a parser input."
  (:require
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
  [root]
  (let [definitions (templates/definitions root)
        interfaces (templates/interface-definitions)
        names (merge (ids/primitive-fn-ids)
                     (indexed-interfaces interfaces #(ids/fn-id (:namespace %) (:name %))))
        shapes (indexed-interfaces interfaces identity)
        records (tenancy/with-org tenancy/public-org
                                  (composition/fn-defs->records definitions names shapes))
        expected (into #{} (map #(ids/fn-id (:namespace %) (:name %))) definitions)
        actual (into #{} (comp (filter #(= :fn (:kind %))) (map :id)) records)]
    ;; Fixed templates deliberately have named type/state/style dependencies.
    ;; A future anonymous definition must be made an explicit owned template
    ;; member instead of silently sharing an org/global shape identity.
    (when-not (= expected actual)
      (throw (ex-info "Installed UI templates have unsupported anonymous dependencies"
                      {:type :browser-plan/unsupported :reason :template-identity-mismatch})))
    {:definitions definitions :records records}))


(defn external-identities
  "Only references outside this fresh bundle need source authorization."
  [records]
  (let [owned (into #{} (comp (filter #(= :fn (:kind %))) (map :id)) records)]
    (into #{}
          (comp (mapcat #(keep % [:base-fn-id :return-type-fn-id :element-fn-id
                                  :type-fn-id :ref-fn-id :type-override-fn-id :resolver-fn-id]))
                (remove owned))
          (concat records (mapcat (fn [row] (for [parent-id (:parent-ids row)] {:ref-fn-id parent-id})) records)))))
