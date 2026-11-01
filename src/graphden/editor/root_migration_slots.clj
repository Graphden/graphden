(ns graphden.editor.root-migration-slots
  "Resolve only migrated bindings against actual, source-authorized inherited
   slot identities. A parser placeholder never becomes a persisted slot."
  (:require
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.compile.surface :as surface]
    [graphden.packages.records.ids :as ids]))


(defn- refuse!
  []
  (throw (ex-info "Personal root callback slots are unavailable"
                  {:type :browser-plan/unsupported :reason :legacy-callback-slots})))


(defn- callback-slot-id
  [index parent-id slot-name]
  (let [slot-name (keyword (name slot-name))]
    (or (renames/find-slot-id-in-tree parent-id slot-name index)
        (let [captured (into #{} (comp (filter #(= slot-name (:ext-name %))) (map :slot-id))
                             (surface/public-free-entries parent-id index))]
          (when (> (count captured) 1) (refuse!))
          (first captured)))))


(defn- inherited-slot-id
  [index parent-id slot-name]
  (or (callback-slot-id index parent-id slot-name)
      (refuse!)))


(defn prune-absent-callback-args
  "A selected callback may ignore inputs accepted by its shipped wrapper.
   Remove those arguments before parsing so they cannot become free slots in
   the persisted root. Only source-authorized callback aliases are pruned."
  [definitions references graph]
  (let [index (lookups/build-lookups graph)
        source-fns (:fn-map index)]
    (mapv (fn [definition]
            (if-let [source-id (get references (:parent definition))]
              (do
                (when-not (get source-fns source-id) (refuse!))
                (update definition :args
                        #(into {} (filter (fn [[slot-name _]]
                                            (callback-slot-id index source-id slot-name))) %)))
              definition))
          definitions)))


(defn resolve-bindings
  "Resolve every new binding against the compiler's actual source slot when
   its parent already exists. Parser placeholders never become persisted IDs;
   missing slots fail before any CREATE."
  [records definitions graph]
  (let [index (lookups/build-lookups graph)
        source-ids (set (keys (:fn-map index)))
        binding-keys (into #{} (map (juxt :fn-id :slot-id))
                           (filter #(= :binding (:kind %)) records))
        definitions-by-id (into {} (map (fn [{path :namespace :keys [name] :as definition}]
                                          [(ids/fn-id path name) definition])) definitions)
        replacements
        (into {}
              (mapcat (fn [{:keys [id parent-ids]}]
                        (when-let [definition (get definitions-by-id id)]
                          (for [parent-id (filter source-ids parent-ids)
                                slot-name (keys (:args definition))
                                :let [placeholder (ids/slot-id parent-id slot-name)]
                                :when (contains? binding-keys [id placeholder])]
                            [[id placeholder] (inherited-slot-id index parent-id slot-name)]))))
              (filter #(= :fn (:kind %)) records))
        resolved (mapv (fn [row]
                         (if (and (= :binding (:kind row))
                                  (contains? replacements [(:fn-id row) (:slot-id row)]))
                           (assoc row :slot-id (get replacements [(:fn-id row) (:slot-id row)]))
                           row)) records)
        valid-slots (into (set (keys (:slot-map index)))
                          (map :id) (filter #(= :slot (:kind %)) records))]
    (when-not (every? #(contains? valid-slots (:slot-id %))
                      (filter #(= :binding (:kind %)) resolved))
      (refuse!))
    resolved))
