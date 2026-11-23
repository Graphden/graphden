(ns graphden.input.source-forward-generic
  "Exact const ABI evidence for a closed, source-backed named map result."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core :as types]))


(defn origin
  "Locate a provisional generic edge. This is not a frozen type proof."
  [{:keys [defs-by-name name->id]} argument binding {:keys [target-id return-type return-owner-ref]}]
  (let [base (get defs-by-name (get-in argument [:target :owner]))
        base-id (ids/fn-id "core.logic" :const)
        field-id (ids/slot-id base-id :value)
        variable (get-in argument [:target :type])
        return-owner (get defs-by-name return-owner-ref)
        resolved (when (keyword? return-type)
                   (parse/normalize-ref return-type name->id (:namespace return-owner)))
        named (get defs-by-name resolved)
        type-ref (when named (keyword (or (:namespace named) "") (name (:name named))))
        type-id (get name->id type-ref)]
    (when (and (= base-id (:fn-id base)) (nil? (:parent base)) (empty? (:parents base))
               (= #{:value} (set (keys (:args base)))) (empty? (:effects base))
               (types/type-var? variable) (= variable (get-in base [:args :value :type]) (:return-type base))
               (= :value (get-in argument [:target :field])) (= field-id (:slot-id binding))
               (qualified-keyword? type-ref) (uuid? type-id) (= type-id (:fn-id named))
               (map? (:map named)) (= #{:key :value} (set (keys (:map named))))
               (nil? (:parent named)) (empty? (:parents named)))
      {:version 1 :base-fn-id base-id :field-id field-id :variable variable
       :binding-id (:id binding) :target-fn-id target-id :return-type-ref type-ref :return-type-id type-id})))


(defn verify!
  "Require the original ABI field and the stock checker's closed substitution.
   A source variable or equal inferred shape alone is insufficient."
  [snapshot graph owner witness]
  (context/call-with-types
    snapshot
    (fn []
      (let [{:keys [version base-fn-id field-id variable binding-id target-fn-id return-type-ref return-type-id]} witness
            _ (when-not (and (= 1 version) (= base-fn-id (ids/fn-id "core.logic" :const))
                             (= field-id (ids/slot-id base-fn-id :value)) (types/type-var? variable)
                             (= return-type-id (get-in snapshot [:alias-ids return-type-ref])))
                (context/reject! :unproved-generic-forward-reference {:fn-id owner :binding-id binding-id}))
            scope [owner []]
            address [scope [field-id]]
            root (get-in graph [:scopes []])
            declared (descriptor/node-at (:declared-contract root) [field-id])
            configured (descriptor/node-at (:configured-contract root) [field-id])
            source (:signature-source root)
            entry (:root-entry source)
            scoped (variables/type-in-scope scope variable)
            solved (types/resolve (:substitution graph) scoped)
            field (get-in snapshot [:fields field-id])
            named (:type (descriptor/resolve-descriptor snapshot return-type-id))
            bound (get-in root [:bindings address])
            edge (first (filter #(= address (:from %)) (:dependencies graph)))
            target (get-in graph [:scopes (second (:to edge))])
            signature (:signature target)]
        (when-not (and (= base-fn-id (:base-fn-id source)) (= {:value [field-id]} (:argument-paths source))
                       (= variable (:type declared) (get-in snapshot [:source-field-types [field-id (:type-fn-id field)]]))
                       (= field-id (:id declared) (:id configured) (:id field))
                       (= scoped (get-in entry [:args :value]) (:return entry)) (= #{} (:effects entry))
                       (types/map-type? named) (not (types/type-any? types/type-var? named))
                       (not (types/type-any? types/callable-type? named))
                       (= named solved (:type configured) (:expected-type bound)
                          (get-in bound [:reference :actual-type]) (:return signature))
                       (= binding-id (:binding-id bound) (:binding-id edge))
                       (= target-fn-id (:target-fn-id edge) (:fn-id target))
                       (= :value (:mode edge) (get-in bound [:reference :mode]))
                       (= :checked (get-in root [:proofs address :status]))
                       (= (:effects signature) (get-in root [:signature :effects]))
                       (set? (:effects signature)))
          (context/reject! :unproved-generic-forward-reference {:fn-id owner :binding-id binding-id}))
        witness))))
