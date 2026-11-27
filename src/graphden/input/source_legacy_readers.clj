(ns graphden.input.source-legacy-readers
  "Replay legacy reader dispatch from the package's original rows."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.compile.surface :as surface]
    [graphden.executor.registry.core :as registry]
    [graphden.input.legacy-use :as legacy-use]
    [graphden.input.source-forward :as forward]
    [graphden.packages.records.ids :as ids]
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


(defn- owner-rows
  [prepared owner]
  (let [by-id (into {} (map (juxt :fn-id identity)) (vals (:defs-by-name prepared)))]
    (closure-rows prepared by-id owner)))


(defn- index-rows
  [rows]
  (let [kinds (group-by :kind rows)]
    (lookups/build-lookups {:fns (:fn kinds) :slots (:slot kinds) :fn-slots (:fn-slot kinds)
                            :bindings (:binding kinds) :list-items (:binding-list-item kinds)})))


(defn owner-index
  "Resolve the legacy call graph for one original authored package owner."
  [prepared owner]
  (index-rows (owner-rows prepared owner)))


(defn callback-readers
  "Replay the original positional and captured readers of a direct callable
   binding. The witness is scoped to the omitted owner's original graph."
  [prepared owner roots]
  (let [compiler (delay (owner-index prepared owner))]
    (fn [slot target binding-id]
      (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
                registry/*per-org-rich-override* (atom {})]
        (let [graph @compiler
              row (get-in graph [:binding-by-fn-slot [owner slot]])
              [reader & others] (filter #(= slot (:slot-id %)) (bindings/collect-bindings owner graph))]
          (when (and (= binding-id (:id row)) (= target (:ref-fn-id row) (:ref-id reader))
                     (empty? others) (= :ref (:kind reader)) (true? (:is-fn reader)))
            (let [parameters (renames/hof-lambda-params target slot row owner graph)]
              {:parameters (mapv keyword parameters)
               :positional (renames/hof-lambda-slot-routes target parameters graph)
               :captured (renames/build-hof-translation target parameters graph)})))))))


(defn selected-binding
  "Resolve the legacy writer and physical reader using only these frozen native signatures."
  [graph roots owner]
  (when (seq roots)
    (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
              registry/*per-org-rich-override* (atom {})]
      (legacy-use/select-binding graph owner))))


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


(defn scoped-literal-witness
  "The original compiler must install one literal at the repeated field's
   physical ID. Check the free reader surface without that row and reject an
   inner environment write that can replace the name or the reader ID."
  [prepared owner roots]
  (let [rows (delay (owner-rows prepared owner))
        graph (delay (index-rows @rows))]
    (fn [field {:keys [id slot-id value] :as binding}]
      (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
                registry/*per-org-rich-override* (atom {})]
        (let [original @graph
              written (filter #(= field (:env-name %)) (bindings/collect-env-bindings owner original))
              expected {:kind :value :env-name field :slot-id slot-id :value value}]
          (when (and (= binding (get-in original [:binding-by-fn-slot [owner slot-id]]))
                     (= [expected] (vec written)))
            (let [free (index-rows (remove #(= id (:id %)) @rows))
                  installed (reader-ids (renames/deep-free-ext-entries owner free) field)]
              (and (= #{slot-id} installed)
                   (not-any? (fn [target]
                               (let [owned (lookups/fn-owned-slot-ids target free)
                                     native (bindings/collect-bindings target free)]
                                 (some (fn [writer]
                                         (or (= field (:env-name writer))
                                             (some #{slot-id} (lookups/env-binding-route-ids target writer native owned free))))
                                       (bindings/collect-env-bindings target free))))
                             (keys (:fn-map free)))))))))))


(defn- shared-reader-uses
  [compiler roots owner proofs]
  (let [{:keys [source-address target-field-id source-path]} (first proofs)
        field (peek source-path)
        index @compiler
        rows (into {} (map (juxt :id identity)) (vals (:binding-by-fn-slot index)))
        source-slot (get-in index [:slot-map (first (second source-address))])
        traverses? (fn [proof]
                     (let [[[root uses] fields] (:target-address proof)
                           target (reduce (fn [from binding-id]
                                            (let [row (get rows binding-id)]
                                              (if (and (= from (:fn-id row)) (uuid? (:ref-fn-id row))
                                                       (value-edge? compiler roots from row))
                                                (:ref-fn-id row)
                                                (reduced nil))))
                                          owner uses)]
                       (and target (= root owner) (= fields [target-field-id]) (seq uses)
                            (= #{target-field-id} (reader-ids (surface/surface-entries target index) field)))))]
    (when (and (> (count proofs) 1) (every? :multiple-readers? proofs)
               (= source-address [[owner []] [(ids/slot-id owner field)]])
               (= target-field-id (:source-slot-id source-slot))
               (every? #(= target-field-id (:target-field-id %)) proofs))
      (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
                registry/*per-org-rich-override* (atom {})]
        (when (and (= #{target-field-id} (reader-ids (surface/surface-entries owner index) field)
                      (reader-ids (renames/deep-free-ext-entries owner index) field))
                   (not-any? (fn [id] (some #(= field (:env-name %)) (bindings/collect-env-bindings id index))) (keys (:fn-map index)))
                   (every? traverses? proofs))
          (into {} (map #(vector (:target-address %) source-address)) proofs))))))


(defn shared-forwarded-readers
  "Authenticate each source path against the V1 value edges, its public UUID and
   every lexical writer before treating separate free addresses as one input in the migration baseline."
  [prepared owner proofs]
  (when-let [reader (::shared-reader-uses prepared)]
    (into {} (mapcat #(reader owner %)) (vals (group-by :source-address proofs)))))


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
     (let [compiler (delay (index-rows (owner-rows prepared owner)))
           incoming (or incoming (delay (incoming-targets graph)))]
       (cond-> (assoc prepared ::frozen-roots roots ::shared-reader-uses (partial shared-reader-uses compiler roots)
                      ::forward/value-edge? (partial value-edge? compiler roots))
         graph (assoc ::forward/named-leaf-readers (partial named-leaf-readers graph incoming compiler roots))))
     prepared)))
