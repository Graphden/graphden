(ns graphden.editor.root-migration-plan
  "Checked proposal and same-transaction commit for personal HOF roots."
  (:require
    [graphden.editor.cohort-preparation :as cohort]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-binding :as binding]
    [graphden.editor.root-compatibility :as compatibility]
    [graphden.editor.root-migration-parse :as migration-parse]
    [graphden.editor.root-migration-slots :as migration-slots]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.source-contract :as contract]
    [graphden.graph.metadata :as metadata]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]))


(def ^:private component-root-id (ids/fn-id "app.ui-config" :component-editor-root))
(def ^:private migration-description "Personal UI root created by Graphden migration")


(defn migration-owned?
  "A selector target is managed by the explicit migration in this exact home."
  [row home-id]
  (and row (= "editor-root" (:name row))
       (= migration-description (:description row))
       (= home-id (:namespace-id row))))


(defn- refuse!
  [reason]
  (throw (ex-info "Personal editor root migration is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- validate-root!
  [graph root-id]
  (when-not (some #(and (= root-id (:id %)) (= "editor-root" (:name %))) (:fns graph))
    (refuse! :invalid-root)))


(defn- renamed-graph
  [graph renames]
  (update graph :fns
          #(mapv (fn [row]
                   (if-let [name (get renames (:id row))]
                     (assoc row :name name)
                     row)) %)))


(defn proposal
  ([storage policy nonce]
   (proposal storage policy nonce {}))
  ([storage policy nonce {:keys [selection staged-source renames]}]
   (let [authorize! (tenancy/browser-source-policy storage)
         handler (binding/capture! storage)
         choice (resolution/root-choice storage authorize! policy)
         _ (when-not (= resolution/default-root-id (:root handler))
             (refuse! :custom-handler-root))
         selection (or selection (compatibility/capture-selection storage authorize! policy))
         home (:home selection)
         _ (when-not home (refuse! :personal-home-unavailable))
         home-row (metadata/namespace-row storage (:namespace-path home) (tenancy/current-org))
         _ (when-not home-row (refuse! :personal-home-unavailable))
         prior-row (when-not (= resolution/default-root-id (:id choice))
                     (sp/read-entity storage :fn (:id choice)))
         _ (when (and (not= resolution/default-root-id (:id choice)) (nil? prior-row))
             (refuse! :source-missing))
         _ (when prior-row
             (when-not (migration-owned? prior-row (:id home-row))
               (refuse! :unmanaged-personal-root))
             (writer/assert-write-authorized! storage :fn prior-row (:id prior-row)))
         prior-graph (when prior-row
                       (source/collect-client-closure storage [(:id prior-row)] authorize!))
         _ (when prior-row
             (snapshot/validate-source!
               {:graph prior-graph :namespaces (source/collect-namespaces storage prior-graph)}
               (:id prior-row) policy))
         prior-name (when prior-row (str "_editor-root-prior-" nonce))
         prior-revision (when prior-row
                          (resolution/source-revision
                            (:id prior-row)
                            {:graph (renamed-graph prior-graph
                                                   (assoc renames (:id prior-row) prior-name))}
                            (:scope choice)))
         parsed (migration-parse/parse (str "root-migration." nonce) (:configuration selection))
         _ (when (sp/read-entity storage :fn (:root-id parsed))
             (refuse! :stale-configuration))
         role-ids (vec (vals (:reference-identities parsed)))
         prepared (cohort/prepare
                    storage home-row parsed
                    {:authorize! authorize! :policy policy :source-root component-root-id
                     :source-roots (conj role-ids component-root-id)
                     :staged-source staged-source
                     :collect-external! source/collect-client-closure
                     :normalize-records #(migration-slots/resolve-bindings
                                           %1 (:definitions parsed) %2)
                     :validate-result! validate-root!
                     :root-metadata {:description migration-description}})
         scope (:scope choice)
         function-ids (mapv :id (filter #(= :fn (:kind %)) (:records prepared)))
         receipt {:id (:id prepared) :created true :functions function-ids
                  :function-receipts (mapv #(select-keys % [:id :name :namespace-id])
                                           (filter #(= :fn (:kind %)) (:records prepared)))
                  :preserved-callbacks (vec (distinct (vals (:reference-identities parsed))))
                  :selection-id (:id selection)
                  :selection-revision (resolution/source-revision (:id selection) (:snapshot selection) scope)
                  :source-revision (resolution/source-revision (:id prepared) (:snapshot prepared) scope)
                  :cohort-revision (resolution/cohort-revision function-ids (:snapshot prepared) scope)
                  :restore-id (:id choice)
                  :restore-revision (when prior-row
                                      (resolution/source-revision (:id prior-row)
                                                                  {:graph prior-graph} scope))
                  :namespaces []
                  :changed-functions (if prior-row [(:id prior-row)] [])
                  :previous (when prior-row {:id (:id prior-row) :name prior-name
                                             :namespace-id (:id home-row)
                                             :revision prior-revision})}
         source-ids (mapv :id (get-in prepared [:snapshot :graph :fns]))
         seal (provenance/fingerprint-data
                {:nonce nonce :scope scope :handler handler :choice choice
                 :previous (:previous receipt)
                 :selection (select-keys selection [:id :legacy-preference :revision])
                 :receipt receipt
                 :policy (into {} (map (fn [id]
                                         [id {:class (get-in policy [:classes id])
                                              :signature (select-keys (get-in policy [:rich :by-id id])
                                                                      [:return :args :effects :taint-policy])}])) source-ids)})]
     {:prepared prepared :receipt receipt :scope scope :seal seal :authorize! authorize!
      :prior-row prior-row :prior-name prior-name})))


(defn- verify-committed!
  [storage {:keys [prepared receipt scope authorize!]}]
  (let [graph (source/collect-client-closure storage (:functions receipt) authorize!)
        expected (get-in prepared [:snapshot :graph])
        ids (set (map :id (:fns expected)))]
    (when-not (and (= ids (set (map :id (:fns graph))))
                   (contract/matches? expected graph ids)
                   (= (:cohort-revision receipt)
                      (resolution/cohort-revision (:functions receipt) {:graph graph} scope)))
      (refuse! :write-source-mismatch))))


(defn commit!
  "Commit an already rebuilt proposal inside the caller's graph writer transaction."
  [storage {:keys [prior-row prior-name prepared receipt] :as plan}]
  (when prior-row
    (let [renamed (sp/update-entity storage :fn (:id prior-row) {:name prior-name})]
      (when-not (= prior-name (:name renamed))
        (refuse! :write-refused))))
  (preparation/create-records! storage (:records prepared))
  (verify-committed! storage plan)
  (assoc receipt :committed true))
