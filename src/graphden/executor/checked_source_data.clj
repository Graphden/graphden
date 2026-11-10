(ns graphden.executor.checked-source-data
  "Conservative immutable admission for opt-in checker artifacts. This is not
   source authority or general-purpose serialization. Unknown values miss."
  (:require
    [graphden.types.check.provenance :as provenance])
  (:import
    (clojure.lang
      BigInt
      Ratio)
    (java.lang.reflect
      Modifier)
    (java.math
      BigDecimal
      BigInteger)))


(def ^:private containers
  {clojure.lang.PersistentArrayMap :map clojure.lang.PersistentHashMap :map
   clojure.lang.PersistentVector :sequence
   clojure.lang.PersistentList :list clojure.lang.PersistentList$EmptyList :sequence
   clojure.lang.PersistentHashSet :sequence})


(def ^:private scalar-classes
  #{nil String Boolean Character java.util.UUID clojure.lang.Keyword clojure.lang.Symbol
    Long Integer Short Byte Double Float BigDecimal BigInteger BigInt Ratio})


(defn static-callback?
  "Caller must separately approve deterministic checker behavior and ABI.
   Refuse closures with retained instance captures, even if explicitly listed."
  [value]
  (and (fn? value) (nil? (meta value))
       (contains? #{clojure.lang.AFunction clojure.lang.RestFn} (.getSuperclass (class value)))
       (every? #(Modifier/isStatic (.getModifiers ^java.lang.reflect.Field %))
               (.getDeclaredFields (class value)))))


(defn- approved-callback?
  [callbacks value]
  (and (fn? value) (some #(identical? value %) callbacks)))


(defn- integer-charge
  [^BigInteger value]
  (+ 96 (quot (+ 7 (.bitLength value)) 8)))


(defn- scalar-charge
  [value]
  (cond
    (string? value) (+ 96 (* 2 (count value)))
    (or (keyword? value) (symbol? value))
    (+ 192 (* 2 (+ (count (name value)) (count (namespace value)))))
    (instance? BigInteger value) (integer-charge value)
    (instance? BigInt value) (+ 96 (integer-charge (.toBigInteger ^BigInt value)))
    (instance? BigDecimal value) (+ 96 (integer-charge (.unscaledValue ^BigDecimal value)))
    (instance? Ratio value) (+ 96 (integer-charge (.-numerator ^Ratio value))
                               (integer-charge (.-denominator ^Ratio value)))
    :else 96))


(defn charge
  "Return a deliberately conservative retained-data estimate, or nil. Walks
   every occurrence (including shared references), every known metadata witness,
   and all container members. No identity shortcut can hide mutable leaves.
   Approved callbacks are static code references, never captured user closures."
  [value {:keys [max-bytes max-nodes callbacks]}]
  (let [charged-bytes (volatile! 0) nodes (volatile! 0)]
    (letfn [(add!
              [n]
              (and (<= (vswap! nodes inc) max-nodes)
                   (<= (vswap! charged-bytes +' n) max-bytes)))
            (walk
              [v depth]
              (when (and (<= depth 64) (add! (if (contains? containers (class v)) 256 96))
                         ;; Unknown IMeta implementations can execute code.
                         ;; Refuse their class before asking for metadata.
                         (or (contains? containers (class v))
                             (contains? scalar-classes (class v))
                             (approved-callback? callbacks v)))
                (and
                  (if (meta v)
                    (when-let [retained (provenance/retained-checker-metadata v)]
                      (walk retained (inc depth)))
                    true)
                  (if-let [kind (get containers (class v))]
                    (case kind
                      :map (every? (fn [[k x]] (and (walk k (inc depth)) (walk x (inc depth)))) v)
                      ;; List tails retain their own metadata. Subvectors are
                      ;; refused entirely: their hidden backing vector can hold
                      ;; mutable/unbounded values outside the visible slice.
                      :list (and (walk (first v) (inc depth)) (walk (rest v) (inc depth)))
                      (every? #(walk % (inc depth)) v))
                    (cond
                      (contains? scalar-classes (class v)) (add! (scalar-charge v))
                      (approved-callback? callbacks v) (add! 256)
                      :else false)))))]
      (when (walk value 0) @charged-bytes))))


(defn same-input?
  "Only for separately admitted immutable inputs. Preserve concrete numeric
   representation, container kind/iteration order and provenance identity."
  [left right]
  (and (= (class left) (class right))
       (identical? (meta left) (meta right))
       (if-let [kind (get containers (class left))]
         (and (= (count left) (count right))
              (case kind
                :map (every? true? (map (fn [[ak av] [bk bv]]
                                          (and (same-input? ak bk) (same-input? av bv))) left right))
                :list (and (same-input? (first left) (first right))
                           (same-input? (rest left) (rest right)))
                (every? true? (map same-input? left right))))
         (cond
           (fn? left) (identical? left right)
           (instance? Ratio left) (and (= (.-numerator ^Ratio left) (.-numerator ^Ratio right))
                                       (= (.-denominator ^Ratio left) (.-denominator ^Ratio right)))
           (nil? left) true
           :else (.equals ^Object left right)))))
