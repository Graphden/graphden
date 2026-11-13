(ns graphden.executor.source-recipe-values
  "Bounded private copies of the known JDBC source row representation.
   Unknown values are ineligible; mutable driver values are never shared
   between a retained recipe and a caller. This module grants no read access."
  (:import
    (java.math
      BigDecimal
      BigInteger)
    (java.sql
      Timestamp)
    (java.util
      Date
      UUID)
    (org.postgresql.util
      PGobject)))


(deftype ^:private MutableValue
  [kind value extra])


(deftype ^:private FrozenRows
  [value charge])


(def ^:private scalar-classes
  #{Boolean Byte Short Integer Long Float Double UUID java.time.Instant})


(def ^:private map-classes
  #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap})


(defn- unavailable!
  [reason]
  (throw (ex-info "Source recipe value is ineligible"
                  {:type ::ineligible :reason reason})))


(defn- charge!
  [used limit amount]
  (when (> (vswap! used +' amount) limit)
    (unavailable! :byte-limit)))


(defn- string-charge!
  [used limit value]
  ;; Include UTF-16 backing storage and transient reconstruction headroom.
  (charge! used limit (if value (+' 128 (*' 4 (count value))) 64)))


(defn- integer-charge!
  [used limit ^BigInteger value]
  (charge! used limit (+' 192 (*' 4 (quot (+ 7 (BigInteger/.bitLength value)) 8)))))


(defn- freeze-value
  [value used limit depth structural-sets?]
  (when (> depth 64) (unavailable! :depth-limit))
  ;; Per-node allowance includes collection slots and traversal/copy headroom;
  ;; byte accounting is deliberately conservative, not serialized text length.
  (charge! used limit 128)
  (let [kind (class value)
        descend #(freeze-value % used limit (inc depth) structural-sets?)]
    (cond
      (nil? value) nil
      (= String kind) (do (string-charge! used limit value) value)
      (keyword? value)
      (do (string-charge! used limit (name value))
          (string-charge! used limit (namespace value)) value)
      (contains? scalar-classes kind) value
      (= BigInteger kind) (do (integer-charge! used limit value) value)
      (= clojure.lang.BigInt kind)
      (do (integer-charge! used limit (biginteger value)) value)
      (= BigDecimal kind)
      (do (integer-charge! used limit (BigDecimal/.unscaledValue ^BigDecimal value)) value)

      (= PGobject kind)
      (let [type (PGobject/.getType ^PGobject value)
            text (PGobject/.getValue ^PGobject value)]
        (string-charge! used limit type)
        (string-charge! used limit text)
        (MutableValue. :pg-object text type))

      (= Timestamp kind)
      (MutableValue. :timestamp (Timestamp/.getTime ^Timestamp value)
                     (Timestamp/.getNanos ^Timestamp value))

      (contains? #{Date java.sql.Date java.sql.Time} kind)
      (MutableValue. :date (Date/.getTime ^Date value) kind)

      (contains? map-classes kind)
      (do
        (when (some? (meta value)) (unavailable! :metadata))
        (into {} (map (fn [[k v]]
                        (when-not (or (nil? k) (keyword? k)
                                      (contains? scalar-classes (class k))
                                      (contains? #{String BigInteger BigDecimal clojure.lang.BigInt} (class k)))
                          (unavailable! :map-key-type))
                        [(descend k) (descend v)])) value))

      (= clojure.lang.PersistentVector kind)
      (do
        (when (some? (meta value)) (unavailable! :metadata))
        (mapv descend value))

      (and structural-sets? (= clojure.lang.PersistentHashSet kind))
      (do
        (when (some? (meta value)) (unavailable! :metadata))
        ;; Type constraints use sets for effects, placement and scalar enums.
        ;; Neither arbitrary literal sets nor mutable/hashable members enter
        ;; this field-specific representation.
        (when-not (every? #(or (nil? %) (keyword? %) (= String (class %))
                               (contains? scalar-classes (class %))
                               (contains? #{BigInteger BigDecimal clojure.lang.BigInt} (class %))) value)
          (unavailable! :constraint-set-member))
        (charge! used limit (+ 512 (* 128 (count value))))
        (MutableValue. :constraint-set (mapv descend value) nil))

      :else (unavailable! :value-type))))


(defn freeze
  "Copy raw rows under a caller-owned reservation. The returned opaque value
   retains only known immutable data or private copies of mutable JDBC leaves.
   Unknown classes/subclasses, metadata, excessive depth or bytes throw
   ::ineligible; consumers must abandon the complete recipe on that signal."
  [value max-bytes]
  (when-not (and (integer? max-bytes) (pos? max-bytes))
    (throw (ex-info "Source recipe reservation must be positive" {})))
  (let [used (volatile! 256)
        copied (freeze-value value used max-bytes 0 false)]
    (FrozenRows. copied @used)))


(defn freeze-type-constraints
  "Copy only the fn constraint field, additionally supporting closed scalar
   sets used by type declarations. Ordinary source literal admission stays
   unchanged. Metadata and unknown/mutable set members remain ineligible."
  [value max-bytes]
  (when-not (and (integer? max-bytes) (pos? max-bytes))
    (throw (ex-info "Source constraint reservation must be positive" {})))
  (let [used (volatile! 256)
        copied (freeze-value value used max-bytes 0 true)]
    (FrozenRows. copied @used)))


;; Uniform Class/.-field syntax resolves generated fields as methods.
^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn- thaw-value
  [value]
  (cond
    (instance? MutableValue value)
    (let [kind (.-kind ^MutableValue value)
          data (.-value ^MutableValue value)
          extra (.-extra ^MutableValue value)]
      (case kind
        :constraint-set (set (map thaw-value data))
        :pg-object (doto (PGobject.) (PGobject/.setType extra) (PGobject/.setValue data))
        :timestamp (doto (Timestamp. data) (Timestamp/.setNanos extra))
        :date (condp = extra
                Date (Date. data)
                java.sql.Date (java.sql.Date. data)
                java.sql.Time (java.sql.Time. data))))
    (map? value) (into {} (map (fn [[k v]] [(thaw-value k) (thaw-value v)])) value)
    (vector? value) (mapv thaw-value value)
    :else value))


^{:splint/disable [lint/prefer-method-values]}
(defn thaw
  "Return rows with fresh mutable driver leaves for this replay only."
  [frozen]
  (when-not (instance? FrozenRows frozen)
    (throw (ex-info "Expected frozen source rows" {})))
  (thaw-value (.-value ^FrozenRows frozen)))


^{:splint/disable [lint/prefer-method-values]}
(defn charge-bytes
  "Conservative retained/copy allowance to charge to the recipe reservation."
  [frozen]
  (when-not (instance? FrozenRows frozen)
    (throw (ex-info "Expected frozen source rows" {})))
  (.-charge ^FrozenRows frozen))
