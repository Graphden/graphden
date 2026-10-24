(ns graphden.editor.cohort-parser
  "Parse a fresh personal graph cohort against fixed, parse-only declarations.
   Real installed dependencies are source-authorized separately before write."
  (:require
    [clojure.string :as str]
    [graphden.executor.composition.core :as composition]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]))


(defn parse-cohort
  "Assign stable fresh identities while retaining source UUIDs as parser-only
   aliases. Local helper names include the nonce suffix to avoid collisions."
  [root root-name function-definitions references declarations]
  (let [index (fn [value-fn]
                (reduce (fn [result {:keys [name] path :namespace :as definition}]
                          (assoc result name (value-fn definition)
                                 (keyword path (clojure.core/name name)) (value-fn definition)))
                        {} declarations))
        reference-names (into references (map (fn [[reference id]] [(keyword (name reference)) id])) references)
        names (merge (ids/primitive-fn-ids) (index #(ids/fn-id (:namespace %) (:name %))) reference-names)
        suffix (last (str/split root #"\."))
        local-names (into {} (keep (fn [{:keys [name]}]
                                     (when (not= root-name name)
                                       [name (keyword (str (clojure.core/name name) "-" suffix))]))) function-definitions)
        rewrite (fn [reference]
                  (if (or (nil? (namespace reference)) (= root (namespace reference)))
                    (if-let [renamed (get local-names (keyword (name reference)))]
                      (if (namespace reference) (keyword root (name renamed)) renamed)
                      reference)
                    reference))
        fresh-definitions (mapv #(assoc (rebase/rewrite-references % rewrite)
                                        :name (get local-names (:name %) (:name %))) function-definitions)
        records (composition/fn-defs->records fresh-definitions names (index identity))]
    {:definitions fresh-definitions :records records :root-id (ids/fn-id root root-name)
     :reference-identities references}))
