(ns graphden.schema-malli.core
  "Malli implementation of SchemaProvider protocol"
  (:require
   [graphden.schema.interface :as schema]
   [integrant.core :as ig]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]))

(defn- malli-type->generic
  "Convert Malli type to generic type keyword"
  [malli-type]
  (case malli-type
    :keyword :keyword
    :string :string
    :int :int
    :boolean :boolean
    :double :double
    (:vector :sequential) :vector
    :map :map
    :any))

(defn- extract-fields
  "Extract field definitions from Malli map schema"
  [schema]
  (when (= :map (m/type schema))
    (map (fn [child]
           (let [[field-name props field-schema] (if (= 3 (count child))
                                                   child
                                                   [(first child) {} (second child)])
                 field-type (m/type field-schema)
                 optional? (or (:optional props)
                               (= :maybe field-type))]
             {:name field-name
              :type (if (= :maybe field-type)
                      (malli-type->generic (m/type (first (m/children field-schema))))
                      (malli-type->generic field-type))
              :optional? optional?}))
         (m/children schema))))

(defrecord MalliSchemaProvider [schemas relations derived-queries]
  schema/SchemaProvider

  (validate [_ schema-key data]
    (if-let [s (get schemas schema-key)]
      (if (m/validate s data)
        {:valid? true}
        {:valid? false
         :errors (-> s (m/explain data) me/humanize)})
      {:valid? false
       :errors [(str "Unknown schema: " schema-key)]}))

  (coerce [_ schema-key data]
    (if-let [s (get schemas schema-key)]
      (m/coerce s data (mt/transformer
                        mt/string-transformer
                        mt/strip-extra-keys-transformer))
      data))

  (get-fields [_ schema-key]
    (when-let [s (get schemas schema-key)]
      (extract-fields s)))

  (get-relations [_]
    relations)

  (get-derived-queries [_]
    derived-queries))

(defn create-provider
  "Create MalliSchemaProvider from config map"
  [{:keys [schemas relations derived-queries]}]
  (->MalliSchemaProvider schemas
                         (or relations {})
                         (or derived-queries #{})))

;; Integrant integration
(defmethod ig/init-key ::provider
  [_ config]
  (create-provider config))
