(ns graphden.editor.root-plan
  "Export the editor's graph-selected HOF root under snapshot source policy."
  (:require
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-binding :as binding]
    [graphden.editor.root-compatibility :as compatibility]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.storage.protocol.core :as sp]))


(defn- reject!
  [reason]
  (throw (ex-info "Editor root is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


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
           (let [{handler-root :root} (binding/capture! storage)
                 choice (resolution/root-choice storage authorize! policy)
                 root (binding/selected-root handler-root choice)
                 personal? (not= resolution/default-root-id (:id choice))
                 row (sp/read-entity storage :fn root)]
             (when-not row (reject! :source-missing))
             (authorize! row {:execute? true})
             (components/assert-current-policy! storage)
             (let [graph (source/collect-client-closure storage [root] authorize!)
                   plan (server/export-client-plan
                          ctx storage authorize! policy {:root root} request
                          {:handler-root handler-root :selected-root root})
                   adapted (if personal? {:plan plan}
                               (compatibility/adapt-root ctx storage authorize! policy root plan))
                   plan (:plan adapted)
                   input (get-in plan [:inputs (str root)])
                   required (:required input)
                   argument (first required)]
               ;; This first slice exports closed static roots with at most
               ;; one invocation input. Remaining free captures need a caller
               ;; environment and must not silently become client arguments.
               (when (or (> (count required) 1)
                         (and (= [] (:lambda-params (some #(when (= root (:id %)) %) (:fns graph))))
                              (seq required)))
                 (reject! :root-captures))
               (components/assert-current-policy! storage)
               (when-not (= epoch (context/invalidation-epoch ctx))
                 (reject! :policy-refresh-required))
               {:ok true :plan plan :argument-name argument
                :component-selection-id (:component-selection-id adapted)})))))
     (catch Exception error
       (assoc (components/failure-response error) :reason "Editor root is unavailable.")))))
