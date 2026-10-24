(ns graphden.editor.config-resolution
  "Protected ordinary editor identity resolution. A closed packaged graph may
   use one authenticated snapshot lookup; tenant graphs receive no capability."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.editor.component-config :as config]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.source-contract :as contract]
    [graphden.executor.structural-projection :as projection]
    [graphden.graph.metadata :as metadata]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private path "app.ui-config")
(def resolver-id (ids/fn-id path :editor-resolver))
(def defaults-id (ids/fn-id path :editor-defaults))
(def root-resolver-id (ids/fn-id path :editor-root-resolver))
(def default-root-id (ids/fn-id path :editor-root))
(def ^:private configuration-resolver-names #{:editor-resolver :_editor-discovery :_editor-defaults-id})
(def ^:private root-resolver-names #{:editor-root-resolver :_editor-root-discovery :_editor-root-default-id})


(defn- module
  [resource]
  (edn/read-string (slurp (io/resource resource))))


(defn- canonical-source
  [names-to-protect]
  (let [definitions (:fns (module "packages/app/ui-config/fns.edn"))
        interfaces (mapcat (fn [[resource names]]
                             (let [{:keys [fns] namespace-path :namespace} (module resource)]
                               (map #(assoc % :namespace namespace-path)
                                    (filter #(contains? names (:name %)) fns))))
                           [["packages/core/logic/fns.edn" #{:const}]
                            ["packages/core/collections/fns.edn" #{:get}]
                            ["packages/web/graph-metadata/fns.edn" #{:find-function}]])
        names (reduce (fn [result {:keys [name] namespace-path :namespace}]
                        (assoc result name (ids/fn-id namespace-path name)
                               (keyword namespace-path (clojure.core/name name)) (ids/fn-id namespace-path name)))
                      (ids/primitive-fn-ids)
                      (concat interfaces (map #(assoc % :namespace path) definitions)))
        definitions-by-name (reduce (fn [result {:keys [name] namespace-path :namespace :as definition}]
                                      (assoc result name definition
                                             (keyword namespace-path (clojure.core/name name)) definition))
                                    {} interfaces)
        records (parse/parse-module
                  (mapv #(assoc % :namespace path) (filter #(contains? names-to-protect (:name %)) definitions))
                  names definitions-by-name)
        grouped (group-by :kind records)]
    {:fns (:fn grouped) :slots (:slot grouped) :fn-slots (:fn-slot grouped)
     :bindings (:binding grouped) :list-items (:binding-list-item grouped)}))


(def ^:private fixed-sources
  (delay {:configuration (canonical-source configuration-resolver-names)
          :root (canonical-source root-resolver-names)}))


(defn- public-row?
  [row]
  (= tenancy/public-org (or (:org-id row) tenancy/public-org)))


(defn- complete-public-path
  [by-id id]
  ;; Unlike display paths, a trusted source path cannot tolerate missing
  ;; parents, cycles, foreign namespace rows or a partial ancestry.
  (loop [id id seen #{} segments ()]
    (when (< (count seen) 16)
      (if (nil? id)
        (str/join "." segments)
        (when-let [row (get by-id id)]
          (when (and (not (contains? seen id)) (public-row? row)
                     (string? (:name row)) (not (str/blank? (:name row)))
                     (not (str/includes? (:name row) ".")))
            (recur (:parent-id row) (conj seen id) (cons (:name row) segments))))))))


(defn- assert-protected-source*
  [{:keys [graph namespaces]} names-to-protect source-kind]
  (let [protected-ids (set (map #(ids/fn-id path %) names-to-protect))
        by-id (into {} (map (juxt :id identity)) namespaces)
        protected (filter #(contains? protected-ids (:id %)) (:fns graph))
        normalized (update graph :fns
                           #(mapv (fn [row]
                                    (if (contains? protected-ids (:id row))
                                      (assoc row :namespace-id (complete-public-path by-id (:namespace-id row)))
                                      row)) %))]
    (when-not (and (= protected-ids (set (map :id protected)))
                   (every? #(and (public-row? %) (owned/owned-fn-id? (:id %))
                                 (= path (complete-public-path by-id (:namespace-id %)))) protected)
                   (contract/matches? (get @fixed-sources source-kind) normalized protected-ids))
      (throw (ex-info "Editor resolver source is unavailable"
                      {:type :browser-plan/unsupported :reason :resolver-source-mismatch}))))
  nil)


(defn assert-protected-source!
  "Check the closed, packaged configuration resolver before metadata discovery."
  [selected]
  (assert-protected-source* selected configuration-resolver-names :configuration))


(defn assert-protected-root-source!
  "Check the closed, packaged browser-root resolver before metadata discovery."
  [selected]
  (assert-protected-source* selected root-resolver-names :root))


(defn- resolve-protected-identity
  [snapshot resolver discovery-name inputs lookup-capability]
  (let [selected-reference (projection/project-resolver (:graph snapshot) resolver
                                                        #(or (nil? %) (contains? #{:fn-id discovery-name} %))
                                                        inputs lookup-capability)]
    (when-not (projection/identity-value? selected-reference)
      (throw (ex-info "Editor resolver identity is unavailable"
                      {:type :browser-plan/unsupported :reason :resolver-identity-invalid})))
    selected-reference))


(defn resolve-identity
  "Project an already source-authorized immutable configuration resolver."
  [snapshot inputs lookup-capability]
  (assert-protected-source! snapshot)
  (resolve-protected-identity snapshot resolver-id "editor" inputs lookup-capability))


(defn resolve-root-identity
  "Project the graph-bound browser-root resolver under authenticated scope."
  [snapshot inputs lookup-capability]
  (assert-protected-root-source! snapshot)
  (resolve-protected-identity snapshot root-resolver-id "editor-root" inputs lookup-capability))


(defn- captured-source
  [storage authorize! id]
  (let [graph (source/collect-manifest storage id authorize!)]
    {:graph graph :namespaces (source/collect-namespaces storage graph)}))


(defn- current-scope
  [storage home name]
  {:namespace-path (:namespace-path home) :name name
   :principal-id (tenancy/current-user-id) :org (tenancy/current-org)
   :branch-id (versioned/current-branch-id storage)})


(defn root-choice
  "Resolve the handler's protected graph selector in this authenticated
   snapshot. Discovery supplies identity only; the caller checks source and
   execute permission for the selected HOF separately."
  [storage authorize! policy]
  (let [scope (current-scope storage (tenancy/current-personal-namespace) "editor-root")
        resolver (captured-source storage authorize! root-resolver-id)
        _ (assert-protected-root-source! resolver)
        _ (snapshot/validate-source! resolver root-resolver-id policy)
        id (if (:principal-id scope)
             (:id (resolve-root-identity resolver scope
                                         (metadata/function-lookup storage authorize! scope)))
             default-root-id)]
    {:id id :scope scope}))


(defn source-revision
  "Expected-state digest of an authorized configuration snapshot, including
   authored presentation metadata. A digest is never an authorization grant."
  [id selected scope]
  (provenance/fingerprint-data
    {:root id :scope (select-keys scope [:principal-id :org :branch-id])
     :sources (contract/fingerprints (:graph selected))
     :metadata (->> (get-in selected [:graph :fns])
                    (map #(select-keys % [:id :name :description]))
                    (sort-by (comp str :id))
                    vec)}))


(defn cohort-revision
  "Digest exact own source of newly created compositions under the ingress
   scope. Manifest references alone do not cover a role target's body."
  [ids selected scope]
  (let [ids (set ids)
        graph (:graph selected)
        fingerprints (select-keys (contract/fingerprints graph) ids)]
    (when-not (= ids (set (keys fingerprints)))
      (throw (ex-info "Configuration cohort source is unavailable"
                      {:type :browser-plan/unsupported :reason :cohort-source-missing})))
    (provenance/fingerprint-data
      {:scope (select-keys scope [:principal-id :org :branch-id])
       :sources fingerprints
       :metadata (->> (:fns graph)
                      (filter #(contains? ids (:id %)))
                      (map #(select-keys % [:id :name :description]))
                      (sort-by (comp str :id)) vec)})))


(defn- personal-view-checker
  [storage current]
  (let [home (:home current)
        namespace-row (when home (metadata/namespace-row storage (:namespace-path home) (tenancy/current-org)))
        rows (into {} (map (juxt :id identity)) (get-in current [:snapshot :graph :fns]))]
    (fn [component]
      (let [id (get-in current [:configuration component :view]) row (get rows id)]
        (when (and row namespace-row (= (:id namespace-row) (:namespace-id row))
                   (not= id (get-in config/component-contracts [component :id])))
          (try
            (writer/assert-write-authorized! storage :fn row id)
            true
            (catch clojure.lang.ExceptionInfo error
              (if (contains? #{:authz/forbidden :branch/merge-required} (:type (ex-data error)))
                false (throw error)))))))))


(defn owned-writable-view?
  "Whether this selected role is editable in the exact authenticated home.
   A foreign shared override is a parent to inherit, not a personal edit."
  [storage current component]
  ((personal-view-checker storage current) component))


(defn owned-writable-components
  "Finite action metadata over captured role rows. Resolve the home once;
   no component target body or repeated namespace walk is read here."
  [storage current]
  (let [editable? (personal-view-checker storage current)]
    (into {} (map (fn [component] [(name component) (boolean (editable? component))]))
          (keys config/component-contracts))))


(defn capture
  "Resolve and validate one ordinary configuration in the caller's SAME
   immutable snapshot. Freeze policy before opening that snapshot. Missing
   personal identity selects the ordinary shared defaults; denied/invalid
   source never becomes absence. No provisioning or migration occurs here."
  [storage authorize! policy]
  (let [home (tenancy/current-personal-namespace)
        scope (current-scope storage home "editor")
        resolver (captured-source storage authorize! resolver-id)
        _ (assert-protected-source! resolver)
        _ (snapshot/validate-source! resolver resolver-id policy)
        lookup (metadata/function-lookup storage authorize! scope)
        selected-reference (resolve-identity resolver scope lookup)
        id (:id selected-reference)
        row (sp/read-entity storage :fn id)
        _ (when-not row
            (throw (ex-info "Editor configuration source is unavailable"
                            {:type :browser-plan/unsupported :reason :source-missing})))
        _ (authorize! row {:execute? true})
        selected (captured-source storage authorize! id)
        _ (snapshot/validate-source! selected id policy)]
    {:id id :home home :scope scope :snapshot selected
     :configuration (config/configuration (:graph selected) id)
     :revision (source-revision id selected scope)}))
