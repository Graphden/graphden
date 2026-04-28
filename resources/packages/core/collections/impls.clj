(ns graphden.packages.core.collections.impls
  "Implementations for core/collections base functions."
  (:require
    [clojure.math :as math]
    [clojure.walk]
    [graphden.executor.defbase :refer [defbase]]
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

(defbase first-fn [coll]
  (first coll))


(defbase rest-fn [coll]
  (rest coll))


(defbase cons-fn [item coll]
  (cons item coll))


(defbase conj-any-fn [coll item]
  (conj coll item))


(defbase get-fn [coll key default]
  (get coll key default))


(defbase get-in-fn [map path default]
  (get-in map path default))


(defbase assoc-any-fn [map key value]
  (assoc (or map {}) key value))


(defbase dissoc-fn [map key]
  (dissoc map key))


(defbase count-fn [coll]
  (count coll))


(defbase empty?-fn [coll]
  (empty? coll))


(defbase contains?-fn [coll key]
  (contains? coll key))


(defbase keys-fn [map]
  (keys map))


(defbase vals-fn [map]
  (vals map))


(defbase merge-fn [maps]
  (apply merge maps))


(defbase into-fn [to from]
  (into to from))


(defbase assoc-in-fn [m path v]
  (assoc-in m path v))


(defbase update-in-fn [m path f]
  (update-in m path f))


(defbase range-fn [start end step]
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


(defbase repeat-fn [count item]
  (let [max-size sp/*max-repeat-size*]
    (validate-non-negative-count! count :count "repeat count cannot be negative")
    (validate-collection-size! count max-size :execution-error/repeat-too-large {:count count}
                               (str "repeat count " count " exceeds max allowed " max-size))
    (vec (repeat count item))))


(defbase take-fn [count coll]
  (vec (take count coll)))


(defbase drop-fn [count coll]
  (vec (drop count coll)))


(defbase reverse-fn [coll]
  (vec (reverse coll)))


(defbase sort-fn [coll]
  (vec (sort coll)))


(defbase concat-fn [colls]
  (into [] cat colls))


(defbase flatten-fn [coll]
  (vec (flatten coll)))


(defbase distinct-fn [coll]
  (vec (distinct coll)))


(defbase stringify-map-keys-fn
  "Converts all map keys to strings (keyword keys become their name)."
  [m]
  (when m
    (into {}
          (map (fn [[k v]]
                 [(if (keyword? k) (name k) (str k)) v])
               m))))


(defbase keywordize-map-keys-fn
  "Recursively converts all string map keys to keywords."
  [m]
  (clojure.walk/postwalk
    (fn [x]
      (if (map? x)
        (into {}
              (map (fn [[k v]]
                     [(if (string? k) (keyword k) k) v])
                   x))
        x))
    m))


(defbase select-keys-fn [m ks]
  (select-keys m ks))


(defbase zipmap-fn [keys vals]
  (zipmap keys vals))


(defbase update-vals-fn [m f]
  (update-vals m f))


;; === Sequence primitives ===
;; Executor resolves the linked-list chain into a Clojure vector before
;; calling these impls.

(defbase list-fn [items]
  (vec items))


(defbase pairs->map-fn [entries]
  (into {} entries))


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
   :assoc-in assoc-in-fn
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
   :select-keys select-keys-fn
   :zipmap zipmap-fn
   :update-vals update-vals-fn
   :update-in update-in-fn
   :list list-fn
   :pairs->map pairs->map-fn})
