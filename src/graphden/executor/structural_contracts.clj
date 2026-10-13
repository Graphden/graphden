(ns graphden.executor.structural-contracts
  "Canonical source-only constructor interfaces. These contracts validate
   declarations; they neither grant source access nor extend browser calls."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [graphden.executor.browser-contracts :as contracts]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- load-contracts
  []
  (into {}
        (mapcat
          (fn [[resource names-to-load]]
            (let [{definitions :fns path :namespace}
                  (edn/read-string (slurp (io/resource resource)))
                  names (into (ids/primitive-fn-ids)
                              (map (fn [{:keys [name]}] [name (ids/fn-id path name)])) definitions)]
              (into {}
                    (map (fn [definition]
                           (let [records (parse/parse-fn-def (assoc definition :namespace path) names)
                                 id (ids/fn-id path (:name definition))
                                 grouped (group-by :kind records)
                                 junctions (filterv #(= id (:fn-id %)) (:fn-slot grouped))
                                 slot-ids (into #{} (map :slot-id) junctions)]
                             [id {:fn (first (filter #(= id (:id %)) (:fn grouped)))
                                  :slots (filterv #(contains? slot-ids (:id %)) (:slot grouped))
                                  :fn-slots junctions}])))
                    (filter #(contains? names-to-load (:name %)) definitions))))
          [["packages/core/collections/fns.edn" #{:into :merge}]
           ["packages/web/graph-metadata/fns.edn" #{:find-function}]])))


(def ^:private fixed-contracts (delay (load-contracts)))


(defn matches?
  "Check an additional source-only operation's exact packaged declaration.
   The caller must already have authorized every supplied source row."
  [id graph]
  (when-let [contract (get @fixed-contracts id)]
    (let [junctions (filter #(= id (:fn-id %)) (:fn-slots graph))
          slot-ids (into #{} (map :slot-id) junctions)]
      (contracts/matches? contract
                          (first (filter #(= id (:id %)) (:fns graph)))
                          (filter #(contains? slot-ids (:id %)) (:slots graph))
                          junctions
                          (filter #(= id (:fn-id %)) (:bindings graph))))))


(defn operation-ids
  []
  (set (keys @fixed-contracts)))
