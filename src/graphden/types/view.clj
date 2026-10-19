(ns graphden.types.view
  "Static counterpart of the explicit view-to-data identity conversion."
  (:require
    [graphden.types.core :as types]
    [graphden.types.core.view :as view]))


(defn encoded-type
  "Encode view labels in data, retaining all other labels and callback contracts."
  [t]
  (types/resolve-alias (view/encoded-type (types/resolve-alias t))))


(defn- hint-paths
  "Find broad data positions, retaining structural paths and alias cycles."
  [actual expected]
  (let [aliases (types/aliases-snapshot)]
    (letfn [(has-view?
              [t seen]
              (cond
                (or (contains? seen t) (> (count seen) 64)) false
                (and (types/marker-type? t) (= :ui-view (types/marker-tag t))) true
                (and (keyword? t) (contains? aliases t)) (has-view? (get aliases t) (conj seen t))
                :else (boolean (some #(has-view? % (conj seen t)) (types/child-types t)))))
            (walk
              [a e path seen]
              (let [pair [a e]]
                (if (or (contains? seen pair) (> (count seen) 64))
                  []
                  (let [seen (conj seen pair)
                        aa (when (keyword? a) (get aliases a))
                        ee (when (keyword? e) (get aliases e))]
                    (cond
                      aa (walk aa e path seen)
                      ee (walk a ee path seen)
                      (and (#{:any :jsonb} e) (has-view? a #{})) [path]
                      (types/refine-type? a) (walk (types/refine-base a) e path seen)
                      (types/refine-type? e) (walk a (types/refine-base e) path seen)
                      (types/marker-type? a) (walk (types/marker-inner a)
                                                   (if (types/marker-type? e) (types/marker-inner e) e)
                                                   path seen)
                      (types/marker-type? e) (walk a (types/marker-inner e) path seen)
                      (types/union-type? a) (mapcat #(walk % e path seen) (types/union-members a))
                      (types/union-type? e) (mapcat #(walk a % path seen) (types/union-members e))
                      (and (types/record-type? a) (types/record-type? e))
                      (mapcat (fn [[k t]] (when (contains? a k) (walk (get a k) t (conj path k) seen))) e)
                      (and (types/list-type? a) (types/list-type? e))
                      (walk (types/list-elem a) (types/list-elem e) (conj path :element) seen)
                      (and (types/map-type? a) (types/map-type? e))
                      (concat (walk (types/map-key a) (types/map-key e) (conj path :key) seen)
                              (walk (types/map-val a) (types/map-val e) (conj path :value) seen))
                      (and (types/record-type? a) (types/map-type? e))
                      (mapcat (fn [[k t]] (walk t (types/map-val e) (conj path k) seen)) a)
                      (and (types/map-type? a) (types/record-type? e))
                      (mapcat (fn [[k t]] (walk (types/map-val a) t (conj path k) seen)) e)
                      (and (types/tuple-type? a) (types/list-type? e))
                      (mapcat (fn [[i t]] (walk t (types/list-elem e) (conj path i) seen))
                              (map-indexed vector (types/tuple-elems a)))
                      (and (types/tuple-type? a) (types/tuple-type? e))
                      (mapcat (fn [[i t]]
                                (when (< i (count (types/tuple-elems e)))
                                  (walk t (nth (types/tuple-elems e) i) (conj path i) seen)))
                              (map-indexed vector (types/tuple-elems a)))
                      :else [])))))]
      (distinct (walk actual expected [] #{})))))


(defn broad-sink-hints
  "Derived path-local hints where view information reaches broad data sinks."
  [expected bindings]
  (into []
        (mapcat (fn [[arg info]]
                  (map (fn [path]
                         {:arg-name arg :path path :code :types/view-to-broad-sink
                          :message "This broad data input accepts a view representation; connect the field value to save data, or use encode-view to encode the view explicitly."})
                       (hint-paths (:type info) (get expected arg)))))
        bindings))
