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


(defn fingerprint-data
  "Stable private digest of immutable structural data, including nested maps.
   This computes evidence only; it neither authorizes nor checks the data."
  [value]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false *print-namespace-maps* false]
    (ids/digest-hex "SHA-256"
                    (pr-str (canonical value)))))


(defn fingerprint
  "Hash semantic checker input. Descriptions/source locations do not affect
   checking; argument presence, values, types and identities do. The caller
   supplies checker-view, so list append syntax has already been normalized."
  [definition]
  (fingerprint-data
    (select-keys definition
                 [:id :name :namespace :parent :parents :args
                  :return-type :lambda-params :expects-effects :branch-local?])))


(defn stamp
  "Attach internal provenance without adding a serializable entry field."
  [signature definition]
  (vary-meta signature #(-> %
                            (dissoc ::storage-input)
                            (assoc ::input (fingerprint definition)))))


(defn matches?
  "Missing provenance is not evidence that this definition was checked."
  [signature definition]
  (= (::input (meta signature)) (fingerprint definition)))


(defn stamp-storage-input
  "Keep a second certificate of exact canonical writer input on the SAME
   authored-checked signature. This never changes types or original evidence;
   failed or superseded authored input cannot acquire a storage certificate."
  [signature definition source-fingerprint]
  (if (and source-fingerprint (matches? signature definition))
    (vary-meta signature assoc ::storage-input
               {:authored-input (::input (meta signature))
                :source-fingerprint source-fingerprint})
    signature))


(defn matches-storage-input?
  "Compare immutable snapshot source to certified writer input. Both the
   original authored certificate and its bound storage certificate must exist.
   Source authorization and fresh checking remain independent requirements."
  [signature source-fingerprint]
  (let [metadata (meta signature)
        witness (::storage-input metadata)]
    (and (some? source-fingerprint)
         (some? (::input metadata))
         (= (::input metadata) (:authored-input witness))
         (= source-fingerprint (:source-fingerprint witness)))))
