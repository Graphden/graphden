(ns graphden.input.arguments
  "Invocation routing through a checked residual input surface. Values remain
   opaque at leaves; only declared record and reference-input groups are walked."
  (:require
    [graphden.input.ir :as ir]
    [graphden.input.runtime :as input]))


(defn route-values
  "Translate named residual inputs to the compiler's exact scoped addresses.
   This is invocation, not binding: no expression grammar or implicit deref.
   Unknown/occupied names fail before execution. Nil and false remain present.
   A callback adapter may route existing field cells without reading them."
  ([surface supplied]
   (route-values surface supplied (fn [record {:keys [name]}] (get record name))))
  ([{:keys [whole-record-inputs] :as surface} supplied leaf-value]
   (letfn [(route
             [node value path]
             (let [fields (:fields node)
                   accepted (set (map :name fields))]
               (when-not (map? value)
                 (ir/reject! :record-input-required {:path path}))
               (let [extras (remove accepted (keys value))
                     key-type (get whole-record-inputs (:address node))]
                 (when (and (seq extras) (or (nil? key-type) (and (= :keyword key-type) (not-every? keyword? extras))))
                   (ir/reject! :unknown-input-field {:path path}))
                 (into (if (= :input-field (:kind node)) {(:address node) (if (input/record-view? value)
                                                                            (input/dictionary-view (select-keys (input/cache-token value) extras))
                                                                            (select-keys value extras))} {})
                       (mapcat (fn [{:keys [name address] :as field}]
                                 (when (contains? value name)
                                   (if (contains? field :fields)
                                     (route field (get value name) (conj path name))
                                     {address (leaf-value value field)}))))
                       fields))))]
     (route (:root surface) supplied []))))


(defn route-sources
  "Route explicit record cells without forcing them. Ordinary map leaves
   are opaque supplied values, including functions, atoms and delays."
  [surface args]
  (let [sources (volatile! {})
        supplied (route-values surface args
                               (fn [record {:keys [address name]}]
                                 (if (input/record-view? record)
                                   (do (vswap! sources assoc address (get (input/cache-token record) name))
                                       nil)
                                   (get record name))))]
    [supplied @sources]))
