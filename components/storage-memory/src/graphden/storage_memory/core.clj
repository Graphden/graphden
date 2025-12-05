(ns graphden.storage-memory.core
  "In-memory implementation of Storage protocol using atoms"
  (:require
   [graphden.storage.interface :as storage]
   [integrant.core :as ig]))

(defrecord MemoryStorage [data-atom watchers-atom]
  storage/StorageInfo
  (storage-type [_] :memory)
  (storage-capabilities [_] #{:crud :batch :watch})

  storage/Storage
  (put [this entity-type id data]
    (swap! data-atom assoc-in [entity-type id] data)
    ;; Notify watchers
    (doseq [[_ watcher-fn] @watchers-atom]
      (watcher-fn {:op :put :entity-type entity-type :id id :data data}))
    this)

  (get-by-id [_ entity-type id]
    (get-in @data-atom [entity-type id]))

  (delete [this entity-type id]
    (let [old-data (get-in @data-atom [entity-type id])]
      (swap! data-atom update entity-type dissoc id)
      ;; Notify watchers
      (doseq [[_ watcher-fn] @watchers-atom]
        (watcher-fn {:op :delete :entity-type entity-type :id id :old-data old-data}))
      this))

  (update-entity [this entity-type id update-fn]
    (let [old-data (get-in @data-atom [entity-type id])
          new-data (update-fn old-data)]
      (swap! data-atom assoc-in [entity-type id] new-data)
      ;; Notify watchers
      (doseq [[_ watcher-fn] @watchers-atom]
        (watcher-fn {:op :update :entity-type entity-type :id id
                     :old-data old-data :new-data new-data}))
      this))

  (find-by [_ entity-type field value]
    (->> (get @data-atom entity-type)
         vals
         (filter #(= (get % field) value))))

  (get-all [_ entity-type]
    (vals (get @data-atom entity-type)))

  (exists? [_ entity-type id]
    (contains? (get @data-atom entity-type) id)))

(defn create-storage
  "Create new MemoryStorage instance"
  ([]
   (create-storage {}))
  ([initial-data]
   (->MemoryStorage (atom initial-data) (atom {}))))

(defn add-watcher
  "Add a watcher function that will be called on storage changes.
   Returns watcher-id that can be used to remove it."
  [storage watcher-fn]
  (let [watcher-id (gensym "watcher-")]
    (swap! (:watchers-atom storage) assoc watcher-id watcher-fn)
    watcher-id))

(defn remove-watcher
  "Remove watcher by id"
  [storage watcher-id]
  (swap! (:watchers-atom storage) dissoc watcher-id))

(defn get-snapshot
  "Get immutable snapshot of current data"
  [storage]
  @(:data-atom storage))

;; Integrant integration
(defmethod ig/init-key ::storage
  [_ {:keys [initial-data]}]
  (create-storage (or initial-data {})))

(defmethod ig/halt-key! ::storage
  [_ storage]
  ;; Clear watchers on shutdown
  (reset! (:watchers-atom storage) {})
  nil)
