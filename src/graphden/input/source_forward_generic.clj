(ns graphden.input.source-forward-generic
  "Exact frozen ABI evidence for source-backed collection reference results."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core :as types]))


(defn- const-origin
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
               (qualified-keyword? type-ref) (uuid? type-id) (= type-id (:fn-id named)) (uuid? (:fn-id return-owner))
               (map? (:map named)) (= #{:key :value} (set (keys (:map named))))
               (nil? (:parent named)) (empty? (:parents named)))
      {:version 1 :base-fn-id base-id :field-id field-id :variable variable
       :binding-id (:id binding) :target-fn-id target-id :return-owner-id (:fn-id return-owner)
       :return-type-ref type-ref :return-type-id type-id})))


(defn- plain-map-form?
  [type]
  (and (vector? type) (= :map (first type)) (= 3 (count type))
       (every? #(or (types/primitive? %) (types/type-var? %)) (rest type))))


(defn- nullable-map-form?
  [type]
  (or (plain-map-form? type)
      (and (vector? type) (= :union (first type)) (= 3 (count type))
           (= 1 (count (filter #{:null} (rest type))))
           (= 1 (count (filter plain-map-form? (rest type)))))))


(defn- map-origin
  [{:keys [defs-by-name name->id]} argument binding {:keys [target-id return-type return-owner-ref]}]
  (let [base (get defs-by-name (get-in argument [:target :owner]))
        base-id (:fn-id base)
        field (get-in argument [:target :field])
        field-id (when (and (uuid? base-id) (simple-keyword? field)) (ids/slot-id base-id field))
        expected (get-in argument [:target :type])
        return-owner (get defs-by-name return-owner-ref)
        resolved (when (keyword? return-type)
                   (parse/normalize-ref return-type name->id (:namespace return-owner)))
        named (get defs-by-name resolved)
        type-ref (when named (keyword (or (:namespace named) "") (name (:name named))))
        type-id (get name->id type-ref)]
    (when (and (uuid? base-id) (nil? (:parent base)) (empty? (:parents base)) (empty? (:effects base))
               (nullable-map-form? expected) (types/type-any? types/type-var? expected)
               (= expected (get-in base [:args field :type])) (= field-id (:slot-id binding))
               (qualified-keyword? type-ref) (uuid? type-id) (= type-id (:fn-id named)) (uuid? (:fn-id return-owner))
               (or (map? (:type named)) (map? (:map named)))
               (nil? (:parent named)) (empty? (:parents named)))
      {:version 1 :kind :map :base-fn-id base-id :field-id field-id :argument field :expected expected
       :binding-id (:id binding) :target-fn-id target-id :return-owner-id (:fn-id return-owner)
       :return-type-ref type-ref :return-type-id type-id})))


(defn origin
  "Locate a provisional generic edge. Frozen ABI, solved types, source rows
   and the unchanged field-read ledger must still be checked before replay."
  [prepared argument binding target]
  (or (const-origin prepared argument binding target)
      (map-origin prepared argument binding target)))


(defn- checked-read?
  [graph address expected]
  ;; A closed gradual map still needs its existing field-read validation.
  (let [proof (get-in graph [:scopes [] :proofs address])]
    (or (= :checked (:status proof))
        (and (= :deferred (:status proof)) (= :runtime-type-obligation (:reason proof))
             (some #{{:id [address :value-type :field-read] :address address
                      :scope (first address) :path (second address)
                      :kind :value-type :trigger :field-read :expected-type expected}}
                   (:obligations graph))))))


(defn- verify-const!
  "Require the original ABI field and the stock checker's closed substitution.
   A source variable or equal inferred shape alone is insufficient. Deferred
   value validation must remain in the exact field-read obligation ledger."
  [snapshot graph owner witness]
  (context/call-with-types
    snapshot
    (fn []
      (let [{:keys [version base-fn-id field-id variable binding-id target-fn-id return-owner-id return-type-ref return-type-id]} witness
            type-origin (get-in snapshot [:type-origins return-type-id])
            _ (when-not (and (= 1 version) (= base-fn-id (ids/fn-id "core.logic" :const))
                             (= field-id (ids/slot-id base-fn-id :value)) (types/type-var? variable)
                             (uuid? return-owner-id)
                             (some #{return-type-id} (vals (:alias-ids snapshot)))
                             (map? (:map type-origin)) (keyword? (:name type-origin))
                             (= return-type-ref (keyword (or (:namespace type-origin) "") (name (:name type-origin)))))
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
                       (= (ids/primitive-fn-id :any) (:type-fn-id field))
                       (= variable (get-in entry [:args :value]) (:return entry)) (= #{} (:effects entry))
                       (types/map-type? named) (not (types/type-any? types/type-var? named))
                       (not (types/type-any? types/callable-type? named))
                       (= named solved (:type configured) (:expected-type bound)
                          (get-in bound [:reference :actual-type]) (:return signature))
                       (= binding-id (:binding-id bound) (:binding-id edge))
                       (= target-fn-id (:target-fn-id edge) (:fn-id target))
                       (some #{return-owner-id} (:declaration-order target))
                       (= return-type-id (get-in snapshot [:fns return-owner-id :return-type-fn-id]))
                       (= :value (:mode edge) (get-in bound [:reference :mode]))
                       (checked-read? graph address named)
                       (= (:effects signature) (get-in root [:signature :effects]))
                       (set? (:effects signature)))
          (context/reject! :unproved-generic-forward-reference {:fn-id owner :binding-id binding-id}))
        witness))))


(defn- verify-map!
  [snapshot graph owner witness]
  (context/call-with-types
    snapshot
    (fn []
      (let [{:keys [base-fn-id field-id argument expected binding-id target-fn-id return-owner-id return-type-ref return-type-id]} witness
            type-origin (get-in snapshot [:type-origins return-type-id])
            scope [owner []]
            address [scope [field-id]]
            node (get-in graph [:scopes []])
            declared (descriptor/node-at (:declared-contract node) [field-id])
            configured (descriptor/node-at (:configured-contract node) [field-id])
            source (:signature-source node)
            entry (:root-entry source)
            solved (types/resolve (:substitution graph) (variables/type-in-scope scope expected))
            field (get-in snapshot [:fields field-id])
            named (:type (descriptor/resolve-descriptor snapshot return-type-id))
            bound (get-in node [:bindings address])
            edges (filter #(= address (:from %)) (:dependencies graph))
            edge (first edges)
            target (get-in graph [:scopes (second (:to edge))])
            signature (:signature target)]
        (when-not (and (nullable-map-form? expected) (types/type-any? types/type-var? expected)
                       (uuid? base-fn-id) (= field-id (ids/slot-id base-fn-id argument))
                       (= base-fn-id (:base-fn-id source)) (= [field-id] (get (:argument-paths source) argument))
                       (= expected (:type declared) (get-in entry [:args argument])
                          (get-in snapshot [:source-field-types [field-id (:type-fn-id field)]]))
                       (= #{} (:effects entry))
                       (= field-id (:id declared) (:id configured) (:id field))
                       (= (if (types/union-type? expected) (ids/primitive-fn-id :any) (ids/primitive-fn-id :jsonb))
                          (:type-fn-id field))
                       (some #{return-type-id} (vals (:alias-ids snapshot)))
                       (keyword? (:name type-origin)) (or (map? (:type type-origin)) (map? (:map type-origin)))
                       (= return-type-ref (keyword (or (:namespace type-origin) "") (name (:name type-origin))))
                       (not-any? #(types/type-any? types/type-var? %) [solved named])
                       (or (types/record-type? named) (types/map-type? named))
                       (not (types/type-any? types/callable-type? named))
                       (= solved (:expected-type bound) (types/resolve (:substitution graph) (:type configured)))
                       (= named (get-in bound [:reference :actual-type]) (:return signature))
                       (types/subtype? named solved)
                       (= 1 (count edges)) (= binding-id (:binding-id bound) (:binding-id edge))
                       (= target-fn-id (:target-fn-id edge) (:fn-id target))
                       (some #{return-owner-id} (:declaration-order target))
                       (= return-type-id (get-in snapshot [:fns return-owner-id :return-type-fn-id]))
                       (= :value (:mode edge) (get-in bound [:reference :mode]))
                       (checked-read? graph address solved))
          (context/reject! :unproved-generic-map-reference {:fn-id owner :binding-id binding-id}))
        witness))))


(defn verify!
  "Verify the exact provisional edge against the stock checked graph. This
   does not remove obligations or admit any execution beyond normal lowering."
  [snapshot graph owner witness]
  (case [(:version witness) (:kind witness)]
    [1 nil] (verify-const! snapshot graph owner witness)
    [1 :map] (verify-map! snapshot graph owner witness)
    (context/reject! :unproved-generic-forward-reference {:fn-id owner})))
