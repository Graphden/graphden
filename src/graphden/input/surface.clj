(ns graphden.input.surface
  "Typed residual input presentation and a separate frozen-route binder.
   Reference-input groups never denote the bound reference's result fields."
  (:require
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.syntax :as syntax]
    [graphden.types.core :as types]))


(defn- residual-type
  [declared fields]
  (cond
    (types/marker-type? declared)
    (types/make-marker-type (types/marker-tag declared)
                            (residual-type (types/marker-inner declared) fields))
    (types/refine-type? declared) (residual-type (types/refine-base declared) fields)
    :else (into {} (map (juxt :name :type)) fields)))


(defn- named-fields
  [fields]
  (when-not (= (count fields) (count (set (map :name fields))))
    (context/reject! :ambiguous-public-input {}))
  fields)


(defn input-at-scope
  "Residual presentation for one use, retaining source addresses and kinds."
  [graph scope-path]
  (let [scope [(:fn-id graph) scope-path]
        node (get-in graph [:scopes scope-path :remaining-contract])
        dependencies (into {} (map (juxt :from identity)) (:dependencies graph))]
    (letfn [(visit
              [node path]
              (let [address [scope path]
                    edge (get dependencies address)]
                (cond
                  (contains? (:hidden-input-addresses graph) address) nil
                  (= :bound (:state node))
                  (when (and edge (get-in graph [:scopes (second (:to edge))]))
                    (let [child (input-at-scope graph (second (:to edge)))]
                      (when (seq (:fields child))
                        {:kind :reference-inputs :name (:name node)
                         :input-scope (:to edge) :result-address address :result-type (:type node)
                         :reference-binding-id (:binding-id edge) :target-fn-id (:target-fn-id edge)
                         :reference-mode (:mode edge)
                         :required (boolean (some :required-now? (:fields child)))
                         :required-now? (boolean (some :required-now? (:fields child)))
                         :type (:type child) :fields (:fields child)})))
                  :else
                  (let [fields (when (:fields node)
                                 (named-fields (into [] (keep #(visit % (conj path (:id %)))) (:fields node))))
                        route {:kind (if (empty? path) :input-record :input-field)
                               :name (:name node) :origin-field-id (:id node) :address address
                               :type (if fields (residual-type (:type node) fields) (:type node))
                               :completion-type (:type node) :required (:required node)
                               :required-now? (:required-now? node)
                               :whole-bindable? (and (= :free (:state node))
                                                     (or (nil? fields)
                                                         (and (= (count fields) (count (:fields node)))
                                                              (every? #(= :input-field (:kind %)) fields))))}]
                    (when-not (and (seq (:fields node)) (empty? fields))
                      (cond-> route fields (assoc :fields fields)))))))]
      (or (visit node []) {:kind :input-record :type {} :fields []}))))


(defn- route-ledger
  [root]
  (letfn [(walk
            [node path]
            (into {path (dissoc node :fields)}
                  (mapcat (fn [field] (walk field (conj path (:name field))))) (:fields node)))]
    (walk root [])))


(defn project
  "Produce a nested typed surface with authored names and explicit route kinds.
   A bound ref at :left exposes a :reference-inputs group, never a reopened
   result field. Completion constraints and source identities stay separate."
  [ctx graph]
  (context/call-with-types
    ctx
    (fn []
      (let [root (input-at-scope graph [])]
        {:snapshot-id (:snapshot-id ctx) :fn-id (:fn-id graph) :root root
         :routes (route-ledger root)
         :scope-contracts (into {} (map (fn [[path node]]
                                          [[(:fn-id graph) path] (:configured-contract node)])) (:scopes graph))
         :bindings (into {} (mapcat (comp :bindings val)) (:scopes graph))}))))


(defn- validate-snapshot!
  [ctx surface]
  (when-not (and (some? (:snapshot-id surface)) (= (:snapshot-id ctx) (:snapshot-id surface)))
    (context/reject! :stale-input-surface {:fn-id (:fn-id surface)})))


(defn- field-names
  [contract path]
  (loop [node contract ids path names []]
    (if-let [id (first ids)]
      (let [field (descriptor/node-at node [id])]
        (recur field (next ids) (conj names (:name field))))
      names)))


(defn- bind-route
  [surface state node form options]
  (when-not (:whole-bindable? node)
    (context/reject! :occupied-public-input {:address (:address node)}))
  (let [[scope path] (:address node)
        contract (get-in surface [:scope-contracts scope])
        full-form (reduce (fn [form name] {name form}) form (reverse (field-names contract path)))
        expression (syntax/parse-expression contract scope full-form options)]
    (input/bind-input contract state scope expression)))


(defn bind-input
  "Bind through this surface's frozen routes, using the existing expression
   grammar and occupancy algebra. This is separate from declared source :bind.
   options supplies :name->id and :binding-id; no rows are persisted. Optional
   state permits incremental edits and defaults to the analyzed occupancy."
  ([ctx surface form options] (bind-input ctx surface (:bindings surface) form options))
  ([ctx surface state form options]
   (validate-snapshot! ctx surface)
   (letfn [(bind-node
             [state node form]
             (if (and (:fields node) (map? form))
               (let [by-name (into {} (map (juxt :name identity)) (:fields node))]
                 (when-not (every? #(contains? by-name %) (keys form))
                   (context/reject! :unknown-public-input {}))
                 (reduce (fn [state field]
                           (if (contains? form (:name field))
                             (bind-node state field (get form (:name field))) state))
                         state (:fields node)))
               (if (= :reference-inputs (:kind node))
                 (context/reject! :reference-inputs-require-partial-map {})
                 (bind-route surface state node form options))))]
     (bind-node (input/merge-bindings (:bindings surface) state) (:root surface) form))))
