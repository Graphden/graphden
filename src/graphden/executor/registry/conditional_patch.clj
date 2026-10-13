(ns graphden.executor.registry.conditional-patch
  "Publish staged checker changes without replacing concurrent certificates."
  (:require
    [clojure.set :as set]))


(defn changed-paths
  "Paths whose immutable values changed, including metadata-only changes."
  [before after paths]
  (filterv #(not (identical? (get-in before % ::absent)
                             (get-in after % ::absent))) paths))


(defn apply!
  "Patch only captured paths while every captured value still has the exact
   same identity. The comparison runs inside swap!, so a concurrent writer
   cannot slip between comparison and publication. Conflict changes nothing."
  [target before after paths]
  (let [write-paths (changed-paths before after paths)
        applied? (volatile! false)]
    (swap! target
           (fn [current]
             (let [current? (every? #(identical? (get-in before % ::absent)
                                                 (get-in current % ::absent)) paths)]
               (vreset! applied? current?)
               (if current?
                 (reduce (fn [result path]
                           (let [value (get-in after path ::absent)]
                             (if (= ::absent value)
                               (update-in result (pop path) dissoc (peek path))
                               (assoc-in result path value)))) current write-paths)
                 current))))
    @applied?))


(defn rich-paths
  "Only selected IDs and their related name-index keys are staged. Package
   name mappings are excluded by the caller's immutable protected ID set."
  [before after ids protected]
  (let [ids (set/difference (set ids) protected)
        names (set/union (set (keys (:by-name before))) (set (keys (:by-name after))))]
    (into (mapv #(vector :by-id %) ids)
          (keep (fn [name]
                  (let [prior (get-in before [:by-name name])
                        following (get-in after [:by-name name])]
                    (when (and (not (contains? protected prior))
                               (or (contains? ids prior) (contains? ids following)))
                      [:by-name name])))) names)))
