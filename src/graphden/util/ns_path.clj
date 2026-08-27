(ns graphden.util.ns-path
  "Dotted namespace paths from `:ns` rows (`{:id :name :parent-id}`).
   Shared by test discovery (`crud.test-runs`) and the qualified-name
   search scope (`crud.entities.list`)."
  (:require
    [clojure.string :as str]))


(defn path-of
  "Dotted path for ns row `id` — walks `:parent-id` chains over `by-id`
   (ns-id → ns row). Cycle-guarded: bad parent data degrades to the
   partial path instead of looping."
  [by-id id]
  (loop [segs () cur id seen #{}]
    (let [row (get by-id cur)]
      (if (or (nil? row) (contains? seen cur))
        (str/join "." segs)
        (recur (cons (:name row) segs) (:parent-id row) (conj seen cur))))))


(defn path-map
  "ns-id → dotted path for every row in `ns-rows`."
  [ns-rows]
  (let [by-id (into {} (map (juxt :id identity)) ns-rows)]
    (into {} (map (fn [n] [(:id n) (path-of by-id (:id n))])) ns-rows)))
