(ns graphden.storage-protocol.codec
  "Common codec utilities for storage backends.

   Provides generic row encoding/decoding functions that can be used
   by backend-specific codec implementations to reduce duplication.

   Backend codecs implement StorageValueCodec protocol with their own
   encode-value/decode-value, then use these helpers for row operations.")


(defn generic-encode-row
  "Generic row encoding that applies encode-value to each field.

   Arguments:
   - encode-fn: (fn [value field-spec] -> encoded-value)
   - row: map of field-name -> value
   - field-specs: map of field-name -> field-spec
   - opts: optional map with:
     - :key-transform - fn to transform field names in output (default: identity)
     - :fallback-specs - map of field-name -> field-spec for fields not in field-specs

   Returns map with encoded values.

   Example:
   (generic-encode-row
     (partial sp/encode-value codec)
     {:name \"John\" :age 30}
     {:name {:type :text} :age {:type :int}}
     {:key-transform util/kw->snake-case})"
  ([encode-fn row field-specs]
   (generic-encode-row encode-fn row field-specs {}))
  ([encode-fn row field-specs {:keys [key-transform fallback-specs]
                               :or {key-transform identity
                                    fallback-specs {}}}]
   (reduce-kv
     (fn [acc field-name value]
       (let [output-key (key-transform field-name)
             field-spec (or (get field-specs field-name)
                            (get fallback-specs field-name))
             encoded (if field-spec
                       (encode-fn value field-spec)
                       value)]
         (assoc acc output-key encoded)))
     {}
     row)))


(defn generic-decode-row
  "Generic row decoding that applies decode-value to each field.

   Arguments:
   - decode-fn: (fn [value field-spec] -> decoded-value)
   - row: map of column-key -> value (from storage)
   - field-specs: map of field-name -> field-spec
   - opts: optional map with:
     - :key-transform - fn to transform input keys to field names (default: identity)

   Returns map with decoded values using canonical field names.

   NOTE: decode-fn is ALWAYS called for each value, even if no field-spec exists.
   This allows backends to handle storage-native types (e.g., PGobject) that need
   decoding regardless of schema metadata.

   Example:
   (generic-decode-row
     (partial sp/decode-value codec)
     {:user_name \"John\" :user_age 30}
     {:user-name {:type :text} :user-age {:type :int}}
     {:key-transform util/snake->kw})"
  ([decode-fn row field-specs]
   (generic-decode-row decode-fn row field-specs {}))
  ([decode-fn row field-specs {:keys [key-transform]
                               :or {key-transform identity}}]
   (reduce-kv
     (fn [acc col-key value]
       (let [field-name (key-transform col-key)
             field-spec (get field-specs field-name)
             ;; Always call decode-fn - backends may need to handle storage-native
             ;; types (e.g., PGobject) that need decoding even without field-spec
             decoded (decode-fn value field-spec)]
         (assoc acc field-name decoded)))
     {}
     row)))
