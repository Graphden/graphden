(ns graphden.input.variables
  "Derived type-variable identity per reference-use scope."
  (:require
    [graphden.types.core :as types]
    [graphden.types.core.execution :as execution])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.util
      UUID)))


(defn- variable-name
  [scope sort variable]
  (let [identity (binding [*print-length* nil *print-level* nil]
                   (pr-str [scope sort variable]))]
    (symbol (str (name variable) "$"
                 (UUID/nameUUIDFromBytes (.getBytes identity StandardCharsets/UTF_8))))))


(defn type-in-scope
  "Instantiate value and execution variables with the existing type walker.
   A declaration or literal payload is never rewritten or persisted."
  [scope rich]
  (types/freshen-with rich (partial variable-name scope)))


(defn map-descriptor-types
  [f node]
  (cond-> (update node :type f)
    (:fields node) (update :fields #(mapv (partial map-descriptor-types f) %))
    (:alternatives node) (update :alternatives #(mapv f %))))


(defn descriptor-in-scope
  [scope node]
  (map-descriptor-types (partial type-in-scope scope) node))


(defn signature-in-scope
  [scope signature]
  (when signature
    (cond-> signature
      (:return signature) (update :return #(type-in-scope scope %))
      (types/execution-var? (:execution signature))
      (update :execution #(vector :execution-var (variable-name scope :execution (second %))))
      (:args signature) (update :args #(into {} (map (fn [[k v]] [k (type-in-scope scope v)])) %)))))


(defn resolve-signature
  "Apply the common graph solution to derived signature evidence."
  [substitution signature]
  (let [resolve-type (partial types/resolve substitution)]
    (cond-> signature
      (:return signature) (update :return resolve-type)
      (:args signature) (update :args #(update-vals % resolve-type))
      (:execution signature) (update :execution #(execution/resolve-constraint substitution %))
      (:resolved-bindings signature) (update :resolved-bindings #(update-vals % (fn [info] (update info :type resolve-type)))))))
