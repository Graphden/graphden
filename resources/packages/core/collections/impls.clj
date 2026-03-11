(ns graphden.packages.core.collections.impls
  "Implementations for core/collections base functions.

   All functions receive already-dereferenced arguments.
   The loader handles deref before calling these implementations."
  (:require
    [clojure.math :as math]
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
  [{:keys [x coll]}]
  (cons x coll))


(defn conj-fn
  [{:keys [coll x]}]
  (conj coll x))


(defn conj-any-fn
  [{:keys [coll x]}]
  (conj coll x))


(defn get-fn
  [{:keys [coll k default]}]
  (get coll k default))


(defn get-in-fn
  [{:keys [m path default]}]
  (get-in m path default))


(defn assoc-fn
  [{:keys [m k v]}]
  (assoc m k v))


(defn assoc-any-fn
  [{:keys [m k v]}]
  (assoc (or m {}) k v))


(defn dissoc-fn
  [{:keys [m k]}]
  (dissoc m k))


(defn count-fn
  [{:keys [coll]}]
  (count coll))


(defn empty?-fn
  [{:keys [coll]}]
  (empty? coll))


(defn contains?-fn
  [{:keys [coll k]}]
  (contains? coll k))


(defn keys-fn
  [{:keys [m]}]
  (keys m))


(defn vals-fn
  [{:keys [m]}]
  (vals m))


(defn merge-fn
  [{:keys [maps]}]
  (apply merge maps))


(defn into-fn
  [{:keys [to from]}]
  (into to from))


(defn range-fn
  [{:keys [start end step]}]
  (let [actual-step (or step 1)
        actual-start (or start 0)
        max-size sp/*max-range-size*]
    (validate-non-zero! actual-step :step "step cannot be zero (would cause infinite loop)")
    (let [range-size (if (or (and (pos? actual-step) (< actual-start end))
                             (and (neg? actual-step) (> actual-start end)))
                       (long (math/ceil (/ (abs (double (- end actual-start)))
                                           (abs (double actual-step)))))
                       0)]
      (validate-collection-size! range-size max-size
                                 :execution-error/range-too-large
                                 {:start actual-start :end end :step actual-step}
                                 (str "range would produce " range-size " elements, max allowed " max-size))
      (vec (range actual-start end actual-step)))))


(defn repeat-fn
  [{:keys [n x]}]
  (let [max-size sp/*max-repeat-size*]
    (validate-non-negative-count! n :n "repeat count cannot be negative")
    (validate-collection-size! n max-size :execution-error/repeat-too-large {:n n}
                               (str "repeat count " n " exceeds max allowed " max-size))
    (vec (repeat n x))))


(defn take-fn
  [{:keys [n coll]}]
  (vec (take n coll)))


(defn drop-fn
  [{:keys [n coll]}]
  (vec (drop n coll)))


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


(defn pair-fn
  [{:keys [a b]}]
  [a b])


(defn triple-fn
  [{:keys [a b c]}]
  [a b c])


;; === Registry ===

(def impls
  {:first first-fn
   :rest rest-fn
   :cons cons-fn
   :conj conj-fn
   :conj-any conj-any-fn
   :get get-fn
   :get-in get-in-fn
   :assoc assoc-fn
   :assoc-any assoc-any-fn
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
   :pair pair-fn
   :triple triple-fn})
