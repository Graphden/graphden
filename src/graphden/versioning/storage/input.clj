(ns graphden.versioning.storage.input
  "Offline v2 resolved-view invariants. The live v1 writer remains unchanged."
  (:require
    [graphden.input.rows :as rows]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.resolution :as res]))


(defn- reject!
  [code]
  (throw (ex-info "Structured input storage invariant violated"
                  {:type :input/invalid-row :code code})))


(defn- index-by-id
  [records]
  (into {} (map (juxt :id identity)) records))


(defn read-view
  "Batch-read a branch-resolved v2 graph; never fall back to legacy bindings."
  [storage branch-id]
  {:fns (index-by-id (res/resolve-all-entities storage :fn branch-id {}))
   :fields (index-by-id (sp/query-entities storage :slot {}))
   :members (group-by :fn-id (res/resolve-all-entities storage :fn-slot branch-id {}))
   :bindings (index-by-id (res/resolve-all-entities storage :input-binding branch-id {}))})


(defn- ancestor-ids
  [fns fn-id]
  (loop [pending [fn-id] seen #{}]
    (if-let [id (peek pending)]
      (recur (into (pop pending) (remove seen (:parent-ids (get fns id))))
             (conj seen id))
      seen)))


(defn- scoped-owner
  [{:keys [fns bindings]} row scope]
  (reduce (fn [owner edge-id]
            (let [edge (get bindings edge-id)]
              (when-not (and edge (= "ref" (:expression-kind edge))
                             (contains? (ancestor-ids fns owner) (:fn-id edge)))
                (reject! :invalid-scope))
              (:ref-fn-id edge)))
          (:fn-id row) scope))


(defn- resolve-path!
  [{:keys [fns fields members] :as view} row scope path]
  (let [owner (get fns (scoped-owner view row scope))
        input-id (:input-type-fn-id owner)]
    (when-not (and input-id (contains? fns input-id))
      (reject! :missing-input-contract))
    (reduce (fn [type-id field-id]
              (when-not (some #(= field-id (:slot-id %)) (get members type-id))
                (reject! :unknown-field))
              (or (:type-fn-id (get fields field-id)) (reject! :unknown-field)))
            input-id path)))


(defn validate-row!
  [view row]
  (let [row (rows/normalize row)
        owner (get-in view [:fns (:fn-id row)])]
    (when-not (and owner (= (:org-id owner) (:org-id row)))
      (reject! :invalid-owner))
    (doseq [id (keep row [:ref-fn-id :resolver-fn-id :type-override-fn-id])]
      (let [target (get-in view [:fns id])]
        (when-not (and target (or (nil? (:org-id target))
                                  (= (:org-id row) (:org-id target))))
          (reject! :invalid-reference))))
    (resolve-path! view row (:scope-path row) (:target-path row))
    (when (= "projection" (:expression-kind row))
      (resolve-path! view row (:projection-scope-path row) (:projection-path row)))
    row))


(defn validate-view!
  "Each definition includes inherited occupied paths; shared diamond origins
   deduplicate, independent intersecting bindings conflict. Type fits remain
   the checker stage's concern. No ref evaluation occurs here."
  [{:keys [fns bindings] :as view}]
  (let [normalized (mapv #(validate-row! view %) (vals bindings))
        by-owner (group-by :fn-id normalized)]
    (doseq [fn-id (keys fns)]
      (rows/state (map #(assoc % :fn-id fn-id)
                       (mapcat by-owner (ancestor-ids fns fn-id)))))
    view))


(defn check-collisions!
  "Check the post-write view under the owner's advisory lock. Called for
   singular/bulk writes and again inside a provisional merge transaction."
  [storage branch-id entity-name candidates]
  (when (and (= :input-binding entity-name) (seq candidates))
    (let [view (read-view storage branch-id)
          view (update view :bindings merge (index-by-id candidates))]
      (validate-view! view))))
