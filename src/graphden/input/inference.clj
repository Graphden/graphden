(ns graphden.input.inference
  "Signature ordering over canonical reference and projection dependencies.
   Rules run through the existing checker adapter, never through implementations."
  (:require
    [graphden.input.captures :as captures]
    [graphden.input.checking :as checking]
    [graphden.input.context :as context]
    [graphden.input.projections :as projections]
    [graphden.input.resolvers :as resolvers]
    [graphden.input.signatures :as signatures]
    [graphden.types.reference :as reference]))


(defn with-root-signatures
  "Seed traversal with frozen root declarations. These are provisional shapes,
   not inferred target signatures; reference checks must wait for analysis."
  [ctx implementations]
  (reduce-kv (fn [ctx fn-id {:keys [root-entry]}]
               (when-not (and (map? root-entry) (:return root-entry))
                 (context/reject! :signature-provider-required {:fn-id fn-id}))
               (update-in ctx [:signatures fn-id] #(merge root-entry %)))
             ctx implementations))


(defn- scope-dependencies
  [graph]
  (let [edges (projections/value-dependencies graph (:projections graph))
        targets (into {} (map (juxt :from (comp second :to))) (:dependencies graph))
        reachable (reduce (fn [result address]
                            (assoc result address
                                   (into (if-let [target (get targets address)] #{target} #{})
                                         (mapcat #(get result % #{})) (get edges address))))
                          {} (projections/value-order edges))]
    (into {} (map (fn [[path node]]
                    [path (into #{} (mapcat #(get reachable % #{})) (keys (:bindings node)))])) (:scopes graph))))


(defn- dependency-order
  [graph]
  (let [dependencies (scope-dependencies graph)
        dependents (reduce-kv (fn [result scope dependencies]
                                (reduce #(update %1 %2 (fnil conj #{}) scope) result dependencies))
                              {} dependencies)
        sorted-paths #(into (sorted-set-by (fn [a b] (compare (mapv str a) (mapv str b)))) %)]
    (loop [pending dependencies
           ready (sorted-paths (keep (fn [[scope dependencies]] (when (empty? dependencies) scope)) dependencies))
           result []]
      (if-let [scope (first ready)]
        (let [pending (dissoc pending scope)
              pending (reduce #(update %1 %2 disj scope) pending (get dependents scope))
              next-ready (keep #(when (empty? (get pending %)) %) (get dependents scope))]
          (recur pending (into (disj ready scope) next-ready) (conj result scope)))
        (if (empty? pending)
          result
          (context/reject! :signature-dependency-cycle {}))))))


(defn- prepare-reference
  [ctx graph edge-index]
  (let [{:keys [from to mode] :as edge} (get-in graph [:dependencies edge-index])
        entry-path [:scopes (second (first from)) :bindings from]
        entry (get-in graph entry-path)
        target (get-in graph [:scopes (second to) :signature])
        _ (when-not target (context/reject! :reference-signature-required {:address from}))
        dispatch (if (= :resolved-value mode)
                   {:mode :resolved-value :actual-type (:return target)}
                   (reference/classify (or (:dispatch-type entry) (:expected-type entry)) target))
        mode (:mode dispatch)]
    (when-not (contains? #{:value :graph-callable :callable-producer :resolved-value} mode)
      (context/reject! :reference-mode-required {:address from :reason (:reason dispatch)}))
    (let [graph (-> graph (assoc-in (conj entry-path :reference) dispatch)
                    (assoc-in [:dependencies edge-index :mode] mode))]
      (if (= :resolved-value mode)
        graph
        (captures/prepare-edge ctx graph (assoc edge :mode mode))))))


(defn- infer-scope
  [ctx implementations infer-signature graph path]
  (let [outgoing (keep-indexed (fn [index edge]
                                 (when (= path (second (first (:from edge)))) index)) (:dependencies graph))
        graph (reduce (partial prepare-reference ctx) graph outgoing)
        incoming (some #(when (and (= path (second (:to %))) (= :resolved-value (:mode %))) %)
                       (:dependencies graph))
        graph (if incoming (resolvers/bind-argument graph incoming) graph)
        node (get-in graph [:scopes path])
        scope [(:fn-id graph) path]
        result (signatures/infer-use ctx graph scope (get implementations (:fn-id node)) infer-signature)
        diagnostics (into [] (keep (fn [[_ proof]]
                                     (when (= :deferred (:status proof))
                                       {:code (:reason proof) :address (:address proof)
                                        :expected-type (:expected-type proof)}))) (:argument-proofs result))]
    (-> graph
        (assoc-in [:scopes path :signature] (:signature result))
        (assoc-in [:scopes path :signature-source] (:signature-source result))
        (assoc-in [:scopes path :signature-proofs] (:argument-proofs result))
        (update-in [:scopes path :substitution] checking/merge-solutions (:substitution result))
        (update-in [:scopes path :diagnostics] into diagnostics)
        (update :diagnostics into diagnostics))))


(defn analyze
  "Infer target uses before classifying their incoming references. Explicit
   projections participate in ordering. Lexical captures are routed after the
   target's authored/configured signature is known, preserving mode selection
   independently of speculative caller capture values."
  [ctx graph {:keys [implementations infer-signature]}]
  (when-not (and (map? implementations) (fn? infer-signature))
    (context/reject! :signature-provider-required {}))
  (-> (reduce (partial infer-scope ctx implementations infer-signature)
              (captures/initialize (resolvers/mark-boundaries graph))
              (dependency-order graph))
      (assoc-in [:coverage :signatures] true)))
