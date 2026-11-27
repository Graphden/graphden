(ns graphden.input.source-reader-writers
  "Prove that every statically linked caller reaches a single inherited environment writer."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.compile.surface :as surface]
    [graphden.packages.records.ids :as ids]))


(defn incoming-uses
  [{:keys [children-by-fn binding-by-fn-slot items-by-binding]}]
  (let [rows (vals binding-by-fn-slot)
        owners (into {} (map (juxt :id :fn-id)) rows)]
    (group-by :target
              (concat (for [[p children] children-by-fn c children] {:target p :owner c :kind :parent})
                      (for [row rows k [:ref-fn-id :resolver-fn-id] :when (uuid? (k row))]
                        {:target (k row) :owner (:fn-id row) :kind k :row row})
                      (for [[b items] items-by-binding item items :when (:ref-fn-id item)]
                        {:target (:ref-fn-id item) :owner (owners b) :kind :list})))))


(defn- environment-writes
  [graph owner field targets]
  (let [native (bindings/collect-bindings owner graph)
        owned (lookups/fn-owned-slot-ids owner graph)]
    (keep (fn [writer]
            (let [routes (set (lookups/env-binding-route-ids owner writer native owned graph))]
              (when (or (= field (:env-name writer)) (some targets routes))
                (assoc writer :routes routes))))
          (bindings/collect-env-bindings owner graph))))


(defn- direct-native?
  [graph roots target field slot targets]
  (let [{:keys [name args] module-ns :namespace :as native} (get roots target)
        row (get-in graph [:fn-map target])
        slots (set (map :slot-id (get-in graph [:fn-slots-by-fn target])))]
    (and (uuid? target) (= target (:id row) (:fn-id native))
         (string? module-ns) (simple-keyword? name)
         (= target (ids/fn-id module-ns name)) (= [(clojure.core/name name) module-ns] [(:name row) (:namespace-id row)])
         (empty? (:parent-ids row)) (nil? (:base-fn-id row)) (nil? (:element-fn-id row))
         (empty? (get-in graph [:bindings-by-fn target]))
         (some? (:return native)) (uuid? (:type-fn-id slot)) (= (:type-fn-id slot) (:return-type-fn-id row))
         (map? args) (not (contains? args field)) (not-any? targets slots)
         (= slots (set (map #(ids/slot-id target %) (keys args))))
         (every? (fn [[k t]]
                   (and (contains? #{:int :text :bool :jsonb} t)
                        (= (ids/primitive-fn-id t) (get-in graph [:slot-map (ids/slot-id target k) :type-fn-id]))))
                 args))))


(defn- immediate-writer
  [graph roots parent field {:keys [id source-slot-id] :as slot} targets {:keys [kind owner]}]
  (when (and (= :parent kind) (= [parent] (get-in graph [:fn-map owner :parent-ids])))
    (let [[writer & others] (environment-writes graph owner field targets)
          bound (get-in graph [:binding-by-fn-slot [owner id]])]
      (when (and writer (empty? others) (= :ref (:kind writer))
                 (false? (:is-fn writer)) (false? (:produces-callable? writer))
                 (= field (:env-name writer)) (= id (:slot-id writer))
                 (= (:ref-id writer) (:ref-fn-id bound)) (direct-native? graph roots (:ref-id writer) field slot targets)
                 (= #{id source-slot-id} (:routes writer)))
        writer))))


(defn- independent-source?
  [graph owner field targets]
  (let [{:keys [accepted]} (surface/surface-names owner graph)
        entries (surface/surface-entries owner graph)]
    (and (not (contains? accepted field))
         (not-any? (fn [{:keys [slot-id ext-name source-name]}]
                     (and (targets slot-id) (or (accepted ext-name) (accepted source-name)))) entries)
         (not-any? (fn [{:keys [chain-name rename-name]}] (and (= field chain-name) (not= field rename-name)))
                   (renames/compute-rename-aliases owner graph)))))


(defn- preserves-frame?
  [graph field targets {:keys [kind target owner row]}]
  (case kind
    (:parent :list) true
    :ref-fn-id
    (let [sid (:slot-id row)
          [reader & others] (filter #(and (= sid (:slot-id %)) (= target (:ref-id %)))
                                    (concat (bindings/collect-bindings owner graph)
                                            (bindings/collect-env-bindings owner graph)))]
      (and (= :ref (:kind reader)) (empty? others)
           (not (contains? (renames/build-ref-renames target owner graph) field))
           (or (not (:is-fn reader)) (:produces-callable? reader)
               (let [parameters (renames/hof-lambda-params target sid row owner graph)
                     slots (renames/hof-lambda-slot-routes target parameters graph)
                     captures (select-keys (renames/build-hof-translation target parameters graph) targets)]
                 (and (not-any? #{field} parameters)
                      (not-any? targets (keys slots))
                      (every? #{field} (vals captures)))))))
    false))


(defn inherited-writer?
  "Before the inheriting frame is entered, no named entry point, callback
   parameter, capture, rename or environment writer may supply the name or
   an independent reader UUID. Inheritance carries the same lexical writer.
   A scalar/list call starts another frame and may not install that writer."
  [graph incoming roots owner field slot targets]
  (let [uses (get @incoming owner)
        writers (mapv #(immediate-writer graph roots owner field slot targets %) uses)]
    (when (and (seq uses) (every? some? writers))
      (loop [pending (mapv (fn [edge writer] [(:owner edge) writer]) uses writers) seen #{}]
        (if-let [[id writer :as state] (peek pending)]
          (if (seen state)
            (recur (pop pending) seen)
            (let [edges (get @incoming id)
                  writes (environment-writes graph id field targets)]
              (when (and (get-in graph [:fn-map id])
                         (independent-source? graph id field targets)
                         (= (if writer [writer] []) writes)
                         (every? #(preserves-frame? graph field targets %) edges))
                (recur (into (pop pending)
                             (map (fn [{:keys [owner kind]}] [owner (when (= :parent kind) writer)])) edges)
                       (conj seen state)))))
          true)))))
