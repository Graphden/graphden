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
  [scope variable-sort variable]
  (let [identity-text (binding [*print-length* nil *print-level* nil]
                        (pr-str [scope variable-sort variable]))]
    (symbol (str (name variable) "$"
                 (UUID/nameUUIDFromBytes (String/.getBytes identity-text StandardCharsets/UTF_8))))))


(defn type-in-scope
  "Instantiate value and execution variables with the existing type walker.
   A declaration or literal payload is never rewritten or persisted."
  [scope rich]
  (types/freshen-with rich (partial variable-name scope)))


(defn value-renaming
  "Derive value-variable names for a proved shared declaration in two scopes.
   This does not establish binder correspondence. The common type walker keeps
   execution variables and refinement payload symbols out of the renaming."
  [from-scope to-scope rich]
  (let [renaming (volatile! {})]
    (types/freshen-with rich
                        (fn [variable-sort variable]
                          (when (= :value variable-sort)
                            (vswap! renaming assoc
                                    (variable-name from-scope variable-sort variable)
                                    (variable-name to-scope variable-sort variable)))
                          variable))
    @renaming))


(defn- canonical-value-binders
  [type]
  (let [names (volatile! {})]
    (types/freshen-with
      type
      (fn [variable-sort variable]
        (if (= :value variable-sort)
          (or (get @names variable)
              (let [name (symbol (str "a" (count @names)))]
                (vswap! names assoc variable name)
                name))
          variable)))))


(defn shared-value-domain
  "Find one domain for multiple scalar readers of a positional argument.
   Independent generic readers can vary in the identities of their value
   binders. Recheck after inference so concrete, incompatible types fail."
  [types]
  (let [specific (remove #{:any} types)
        domain (first specific)]
    (when (seq types)
      (if domain
        (when (or (apply = specific) (apply = (map canonical-value-binders specific))) domain)
        :any))))


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
      (:resolved-bindings signature)
      (update :resolved-bindings
              #(update-vals % (fn [info]
                                (cond-> (update info :type resolve-type)
                                  (:list-elements info) (update :list-elements
                                                                (partial mapv (fn [element]
                                                                                (update element :type resolve-type)))))))))))
