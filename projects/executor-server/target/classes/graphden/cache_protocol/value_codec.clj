(ns graphden.cache-protocol.value-codec
  "Shared value encoding/decoding for cache implementations.

   Cache implementations store merged argument values using a union format:
   - {:kind :literal :value <any-value>} - for literal values
   - {:kind :fn-ref :fn-id <uuid>} - for function references

   This codec provides format-agnostic parsing and formatting of these values,
   while the actual serialization (JSON/EDN/etc) is left to implementations.")


(defn parse-cached-value
  "Parses a cached value from the union format.

   Input is a parsed map (already deserialized from JSON/EDN):
   - {:kind :literal :value ...} -> returns the :value
   - {:kind :fn-ref ...} -> returns the map with :kind as keyword
   - Other maps or nil -> returns as-is

   This function should be called AFTER deserializing from storage format."
  [parsed]
  (when parsed
    (if (and (map? parsed) (contains? parsed :kind))
      (case (keyword (:kind parsed))
        :literal (:value parsed)
        :fn-ref (assoc parsed :kind :fn-ref)
        parsed)
      parsed)))


(defn format-cached-value
  "Formats a value for caching in the union format.

   Returns a map suitable for serialization:
   - nil -> nil
   - {:kind :fn-ref ...} -> returns as-is (function reference)
   - any other value -> {:kind :literal :value <value>}

   This function should be called BEFORE serializing to storage format."
  [value]
  (cond
    (nil? value)
    nil

    ;; fn-ref values keep their kind
    (and (map? value) (= :fn-ref (:kind value)))
    value

    ;; literal values wrap in union format
    :else
    {:kind :literal :value value}))


(defn fn-ref?
  "Returns true if the value is a function reference."
  [value]
  (and (map? value) (= :fn-ref (:kind value))))


(defn literal-value?
  "Returns true if the value is a wrapped literal value."
  [value]
  (and (map? value) (= :literal (:kind value))))
