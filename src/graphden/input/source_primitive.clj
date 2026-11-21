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


(defn- require-primitive!
  [{:keys [input return-type effects] :as source}]
  (when (some #(contains? source %) [:parent :parents])
    (reject! :staged-primitive-inheritance-unsupported))
  (when (some #(contains? source %) [:bind :configure])
    (reject! :staged-primitive-bindings-unsupported))
  (when-not (and (every? #{:name :namespace :description :input :return-type :effects} (keys source))
                 (or (nil? (:description source)) (string? (:description source)))
                 (or (nil? effects) (and (set? effects) (every? keyword? effects))))
    (reject! :staged-primitive-declaration-unsupported))
  (when-not (and (map? input) (every? simple-keyword? (keys input))
                 (every? field-spec? (vals input)) (leaf-type? return-type))
    (reject! :staged-primitive-type-unsupported)))


(defn prepare
  "Return one v2 parser-validated primitive definition with exact declaration
   rows. The selected root has only scalar fields and no bindings or parents."
  [{module-ns :namespace :keys [fns] :as source} root]
  (format/require-v2! source)
  (when-not (and (or (nil? module-ns) (string? module-ns)) (vector? fns)
                 (every? #(and (map? %) (simple-keyword? (:name %))) fns)
                 (= (count fns) (count (set (map :name fns)))))
    (reject! :staged-primitive-module-ledger))
  (let [definition (some #(when (= root (ids/fn-id module-ns (:name %))) %) fns)]
    (when-not definition (reject! :staged-root-required))
    (require-primitive! definition)
    (let [environment (source-context/environment {:name->id {}})
          context-for (fn [authored]
                        (let [rows (parse/base-declaration-records authored (:input authored) {})]
                          (source-context/declaration-context environment authored rows [])))]
      (first (:definitions (module/parse-module-v2 (assoc source :fns [definition])
                                                   {:context-for context-for}))))))
