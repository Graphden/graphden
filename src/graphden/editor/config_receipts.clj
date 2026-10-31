(ns graphden.editor.config-receipts
  "Read-only confirmation after an uncertain configuration APPLY. Absence is
   not proof of never committing; this path never recreates or stamps source."
  (:require
    [graphden.crud.entities.delete-batch :as deletion]
    [graphden.crud.request :as request]
    [graphden.editor.components :as components]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.config-undo :as undo]
    [graphden.editor.preference-write :as preferences]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.graph.metadata :as metadata]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.resolution :as branch-resolution]))


(defn- refuse!
  []
  (throw (ex-info "Configuration receipt cannot be confirmed"
                  {:type :browser-plan/unsupported :reason :stale-configuration})))


(defn- assert-cohort-metadata!
  [storage receipts home-id]
  ;; Check actual metadata before any receipt target's bindings are queried.
  ;; Submitted names/namespace IDs are expected state, never read authority.
  (let [rows (sp/read-entities storage :fn (mapv :id receipts))]
    (doseq [{:keys [id name namespace-id]} receipts]
      (let [row (get rows id)]
        (when-not (and row (= home-id namespace-id (:namespace-id row)) (= name (:name row)))
          (refuse!))
        (writer/assert-write-authorized! storage :fn row id)))))


(defn- assert-previous!
  [storage previous home-id scope authorize! policy]
  (when previous
    (let [row (sp/read-entity storage :fn (:id previous))]
      (when-not (and row (= home-id (:namespace-id previous) (:namespace-id row))
                     (= (:name previous) (:name row)))
        (refuse!))
      (writer/assert-write-authorized! storage :fn row (:id row))
      (let [selected (activation/selected-source storage (:id row) authorize! policy)]
        (when-not (= (:revision previous) (resolution/source-revision (:id row) (:snapshot selected) scope))
          (refuse!))
        selected))))


(defn- assert-root-previous!
  [storage previous home-id scope authorize! policy]
  (let [row (sp/read-entity storage :fn (:id previous))]
    (when-not (and row (= home-id (:namespace-id previous) (:namespace-id row))
                   (= (:name previous) (:name row)))
      (refuse!))
    (writer/assert-write-authorized! storage :fn row (:id row))
    (let [graph (source/collect-client-closure storage [(:id row)] authorize!)
          selected {:graph graph :namespaces (source/collect-namespaces storage graph)}]
      (snapshot/validate-source! selected (:id row) policy)
      (when-not (= (:revision previous)
                   (resolution/source-revision (:id row) selected scope))
        (refuse!))
      selected)))


(defn- assert-root-receipt!
  [storage root home-id authorize! policy]
  (let [choice (resolution/root-choice storage authorize! policy)
        ids (mapv :id (:functions root))]
    (when-not (= (:current-id root) (:id choice)) (refuse!))
    (assert-cohort-metadata! storage (:functions root) home-id)
    (assert-root-previous! storage (:previous root) home-id (:scope choice) authorize! policy)
    (let [cohort {:graph (source/collect-client-closure storage ids authorize!)}]
      (when-not (= (:cohort-revision root)
                   (resolution/cohort-revision ids cohort (:scope choice)))
        (refuse!)))
    choice))


(defn- confirm-locked
  [storage input policy]
  (let [authorize! (tenancy/browser-source-policy storage)
        current (resolution/capture storage authorize! policy)
        home (:home current)
        home-row (when home (metadata/namespace-row storage (:namespace-path home) (tenancy/current-org)))
        receipts (:functions input)
        ids (mapv :id receipts)]
    (when-not (and home-row (= (:id current) (:current-id input))
                   (= (:revision current) (:expected-configuration-revision input)))
      (refuse!))
    (assert-cohort-metadata! storage receipts (:id home-row))
    (assert-previous! storage (:previous input) (:id home-row) (:scope current) authorize! policy)
    (when-let [root (:root input)]
      (assert-root-receipt! storage root (:id home-row) authorize! policy))
    (let [cohort {:graph (source/collect-manifests storage ids authorize!)}]
      (when-not (= (:cohort-revision input) (resolution/cohort-revision ids cohort (:scope current)))
        (refuse!)))
    ;; A confirmation is only the exact current state under normal source
    ;; checks. It adds no rich certificate and promises no historical once-only
    ;; execution. Partial, changed or tombstoned states remain uncertain.
    (cond-> {:confirmed true :committed true :created true :id (:id current)
             :configuration-revision (:expected-configuration-revision input)
             :cohort-revision (:cohort-revision input) :previous (:previous input)
             :functions ids :function-receipts receipts :namespaces []}
      (:root input) (assoc :root (:root input)))))


