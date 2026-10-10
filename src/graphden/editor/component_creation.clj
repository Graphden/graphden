(ns graphden.editor.component-creation
  "Create-only fixed UI bundles. Preview is an internal recovery protocol;
   apply owns one atomic decorated transaction and never revives a UUID."
  (:require
    [graphden.crud.entities.invalidation :as invalidation]
    [graphden.crud.request :as request]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-templates :as templates]
    [graphden.executor.browser-contracts :as contracts]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.sync :as sync]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router.epoch :as router-epoch]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]
    [graphden.util.abort-shield :as shield]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.storage.core :as versioned]
    [graphden.versioning.storage.resolution :as resolution]))


(defn- refuse!
  [reason status]
  (throw (ex-info "Personal UI graphs could not be created"
                  {:reason reason :http-status status})))


(defn- canonical
  [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key v]] [key (canonical v)])) value)
    (set? value) (sort-by pr-str (map canonical value))
    (sequential? value) (mapv canonical value)
    :else value))


(defn- identity-order
  [rows]
  (vec (sort-by (comp str :id) rows)))


(defn- digest
  [value]
  (binding [*print-length* nil *print-level* nil]
    (ids/digest-hex "SHA-256" (pr-str (canonical value)))))


(defn- scope
  [storage]
  (let [branch (versioned/current-branch-id storage)]
    (when-not (uuid? branch) (refuse! :invalid-branch 403))
    {:owner (tenancy/current-user-id) :org (tenancy/current-org) :branch-id (str branch)}))


