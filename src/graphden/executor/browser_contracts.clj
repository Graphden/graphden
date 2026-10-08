(ns graphden.executor.browser-contracts
  "Fixed shipped type contracts required by the bounded editor component ABI.
   This does not grant source access to arbitrary package compositions."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.types :as record-types]
    [graphden.tenancy.context :as tenancy]))


(def ^:private declarations
  {"core/refinements" #{:color :non-negative-int :text-map :keyword-map :text-keyed-map :keyword-or-text}
   "web/html" #{:hiccup-node}
   "app/ui-components" #{:_ui-components-identities}})


(defn- shipped-module
  [module]
  (-> (str "packages/" module "/fns.edn") io/resource slurp edn/read-string))


(defn- type-records
  [module names]
  (let [{:keys [fns] fn-namespace :namespace} (shipped-module module)]
    (mapcat #(parse/parse-fn-def (assoc % :namespace fn-namespace) {})
            (filter #(contains? names (:name %)) fns))))


(defn- load-contracts
  []
  (tenancy/with-org tenancy/public-org
                    (let [named (mapcat (fn [[module names]] (type-records module names)) declarations)
                          map-definition (first (filter #(= :map (:name %))
                                                        (:fns (shipped-module "core/hof"))))
                          callback (record-types/inline-fn-type-rows-from-form (get-in map-definition [:args :func :type]))
                          records (concat named callback)
                          grouped (group-by :kind records)
                          by-owner (group-by :fn-id (:fn-slot grouped))
                          slots (into {} (map (juxt :id identity)) (:slot grouped))]
                      (into {}
                            (map (fn [row]
                                   (let [junctions (vec (get by-owner (:id row)))]
                                     [(:id row) {:fn row :fn-slots junctions
                                                 :slots (mapv #(get slots (:slot-id %)) junctions)}])))
                            (:fn grouped)))))


(def ^:private fixed-contracts (delay (load-contracts)))


(defn contract
  "A shipped normative type contract by canonical identity, or nil. The
   caller still requires public/read-visible and package-protected ownership."
  [id]
  (get @fixed-contracts id))


(defn- same-shape?
  [expected actual]
  (cond
    (symbol? expected) (and (or (symbol? actual) (keyword? actual) (string? actual))
                            (= (name expected) (name actual)))
    (map? expected) (and (map? actual) (= (set (keys expected)) (set (keys actual)))
                         (every? (fn [[key value]] (same-shape? value (get actual key))) expected))
    (sequential? expected) (and (sequential? actual) (= (count expected) (count actual))
                                (every? true? (map same-shape? expected actual)))
    :else (= expected actual)))


(defn matches?
  "Compare only structural source, never mutable descriptions or version
   timestamps. No own binding/literal or predicate implementation is allowed.
   Type variables tolerate the storage codec's symbol spelling conversion."
  [{expected :fn expected-slots :slots expected-junctions :fn-slots} row slots junctions bindings]
  (let [fields [:id :name :parent-ids :base-fn-id :element-fn-id
                :return-type-fn-id :anonymous-hash :constraint]
        slot-fields [:id :name :type-fn-id :required]
        junction-fields [:id :fn-id :slot-id :position]]
    (and (empty? bindings)
         (same-shape? (select-keys expected fields) (select-keys row fields))
         (= (set (map #(select-keys % slot-fields) expected-slots))
            (set (map #(select-keys % slot-fields) slots)))
         (= (set (map #(select-keys % junction-fields) expected-junctions))
            (set (map #(select-keys % junction-fields) junctions))))))
