(ns graphden.versioning.storage.small-graph
  "Fresh bounded acquisition for small execution closures. Dependency hints
   choose the strategy only; all returned values use the existing resolver."
  (:require
    [clojure.set :as set]
    [graphden.executor.compile.deps :as deps]
    [graphden.storage.bounded-query :as bounded]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.resolution :as resolution]))


(defn hinted-ids
  "Select a small complete advisory closure without touching storage.
   Unknown and large contexts keep the constant-query whole-branch path."
  [forward-deps root-id]
  (when (contains? forward-deps root-id)
    (let [ids (deps/forward-closure forward-deps [root-id])]
      (when (and (<= (count ids) 64) (every? #(contains? forward-deps %) ids))
        ids))))


(defn- limit!
  [ok?]
  (when-not ok?
    (throw (ex-info "Small execution graph exceeded acquisition bounds"
                    {:type ::limit}))))


(defn- read-resolved
  [storage branch entity ids]
  (if (empty? ids)
    {}
    (resolution/resolve-entities-batch storage entity
                                       (vals (sp/read-entities storage entity (vec ids))) branch)))


(defn- owner-rows
  [storage branch entity owner owners budget]
  (if (empty? owners)
    []
    (let [where {owner (vec owners)}
          candidates
          (reduce (fn [ids version-source]
                    (let [rows (bounded/candidate-ids storage entity where version-source @budget)
                          count-read (or (::sp/candidate-count (meta rows)) (count rows))]
                      (swap! budget - count-read)
                      (limit! (not (neg? @budget)))
                      (into ids rows)))
                  #{} [nil (get resolution/entity-config entity)])]
      ;; An owner can move in a branch; identity and ANY historical version
      ;; are candidate hints. Filter only after normal merge/tombstone resolution.
      (filterv #(contains? owners (get % owner))
               (vals (read-resolved storage branch entity candidates))))))


(defn- read-slots
  [storage ids known]
  (loop [pending (set/difference ids (set (keys known))) rows known visited (set (keys known))]
    (limit! (<= (+ (count visited) (count pending)) 4096))
    (if (empty? pending)
      rows
      (let [loaded (sp/read-entities storage :slot (vec pending))
            visited (into visited pending)]
        (recur (into #{} (comp (keep :source-slot-id) (remove visited)) (vals loaded))
               (into rows loaded) visited)))))


(defn- acquire
  [storage branch root-id hints]
  (let [budget (atom 4096)]
    (loop [pending hints seen #{} index {:all-fns {} :slot-by-id {}} rounds 0]
      (limit! (and (<= (+ (count seen) (count pending)) 128) (< rounds 8)))
      (if (empty? pending)
        (resolution/execution-graph-from-index
          root-id (assoc index :slots (vec (vals (:slot-by-id index)))))
        (let [fns (read-resolved storage branch :fn pending)
              owners (set (keys fns))
              junctions (owner-rows storage branch :fn-slot :fn-id owners budget)
              bindings (owner-rows storage branch :binding :fn-id owners budget)
              items (owner-rows storage branch :binding-list-item :binding-id
                                (set (map :id bindings)) budget)
              slots (read-slots storage (into #{} (keep :slot-id) (concat junctions bindings))
                                (:slot-by-id index))
              junction-groups (group-by :fn-id junctions)
              binding-groups (group-by :fn-id bindings)
              item-groups (group-by :binding-id items)
              refs (into #{} (mapcat (fn [[id row]]
                                       (let [bs (get binding-groups id)]
                                         (resolution/execution-references
                                           row (get junction-groups id) bs
                                           (mapcat #(get item-groups (:id %)) bs) slots)))) fns)
              seen (into seen pending)]
          (recur (set/difference refs seen) seen
                 (-> index
                     (update :all-fns into fns)
                     (assoc :slot-by-id slots)
                     (update :fn-slots-by-fn merge junction-groups)
                     (update :bindings-by-fn merge binding-groups)
                     (update :items-by-binding merge item-groups))
                 (inc rounds)))))))


(defn resolve-hinted
  "Return a freshly resolved graph, or nil to use ordinary acquisition.
   Limits bound advisory identities/owner candidates and expansion rounds,
   not historical version rows already read by the shared merge resolver.
   A stale hint never supplies values or hides newly reachable dependencies.
   Unsupported decorators retain their ordinary graph resolver."
  [storage branch root-id hints]
  (when (and (seq hints) (contains? hints root-id) (<= (count hints) 64)
             (satisfies? sp/StorageBoundedQuery storage))
    (try
      (resolution/call-with-merges-memo #(acquire storage branch root-id hints))
      (catch clojure.lang.ExceptionInfo error
        (when-not (#{::limit :storage-error/candidate-limit
                     :storage-error/bounded-query-unsupported} (:type (ex-data error)))
          (throw error))))))