(defn- parse-command
  [storage input preview?]
  (let [current (scope storage)
        allowed (if preview? #{:owner :namespace-id}
                    #{:owner :org :branch-id :namespace-id :root-id :expected-state})
        parent (request/parse-uuid-or-clear (:namespace-id input))
        root-id (if preview? (random-uuid) (request/parse-uuid-or-clear (:root-id input)))]
    (when-not (and (map? input) (= allowed (set (keys input))) root-id
                   (or (nil? (:namespace-id input)) parent))
      (refuse! :invalid-request 400))
    (when-not (and (= (:owner input) (:owner current))
                   (or preview? (= current (select-keys input [:owner :org :branch-id]))))
      (refuse! :changed-session 403))
    (when (and (not preview?) (not (string? (:expected-state input))))
      (refuse! :invalid-request 400))
    (assoc current :namespace-id parent :root-id root-id)))


(defn- plan-namespaces
  [storage command authorize!]
  (let [parent (:namespace-id command)
        proposed {:id (:root-id command) :org-id (:org command) :namespace-id parent}
        _ (authorize! proposed {:write? true})
        rows (source/collect-namespaces storage {:fns [proposed]})
        path (when parent (get (ns-path/path-map rows) parent))
        name (str "ui-" (:root-id command))
        root (if path (str path "." name) name)
        root-row {:id (:root-id command) :name name :parent-id parent :path root}
        children (mapv (fn [name]
                         {:id (ids/uuid-v5 (:root-id command) (str "namespace:" name))
                          :name name :parent-id (:root-id command) :path (str root "." name)})
                       templates/namespace-suffixes)]
    (writer/assert-write-authorized! storage :ns (dissoc root-row :path) nil)
    {:root root :rows (into [root-row] children) :parent-rows rows}))


(defn- existing-identities!
  [storage records namespaces]
  (let [base (versioned/unwrap storage)]
    (doseq [[entity rows] (assoc (group-by :kind records) :ns namespaces)]
      ;; Reading identity rows, rather than resolved versions, rejects a
      ;; tombstone too. Hidden collisions are still rejected by CREATE's
      ;; database identity constraint; no UPSERT follows an absent read.
      (when (seq (sp/read-entities base entity (mapv :id rows)))
        (refuse! :identity-conflict 409)))))


(defn- plan
  [storage command]
  (let [authorize! (tenancy/browser-source-policy storage)
        {:keys [root rows parent-rows]} (plan-namespaces storage command authorize!)
        parsed (bundle/parse root)
        ns-ids (into {} (map (juxt :path :id)) rows)
        records (mapv (fn [row]
                        (cond-> row (= :fn (:kind row))
                                (assoc :namespace-id (get ns-ids (:namespace-id row))))) (:records parsed))
        external-ids (bundle/external-identities records)
        discriminator-ids (filter #(contains? contracts/component-identities %) external-ids)
        metadata (source/collect-component-identities storage discriminator-ids authorize!)
        external (-> (source/collect-closure storage
                                             (remove #(contains? contracts/component-identities %) external-ids)
                                             authorize!)
                     (update :fns into metadata))
        functions (mapv #(select-keys % [:id :name :namespace-id])
                        (identity-order (filter #(= :fn (:kind %)) records)))
        manifest {:namespaces rows :functions functions :roots (templates/descriptor root)}
        expected (digest {:command command :manifest manifest :records (identity-order records)
                          :parent-rows (identity-order parent-rows)
                          :external (update-vals external identity-order)})]
    ;; The namespace does not exist yet. Its parent's write grant is proven
    ;; freshly above; this equivalent existing destination also proves branch
    ;; admission before a preview can stage any identities.
    (writer/assert-creation-authorized!
      storage (assoc (first (filter #(and (= :fn (:kind %))
                                          (= (ids/fn-id root :ui) (:id %))) records))
                     :namespace-id (:namespace-id command))
      (versioned/current-branch-id storage))
    (existing-identities! storage records rows)
    {:records records :manifest manifest :expected-state expected :command command}))


(defn- wire-manifest
  [manifest]
  ;; JSON handles UUID values; explicitly stringify here for graph callers and
  ;; pre-apply client ledgers, not just callers going through a JSON encoder.
  (-> manifest
      (update :namespaces #(mapv (fn [row] (-> row (update :id str) (update :parent-id (fn [id] (some-> id str))))) %))
      (update :functions #(mapv (fn [row] (-> row (update :id str) (update :namespace-id str))) %))))


(defn failure-response
  "Classify a rejected creation without returning exception text or data."
  [error]
  (let [{:keys [http-status type]} (ex-data error)
        status (or http-status
                   (cond
                     (= type :authz/forbidden) 403
                     (= type :quota/entity-limit) 429
                     (contains? #{:browser-plan/unsupported :storage-error/candidate-limit
                                  :storage-error/bounded-query-unsupported} type) 422
                     :else 500))
        reason (case status
                 400 "The UI graph creation request is invalid. Refresh and try Create again."
                 403 "Choose an available writable namespace for your UI graphs."
                 409 "The UI graph preview has changed. Refresh and try Create again."
                 422 "This UI graph template is unavailable. Choose another template or try again later."
                 429 "Your plan's graph limit has been reached. Free capacity or upgrade your plan before creating UI graphs."
                 "UI graphs were not created. Try Create again after refreshing.")]
    {:ok false :committed false :reason reason :http-status status}))


(defn preview
  [ctx input]
  (try
    (let [storage (request/require-storage ctx)
          command (parse-command storage input true)]
      (writer/with-write [storage :graph]
                         (resolution/call-with-fresh-memos
                           #(let [prepared (plan storage command)]
                              {:ok true :request (-> command (update :namespace-id (fn [id] (some-> id str)))
                                                     (update :root-id str) (assoc :expected-state (:expected-state prepared)))
                               :manifest (wire-manifest (:manifest prepared))}))))
    (catch Exception error
      (failure-response error))))


(defn- create!
  [storage prepared]
  (let [records (:records prepared)]
    (when (or (sync/records-seal-rej storage records) (sync/records-vault-path-rej storage records))
      (refuse! :invalid-template-source 422))
    (doseq [row (get-in prepared [:manifest :namespaces])]
      (when-not (sp/create-entity storage :ns (dissoc row :path))
        (refuse! :write-refused 403)))
    (doseq [entity [:fn :slot :fn-slot :binding :binding-list-item]
            row (filter #(= entity (:kind %)) records)]
      (when-not (sp/create-entity storage entity (dissoc row :kind))
        (refuse! :write-refused 403)))
    {:ok true :committed true :manifest (wire-manifest (:manifest prepared))}))


(defn- publish
  [ctx storage result]
  (let [fn-ids (mapv #(request/parse-uuid-or-clear (:id %)) (get-in result [:manifest :functions]))]
    (try
      (context/invalidate-graph-cache! ctx fn-ids)
      (recheck/record-imported-fn-types! ctx (versioned/current-branch-id storage) fn-ids)
      (doseq [id fn-ids]
        (invalidation/notify-after-write! ctx storage :fn :write
                                          {:id id :org-id (tenancy/current-org)}))
      (router-epoch/note-graph-epoch-validated! storage)
      result
      (catch Exception _
        (assoc result :publication-warnings
               [{:reason "Your UI graphs were created. Refresh the editor to reload their derived state."}])))))


(defn apply!
  [ctx input]
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            result (try
                     (tx/assert-owns-commit! storage)
                     (let [command (parse-command storage input false)]
                       (writer/with-write [storage :graph]
                                          (resolution/call-with-fresh-memos
                                            #(let [prepared (plan storage command)]
                                               (when-not (= (:expected-state input) (:expected-state prepared))
                                                 (refuse! :stale-preview 409))
                                               (create! storage prepared)))))
                     (catch Exception error
                       (failure-response error)))]
        (if (:committed result) (publish ctx storage result) result)))))
