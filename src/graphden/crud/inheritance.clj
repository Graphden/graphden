(ns graphden.crud.inheritance
  "Preview and atomic apply for inheritance intents, preserving ordinary PUT."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.entities.invalidation :as inval]
    [graphden.crud.inheritance.command :as command]
    [graphden.crud.inheritance.plan :as plan]
    [graphden.crud.inheritance.snapshot :as snap]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.validation :as validation]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]
    [graphden.web.errors :as errors]))


(def parse-command command/parse-command)


(defn- fresh-plan
  [storage command]
  (res/call-with-fresh-memos #(plan/build storage command)))


(defn- ancestor-order
  "Nearest ancestors first, preserving each parent's declared order. Diamond
   joins appear once so the picker offers one identity per candidate."
  [rows source-id]
  (loop [frontier [source-id] seen #{} result []]
    (if (empty? frontier)
      result
      (let [new-ids (vec (distinct (remove seen frontier)))
            seen (into seen new-ids)]
        (recur (into [] (mapcat #(get-in rows [% :parent-ids])) new-ids)
               seen (into result new-ids))))))


(defn- candidates
  [storage command]
  (let [target (snap/required-fn storage (:target-fn-id command))
        edge-index (plan/selected-parent-index! target command)
        source (snap/required-fn storage (:source-fn-id command))
        ancestor-rows (snap/closure storage [(:id source)])
        descriptors (snap/descriptors storage (vals ancestor-rows))]
    {:ok true
     :candidates
     (mapv (fn [id]
             (merge (get descriptors id) {:current? (= id (:id source))}
                    (:model (fresh-plan storage {:action "reparent" :kind "parent-edge"
                                                 :target-fn-id (:target-fn-id command)
                                                 :parent-ids (assoc (vec (:parent-ids target)) edge-index id)}))))
           (ancestor-order ancestor-rows (:id source)))}))


(defn- batch-rows
  [plans]
  (let [rows (reduce (fn [result {:keys [rows]}]
                       (reduce-kv (fn [result entity entries]
                                    (update result entity (fnil merge {})
                                            (into {} (map (juxt :id identity)) entries)))
                                  result rows)) {} plans)]
    (update-vals rows #(vec (vals %)))))


(defn- dependent-batch-targets?
  [plans]
  (let [target-ids (set (map (comp :id :target) plans))]
    (some (fn [{:keys [target rows]}]
            (some #(and (not= (:id target) (:id %))
                        (contains? target-ids (:id %))) (:fn rows)))
          plans)))


(defn- colliding-batch-identities?
  [plans]
  (let [target-ids (set (map (comp :id :target) plans))
        clone-ids (mapcat #(map :id (get-in % [:clones :fn])) plans)]
    (or (not= (count clone-ids) (count (set clone-ids)))
        (some target-ids clone-ids))))


(defn- row-ids
  [rows]
  (->> rows (map :id) distinct (sort-by str) vec))


(defn- batch-write-set
  [plans]
  {:fn (row-ids (concat (map :target plans) (mapcat #(get-in % [:clones :fn]) plans)))
   :fn-slot (row-ids (mapcat #(get-in % [:clones :fn-slot]) plans))
   :binding (row-ids (concat (mapcat :orphans plans)
                             (mapcat #(get-in % [:clones :binding]) plans)))
   :binding-list-item (row-ids (concat (mapcat :orphan-items plans)
                                       (mapcat #(get-in % [:clones :binding-list-item]) plans)))})


(defn- batch-plan
  [storage command]
  (let [plans (mapv #(fresh-plan storage %) (:commands command))
        targets (mapv :model plans)
        refusal (some #(when-not (:allowed %) %) targets)
        related? (and (not refusal) (dependent-batch-targets? plans))
        collision? (and (not refusal) (colliding-batch-identities? plans))]
    (cond
      refusal {:model {:ok true :action "batch" :allowed false :targets targets
                       :type (:type refusal) :reason (:reason refusal)}}
      related? {:model {:ok true :action "batch" :allowed false :targets targets
                        :type :inheritance/batch-dependent-targets
                        :reason "Targets depend on one another; split this batch into independent changes"}}
      collision? {:model {:ok true :action "batch" :allowed false :targets targets
                          :type :inheritance/batch-identity-collision
                          :reason "Batch changes create overlapping function identities"}}
      :else (let [rows (batch-rows plans)
                  normalized (assoc command :commands (mapv (comp :request :model) plans))
                  read-set (update-vals rows row-ids)
                  write-set (batch-write-set plans)
                  read-ids (:fn read-set)
                  write-ids (:fn write-set)
                  orphan-ids (vec (mapcat #(get-in % [:model :orphan-binding-ids]) plans))
                  orphan-item-ids (vec (mapcat #(get-in % [:model :orphan-item-ids]) plans))]
              {:plans plans :rows rows
               :model {:ok true :action "batch" :allowed true :request normalized
                       :targets targets :read-set read-set :write-set write-set
                       :read-fn-ids read-ids :write-fn-ids write-ids
                       :orphan-binding-ids orphan-ids
                       :orphan-item-ids orphan-item-ids
                       :expected-state (snap/fingerprint storage normalized rows)}}))))


(defn- mutation-ids
  [command]
  (if (= "batch" (:action command))
    (mapv :target-fn-id (:commands command))
    [(or (:target-fn-id command) (:owner-fn-id command))]))


(defn preview
  [ctx command]
  (let [storage (request/require-storage ctx)]
    (writer/with-write [storage {:entity :fn :ids (mutation-ids command)}]
                       (case (:action command)
                         "candidates" (candidates storage command)
                         "batch" (:model (batch-plan storage command))
                         (:model (fresh-plan storage command))))))


(defn- checked-result
  [result]
  (or result (plan/reject! :inheritance/write-refused "The decorated storage refused this write")))


(defn- create-clone!
  [storage clones]
  (doseq [entity [:fn :fn-slot :binding :binding-list-item]
          row (get clones entity)]
    (when-let [rejection (validation/write-rej storage entity row)]
      (plan/reject! (:type rejection) (:reason rejection)))
    (checked-result (sp/create-entity storage entity row))))


(defn- verify-preview!
  [plan command]
  (let [model (:model plan)]
    (when-not (:allowed model)
      (plan/reject! (:type model) (:reason model)))
    (when-not (and (string? (:expected-state command))
                   (= (:expected-state command) (:expected-state model)))
      (plan/reject! :inheritance/stale-preview "The graph changed. Review a fresh preview before applying"))
    (when-not (= (set (:accepted-orphan-binding-ids command))
                 (set (:orphan-binding-ids model)))
      (plan/reject! :inheritance/orphans-not-accepted "Accept exactly the orphan bindings shown in this preview"))))


(defn- apply-plan!
  [storage plan command]
  (verify-preview! plan command)
  (create-clone! storage (:clones plan))
  (binding [vs/*tombstone-delete?* true]
    (doseq [row (:orphan-items plan)]
      (checked-result (sp/delete-entity storage :binding-list-item (:id row))))
    (doseq [row (:orphans plan)]
      (checked-result (sp/delete-entity storage :binding (:id row)))))
  (if-let [binding (:binding plan)]
    (let [id (:id binding)
          ref-id (:proposed-fn-id (:command plan))]
      (checked-result (sp/update-entity storage :binding id {:ref-fn-id ref-id}))
      (when-not (= ref-id (:ref-fn-id (sp/read-entity storage :binding id)))
        (plan/reject! :inheritance/write-refused "The own reference replacement did not land")))
    (let [id (:id (:target plan))
          parents (:parent-ids (get-in plan [:changes :fn id]))]
      (checked-result (sp/update-entity storage :fn id {:parent-ids parents}))
      (when-not (= (vec parents) (vec (:parent-ids (sp/read-entity storage :fn id))))
        (plan/reject! :inheritance/write-refused "The parent replacement did not land"))))
  (let [command (:command plan)
        clone-id (:proposed-fn-id command)
        target-id (:id (:target plan))
        publication-ids (into [target-id] (map :id) (get-in plan [:clones :fn]))]
    {:response (cond-> {:ok true :action (:action command) :kind (:kind command)
                        :fn-id (or clone-id target-id) :target-fn-id target-id}
                 clone-id (assoc :created-fn-id clone-id)
                 (:binding plan) (assoc :binding-id (:id (:binding plan))))
     :publication-rows (mapv #(snap/required-fn storage %) publication-ids)}))


(defn- apply-batch!
  [storage batch command]
  (let [model (:model batch)]
    (when-not (:allowed model)
      (plan/reject! (:type model) (:reason model)))
    (when-not (and (string? (:expected-state command))
                   (= (:expected-state command) (:expected-state model)))
      (plan/reject! :inheritance/stale-preview "The batch changed. Review a fresh preview before applying"))
    (when-not (= (set (:accepted-orphan-binding-ids command))
                 (set (:orphan-binding-ids model)))
      (plan/reject! :inheritance/orphans-not-accepted
                    "Accept exactly the orphan bindings shown in this batch preview"))
    (let [results (mapv (fn [item]
                          (apply-plan! storage item
                                       (assoc (:command item)
                                              :expected-state (get-in item [:model :expected-state])
                                              :accepted-orphan-binding-ids
                                              (get-in item [:model :orphan-binding-ids]))))
                        (:plans batch))]
      {:response {:ok true :action "batch"
                  :target-fn-ids (mapv (comp :id :target) (:plans batch))
                  :targets (mapv :response results)}
       :publication-rows (vec (mapcat :publication-rows results))})))


(defn- publication-step!
  [warnings stage fn-id f]
  (try (f)
       (catch Exception e
         (log/warn e "Committed inheritance write needs publication refresh"
                   {:stage stage :fn-id fn-id})
         (swap! warnings conj {:stage stage :fn-id fn-id
                               :reason "The change committed; refresh the editor to reload derived state"})
         nil)))


(defn- publish!
  [ctx storage {:keys [response publication-rows]}]
  ;; Captured postimages permit invalidation even if another writer deletes a
  ;; row immediately after commit. Publication failures cannot undo the change.
  (let [warnings (atom [])]
    (res/forget-read-memos!)
    (publication-step! warnings :resolution nil #(bl/invalidate! (vs/unwrap storage)))
    (res/call-with-fresh-memos
      (fn []
        ;; Cache invalidation precedes every fallible type-check/notification.
        (doseq [row publication-rows]
          (publication-step! warnings :invalidate (:id row)
                             #(inval/invalidate! ctx storage :fn row)))
        (let [failed-ids (into #{} (keep #(when (= :invalidate (:stage %)) (:fn-id %))) @warnings)]
          (doseq [row publication-rows]
            ;; A failed local publication must be repaired by its echo too.
            ;; Another row can cover this request's bumps, so the epoch heal
            ;; alone cannot replace this explicit retry on the origin.
            (publication-step! warnings :notify (:id row)
                               #(inval/notify-after-write!
                                  ctx storage :fn :write
                                  (cond-> row
                                    (contains? failed-ids (:id row))
                                    (assoc :invalidate-origin? true))))))
        (let [type-warnings (into []
                                  (keep (fn [row]
                                          (some-> (publication-step!
                                                    warnings :type-check (:id row)
                                                    #(tc/type-check-fn-and-dependents! ctx storage (:id row)))
                                                  :diagnostic))) (reverse publication-rows))]
          (cond-> (assoc response :committed true)
            (seq type-warnings) (assoc :type-warnings type-warnings)
            (seq @warnings) (assoc :publication-warnings @warnings)))))))


(defn- transact!
  [storage command]
  (try
    (tx/assert-owns-commit! storage)
    (writer/with-write [storage {:entity :fn :ids (mutation-ids command)}]
                       (if (= "batch" (:action command))
                         (apply-batch! storage (batch-plan storage command) command)
                         (apply-plan! storage (fresh-plan storage command) command)))
    (catch clojure.lang.ExceptionInfo e
      (let [reason (or (:reason (ex-data e)) (ex-message e))]
        {:response {:ok false :committed false :type (:type (ex-data e))
                    :reason reason :error reason
                    :http-status (errors/status-for-ex-data (ex-data e))}}))))


(defn apply-operation!
  "Owned mutation and mandatory publication remain inseparable."
  [ctx command]
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            result (transact! storage command)]
        (if (get-in result [:response :ok])
          (publish! ctx storage result)
          (:response result))))))


(defn apply!
  "Native command facade; graph callers compose preview-only command policy."
  [ctx command]
  (if (= "candidates" (:action command))
    {:ok false :committed false :type :validation-error/inheritance-command
     :error "Candidates is a preview-only action" :http-status 400}
    (apply-operation! ctx command)))
