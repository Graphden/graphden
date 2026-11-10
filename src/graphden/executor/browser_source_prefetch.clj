(ns graphden.executor.browser-source-prefetch
  "Request-local hints for general source collection. Hints select IDs only;
   fresh decorated storage and source authorization supply every cached row.
   Manifest and client collectors deliberately do not use this optimization."
  (:require
    [clojure.set :as set]))


(defn bounded-closure
  "Return a bounded advisory ID set, or nil when incomplete/oversized. The
   supplied dependency reader uses an existing graph, never database reads."
  [roots known-ids dependencies limit]
  (loop [pending (set roots) seen #{}]
    (cond
      (> (+ (count pending) (count seen)) limit) nil
      (empty? pending) seen
      :else
      (let [id (first pending)]
        (when (contains? known-ids id)
          (let [seen (conj seen id)]
            (recur (into (disj pending id) (remove seen (dependencies id))) seen)))))))


(defn prepare
  "Read fresh identities and authorize ALL hints before reading any values.
   Callbacks retain existing version, ownership and candidate-budget semantics.
   Missing/denied hints are advisory misses, not request failures."
  [ids read-rows query-rows authorize!]
  (when (seq ids)
    (let [fns (vec (read-rows :fn ids))
          authorized? (and (= ids (set (map :id fns)))
                           (try
                             (doseq [row fns] (authorize! row))
                             true
                             (catch clojure.lang.ExceptionInfo error
                               (if (= :authz/forbidden (:type (ex-data error)))
                                 false
                                 (throw error)))))]
      (when authorized?
        (let [junctions (query-rows :fn-slot {:fn-id (vec ids)})
              bindings (query-rows :binding {:fn-id (vec ids)})
              binding-ids (set (map :id bindings))
              items (if (seq binding-ids)
                      (query-rows :binding-list-item {:binding-id (vec binding-ids)}) [])
              slot-ids (set (map :slot-id junctions))
              slots (if (seq slot-ids) (read-rows :slot slot-ids) [])]
          {:rows {:fn (into {} (map (juxt :id identity)) fns)
                  :slot (into {} (map (juxt :id identity)) slots)}
           :owners {:fn-slot ids :binding ids :binding-list-item binding-ids}
           :groups {:fn-slot (group-by :fn-id junctions)
                    :binding (group-by :fn-id bindings)
                    :binding-list-item (group-by :binding-id items)}})))))


(defn read-through
  "Serve known fresh rows; missing IDs retain the ordinary storage path."
  [cache entity ids read-rows]
  (let [rows (get-in cache [:rows entity])
        hits (select-keys rows ids)
        missing (set/difference (set ids) (set (keys hits)))]
    (into (vec (vals hits)) (when (seq missing) (read-rows missing)))))


(defn query-through
  "Only complete owner groups are hits. Prefetched candidate rows have already
   consumed the request budget; new owners use the ordinary bounded query."
  [cache entity where query-rows]
  (let [[owner ids] (first where)
        known (get-in cache [:owners entity] #{})
        requested (set ids)
        hits (set/intersection known requested)
        missing (set/difference requested known)
        cached (mapcat #(get-in cache [:groups entity %] []) hits)]
    (into (vec cached) (when (seq missing) (query-rows {owner (vec missing)})))))
