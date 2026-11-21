(ns graphden.input.stored-implementations
  "Match branch-resolved stored callables with explicit trusted native ABIs."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.signatures :as signatures]
    [graphden.types.core :as types]))


(defn- root-id
  [ctx owner]
  (let [roots (filterv #(empty? (get-in ctx [:fns % :parent-ids]))
                       (inheritance/declaration-order ctx owner))]
    (when-not (= 1 (count roots))
      (context/reject! :multiple-implementation-roots {:fn-id owner}))
    (first roots)))


(defn- implementation
  [ctx trusted base]
  (let [{:keys [input-type-fn-id return-type-fn-id name]} (get-in ctx [:fns base])
        {:keys [signature impl]} (get trusted base)]
    (when-not (and (uuid? input-type-fn-id) (uuid? return-type-fn-id) (string? name)
                   (= base (:fn-id signature)) (= (keyword name) (:name signature))
                   (map? (:args signature)) (set? (:effects signature)) (fn? impl))
      (context/reject! :implementation-signature-required {:fn-id base}))
    (when-not (signatures/local-type-rules? signature)
      (context/reject! :rule-signature-view-required {:fn-id base}))
    (let [fields (:fields (descriptor/resolve-contract ctx base))
          args (into {} (map (juxt :name :type)) fields)
          return (:type (descriptor/resolve-descriptor ctx return-type-fn-id))]
      (when-not (and (= args (update-vals (:args signature) types/resolve-alias))
                     (= return (types/resolve-alias (:return signature))))
        (context/reject! :implementation-signature-mismatch {:fn-id base}))
      {:base-fn-id base :root-entry signature :impl impl
       :argument-paths (into {} (map (fn [{:keys [name id]}] [name [id]])) fields)})))


(defn resolve-closure
  "Resolve all stored callable IDs in a frozen input closure. The caller
   supplies implementations and signatures by trusted base UUID. Definitions
   and row identities come from the same authorized snapshot as its bindings."
  [ctx owner trusted]
  (when-not (get-in ctx [:fns owner :input-type-fn-id])
    (context/reject! :missing-input-contract {:fn-id owner}))
  (context/call-with-types
    ctx
    (fn []
      (let [owners (into [] (keep (fn [[id row]] (when (:input-type-fn-id row) id))) (:fns ctx))
            resolve-base (memoize (partial implementation ctx trusted))]
        (into {} (map (fn [id] [id (resolve-base (root-id ctx id))])) owners)))))
