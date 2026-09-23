(ns graphden.util.ns-path
  "Dotted namespace paths from `:ns` rows (`{:id :name :parent-id}`).
   The ONE walker every reader of the ns table uses — test discovery
   (`crud.test-runs`), the qualified-name search scope
   (`crud.entities.list`), save-time type-check reconstruction, package
   sync / export, the graph linter and the branch diff view."
  (:require
    [clojure.string :as str]))


(defn path-of
  "Dotted path for ns row `id` — walks `:parent-id` chains over `by-id`
   (ns-id → ns row). Cycle-guarded: bad parent data degrades to the
   partial path instead of looping.

   `dangling` (optional) is the segment written in place of a
   `:parent-id` that names no row. Without it the walk stops there and
   yields the partial path (`utils` under a missing `app`)."
  ([by-id id] (path-of by-id id nil))
  ([by-id id dangling]
   (loop [segs () cur id seen #{}]
     (let [row (get by-id cur)]
       (cond
         (contains? seen cur) (str/join "." segs)
         (nil? row) (str/join "." (cond->> segs
                                    (and dangling cur (seq segs)) (cons dangling)))
         :else (recur (cons (:name row) segs) (:parent-id row) (conj seen cur)))))))


(defn path-map
  "ns-id → dotted path for every row in `ns-rows`. Ids that name no row
   are absent (so `(get m id)` / `(m id)` is nil for them). `dangling` as
   in `path-of`."
  ([ns-rows] (path-map ns-rows nil))
  ([ns-rows dangling]
   (let [by-id (into {} (map (juxt :id identity)) ns-rows)]
     (into {} (map (fn [n] [(:id n) (path-of by-id (:id n) dangling)])) ns-rows))))