(defn- call-with-proof
  [ctx f]
  (let [storage (request/require-storage ctx)
        epoch (context/invalidation-epoch ctx)
        policy (snapshot/capture-context-policy ctx)]
    (tx/assert-owns-commit! storage)
    (writer/with-write [storage :graph]
                       (preferences/call-with-lock
                         storage "components"
                         (fn [storage]
                           (branch-resolution/call-with-fresh-memos
                             #(do
                                (components/assert-current-policy! storage)
                                (let [result (f storage policy)]
                                  (when-not (= epoch (context/invalidation-epoch ctx))
                                    (throw (ex-info "Configuration policy changed"
                                                    {:type :browser-plan/unsupported :reason :policy-refresh-required})))
                                  result))))))))


(defn reconcile!
  [ctx input]
  (let [input (undo/parse-command input)]
    (call-with-proof ctx #(confirm-locked %1 input %2))))


(defn parse-undo-confirmation
  [input]
  (when-not (and (map? input) (= #{:receipt :scope :restore-revision} (set (keys input)))
                 (activation/revision? (:restore-revision input)))
    (throw (ex-info "Invalid configuration confirmation request"
                    {:type :browser-plan/unsupported :reason :invalid-request})))
  (-> input
      (update :scope activation/parse-scope)
      (update :receipt undo/parse-command)))


(defn- confirm-undo-locked
  [storage {:keys [receipt scope restore-revision]} policy]
  (let [current (resolution/capture storage (tenancy/browser-source-policy storage) policy)
        expected-id (or (get-in receipt [:previous :id]) resolution/defaults-id)
        home-path (get-in current [:home :namespace-path])
        home-row (when home-path (metadata/namespace-row storage home-path (tenancy/current-org)))
        ids (mapv :id (:functions receipt))
        root (:root receipt)
        root-choice (when root (resolution/root-choice storage (tenancy/browser-source-policy storage) policy))
        root-ids (mapv :id (:functions root))]
    (when-not (and (= scope (activation/request-scope (:scope current)))
                   home-row (every? #(= (:id home-row) (:namespace-id %)) (:functions receipt))
                   (= expected-id (:id current)) (= restore-revision (:revision current))
                   (empty? (sp/read-entities storage :fn (into ids root-ids)))
                   (or (nil? root)
                       (and (= (get-in root [:previous :id]) (:id root-choice))
                            (= (:id home-row) (get-in root [:previous :namespace-id])))))
      (refuse!))
    (when root
      (let [authorize! (tenancy/browser-source-policy storage)
            graph (source/collect-client-closure storage [(:id root-choice)] authorize!)
            selected {:graph graph :namespaces (source/collect-namespaces storage graph)}]
        (snapshot/validate-source! selected (:id root-choice) policy)
        (when-not (= (:restore-revision root)
                     (resolution/source-revision (:id root-choice) selected (:scope root-choice)))
          (refuse!))))
    ;; Exact current postconditions, not evidence of historical once-only
    ;; execution or global physical absence. An edited restore target, identity
    ;; surviving in this scoped resolved view or mismatched requested scope
    ;; remains uncertain. Never adopt a newly observed source stamp.
    (cond-> {:confirmed true :undone true :id expected-id :restore-revision restore-revision}
      root (assoc :root-id (:id root-choice)
                  :root-restore-revision (:restore-revision root)))))


(defn reconcile-undo!
  "Confirm a staged undo's exact postconditions after an uncertain response.
   The expected restore witness must precede DELETE; no live stamp refresh."
  [ctx input]
  (let [input (parse-undo-confirmation input)]
    (call-with-proof ctx #(confirm-undo-locked %1 input %2))))


(defn- restored-source-revision
  [storage previous selected scope authorize! policy]
  (if previous
    (let [id (:id previous)
          restored (update-in selected [:snapshot :graph :fns]
                              #(mapv (fn [row] (if (= id (:id row)) (assoc row :name "editor") row)) %))]
      (resolution/source-revision id (:snapshot restored) scope))
    (resolution/source-revision resolution/defaults-id
                                (:snapshot (activation/selected-source storage resolution/defaults-id authorize! policy)) scope)))


(defn- restored-root-revision
  [previous selected scope ordinary-previous]
  (let [id (:id previous)
        names (cond-> {id "editor-root"}
                ordinary-previous (assoc (:id ordinary-previous) "editor"))
        restored (update-in selected [:graph :fns]
                            #(mapv (fn [row]
                                     (if-let [restored-name (get names (:id row))]
                                       (assoc row :name restored-name) row)) %))]
    (resolution/source-revision id restored scope)))


(defn- cleanup-review-locked
  [storage {:keys [receipt scope restore-revision]} policy]
  (let [authorize! (tenancy/browser-source-policy storage)
        current (resolution/capture storage authorize! policy)
        root (:root receipt)
        root-choice (when root (resolution/root-choice storage authorize! policy))
        home-path (get-in current [:home :namespace-path])
        home (when home-path (metadata/namespace-row storage home-path (tenancy/current-org)))
        ids (mapv :id (:functions receipt))
        root-ids (mapv :id (:functions root))]
    (when-not (and home (= scope (activation/request-scope (:scope current)))
                   (= (:current-id receipt) (:id current)))
      (refuse!))
    (when root
      (when-not (= (:current-id root) (:id root-choice)) (refuse!))
      (assert-root-previous! storage (:previous root) (:id home)
                             (:scope root-choice) authorize! policy))
    (let [all-ids (into ids root-ids)
          rows (sp/read-entities storage :fn all-ids)]
      ;; Only current names may change in the visible deletion proposal; IDs,
      ;; actual home and the original restore anchor never become client rights.
      (doseq [id all-ids :let [row (get rows id)]]
        (when-not (and row (= (:id home) (:namespace-id row))) (refuse!))
        (writer/assert-write-authorized! storage :fn row id)
        (authorize! row))
      (let [previous (assert-previous! storage (:previous receipt) (:id home) (:scope current) authorize! policy)
            functions (mapv #(select-keys (get rows %) [:id :name :namespace-id]) ids)
            root-functions (mapv #(select-keys (get rows %) [:id :name :namespace-id]) root-ids)
            cohort {:graph (source/collect-manifests storage ids authorize!)}
            restored (restored-source-revision storage (:previous receipt) previous (:scope current) authorize! policy)
            root-previous (when root (assert-root-previous! storage (:previous root) (:id home)
                                                            (:scope root-choice) authorize! policy))
            root-restored (when root (restored-root-revision (:previous root) root-previous
                                                             (:scope root-choice) (:previous receipt)))
            root-cohort (when root {:graph (source/collect-client-closure storage root-ids authorize!)})
            request (undo/parse-command
                      (cond-> (assoc receipt :functions functions
                                     :expected-configuration-revision (:revision current)
                                     :cohort-revision (resolution/cohort-revision ids cohort (:scope current)))
                        root (assoc :root (assoc root :functions root-functions
                                                 :cohort-revision (resolution/cohort-revision
                                                                    root-ids root-cohort (:scope root-choice))))))]
        (when (and (:previous receipt) (not= restore-revision restored)) (refuse!))
        (when (and root (not= (:restore-revision root) root-restored)) (refuse!))
        (deletion/validate-locked! storage (into functions root-functions))
        {:kind "editor-configuration-cleanup-review" :scope scope :request request :restore-revision restored
         :delete (into functions root-functions)
         :restore {:id (or (get-in receipt [:previous :id]) resolution/defaults-id)
                   :name (if (:previous receipt) "editor" "Shared defaults")
                   :root (when root {:id (get-in root [:previous :id]) :name "editor-root"})}}))))


(defn cleanup-preview!
  "Read-only proposal for an explicit new deletion decision. The original
   creation receipt is unchanged; show these exact rows and restore target
   before consent, then submit the returned request without refreshing it."
  [ctx input]
  (let [input (parse-undo-confirmation input)]
    (call-with-proof ctx #(cleanup-review-locked %1 input %2))))
