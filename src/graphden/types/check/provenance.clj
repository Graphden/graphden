(ns graphden.types.check.provenance
  "An internal certificate of the checker input, not a graph property.
   Metadata keeps the digest out of public rich-type maps and JSON responses."
  (:require
    [graphden.packages.records.ids :as ids]))


(defn- canonical
  [value]
  (cond
    (map? value) [::map (->> value
                             (map (fn [[k v]] [(canonical k) (canonical v)]))
                             (sort-by (comp pr-str first))
                             vec)]
    (set? value) [::set (vec (sort-by pr-str (map canonical value)))]
    (vector? value) [::vector (mapv canonical value)]
    (sequential? value) [::sequence (mapv canonical value)]
    :else value))


(defn fingerprint
  "Hash semantic checker input. Descriptions/source locations do not affect
   checking; argument presence, values, types and identities do. The caller
   supplies checker-view, so list append syntax has already been normalized."
  [definition]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false *print-namespace-maps* false]
    (ids/digest-hex "SHA-256"
                    (pr-str (canonical
                              (select-keys definition
                                           [:id :name :namespace :parent :parents :args
                                            :return-type :lambda-params :expects-effects
                                            :branch-local?]))))))


(defn stamp
  "Attach internal provenance without adding a serializable entry field."
  [signature definition]
  (vary-meta signature assoc ::input (fingerprint definition)))


(defn matches?
  "Missing provenance is not evidence that this definition was checked."
  [signature definition]
  (= (::input (meta signature)) (fingerprint definition)))
