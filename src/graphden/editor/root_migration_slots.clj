(ns graphden.editor.root-migration-slots
  "Resolve only migrated bindings against actual, source-authorized inherited
   slot identities. A parser placeholder never becomes a persisted slot."
  (:require
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.packages.records.ids :as ids]))


(defn- refuse!
  []
  (throw (ex-info "Personal root callback slots are unavailable"
                  {:type :browser-plan/unsupported :reason :legacy-callback-slots})))


(defn- inherited-slot-id
  [index parent-id slot-name]
  (or (renames/find-slot-id-in-tree parent-id (keyword (name slot-name)) index)
      (refuse!)))


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
