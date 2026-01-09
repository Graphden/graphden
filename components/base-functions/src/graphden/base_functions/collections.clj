(ns graphden.base-functions.collections
  "Collection manipulation base functions.

   Functions: first, rest, cons, conj, get, assoc, dissoc, count, empty?,
              contains?, keys, vals, merge, into, range, repeat, take, drop,
              reverse, sort, concat, flatten, distinct"
  (:require
    [clojure.math :as math]
    [graphden.fn-registry.macros :refer [defbase]]))


(defbase first-fn
  {:args {:coll :jsonb}
   :return-type :any}
  (first coll))


(defbase rest-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (rest coll))


(defbase cons-fn
  {:args {:x :any, :coll :jsonb}
   :return-type :jsonb}
  (cons x coll))


(defbase conj-fn
  {:args {:coll :jsonb, :x :any}
   :return-type :jsonb}
  (conj coll x))


(defbase get-fn
  {:args {:coll :jsonb, :k :any, :default :any}
   :return-type :any}
  (get coll k default))


(defbase assoc-fn
  {:args {:m :jsonb, :k :any, :v :any}
   :return-type :jsonb}
  (assoc m k v))


(defbase dissoc-fn
  {:args {:m :jsonb, :k :any}
   :return-type :jsonb}
  (dissoc m k))


(defbase count-fn
  {:args {:coll :jsonb}
   :return-type :int}
  (count coll))


(defbase empty?-fn
  {:args {:coll :jsonb}
   :return-type :bool}
  (empty? coll))


(defbase contains?-fn
  {:args {:coll :jsonb, :k :any}
   :return-type :bool}
  (contains? coll k))


(defbase keys-fn
  {:args {:m :jsonb}
   :return-type :jsonb}
  (keys m))


(defbase vals-fn
  {:args {:m :jsonb}
   :return-type :jsonb}
  (vals m))


(defbase merge-fn
  {:args {:maps :jsonb}
   :return-type :jsonb}
  (apply merge maps))


(defbase into-fn
  {:args {:to :jsonb, :from :jsonb}
   :return-type :jsonb}
  (into to from))


(def ^:private max-range-size
  "Maximum number of elements allowed in range to prevent memory exhaustion.
   Default: 1 million elements."
  1000000)


(defbase range-fn
  {:args {:start {:type :int :required false}, :end :int, :step {:type :int :required false}}
   :return-type :jsonb}
  (let [actual-step (or step 1)
        actual-start (or start 0)]
    (when (zero? actual-step)
      (throw (ex-info "step cannot be zero (would cause infinite loop)"
                      {:type :execution-error/invalid-step
                       :start actual-start :end end :step step})))
    ;; Calculate range size to check against limit
    (let [range-size (if (or (and (pos? actual-step) (< actual-start end))
                             (and (neg? actual-step) (> actual-start end)))
                       (long (math/ceil (/ (abs (double (- end actual-start)))
                                           (abs (double actual-step)))))
                       0)]
      (when (> range-size max-range-size)
        (throw (ex-info (str "range would produce " range-size " elements, max allowed is " max-range-size)
                        {:type :execution-error/range-too-large
                         :start actual-start :end end :step actual-step
                         :range-size range-size :max-size max-range-size})))
      (vec (range actual-start end actual-step)))))


(def ^:private max-repeat-size
  "Maximum number of elements allowed in repeat to prevent memory exhaustion.
   Default: 1 million elements."
  1000000)


(defbase repeat-fn
  {:args {:n :int, :x :any}
   :return-type :jsonb}
  (when (neg? n)
    (throw (ex-info "repeat count cannot be negative"
                    {:type :execution-error/invalid-repeat-count
                     :n n})))
  (when (> n max-repeat-size)
    (throw (ex-info (str "repeat count " n " exceeds max allowed " max-repeat-size)
                    {:type :execution-error/repeat-too-large
                     :n n :max-size max-repeat-size})))
  (vec (repeat n x)))


(defbase take-fn
  {:args {:n :int, :coll :jsonb}
   :return-type :jsonb}
  (vec (take n coll)))


(defbase drop-fn
  {:args {:n :int, :coll :jsonb}
   :return-type :jsonb}
  (vec (drop n coll)))


(defbase reverse-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (reverse coll)))


(defbase sort-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (sort coll)))


(defbase concat-fn
  {:args {:colls :jsonb}
   :return-type :jsonb}
  (vec (apply concat colls)))


(defbase flatten-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (flatten coll)))


(defbase distinct-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (distinct coll)))


;; === Exports ===

(def collection-defs
  {:first first-fn
   :rest rest-fn
   :cons cons-fn
   :conj conj-fn
   :get get-fn
   :assoc assoc-fn
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
   :distinct distinct-fn})
