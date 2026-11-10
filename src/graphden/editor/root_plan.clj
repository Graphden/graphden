(ns graphden.editor.root-plan
  "Export the editor's graph-selected HOF root under snapshot source policy."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-migration-plan :as migration]
    [graphden.editor.root-migration-templates :as migration-templates]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.graph.metadata :as metadata]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(defn- reject!
  [reason]
  (throw (ex-info "Editor root is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- editable-role-ids
  [storage authorize! root-row configuration]
  (let [overrides (migration-templates/overrides configuration)
        graph (source/collect-client-closure storage [(:id root-row)] authorize!)
        rows (:fns graph)]
    (into {}
          (map (fn [[component roles]]
                 [(name component)
                  (into {}
                        (map (fn [[role source-id]]
                               (let [prefix (str (name (migration-templates/bridge-name component role)) "-")
                                     matches (filter #(and (= (:namespace-id root-row) (:namespace-id %))
                                                           (str/starts-with? (:name %) prefix)
                                                           (some #{source-id} (:parent-ids %)))
                                                     rows)]
                                 (when-not (= 1 (count matches)) (reject! :invalid-root))
                                 [(name role) {:source-id (str source-id)
                                               :editable-id (str (:id (first matches)))}])))
                        roles)]))
          overrides)))


(defn export-current
  "The request carries no target identity. The handler's graph-bound resolver
   selects an authorized personal HOF; its static root remains the fallback."
  ([ctx] (export-current ctx {}))
  ([ctx request]
   (try
     (let [policy (snapshot/capture-context-policy ctx)
           epoch (context/invalidation-epoch ctx)]
       (source/with-snapshot
         (:storage ctx)
         (fn [storage authorize!]
           (server/call-with-root-export
             ctx storage authorize! policy request
             (fn [root export-plan]
               (let [row (sp/read-entity storage :fn root)
                     home (tenancy/current-personal-namespace)
                     home-row (when home
                                (metadata/namespace-row storage (:namespace-path home)
                                                        (tenancy/current-org)))
                     migrated? (migration/migration-owned? row (:id home-row))
                     selected-configuration? (and (tenancy/current-user-id)
                                                  (or (= resolution/default-root-id root)
                                                      migrated?))]
                 (when-not row (reject! :source-missing))
                 (authorize! row {:execute? true})
                 (components/assert-current-policy! storage)
                 (let [plan (export-plan)
                       input (get-in plan [:inputs (str root)])
                       required (:required input)
                       argument (first required)
                       selected (when selected-configuration?
                                  (resolution/capture storage authorize! policy))
                       editable (when (and migrated? selected)
                                  (editable-role-ids storage authorize! row (:configuration selected)))]
                   ;; This first slice exports closed static roots with at most
                   ;; one invocation input. Remaining free captures need a caller
                   ;; environment and must not silently become client arguments.
                   (when (or (> (count required) 1)
                             (and (= [] (:lambda-params row))
                                  (seq required)))
                     (reject! :root-captures))
                   (components/assert-current-policy! storage)
                   (when-not (= epoch (context/invalidation-epoch ctx))
                     (reject! :policy-refresh-required))
                   {:ok true :plan plan :argument-name argument :root-id (str root)
                    :editable-role-ids editable
                    :component-selection-id (:id selected)})))))))
     (catch Exception error
       (let [{:keys [type reason]} (ex-data error)]
         (when-not (= reason :policy-refresh-required)
           (log/warn "Editor root export refused" {:type type :reason reason})))
       (components/failure-facts error)))))
