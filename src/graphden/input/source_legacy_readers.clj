(ns graphden.input.source-legacy-readers
  "Replay legacy reader dispatch from the package's original rows."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.compile.surface :as surface]
    [graphden.executor.registry.core :as registry]
    [graphden.input.source-forward :as forward]
    [graphden.packages.records.parse :as parse]))


(defn- closure-rows
  [prepared by-id owner]
  (loop [pending [owner] seen #{} result #{}]
    (if-let [id (peek pending)]
      (if (contains? seen id)
        (recur (pop pending) seen result)
        (let [definition (get by-id id)
              rows (parse/parse-prepared-module (assoc prepared :fn-defs [definition]))
              next-ids (into #{} (mapcat (fn [row]
                                           (into (:parent-ids row [])
                                                 (keep row [:ref-fn-id :type-fn-id :type-override-fn-id
                                                            :return-type-fn-id :element-fn-id])))) rows)]
          (recur (into (pop pending) (filter by-id next-ids)) (conj seen id) (into result rows))))
      result)))


(defn- index
  [prepared owner]
  (let [by-id (into {} (map (juxt :fn-id identity)) (vals (:defs-by-name prepared)))
        kinds (group-by :kind (closure-rows prepared by-id owner))]
    (lookups/build-lookups {:fns (:fn kinds) :slots (:slot kinds) :fn-slots (:fn-slot kinds)
                            :bindings (:binding kinds) :list-items (:binding-list-item kinds)})))


(defn- value-edge?
  "Require the original compiler to evaluate the same effective slot and target
   that the source parser recorded. Callable and identity dispatch stay distinct."
  [compiler signatures owner row]
  (binding [registry/*rich-types-override* (atom {:by-id signatures :by-name {}})
            registry/*per-org-rich-override* (atom {})]
    (let [idx @compiler
          matches (filter #(= (:slot-id row) (:slot-id %))
                          (concat (bindings/collect-bindings owner idx)
                                  (bindings/collect-env-bindings owner idx)))
          [reader & more] matches]
      (and reader (empty? more) (= row (get-in idx [:binding-by-fn-slot [owner (:slot-id row)]]))
           (= :ref (:kind reader)) (false? (:is-fn reader)) (= (:ref-fn-id row) (:ref-id reader))))))


(defn incoming-targets
  "Closed-corpus legacy consumers include inherited, resolved and list-item
   uses. A named-only proof is valid only when none references its owner."
  [graph]
  (into (set (keys (:children-by-fn graph)))
        (concat (mapcat (juxt :ref-fn-id :resolver-fn-id) (vals (:binding-by-fn-slot graph)))
                (keep :ref-fn-id (mapcat val (:items-by-binding graph))))))


(defn- reader-ids
  [entries field]
  (into #{} (comp (filter #(= field (:ext-name %))) (map :slot-id)) entries))


(defn- named-leaf-readers
  [graph incoming compiler roots owner field slot]
  (when (and (not (contains? @incoming owner))
             (= slot (get-in graph [:slot-map (:id slot)])))
    (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
              registry/*per-org-rich-override* (atom {})]
      (let [closure @compiler
            public (surface/surface-entries owner closure)
            targets (reader-ids public field)]
        (when (and (> (count targets) 1) (contains? targets (:source-slot-id slot)) (every? uuid? targets)
                   (contains? (set (:names (surface/surface-names owner closure))) field)
                   (= targets (reader-ids (renames/deep-free-ext-entries owner closure) field))
                   (every? (fn [{:keys [slot-id ext-name source-name]}]
                             (or (not (contains? targets slot-id))
                                 (and (= field ext-name) (or (nil? source-name) (= field source-name)))))
                           public)
                   ;; An inner env write can intercept only some UUIDs even if
                   ;; the outermost named invocation populated all of them.
                   (not-any? #(seq (bindings/collect-env-bindings % closure)) (keys (:fn-map closure))))
          targets)))))


(defn prepare
  "Keep the legacy compiler's registry reads inside this trusted source replay.
   The compiler index is lazy and private to this exact forwarding baseline."
  ([prepared owner roots] (prepare prepared owner roots nil nil))
  ([prepared owner roots graph incoming]
   (if (seq roots)
     (let [compiler (delay (index prepared owner))
           incoming (or incoming (delay (incoming-targets graph)))]
       (cond-> (assoc prepared ::forward/value-edge? (partial value-edge? compiler roots))
         graph (assoc ::forward/named-leaf-readers (partial named-leaf-readers graph incoming compiler roots))))
     prepared)))
