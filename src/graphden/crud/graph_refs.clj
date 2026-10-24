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
