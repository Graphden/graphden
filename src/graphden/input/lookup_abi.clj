(ns graphden.input.lookup-abi
  "Native lookup arguments whose private map-key variable does not occur elsewhere."
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(def ^:private lookups
  (let [signature (fn [name argument other-args collection-arms]
                    (let [id (ids/fn-id "core.collections" name)]
                      [id {:name name :argument argument :path [(ids/slot-id id argument)]
                           :other-args other-args :collection-arms collection-arms}]))]
    (into {} [(signature :get :coll {:key :any :default :any} #{:null [:list :any]})
              (signature :get-in :map {:path [:list [:union :int :keyword :text]] :default :any} #{:null})])))


(defn collection-argument
  "Match the frozen UUID and complete native ABI, including binder identity."
  [{module-ns :namespace :keys [fn-id name args return]} variable]
  (when-let [lookup (get lookups fn-id)]
    (let [{:keys [argument collection-arms other-args]} lookup]
      (when (and (= "core.collections" module-ns) (= name (:name lookup)) (= return :any)
                 (= other-args (dissoc args argument))
                 (= (conj collection-arms [:map variable :any]) (set (types/union-members (get args argument)))))
        (select-keys lookup [:argument :path])))))
