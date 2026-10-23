(ns kaocha.plugin.priority
  "Portable ordering hints for the authoritative Kaocha namespace roster."
  (:require
    [clojure.edn :as edn]))


(defn namespace-hints
  "Malformed hints widen to the unchanged roster. Unknown names are ignored by
   scheduling, so hints can never introduce or remove a selected testable."
  [encoded suite-id]
  (try
    (let [hints (get (edn/read-string (or encoded "{}")) suite-id)]
      (if (and (#{:unit :integration} suite-id) (vector? hints) (every? string? hints)) (vec (distinct hints)) []))
    (catch Exception _ [])))


(defn namespace-layers
  "Validated distance layers for unit/integration. Bad input drops ordering hints
   and leaves the authoritative test roster intact."
  [encoded suite-id]
  (try
    (let [layers (get (edn/read-string (or encoded "{}"))
                      (keyword (str (name suite-id) "-layers")))]
      (if (and (#{:unit :integration} suite-id) (vector? layers)
               (every? #(and (vector? %) (every? string? %)) layers))
        layers
        []))
    (catch Exception _ [])))


(defn indexed-layers
  "Select each existing testable once, preserving its original result index."
  [testables layers]
  (let [indexed (mapv vector (range) testables)
        by-name (into {} (map (fn [[i t]] [(some-> (:kaocha.testable/id t) name) [i t]])) indexed)
        seen (volatile! #{})
        selected (mapv (fn [layer]
                         (into []
                               (keep (fn [ns-name]
                                       (when-not (contains? @seen ns-name)
                                         (vswap! seen conj ns-name)
                                         (get by-name ns-name))))
                               layer))
                       layers)]
    {:layers selected
     :remaining (filterv (fn [[_ t]]
                           (not (contains? @seen (some-> (:kaocha.testable/id t) name))))
                         indexed)}))


(defn indexed-order
  "Priority pairs followed by the remaining original pairs, each exactly once.
   Keeping the original index lets the runner restore Kaocha's result order."
  [testables hints]
  (let [ranks (zipmap hints (range))
        indexed (mapv vector (range) testables)
        priority? (fn [[_ testable]]
                    (contains? ranks (some-> (:kaocha.testable/id testable) name)))
        priority (->> indexed
                      (filter priority?)
                      (sort-by #(get ranks (name (:kaocha.testable/id (second %)))))
                      vec)]
    {:priority priority
     :remaining (filterv (complement priority?) indexed)}))
