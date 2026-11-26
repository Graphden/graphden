(ns graphden.input.source-legacy-readers
  "Replay legacy reader dispatch from the package's original rows."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records.parse :as parse]))


(defn- closure-rows
  [prepared by-id owner]
  (loop [pending [owner] seen #{} result #{}]
    (if-let [id (peek pending)]
      (if (contains? seen id)
        (recur (pop pending) seen result)
        (let [definition (get by-id id)
              rows (parse/parse-prepared-module (assoc prepared :fn-defs [definition]))
              next-ids (into #{} (mapcat (fn [row]
                                           (into (:parent-ids row [])
                                                 (keep row [:ref-fn-id :type-fn-id :type-override-fn-id
                                                            :return-type-fn-id :element-fn-id])))) rows)]
          (recur (into (pop pending) (filter by-id next-ids)) (conj seen id) (into result rows))))
      result)))


(defn- index
  [prepared owner]
  (let [by-id (into {} (map (juxt :fn-id identity)) (vals (:defs-by-name prepared)))
        kinds (group-by :kind (closure-rows prepared by-id owner))]
    (lookups/build-lookups {:fns (:fn kinds) :slots (:slot kinds) :fn-slots (:fn-slot kinds)
                            :bindings (:binding kinds) :list-items (:binding-list-item kinds)})))


(defn prepare
  "Keep the legacy compiler's registry reads inside this trusted source replay.
   The compiler index is lazy and private to this exact forwarding baseline."
  [prepared owner roots]
  (if (seq roots)
    (assoc prepared ::compiler (delay (index prepared owner)) ::signatures roots)
    prepared))


(defn value-edge?
  "Require the original compiler to evaluate the same effective slot and target
   that the source parser recorded. Callable and identity dispatch stay distinct."
  [{::keys [compiler signatures]} owner row]
  (when compiler
    (binding [registry/*rich-types-override* (atom {:by-id signatures :by-name {}})
              registry/*per-org-rich-override* (atom {})]
      (let [idx @compiler
            matches (filter #(= (:slot-id row) (:slot-id %))
                            (concat (bindings/collect-bindings owner idx)
                                    (bindings/collect-env-bindings owner idx)))
            [reader & more] matches]
        (and reader (empty? more) (= row (get-in idx [:binding-by-fn-slot [owner (:slot-id row)]]))
             (= :ref (:kind reader)) (false? (:is-fn reader)) (= (:ref-fn-id row) (:ref-id reader)))))))
