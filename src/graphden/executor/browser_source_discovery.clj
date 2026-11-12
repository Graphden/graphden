(ns graphden.executor.browser-source-discovery
  "Pure reachability over fresh, authorized structural rows before values.
   Manifest classification runs on exactly the same monotonically acquired
   owner set as the ordinary collector, never unrelated hinted owners."
  (:require
    [clojure.set :as set]
    [graphden.executor.browser-source-prefetch :as prefetch]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.compile.lookups :as lookups]))


(defn- graph-for
  [graph ids]
  (let [junctions (filterv #(contains? ids (:fn-id %)) (:fn-slots graph))
        slots (set (map :slot-id junctions))
        owned (filterv #(contains? ids (:fn-id %)) (:bindings graph))
        binding-ids (set (map :id owned))]
    {:fns (filterv #(contains? ids (:id %)) (:fns graph))
     :fn-slots junctions :slots (filterv #(contains? slots (:id %)) (:slots graph))
     :bindings owned :list-items (filterv #(contains? binding-ids (:binding-id %)) (:list-items graph))}))


(defn closure
  "Mirror ordinary initial metadata traversal and pending-empty classification.
   Nil means unknown/oversized dependency and requires WHOLE collector fallback."
  [graph roots mode related-ids limit]
  (let [{:keys [fn-map slot-map fn-slots-by-fn bindings-by-fn items-by-binding]} (deps/index-graph graph)
        manifest? (= :manifest mode)
        expand (fn [roots]
                 (prefetch/bounded-closure
                   roots (set (keys fn-map))
                   (fn [id]
                     (let [owned (get bindings-by-fn id)]
                       (related-ids [(get fn-map id)]
                                    (keep #(get slot-map (:slot-id %)) (get fn-slots-by-fn id))
                                    (map #(if (or manifest? (capabilities/server-call-slot? (:slot-id %)))
                                            (dissoc % :ref-fn-id) %) owned)
                                    (when-not manifest? (mapcat #(get items-by-binding (:id %)) owned))))) limit))]
    (when-let [initial (expand roots)]
      (if-not manifest?
        {:ids initial :terminal #{}}
        (loop [seen initial]
          (let [current (graph-for graph seen)
                index (lookups/build-lookups current)
                classified (mapcat #(bindings/collect-bindings (:id %) index) (:fns current))
                terminal (into #{} (keep #(when (= :fn-ref (:kind %)) (:ref-id %))) classified)
                ordinary (keep #(when (= :ref (:kind %)) (:ref-id %)) classified)
                item-refs (keep :ref-fn-id (:list-items current))
                raw-refs (concat (keep :ref-fn-id (:bindings current)) item-refs)
                pending (set/difference (set (concat ordinary item-refs (remove terminal raw-refs))) seen)]
            (if (empty? pending)
              {:ids seen :terminal (set/difference terminal seen)}
              (when-let [expanded (expand (into seen pending))]
                (recur expanded)))))))))
