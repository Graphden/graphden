(ns graphden.crud.inheritance.plan
  "Transient inheritance intent: exact use-site, projected rows and guards."
  (:require
    [clojure.string :as str]
    [graphden.crud.inheritance.check :as check]
    [graphden.crud.inheritance.overlay :as overlay]
    [graphden.crud.inheritance.snapshot :as snap]
    [graphden.crud.package-guard :as pkg]
    [graphden.crud.secret-shape :as secret-shape]
    [graphden.crud.type-check :as tc]
    [graphden.crud.validation :as validation]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn reject!
  [type reason]
  (throw (ex-info reason {:type type :reason reason})))


(defn- own-ref!
  [storage command]
  (let [binding (sp/read-entity storage :binding (:binding-id command))]
    (when-not (and binding (= (:owner-fn-id command) (:fn-id binding))
                   (= (:slot-id command) (:slot-id binding))
                   (= (:source-fn-id command) (:expected-old-ref-id command) (:ref-fn-id binding))
                   (not (:value-present? binding)) (not (:value-present binding))
                   (empty? (sp/query-entities storage :binding-list-item {:binding-id (:id binding)})))
      (reject! :inheritance/use-site-changed "The own reference binding no longer matches this source"))
    binding))


(defn- parent-edge!
  [target command]
  (when-not (= [(:source-fn-id command)] (vec (:parent-ids target)))
    (reject! :inheritance/use-site-changed "Local variation requires one immediate parent on the target"))
  (when-not (= (:source-fn-id command) (:expected-parent-id command))
    (reject! :inheritance/use-site-changed "The expected parent no longer matches the source")))


(defn- normalized-command
  [command target]
  (if (= "variation" (:action command))
    (let [id (or (:proposed-fn-id command) (random-uuid))]
      (when (and (contains? command :namespace-id)
                 (not= (:namespace-id command) (:namespace-id target)))
        (reject! :inheritance/namespace-changed "Local variation uses the writable target's namespace"))
      (assoc command :proposed-fn-id id :namespace-id (:namespace-id target)
             :proposed-name (or (:proposed-name command) (str "_variation-" (subs (str id) 0 12)))))
    command))


(defn- copied-row
  [storage command entity source]
  (-> (select-keys source (keys (sp/current-fields storage entity)))
      (dissoc :org-id :anonymous-hash)
      (assoc :id (ids/uuid-v5 (:proposed-fn-id command) (str (name entity) "/" (:id source)))
             :fn-id (:proposed-fn-id command))))


(defn- clone-rows
  [storage command source rows]
  (let [own (snap/own-rows rows (:id source))
        bindings (mapv #(copied-row storage command :binding %) (:binding own))
        by-source (zipmap (map :id (:binding own)) (map :id bindings))
        fn-row (-> (select-keys source (keys (sp/current-fields storage :fn)))
                   (dissoc :anonymous-hash :org-id)
                   (assoc :id (:proposed-fn-id command) :name (:proposed-name command)
                          :namespace-id (:namespace-id command)))]
    {:fn [fn-row]
     :fn-slot (mapv #(copied-row storage command :fn-slot %) (:fn-slot own))
     :binding bindings
     :binding-list-item
     (mapv (fn [row]
             (-> (select-keys row (keys (sp/current-fields storage :binding-list-item)))
                 (dissoc :org-id)
                 (assoc :id (ids/uuid-v5 (:proposed-fn-id command) (str "item/" (:id row)))
                        :binding-id (get by-source (:binding-id row))))) (:binding-list-item own))}))


(defn- graph-view
  [rows]
  {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
   :bindings (:binding rows) :list-items (:binding-list-item rows)})


(defn- slot-surface
  [storage fn-id]
  (let [rows (snap/graph-rows storage (snap/closure storage [fn-id]))
        view (lookups/build-lookups (graph-view rows))
        names (validation/visible-slot-names fn-id view)
        by-name (group-by val names)]
    (when-let [[nm pairs] (some #(when (> (count (val %)) 1) %) by-name)]
      (reject! :constraint-violation/mi-collision
               (str "Different immutable slots use the same argument name " nm ": " (mapv key pairs))))
    ;; Source slots of renamed views remain usable binding identities.
    (loop [visible (set (keep :slot-id (:fn-slot rows))) frontier (set (keep :slot-id (:fn-slot rows)))]
      (if (empty? frontier) visible
          (let [sources (into #{} (keep :source-slot-id) (vals (sp/read-entities storage :slot (vec frontier))))
                unseen (reduce disj sources visible)]
            (recur (into visible unseen) unseen))))))


(defn- check-row!
  [storage entity data]
  (when-let [rejection (validation/write-rej storage entity data)]
    (reject! (:type rejection) (:reason rejection)))
  (when (and (= entity :fn)
             (some (secret-shape/find-admin-only-vault-base-fn-ids storage) (:parent-ids data)))
    (reject! :capability/secret-leaf-restricted "Admin-only secret sources require the Secrets flow")))


(defn- check-readable-refs!
  [storage rows]
  (let [refs (into #{} (keep identity)
                   (concat (mapcat (juxt :ref-fn-id :type-override-fn-id :resolver-fn-id) (:binding rows))
                           (keep :ref-fn-id (:binding-list-item rows))
                           (mapcat (juxt :type-fn-id :element-fn-id) (:slot rows))))]
    (when-not (= refs (set (keys (sp/read-entities storage :fn (vec refs)))))
      (reject! :inheritance/incomplete-source "The source contains an unavailable reference"))))


(defn- source-eligible!
  [storage command source target]
  (when (= "variation" (:action command))
    (when-not (and (= 1 (count (:parent-ids source)))
                   (nil? (:base-fn-id source)) (nil? (:constraint source)))
      (reject! :inheritance/unsupported-source "Local variation requires a composed source with one parent"))
    (when-not (str/starts-with? (:proposed-name command) "_")
      (reject! :inheritance/private-name-required "A local variation name must start with _"))
    (when (sp/read-entity (vs/unwrap storage) :fn (:proposed-fn-id command))
      (reject! :inheritance/identity-exists "The proposed local identity already exists"))
    (when (= "parent-edge" (:kind command)) (parent-edge! target command))))


(defn- projected-plan
  [storage command target source]
  (let [variation? (= "variation" (:action command))
        target-id (:id target)
        binding (when (and variation? (= "own-ref" (:kind command))) (own-ref! storage command))
        fn-rows (snap/closure storage (concat [target-id (:id source)] (:parent-ids command)))
        rows (snap/graph-rows storage fn-rows)
        _ (check-readable-refs! storage rows)
        clones (if variation? (clone-rows storage command source rows) {})
        fn-change (when-not binding
                    (assoc target :parent-ids (if variation? [(:proposed-fn-id command)] (:parent-ids command))))
        changes (cond-> (into {} (map (fn [[entity rows]] [entity (into {} (map (juxt :id identity)) rows)])) clones)
                  fn-change (update :fn assoc target-id fn-change)
                  binding (update :binding assoc (:id binding) (assoc binding :ref-fn-id (:proposed-fn-id command))))
        projected (overlay/project storage changes {})
        slots (slot-surface projected target-id)
        own (snap/own-rows rows target-id)
        orphans (if binding [] (filterv #(not (contains? slots (:slot-id %))) (:binding own)))
        orphan-ids (set (map :id orphans))
        orphan-items (filterv #(contains? orphan-ids (:binding-id %)) (:binding-list-item own))
        removed {:binding orphan-ids :binding-list-item (set (map :id orphan-items))}]
    {:command command :target target :source source :binding binding :rows rows :own own
     :fn-change fn-change :changes changes :clones clones :orphans orphans :orphan-items orphan-items
     :projected (overlay/project storage changes removed)}))


(defn- authorize-plan!
  [storage {:keys [command target binding fn-change clones orphans orphan-items]}]
  (when-let [reason (pkg/write-rejection storage :fn {:id (:id target)})]
    (reject! :inheritance/package-owned reason))
  (if binding
    (writer/assert-write-authorized! storage :binding {:ref-fn-id (:proposed-fn-id command)} (:id binding))
    (do (writer/assert-write-authorized! storage :fn {:parent-ids (:parent-ids fn-change)} (:id target))
        ;; Compare the REAL old parent identity, never the projected new row.
        (when-let [rejection (validation/reparent-cross-branch-rej storage :fn fn-change)]
          (reject! :constraint-violation/reparent-cross-branch (:reason rejection)))))
  (when (= "variation" (:action command))
    (writer/assert-creation-authorized! storage (first (:fn clones)) (:branch-id storage)))
  (doseq [row orphans] (writer/assert-write-authorized! storage :binding {} (:id row)))
  (doseq [row orphan-items] (writer/assert-write-authorized! storage :binding-list-item {} (:id row))))


(defn- validate-plan!
  [{:keys [command projected clones own changes orphans fn-change target]}]
  (let [orphan-ids (set (map :id orphans))]
    (when fn-change (check-row! projected :fn fn-change))
    (doseq [[entity new-rows] clones row new-rows] (check-row! projected entity row))
    (doseq [row (:binding own) :when (not (contains? orphan-ids (:id row)))]
      (check-row! projected :binding (or (get-in changes [:binding (:id row)]) row)))
    (doseq [row (:binding-list-item own) :when (not (contains? orphan-ids (:binding-id row)))]
      (check-row! projected :binding-list-item row))
    (let [diagnostics (check/projected-diagnostics projected
                                                   (cond-> [] (:proposed-fn-id command) (conj (:proposed-fn-id command))
                                                           true (conj (:id target))))]
      (when-let [secret (some #(when (tc/secret-diagnostic? (:diagnostic %)) %) diagnostics)]
        (reject! :type-check/secret-flow (:reason secret)))
      diagnostics)))


(defn build
  "Build a transient plan from current decorated reads. Readable source/target
   descriptors survive writable and unsupported-source refusals."
  [storage requested]
  (let [target (snap/required-fn storage (or (:target-fn-id requested) (:owner-fn-id requested)))
        source-id (or (:source-fn-id requested) (first (:parent-ids target)))
        source (when source-id (snap/required-fn storage source-id))
        ;; Namespace mismatch is a readable refusal, so normalize only after
        ;; assembling the descriptors that the source-navigation action uses.
        command (normalized-command (dissoc requested :namespace-id) target)
        descriptors (snap/descriptors storage (remove nil? [source target]))
        model (cond-> {:ok true :request command :target (get descriptors (:id target))}
                source (assoc :source (get descriptors source-id))
                (= "variation" (:action command))
                (assoc :proposed {:id (:proposed-fn-id command) :name (:proposed-name command)
                                  :namespace-id (:namespace-id command)}))]
    (try
      (when (and (= "variation" (:action command)) (contains? requested :namespace-id)
                 (not= (:namespace-id requested) (:namespace-id target)))
        (reject! :inheritance/namespace-changed "Local variation uses the writable target's namespace"))
      (source-eligible! storage command source target)
      (let [plan (projected-plan storage command target source)
            _ (authorize-plan! storage plan)
            diagnostics (validate-plan! plan)]
        (assoc plan :diagnostics diagnostics
               :model (assoc model :allowed true :expected-state (snap/fingerprint storage command (:rows plan))
                             :orphan-binding-ids (mapv :id (:orphans plan))
                             :orphan-item-ids (mapv :id (:orphan-items plan)))))
      (catch clojure.lang.ExceptionInfo e
        {:model (assoc model :allowed false :type (:type (ex-data e))
                       :reason (or (:reason (ex-data e)) (ex-message e)))}))))
