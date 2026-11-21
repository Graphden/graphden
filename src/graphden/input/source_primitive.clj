(ns graphden.input.source-primitive
  "Prepare an independent format-2 primitive declaration for offline execution."
  (:require
    [graphden.input.format :as format]
    [graphden.input.module :as module]
    [graphden.input.source-context :as source-context]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- reject!
  [code]
  (throw (ex-info "Cannot prepare staged primitive declaration"
                  {:type :input/staged-primitive :code code})))


(def ^:private primitive-ids (ids/primitive-fn-ids))


(defn- leaf-type?
  [type]
  (and (contains? primitive-ids type) (not (#{:any :fn :fn-ref :sequence :jsonb} type))))


(defn- field-spec?
  [spec]
  (if (map? spec)
    (and (every? #{:type :description :required} (keys spec))
         (leaf-type? (:type spec))
         (or (nil? (:description spec)) (string? (:description spec)))
         (or (not (contains? spec :required)) (boolean? (:required spec))))
    (leaf-type? spec)))


(defn- exact-record?
  [input return-type contract]
  (when (and (map? contract) (= 1 (count contract)))
    (let [[field shape] (first contract)]
      (and (simple-keyword? field) (map? shape) (seq shape)
           (every? simple-keyword? (keys shape)) (every? #{:text} (vals shape))
           (= input {field {:type shape :required true}})
           (= return-type shape)))))


(defn- require-primitive!
  ([source] (require-primitive! source nil))
  ([{:keys [input return-type effects] :as source} record-contract]
   (when (some #(contains? source %) [:parent :parents])
     (reject! :staged-primitive-inheritance-unsupported))
   (when (some #(contains? source %) [:bind :configure])
     (reject! :staged-primitive-bindings-unsupported))
   (when-not (and (every? #{:name :namespace :description :input :return-type :effects} (keys source))
                  (or (nil? (:description source)) (string? (:description source)))
                  (or (nil? effects) (and (set? effects) (every? keyword? effects))))
     (reject! :staged-primitive-declaration-unsupported))
   (when-not (if record-contract
               (exact-record? input return-type record-contract)
               (and (map? input) (every? simple-keyword? (keys input))
                    (every? field-spec? (vals input)) (leaf-type? return-type)))
     (reject! :staged-primitive-type-unsupported))))


(defn- selected-root
  [{module-ns :namespace :keys [fns] :as source} root]
  (format/require-v2! source)
  (when-not (and (or (nil? module-ns) (string? module-ns)) (vector? fns)
                 (every? #(and (map? %) (simple-keyword? (:name %))) fns)
                 (= (count fns) (count (set (map :name fns)))))
    (reject! :staged-primitive-module-ledger))
  (let [definition (some #(when (= root (ids/fn-id module-ns (:name %))) %) fns)]
    (when-not definition (reject! :staged-root-required))
    definition))


(defn- base-context
  [environment authored]
  (let [rows (module/unique-records (parse/base-declaration-records authored (:input authored) {}))]
    (source-context/declaration-context environment authored rows [])))


(defn prepare
  "Prepare a standalone pure-shape declaration. Scalar fields are the default.
   A pinned record ABI may admit one required closed record of text leaves and
   the same result type. The declarations contain no bindings or parents."
  ([source root] (prepare source root nil))
  ([source root record-contract]
   (let [definition (selected-root source root)]
     (require-primitive! definition record-contract)
     (let [environment (source-context/environment {:name->id {}})]
       (first (:definitions (module/parse-module-v2 (assoc source :fns [definition])
                                                    {:context-for (partial base-context environment)})))))))


(defn- scalar-literal?
  [value]
  (or (nil? value) (boolean? value) (number? value) (string? value)
      (keyword? value) (uuid? value) (inst? value)))


(defn- bound-fields
  [{:keys [parent bind] :as definition}]
  (when-not (and (simple-keyword? parent) (not (contains? definition :parents)))
    (reject! :staged-literal-parent-unsupported))
  (when-not (and (every? #{:name :namespace :description :parent :bind} (keys definition))
                 (or (nil? (:description definition)) (string? (:description definition))))
    (reject! :staged-literal-declaration-unsupported))
  (when-not (and (map? bind) (seq bind)
                 (every? (fn [[field expression]]
                           (and (simple-keyword? field) (vector? expression) (= 2 (count expression))
                                (= :value (first expression)) (scalar-literal? (peek expression))))
                         bind))
    (reject! :staged-literal-binding-unsupported))
  (keys bind))


(defn prepare-literal-root
  "Prepare a v2 root with scalar literal bindings to a visible primitive
   parent in the same file. Reuse the parent's declared membership identities."
  [{module-ns :namespace :as source} root visible-fn-ids]
  (let [definition (selected-root source root)
        fields (bound-fields definition)
        parent-name (:parent definition)
        parent-id (ids/fn-id module-ns parent-name)
        parent (some #(when (= parent-name (:name %)) %) (:fns source))]
    (when-not (and parent (not= parent-id root))
      (reject! :staged-literal-parent-required))
    (when-not (and (set? visible-fn-ids) (contains? visible-fn-ids root)
                   (contains? visible-fn-ids parent-id))
      (reject! :staged-function-not-visible))
    (require-primitive! parent)
    (when-not (every? #(contains? (:input parent) %) fields)
      (reject! :staged-literal-field-unknown))
    (let [parent-source (assoc parent :namespace module-ns)
          parent-rows (parse/base-declaration-records parent-source (:input parent-source) {})
          environment (assoc (source-context/environment {:name->id {}})
                             :definition-by-id {parent-id parent-source}
                             :records-of (constantly parent-rows))
          origins (mapv (fn [field]
                          (let [slot (ids/slot-id parent-id field)]
                            {:fn-id root :slot-id slot :id (ids/binding-id root slot)}))
                        fields)
          context-for (fn [authored]
                        (if (= parent-name (:name authored))
                          (source-context/declaration-context environment authored parent-rows [])
                          (let [owner (parse/composed-declaration-record authored [parent-id] nil)]
                            (source-context/declaration-context environment authored [owner] origins))))]
      (:definitions (module/parse-module-v2 (assoc source :fns [parent definition])
                                            {:context-for context-for})))))
