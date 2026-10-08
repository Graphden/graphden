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


(defn- candidates
  [storage command]
  (snap/required-fn storage (:target-fn-id command))
  (let [source (snap/required-fn storage (:source-fn-id command))
        ancestor-rows (snap/closure storage [(:id source)])
        descriptors (snap/descriptors storage (vals ancestor-rows))]
    {:ok true
     :candidates
     (mapv (fn [id]
             (merge (get descriptors id) {:current? (= id (:id source))}
                    (:model (fresh-plan storage {:action "reparent" :kind "parent-edge"
                                                 :target-fn-id (:target-fn-id command) :parent-ids [id]}))))
           ;; The bounded single-parent chain is a recommendation list, never
           ;; advertised as a catalogue of every compatible function.
           (loop [ids [] current (:id source) seen #{}]
             (if (or (nil? current) (contains? seen current)) ids
                 (let [row (get ancestor-rows current)]
                   (recur (conj ids current)
                          (when (= 1 (count (:parent-ids row))) (first (:parent-ids row)))
                          (conj seen current))))))}))


(defn preview
  [ctx command]
  (let [storage (request/require-storage ctx)]
    (writer/with-write [storage {:entity :fn :ids [(or (:target-fn-id command) (:owner-fn-id command))]}]
                       (if (= "candidates" (:action command))
                         (candidates storage command)
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
        target-id (:id (:target plan))]
    {:response (cond-> {:ok true :action (:action command) :kind (:kind command)
                        :fn-id (or clone-id target-id) :target-fn-id target-id}
                 clone-id (assoc :created-fn-id clone-id)
                 (:binding plan) (assoc :binding-id (:id (:binding plan))))
     :publication-rows (mapv #(snap/required-fn storage %)
                             (cond-> [target-id] clone-id (conj clone-id)))}))


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
    (writer/with-write [storage {:entity :fn :ids [(or (:target-fn-id command) (:owner-fn-id command))]}]
                       (let [plan (fresh-plan storage command)]
                         (apply-plan! storage plan command)))
    (catch clojure.lang.ExceptionInfo e
      (let [reason (or (:reason (ex-data e)) (ex-message e))]
        {:response {:ok false :committed false :type (:type (ex-data e))
                    :reason reason :error reason
                    :http-status (errors/status-for-ex-data (ex-data e))}}))))


(defn apply!
  [ctx command]
  (shield/run!
    (fn []
      (if (= "candidates" (:action command))
        {:ok false :committed false :type :validation-error/inheritance-command
         :error "Candidates is a preview-only action" :http-status 400}
        (let [storage (request/require-storage ctx)
              result (transact! storage command)]
          (if (get-in result [:response :ok])
            (publish! ctx storage result)
            (:response result)))))))
