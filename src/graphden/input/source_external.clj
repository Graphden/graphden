(ns graphden.input.source-external
  "Prove that ignoring a call-site argument preserves a handler's exposed inputs.")


(defn- leaves
  [routes]
  (let [groups (into #{} (comp (filter seq) (map pop)) (keys routes))]
    (remove (fn [[path _]] (or (empty? path) (contains? groups path))) routes)))


(defn same-inputs?
  "Every free leaf of the original handler must occur at its original field
   ID and relative path under the new callable edge. Keep its read type,
   requiredness and public spelling as well as the complete capture ledger."
  [original after [owner prefix] public-path captures rename-type]
  (let [source-routes (get-in original [:public-input :routes])
        after-routes (get-in after [:public-input :routes])
        source (leaves source-routes)
        installed (into {} (keep (fn [[path route]]
                                   (when (and (<= (count public-path) (count path))
                                              (= public-path (subvec path 0 (count public-path))))
                                     [(subvec path (count public-path)) route]))) after-routes)
        facts [:name :kind :origin-field-id :whole-bindable? :required :required-now?]
        rebased (fn [[[root path] field-path]]
                  (when (= root (:fn-id original))
                    [[owner (into prefix path)] field-path]))
        expected (mapv (fn [[path {:keys [address]}]]
                         {:target (rebased address) :value-path (subvec path 1)}) source)]
    (and (seq source) (= (set (map key source)) (set (map key (leaves installed))))
         (= (count captures) (count (set (map :target captures))))
         (= (frequencies expected) (frequencies captures))
         (every? (fn [[path route]]
                   (let [nested (get installed path)]
                     (and (= :input-field (:kind route)) (true? (:whole-bindable? route))
                          (uuid? (:origin-field-id route))
                          (= (peek (second (:address route))) (:origin-field-id route))
                          (some? (:address nested)) (= (rebased (:address route)) (:address nested))
                          (= (select-keys route facts) (select-keys nested facts))
                          (= (rename-type (:completion-type route)) (:completion-type nested))
                          (= (rename-type (:type route)) (:type nested)))))
                 source))))
