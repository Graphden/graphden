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
  (first
    (reduce (fn [[owner prefix] edge-id]
              (let [edge (some-> (get bindings edge-id) rows/normalize)
                    declaration? (and (empty? (:scope-path edge))
                                      (contains? (ancestor-ids fns owner) (:fn-id edge)))
                    overlay? (and (= prefix (:scope-path edge))
                                  (contains? (ancestor-ids fns (:fn-id row)) (:fn-id edge)))]
                (when-not (and edge (= "ref" (:expression-kind edge))
                               (or declaration? overlay?))
                  (reject! :invalid-scope))
                [(:ref-fn-id edge) (conj prefix edge-id)]))
            [(:fn-id row) []] scope)))


(defn- resolve-path!
  [{:keys [fns fields members] :as view} row scope path]
  (let [owner (get fns (scoped-owner view row scope))
        input-id (:input-type-fn-id owner)]
    (when-not (and input-id (contains? fns input-id))
      (reject! :missing-input-contract))
    (doseq [entity [owner (get fns input-id)]]
      (when-not (or (nil? (:org-id entity)) (= (:org-id row) (:org-id entity)))
        (reject! :invalid-reference)))
    (reduce (fn [type-id field-id]
              (doseq [entity [(get fns type-id) (get fields field-id)]]
                (when-not (and entity (or (nil? (:org-id entity))
                                          (= (:org-id row) (:org-id entity))))
                  (reject! :invalid-reference)))
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
   the checker stage's concern. Recursive referenced availability is NOT
   expanded here: this is structural storage validation, not the effective
   remaining-input calculation. Public v2 writers stay disabled until that
   closure checker exists. No ref evaluation occurs here."
  [{:keys [fns bindings] :as view}]
  (let [normalized (mapv #(validate-row! view %) (vals bindings))
        by-owner (group-by :fn-id normalized)]
    (doseq [fn-id (keys fns)]
      (let [parent-ids (disj (ancestor-ids fns fn-id) fn-id)
            inherited (mapcat by-owner parent-ids)
            own (get by-owner fn-id)]
        (doseq [seal (filter :terminal inherited)
                row own
                :when (and (= (:scope-path seal) (:scope-path row))
                           (let [a (:target-path seal) b (:target-path row)]
                             (and (<= (count a) (count b))
                                  (= a (subvec b 0 (count a))))))]
          (reject! :terminal-seal))
        (rows/state (map #(assoc % :fn-id fn-id) (concat inherited own)))))
    view))


(defn check-collisions!
  "Check the post-write view under the owner's advisory lock. Called for
   singular/bulk writes and again inside a provisional merge transaction."
  [storage branch-id entity-name candidates]
  (when (and (= :input-binding entity-name) (seq candidates))
    (let [view (read-view storage branch-id)
          view (update view :bindings merge (index-by-id candidates))]
      (validate-view! view))))
