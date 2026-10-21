(ns graphden.types.check.provenance
  "An internal certificate of the checker input, not a graph property.
   Metadata keeps the digest out of public rich-type maps and JSON responses."
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.types.core.shapes :as shapes]))


(deftype ^:private MarkerWitness
  [seal input fn-id signature flags args return-value])


(deftype ^:private StorageWitness
  [seal authored-input source-fingerprint])


(defonce ^:private witness-seal (Object.))


(defonce ^:private marker-key (Object.))


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


(defn capture-marker-stamp
  "Freeze flags before checker computation. The returned issuer certifies only
   the completed signature, source input and actual registry identity. Ordinary
   maps and copied metadata cannot certify a changed security projection."
  [definition fn-id]
  (let [flags (shapes/markers-snapshot)
        input (fingerprint definition)]
    (fn [signature]
      (vary-meta signature assoc marker-key
                 (MarkerWitness. witness-seal input fn-id
                                 (fingerprint-data (select-keys signature [:args :return]))
                                 flags (:args signature) (:return signature))))))


(defn checker-stamp-present?
  "Authored entries require marker evidence even if that witness was removed.
   A stale certificate must not fall back to an apparently plain signature."
  [signature]
  (let [metadata (meta signature)]
    (or (contains? metadata ::input) (contains? metadata marker-key))))


;; Uniform Class/.-field syntax resolves these generated fields as methods.
^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn checked-marker-flags
  "Return frozen flags only for an internally issued, unmodified signature.
   Registry enrichment outside args/return does not change this certificate."
  [fn-id signature]
  (let [witness (get (meta signature) marker-key)]
    (when (and (instance? MarkerWitness witness)
               (identical? witness-seal (.-seal ^MarkerWitness witness))
               (= fn-id (:fn-id signature) (.-fn-id ^MarkerWitness witness))
               (= (::input (meta signature)) (.-input ^MarkerWitness witness))
               ;; Checked signatures are persistent Clojure data. An enriched
               ;; map shares these immutable values; a rebuilt projection must
               ;; still prove equality through the original digest.
               (or (and (identical? (:args signature) (.-args ^MarkerWitness witness))
                        (identical? (:return signature) (.-return-value ^MarkerWitness witness)))
                   (= (fingerprint-data (select-keys signature [:args :return]))
                      (.-signature ^MarkerWitness witness))))
      (.-flags ^MarkerWitness witness))))


(defn stamp-storage-input
  "Keep a second certificate of exact canonical writer input on the SAME
   authored-checked signature. This never changes types or original evidence;
   failed or superseded authored input cannot acquire a storage certificate."
  [signature definition source-fingerprint]
  (if (and source-fingerprint (matches? signature definition))
    (vary-meta signature assoc ::storage-input
               (StorageWitness. witness-seal (::input (meta signature)) source-fingerprint))
    signature))


;; Keep typed field access for the same generated-type constraint above.
^{:splint/disable [lint/prefer-method-values]}
(defn matches-storage-input?
  "Compare immutable snapshot source to certified writer input. Both the
   original authored certificate and its bound storage certificate must exist.
   Source authorization and fresh checking remain independent requirements."
  [signature source-fingerprint]
  (let [metadata (meta signature)
        witness (::storage-input metadata)]
    (and (some? source-fingerprint)
         (some? (::input metadata))
         (instance? StorageWitness witness)
         (identical? witness-seal (.-seal ^StorageWitness witness))
         (= (::input metadata) (.-authored-input ^StorageWitness witness))
         (= source-fingerprint (.-source-fingerprint ^StorageWitness witness)))))
