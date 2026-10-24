(ns graphden.crud.graph-refs
  "Structural fn references in a viewer-visible graph dump. Both Used-by and
   root traversal consume this edge list so a new reference kind cannot appear
   in one view while remaining invisible in the other.")


(defn edges
  "One edge per semantic use. The caller must pass a graph already filtered for
   its viewer; this pure index does not make authorization decisions."
  [{:keys [fns slots fn-slots bindings list-items]}]
  (let [visible-owners (into #{} (map :id) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        binding-by-id (into {} (map (juxt :id identity)) bindings)
        edge (fn [owner target kind slot-name]
               (when (and (contains? visible-owners owner) target)
                 (cond-> {:fn-id owner :target-id target :kind kind}
                   slot-name (assoc :slot-name slot-name))))]
    (vec
      (distinct
        (concat
          (mapcat (fn [{:keys [id parent-ids]}]
                    (keep #(edge id % :parent-of nil) parent-ids))
                  fns)
          (keep (fn [{:keys [fn-id slot-id ref-fn-id]}]
                  (edge fn-id ref-fn-id :ref-of
                        (:name (get slot-by-id slot-id))))
                bindings)
          (keep (fn [{:keys [binding-id ref-fn-id]}]
                  (when-let [{:keys [fn-id slot-id]} (get binding-by-id binding-id)]
                    (edge fn-id ref-fn-id :ref-of
                          (:name (get slot-by-id slot-id)))))
                list-items)
          (keep (fn [{:keys [fn-id slot-id resolver-fn-id]}]
                  (edge fn-id resolver-fn-id :resolver-of
                        (:name (get slot-by-id slot-id))))
                bindings)
          (keep (fn [{:keys [id base-fn-id]}]
                  (edge id base-fn-id :base-of nil))
                fns)
          (keep (fn [{:keys [id element-fn-id]}]
                  (edge id element-fn-id :element-of nil))
                fns)
          (keep (fn [{:keys [id return-type-fn-id]}]
                  (edge id return-type-fn-id :return-of nil))
                fns)
          (keep (fn [{:keys [fn-id slot-id]}]
                  (when-let [slot (get slot-by-id slot-id)]
                    (edge fn-id (:type-fn-id slot) :slot-of (:name slot))))
                fn-slots)
          (keep (fn [{:keys [fn-id slot-id type-override-fn-id]}]
                  (edge fn-id type-override-fn-id :binding-of
                        (:name (get slot-by-id slot-id))))
                bindings))))))


(defn by-source
  "Owner fn-id -> referenced fn-ids. Traversal treats every edge kind equally."
  [graph]
  (reduce (fn [index {:keys [fn-id target-id]}]
            (update index fn-id (fnil conj #{}) target-id))
          {}
          (edges graph)))


(defn shortest-path
  "The shortest visible structural path from any supplied root to `target-id`.
   Each step after the root names the incoming edge. nil means that the
   supplied roots have no visible structural path; callers decide whether
   dynamic selection or concealed source makes that result unknown."
  [graph roots target-id]
  (let [visible (into #{} (map :id) (:fns graph))
        roots (->> roots (filter visible) distinct (sort-by str) vec)
        adjacent (->> (edges graph)
                      (filter #(contains? visible (:target-id %)))
                      (group-by :fn-id)
                      (reduce-kv (fn [index id links]
                                   (assoc index id
                                          (sort-by (juxt (comp str :target-id)
                                                         :kind :slot-name)
                                                   links)))
                                 {}))]
    (when (contains? visible target-id)
      (loop [queue (into clojure.lang.PersistentQueue/EMPTY roots)
             parents (into {} (map (fn [root] [root nil])) roots)]
        (when-let [current (peek queue)]
          (if (= current target-id)
            (loop [id current path ()]
              (if-let [{:keys [from kind slot-name]} (get parents id)]
                (recur from
                       (conj path (cond-> {:fn-id id :kind kind}
                                    slot-name (assoc :slot-name slot-name))))
                (vec (conj path {:fn-id id}))))
            (let [[queue parents]
                  (reduce (fn [[pending seen] {:keys [target-id kind slot-name]}]
                            (if (contains? seen target-id)
                              [pending seen]
                              [(conj pending target-id)
                               (assoc seen target-id
                                      {:from current :kind kind
                                       :slot-name slot-name})]))
                          [(pop queue) parents]
                          (get adjacent current))]
              (recur queue parents))))))))
