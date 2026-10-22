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
