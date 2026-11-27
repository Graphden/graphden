(ns graphden.input.source-legacy-readers
  "Replay legacy reader dispatch from the package's original rows."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.compile.surface :as surface]
    [graphden.executor.registry.core :as registry]
    [graphden.input.source-forward :as forward]
    [graphden.input.source-reader-writers :as writers]
    [graphden.input.source-surface :as shared]
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
              row (some #(get-in graph [:binding-by-fn-slot [% slot]]) (lookups/inheritance-chain* owner graph))
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
      ((requiring-resolve 'graphden.input.legacy-use/select-binding) graph owner))))


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


(defn- env-reader
  [field {:keys [value-present slot-id value ref-fn-id type-override-fn-id]}]
  (if value-present
    {:kind :value :env-name field :slot-id slot-id :value value}
    {:kind :ref :env-name field :slot-id slot-id :ref-id ref-fn-id :type-override-fn-id type-override-fn-id
     :is-fn false :produces-callable? false}))


(defn- unshadowed-env-readers?
  [graph field slots]
  (not-any? (fn [target]
              (let [owned (lookups/fn-owned-slot-ids target graph)
                    native (bindings/collect-bindings target graph)]
                (some (fn [writer]
                        (or (= field (:env-name writer))
                            (some slots (lookups/env-binding-route-ids target writer native owned graph))))
                      (bindings/collect-env-bindings target graph))))
            (keys (:fn-map graph))))


(defn scoped-reader-witness
  "Verify one legacy environment writer and the readers it shadows. A scalar
   reference is one delayed value shared by those readers. Reject overlapping
   writers that could change the scope of that value or only some reads."
  [prepared owner roots]
  (let [rows (delay (owner-rows prepared owner))
        graph (delay (index-rows @rows))]
    (fn [field {:keys [id slot-id value-present ref-fn-id] :as binding}]
      (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
                registry/*per-org-rich-override* (atom {})]
        (let [original @graph
              [writer & more] (filter #(= field (:env-name %)) (bindings/collect-env-bindings owner original))
              expected (env-reader field binding)]
          (when (and writer (empty? more)
                     (or value-present (uuid? ref-fn-id))
                     (= binding (get-in original [:binding-by-fn-slot [owner slot-id]]))
                     (= expected writer))
            (let [free (index-rows (remove #(= id (:id %)) @rows))
                  installed (reader-ids (renames/deep-free-ext-entries owner free) field)
                  routes (if value-present
                           #{slot-id}
                           (set (lookups/env-binding-route-ids owner writer (bindings/collect-bindings owner original)
                                                             (lookups/fn-owned-slot-ids owner original) original)))]
              (and (= #{slot-id} installed)
                   (unshadowed-env-readers? free field routes)))))))))


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
  (when (and (= slot (get-in graph [:slot-map (:id slot)]))
             (or (not (contains? @incoming owner))
                 (let [children (get-in graph [:children-by-fn owner])]
                   (and (::writers/incoming graph) (seq children)
                        (every? #(uuid? (get-in graph [:binding-by-fn-slot [% (:id slot)] :ref-fn-id])) children)))))
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
          (cond
            (not (contains? @incoming owner)) {:targets targets}
            (and (::writers/incoming graph) (writers/inherited-writer? graph (::writers/incoming graph) owner field slot targets))
            {:targets targets :inherited-reader-slot (:source-slot-id slot)}))))))


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


(defn check-shared-input!
  "Authenticate the two free V1 readers, their types and all compiler writes
   reachable from this owner before introducing explicit forwarding fields."
  [prepared owner source]
  (let [graph (owner-index prepared owner)
        roots (::frozen-roots prepared)]
    (when-not (and (= shared/uri-reader owner) (seq roots)
                   (not-any? #(contains? (:args source) (first %)) shared/fields))
      (throw (ex-info "Missing original public input" {:type :input/source-surface :code :unproved-shared-input})))
    (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
              registry/*per-org-rich-override* (atom {})]
      (let [public (surface/surface-entries owner graph)
            frees (renames/deep-free-ext-entries owner graph)]
        (doseq [[field module reader type-module type required] shared/fields
                :let [id (ids/slot-id (ids/fn-id module reader) field)
                      expected {:id id :name (name field) :type-fn-id (ids/fn-id type-module type) :required required}
                      entries #(filterv (fn [entry] (= field (:ext-name entry))) %)]]
          (when-not (and (= expected (select-keys (get-in graph [:slot-map id]) (keys expected)))
                         (= [id] (mapv :slot-id (entries public)) (mapv :slot-id (entries frees)))
                         (every?
                           (fn [callee]
                             (and (not-any? #(or (= field (:env-name %)) (= id (:slot-id %)))
                                            (bindings/collect-env-bindings callee graph))
                                  (every? #(or (not= :free (:kind %)) (= id (lookups/effective-reader-slot-id callee (:slot-id %) graph)))
                                          (entries (bindings/collect-bindings callee graph)))
                                  (every? (fn [{:keys [ref-id]}]
                                            (let [changes (when ref-id (renames/build-ref-renames ref-id callee graph))]
                                              (not-any? #{field} (concat (keys changes) (vals changes)))))
                                          (bindings/collect-bindings callee graph))))
                           (keys (:fn-map graph))))
            (throw (ex-info "Ambiguous original public input" {:type :input/source-surface :code :unproved-shared-input}))))))))
