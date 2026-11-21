(ns graphden.input.pinned-list-runtime
  "Prepare generated implementations from a verified package receipt. Executable
   code comes from the list compiler and the independently registered native."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.list-execution :as lists]
    [graphden.input.stored-implementations :as stored]
    [graphden.types.check :as check]))


(defn implementations
  [{:keys [generated list-type-id]}]
  (update-vals generated
               (fn [{:keys [contract list-implementation input-type-id declaration-records]}]
                 {:input-id input-type-id :return-id list-type-id
                  :ordered-fields (mapv (juxt :id :name :required) (:fields contract))
                  :memberships (into #{} (comp (filter #(= :fn-slot (:kind %))) (map (juxt :id :slot-id :position))) declaration-records)
                  :signature (assoc (:root-entry list-implementation) :execution #{:jvm-server})
                  :impl (lists/compile-arguments (get-in list-implementation [:generated-list :operands]))})))


(defn checked-declarations
  [list-module root signature impl operands]
  (let [by-kind (group-by :kind (distinct (concat (:records list-module) (mapcat :records (vals operands)))))
        index #(into {} (map (juxt :id identity)) (get by-kind %))
        ctx (context/build-context {:fns (index :fn) :fields (index :slot) :bindings (index :input-binding)
                                    :members (group-by :fn-id (:fn-slot by-kind))})
        trusted (assoc (merge (implementations list-module) operands) root {:signature signature :impl impl})
        options {:implementations (stored/resolve-closure ctx root trusted)
                 :check-expression check/check-input-binding :infer-signature check/infer-input-signature}]
    (doseq [{:keys [fn-id]} (:definitions list-module)]
      (when-not (= :checked (:status (analysis/analyze-effective-input ctx fn-id options)))
        (context/reject! :unverified-list-declaration {:fn-id fn-id})))
    (filterv #(not= :input-binding (:kind %)) (:records list-module))))
