(ns graphden.executor.manifest-graph-copy
  "Private bounded copies of manifest graphs. Structural identity sets have an
   explicit codec; literal values retain the restrictive JDBC value codec."
  (:require
    [graphden.executor.source-recipe-jdbc-rows :as rows]
    [graphden.executor.source-recipe-values :as values]))


(def ^:private graph-keys #{:fns :slots :fn-slots :bindings :list-items :identity-target-ids})


(deftype ^:private Copy
  [graph parents targets charge])


(defn- identity-set!
  [value]
  (when-not (and (contains? #{clojure.lang.PersistentHashSet} (class value))
                 (nil? (meta value)) (every? uuid? value))
    (throw (ex-info "Unknown manifest identity set" {:type ::ineligible})))
  (vec value))


(defn freeze
  "Freeze only the complete collector manifest shape, with explicit byte/row bounds."
  [graph max-bytes max-rows]
  (when-not (and (= graph-keys (set (keys graph))) (nil? (meta graph))
                 (every? vector? (vals (dissoc graph :identity-target-ids)))
                 (<= (reduce + (map count (vals (dissoc graph :identity-target-ids)))) max-rows))
    (throw (ex-info "Unknown manifest graph shape" {:type ::ineligible})))
  (let [parents (into #{} (keep #(when (set? (:parent-ids %)) (:id %))) (:fns graph))
        plain (update graph :fns #(mapv (fn [row]
                                          (if (contains? parents (:id row))
                                            (update row :parent-ids identity-set!) row)) %))
        targets (values/freeze (identity-set! (:identity-target-ids graph)) max-bytes)
        result (reduce-kv (fn [{:keys [charge] :as acc} key value]
                            (let [copy (rows/freeze value false (- max-bytes charge))]
                              (-> acc (assoc-in [:graph key] copy)
                                  (update :charge + (rows/charge-bytes copy)))))
                          {:graph {} :charge (+ 1024 (values/charge-bytes targets) (* 128 (count parents)))}
                          (dissoc plain :identity-target-ids))]
    (Copy. (:graph result) parents targets (:charge result))))


^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn thaw
  [^Copy copy connection]
  (-> (update-vals (.-graph copy) #(rows/thaw % connection {}))
      (update :fns #(mapv (fn [row]
                            (if (contains? (.-parents copy) (:id row))
                              (update row :parent-ids set) row)) %))
      (assoc :identity-target-ids (set (values/thaw (.-targets copy))))))


^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn charge-bytes
  [^Copy copy]
  (.-charge copy))
