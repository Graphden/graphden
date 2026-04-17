(ns graphden.packages.core.collections.impls
  "Implementations for core/collections base functions.

   All functions receive already-dereferenced arguments.
   The loader handles deref before calling these implementations."
  (:require
    [clojure.math :as math]
    [clojure.walk]
    [graphden.storage.protocol.core :as sp]))


;; === Validation Helpers ===

(defn- validate-non-zero!
  [value field-name message]
  (when (zero? value)
    (throw (ex-info message
                    {:type :execution-error/invalid-args
                     field-name value}))))


(defn- validate-non-negative-count!
  [n field-name message]
  (when (neg? n)
    (throw (ex-info message
                    {:type :execution-error/invalid-args
                     field-name n}))))


(defn- validate-collection-size!
  [size max-size error-type context message]
  (when (> size max-size)
    (throw (ex-info message
                    (merge {:type error-type
                            :size size
                            :max-size max-size}
                           context)))))


;; === Implementations ===

(defn first-fn
  [{:keys [coll]}]
  (first coll))


(defn rest-fn
  [{:keys [coll]}]
  (rest coll))


(defn cons-fn
  [{:keys [item coll]}]
  (cons item coll))


(defn conj-any-fn
  [{:keys [coll item]}]
  (conj coll item))


(defn get-fn
  [{:keys [coll key default]}]
  (get coll key default))


(defn get-in-fn
  [{:keys [map path default]}]
  (get-in map path default))


(defn assoc-any-fn
  [{:keys [map key value]}]
  (assoc (or map {}) key value))


(defn dissoc-fn
  [{:keys [map key]}]
  (dissoc map key))


(defn count-fn
  [{:keys [coll]}]
  (count coll))


(defn empty?-fn
  [{:keys [coll]}]
  (empty? coll))


(defn contains?-fn
  [{:keys [coll key]}]
  (contains? coll key))


(defn keys-fn
  [{:keys [map]}]
  (keys map))


(defn vals-fn
  [{:keys [map]}]
  (vals map))


(defn merge-fn
  [{:keys [maps]}]
  (apply merge maps))


(defn into-fn
  [{:keys [to from]}]
  (into to from))


(defn range-fn
  [{:keys [start end step]}]
  (let [max-size sp/*max-range-size*]
    (validate-non-zero! step :step "step cannot be zero (would cause infinite loop)")
    (let [range-size (if (or (and (pos? step) (< start end))
                             (and (neg? step) (> start end)))
                       (long (math/ceil (/ (abs (double (- end start)))
                                           (abs (double step)))))
                       0)]
      (validate-collection-size! range-size max-size
                                 :execution-error/range-too-large
                                 {:start start :end end :step step}
                                 (str "range would produce " range-size " elements, max allowed " max-size))
      (vec (range start end step)))))


(defn repeat-fn
  [{:keys [count item]}]
  (let [max-size sp/*max-repeat-size*]
    (validate-non-negative-count! count :count "repeat count cannot be negative")
    (validate-collection-size! count max-size :execution-error/repeat-too-large {:count count}
                               (str "repeat count " count " exceeds max allowed " max-size))
    (vec (repeat count item))))


(defn take-fn
  [{:keys [count coll]}]
  (vec (take count coll)))


(defn drop-fn
  [{:keys [count coll]}]
  (vec (drop count coll)))


(defn reverse-fn
  [{:keys [coll]}]
  (vec (reverse coll)))


(defn sort-fn
  [{:keys [coll]}]
  (vec (sort coll)))


(defn concat-fn
  [{:keys [colls]}]
  (into [] cat colls))


(defn flatten-fn
  [{:keys [coll]}]
  (vec (flatten coll)))


(defn distinct-fn
  [{:keys [coll]}]
  (vec (distinct coll)))


(defn stringify-map-keys-fn
  "Converts all map keys to strings (keyword keys become their name)."
  [{:keys [m]}]
  (when m
    (into {}
          (map (fn [[k v]]
                 [(if (keyword? k) (name k) (str k)) v])
               m))))


(defn keywordize-map-keys-fn
  "Recursively converts all string map keys to keywords."
  [{:keys [m]}]
  (clojure.walk/postwalk
    (fn [x]
      (if (map? x)
        (into {}
              (map (fn [[k v]]
                     [(if (string? k) (keyword k) k) v])
                   x))
        x))
    m))


;; === Registry ===

(def impls
  {:first first-fn
   :rest rest-fn
   :cons cons-fn
   :conj conj-any-fn
   :get get-fn
   :get-in get-in-fn
   :assoc assoc-any-fn
   :dissoc dissoc-fn
   :count count-fn
   :empty? empty?-fn
   :contains? contains?-fn
   :keys keys-fn
   :vals vals-fn
   :merge merge-fn
   :into into-fn
   :range range-fn
   :repeat repeat-fn
   :take take-fn
   :drop drop-fn
   :reverse reverse-fn
   :sort sort-fn
   :concat concat-fn
   :flatten flatten-fn
   :distinct distinct-fn
   :stringify-map-keys stringify-map-keys-fn
   :keywordize-map-keys keywordize-map-keys-fn
   :select-keys (fn [{:keys [m ks]}] (select-keys m ks))
   :zipmap (fn [{ks :keys vs :vals}] (zipmap ks vs))
   :update-vals (fn [{:keys [m f]}] (update-vals m f))})
