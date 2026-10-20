(ns graphden.editor.root-plan
  "Export the editor handler's static root binding under snapshot source policy."
  (:require
    [graphden.editor.components :as components]
    [graphden.editor.root-compatibility :as compatibility]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(def ^:private handler-id (ids/fn-id "app.editor" :_editor-handler))
(def ^:private root-slot-id (ids/slot-id (ids/fn-id "app.editor" :client-handler) :root))


(defn- reject!
  [reason]
  (throw (ex-info "Editor root is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn export-current
  "The request carries no target identity. Read the root only from the stored
   server-owned handler binding. Dynamic root
   producers and caller-provided captures are unsupported."
  ([ctx] (export-current ctx {}))
  ([ctx request]
   (try
     (let [policy (snapshot/capture-context-policy ctx)
           epoch (context/invalidation-epoch ctx)]
       (source/with-snapshot
         (:storage ctx)
         (fn [storage authorize!]
           (let [handler (sp/read-entity storage :fn handler-id)]
             ;; This is the server's selector configuration, not client source.
             ;; Its fixed shipped owner may name a root but cannot authorize any
             ;; source in that root. Do not publish the server handler's body.
             (when-not (and handler (owned/owned-fn-id? handler-id)
                            (= tenancy/public-org (or (:org-id handler) tenancy/public-org)))
               (reject! :root-owner))
             (let [bindings (sp/query-bounded-entities storage :binding
                                                       {:fn-id handler-id :slot-id root-slot-id} 2)
                   binding (first bindings)
                   root (:ref-fn-id binding)]
               (when-not (and (= 1 (count bindings)) (uuid? root)
                              (nil? (:value binding)) (not (:value-present binding))
                              (nil? (:resolver-fn-id binding))
                              (nil? (:type-override-fn-id binding))
                              (not (:list-append binding)) (not (:list-closed binding))
                              (not (:terminal binding)))
                 (reject! :root-binding))
               (let [row (sp/read-entity storage :fn root)]
                 (when-not row (reject! :source-missing))
                 (authorize! row {:execute? true}))
               (components/assert-current-policy! storage)
               (let [graph (source/collect-client-closure storage [root] authorize!)
                     plan (server/export-client-plan ctx storage authorize! policy {:root root} request
                                                     {:fn-id handler-id :slot-id root-slot-id :ref-fn-id root})
                     plan (compatibility/adapt-plan ctx storage authorize! policy root plan)
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
                 {:ok true :plan plan :argument-name argument}))))))
     (catch Exception error
       (assoc (components/failure-response error) :reason "Editor root is unavailable.")))))
