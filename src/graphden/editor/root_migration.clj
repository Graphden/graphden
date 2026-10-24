(ns graphden.editor.root-migration
  "Explicit, create-only migration of a checked legacy component selection to
   an ordinary personal HOF root. Preview is read-only; APPLY rebuilds every
   source, policy and identity check under the same writer lock."
  (:require
    [graphden.crud.request :as request]
    [graphden.editor.cohort-preparation :as cohort]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-binding :as binding]
    [graphden.editor.root-compatibility :as compatibility]
    [graphden.editor.root-migration-parse :as migration-parse]
    [graphden.editor.root-migration-slots :as migration-slots]
    [graphden.executor.browser-source :as source]
    [graphden.executor.source-contract :as contract]
    [graphden.graph.metadata :as metadata]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]))


(def ^:private component-root-id (ids/fn-id "app.ui-config" :component-editor-root))


(defn- refuse!
  [reason]
  (throw (ex-info "Personal editor root migration is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- validate-root!
  [graph root-id]
  (when-not (some #(and (= root-id (:id %)) (= "editor-root" (:name %))) (:fns graph))
    (refuse! :invalid-root)))


(defn- proposal
  [storage policy nonce]
  (let [authorize! (tenancy/browser-source-policy storage)
        handler (binding/capture! storage)
        choice (resolution/root-choice storage authorize! policy)
        _ (when-not (and (= resolution/default-root-id (:root handler))
                         (= resolution/default-root-id (:id choice)))
            (refuse! :already-migrated))
        selection (compatibility/capture-selection storage authorize! policy)
        home (:home selection)
        _ (when-not home (refuse! :personal-home-unavailable))
        home-row (metadata/namespace-row storage (:namespace-path home) (tenancy/current-org))
        _ (when-not home-row (refuse! :personal-home-unavailable))
        parsed (migration-parse/parse (str "root-migration." nonce) (:configuration selection))
        role-ids (vec (vals (:reference-identities parsed)))
        prepared (cohort/prepare
                   storage home-row parsed
                   {:authorize! authorize! :policy policy :source-root component-root-id
                    :source-roots (conj role-ids component-root-id)
                    :collect-external! source/collect-client-closure
                    :normalize-records #(migration-slots/resolve-bindings
                                          %1 (:definitions parsed) %2)
                    :validate-result! validate-root! :root-metadata {}})
        scope (:scope choice)
        function-ids (mapv :id (filter #(= :fn (:kind %)) (:records prepared)))
        receipt {:id (:id prepared) :created true :functions function-ids
                 :preserved-callbacks (vec (distinct (vals (:reference-identities parsed))))
                 :selection-id (:id selection)
                 :selection-revision (resolution/source-revision (:id selection) (:snapshot selection) scope)
                 :source-revision (resolution/source-revision (:id prepared) (:snapshot prepared) scope)
                 :cohort-revision (resolution/cohort-revision function-ids (:snapshot prepared) scope)
                 :namespaces []}
        source-ids (mapv :id (get-in prepared [:snapshot :graph :fns]))
        seal (provenance/fingerprint-data
               {:nonce nonce :scope scope :handler handler :choice choice
                :selection (select-keys selection [:id :legacy-preference :revision])
                :receipt receipt
                :policy (into {} (map (fn [id]
                                        [id {:class (get-in policy [:classes id])
                                             :signature (select-keys (get-in policy [:rich :by-id id])
                                                                     [:return :args :effects :taint-policy])}])) source-ids)})]
    {:prepared prepared :receipt receipt :scope scope :seal seal :authorize! authorize!}))


(defn- with-current-source
  [ctx operation]
  (when-not (tx/datasource (request/require-storage ctx))
    (refuse! :snapshot-storage-unsupported))
  (activation/call-with-current-configuration ctx operation))


(defn preview!
  "Read-only proposal with new identities and current source witnesses."
  [ctx input]
  (when-not (= {} input) (refuse! :invalid-request))
  (let [nonce (random-uuid)]
    (with-current-source
      ctx
      (fn [storage policy]
        (let [{:keys [receipt scope seal]} (proposal storage policy nonce)]
          {:request (activation/wire-value
                      {:nonce nonce :scope (activation/request-scope scope)
                       :expected-proposal seal})
           :receipt (activation/wire-value receipt)})))))


(defn- parse-apply
  [input]
  (when-not (and (map? input)
                 (= #{:nonce :scope :expected-proposal} (set (keys input)))
                 (activation/revision? (:expected-proposal input)))
    (refuse! :invalid-request))
  (let [nonce (if (uuid? (:nonce input)) (:nonce input)
                  (when (string? (:nonce input)) (parse-uuid (:nonce input))))]
    (when-not nonce (refuse! :invalid-request))
    (assoc input :nonce nonce :scope (activation/parse-scope (:scope input)))))


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


(defn apply!
  "Rebuild preview under current ACL and preference locks, then atomically
   create the HOF cohort. A stale or replayed proposal writes nothing."
  [ctx input]
  (let [{:keys [nonce scope expected-proposal]} (parse-apply input)
        storage (request/require-storage ctx)
        result (with-current-source
                 ctx
                 (fn [storage policy]
                   (let [plan (proposal storage policy nonce)]
                     (when-not (and (= scope (activation/request-scope (:scope plan)))
                                    (= expected-proposal (:seal plan)))
                       (refuse! :stale-configuration))
                     (preparation/create-records! storage (get-in plan [:prepared :records]))
                     (verify-committed! storage plan)
                     (assoc (:receipt plan) :committed true))))]
    (preparation/publish! ctx storage result)))
