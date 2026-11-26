(ns graphden.input.source-primitive
  "Prepare an independent format-2 primitive declaration for offline execution."
  (:require
    [graphden.input.field-default :as field-default]
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
    (and (every? #{:type :description :required :default} (keys spec))
         (if (contains? spec :default)
           (field-default/supported? (:required spec) (:type spec) (:default spec))
           (leaf-type? (:type spec)))
         (or (nil? (:description spec)) (string? (:description spec)))
         (or (not (contains? spec :required)) (boolean? (:required spec))))
    (leaf-type? spec)))


(defn- record-of?
  [type member?]
  (and (map? type) (seq type) (every? simple-keyword? (keys type)) (every? member? (vals type))))


(defn- data-type?
  [type]
  (or (leaf-type? type) (record-of? type data-type?)))


(defn- pinned-input-type?
  [type]
  (or (leaf-type? type)
      (record-of? type pinned-input-type?)
      (and (vector? type) (= 4 (count type)) (= :fn (first type))
           (= #{} (peek type)) (record-of? (second type) data-type?) (data-type? (nth type 2)))))


(defn- member-spec?
  [spec]
  (and (map? spec) (contains? spec :type)
       (or (contains? spec :default) (= field-default/nullable-text (:type spec)))))


(defn- occurrence-layout
  "Compile direct optional members into one occurrence. The record identity
   includes their required flags and types, independently of any fallback."
  [{shape :type :as spec}]
  (when (and (map? shape) (some member-spec? (vals shape)))
    (when-not (and (= #{:type :required} (set (keys spec))) (boolean? (:required spec))
                   (not (contains? shape :type)) (every? simple-keyword? (keys shape))
                   (every? (fn [child]
                             (or (leaf-type? child)
                                 (and (member-spec? child) (every? #{:type :required :default} (keys child))
                                      (field-default/supported? (:required child) (:type child) (:default child)))))
                           (vals shape)))
      (reject! :staged-record-member-default-unsupported))
    (let [storage (update-vals shape #(if (member-spec? %) {:type :core.refinements/nullable-text :required false} %))
          record (ids/anonymous-fn-id (ids/shape-hash storage))
          values (into {} (keep (fn [[name child]]
                                  (when (and (member-spec? child) (contains? child :default))
                                    [(str (ids/slot-id record name)) (:default child)]))) shape)]
      {:storage storage :type (update-vals shape #(if (member-spec? %) (:type %) %))
       :envelope (field-default/occurrence-envelope values)})))


(defn- exact-record?
  [input return-type contract layouts]
  (and (map? contract) (seq contract) (= (set (keys contract)) (set (keys input)))
       (every? (fn [[name type]]
                 (and (simple-keyword? name)
                      (if-let [layout (get layouts name)]
                        (= type (:type layout))
                        (and (record-of? type pinned-input-type?) (= {:type type :required true} (get input name))))))
               contract)
       (or (leaf-type? return-type)
           (and (some #{return-type} (vals contract)) (data-type? return-type)))))


(defn- require-primitive!
  ([source] (require-primitive! source nil {}))
  ([{:keys [input return-type effects] :as source} record-contract layouts]
   (when (some #(contains? source %) [:parent :parents])
     (reject! :staged-primitive-inheritance-unsupported))
   (when (some #(contains? source %) [:bind :configure])
     (reject! :staged-primitive-bindings-unsupported))
   (when-not (and (every? #{:name :namespace :description :input :return-type :effects} (keys source))
                  (or (nil? (:description source)) (string? (:description source)))
                  (or (nil? effects) (and (set? effects) (every? keyword? effects))))
     (reject! :staged-primitive-declaration-unsupported))
   (when-not (if record-contract
               (and (map? input) (exact-record? input return-type record-contract layouts))
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
  [environment layouts authored]
  (let [owner (ids/fn-id (:namespace authored) (:name authored))
        defaults (into {} (keep (fn [[field spec]]
                                  (when (and (map? spec) (contains? spec :default))
                                    [(ids/slot-id owner field) {:value (:default spec)}]))) (:input authored))
        storage-input (reduce-kv #(assoc-in %1 [%2 :type] (:storage %3)) (:input authored) layouts)
        member-defaults (into {} (keep (fn [[name {:keys [envelope]}]]
                                         (when envelope [(ids/slot-id owner name) envelope]))) layouts)
        stored-defaults (merge defaults member-defaults)
        rows (module/unique-records (parse/base-declaration-records authored storage-input
                                                                    {:core.refinements/nullable-text field-default/nullable-text-id}))
        rows (mapv #(if (and (= :slot (:kind %)) (contains? defaults (:id %)))
                      (assoc % :type-fn-id field-default/nullable-text-id) %) rows)
        ctx (source-context/declaration-context environment authored rows [])]
    (update ctx :declaration-records
            (fn [records]
              (mapv (fn [{:keys [kind fn-id slot-id] :as row}]
                      (if (and (= :fn-slot kind) (= (:input-type-id ctx) fn-id) (contains? stored-defaults slot-id))
                        (assoc row :input-default (get stored-defaults slot-id))
                        row)) records)))))


(defn prepare
  "Prepare a standalone pure-shape declaration. Scalar fields are the default.
   The record ABI pins field types, including direct optional nullable-text
   members with occurrence-specific defaults. A callable field
   must have a closed effect-free signature and data-only call values.
   Results are scalar or an admitted data record. No bindings or parents."
  ([source root] (prepare source root nil))
  ([source root record-contract]
   (let [definition (selected-root source root)
         layouts (when (and record-contract (map? (:input definition)))
                   (into {} (keep (fn [[k v]] (when-let [layout (occurrence-layout v)] [k layout]))) (:input definition)))]
     (require-primitive! definition record-contract layouts)
     (let [environment (source-context/environment {:name->id {}})
           parsed (first (:definitions (module/parse-module-v2 (assoc source :fns [definition])
                                                               {:context-for (partial base-context environment layouts)})))]
       (cond-> parsed (seq layouts) (assoc :occurrence-fields (set (keys layouts))))))))


(defn- scalar-literal?
  [value]
  (or (nil? value) (boolean? value) (number? value) (string? value)
      (keyword? value) (uuid? value) (inst? value)))


(defn- literal-binding?
  [[field expression]]
  (and (simple-keyword? field) (vector? expression) (= 2 (count expression))
       (= :value (first expression)) (scalar-literal? (peek expression))))


(defn- bound-fields
  [{:keys [parent bind] :as definition}]
  (when-not (and (simple-keyword? parent) (not (contains? definition :parents)))
    (reject! :staged-literal-parent-unsupported))
  (when-not (and (every? #{:name :namespace :description :parent :bind} (keys definition))
                 (or (nil? (:description definition)) (string? (:description definition))))
    (reject! :staged-literal-declaration-unsupported))
  (when-not (and (map? bind) (seq bind) (every? literal-binding? bind))
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
    (when (some #(and (map? %) (contains? % :default)) (vals (:input parent)))
      (reject! :staged-literal-parent-default-unsupported))
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
