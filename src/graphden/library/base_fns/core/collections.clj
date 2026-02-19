(ns graphden.library.base-fns.core.collections
  "Collection manipulation base functions.

   Functions: first, rest, cons, conj, get, assoc, dissoc, count, empty?,
              contains?, keys, vals, merge, into, range, repeat, take, drop,
              reverse, sort, concat, flatten, distinct"
  (:require
    [clojure.math :as math]
    [graphden.executor.registry.macros :refer [defbase]]
    [graphden.library.base-fns.core.validation :as v]
    [graphden.storage.protocol.interface :as sp]))


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


(defbase conj-any-fn
  "Conj that can work with non-JSON-serializable values like functions.
   Use when building structures that contain function objects."
  {:args {:coll :any, :x :any}
   :return-type :any}
  (conj coll x))


(defbase get-fn
  {:args {:coll :jsonb, :k :any, :default :any}
   :return-type :any}
  (get coll k default))


(defbase get-in-fn
  "Gets value at path in nested structure.

   Arguments:
   - m: Map or nested structure
   - path: Vector of keys
   - default: Default value if not found (optional)

   Example: (get-in-fn {:a {:b 1}} [:a :b]) => 1"
  {:args {:m :jsonb
          :path :jsonb
          :default {:type :any :required false}}
   :return-type :any}
  (get-in m path default))


(defbase assoc-fn
  {:args {:m :jsonb, :k :any, :v :any}
   :return-type :jsonb}
  (assoc m k v))


(defbase assoc-any-fn
  "Assoc that can work with non-JSON-serializable values like functions.
   Use when building structures that contain function objects."
  {:args {:m :any, :k :any, :v :any}
   :return-type :any}
  (assoc (or m {}) k v))


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


(defbase range-fn
  {:args {:start {:type :int :required false}, :end :int, :step {:type :int :required false}}
   :return-type :jsonb}
  (let [actual-step (or step 1)
        actual-start (or start 0)
        max-size sp/*max-range-size*]
    (v/validate-non-zero! actual-step :step "step cannot be zero (would cause infinite loop)")
    ;; Calculate range size to check against limit
    (let [range-size (if (or (and (pos? actual-step) (< actual-start end))
                             (and (neg? actual-step) (> actual-start end)))
                       (long (math/ceil (/ (abs (double (- end actual-start)))
                                           (abs (double actual-step)))))
                       0)]
      (v/validate-collection-size! range-size max-size
                                   :execution-error/range-too-large
                                   {:start actual-start :end end :step actual-step}
                                   (str "range would produce " range-size " elements, max allowed " max-size))
      (vec (range actual-start end actual-step)))))


(defbase repeat-fn
  {:args {:n :int, :x :any}
   :return-type :jsonb}
  (let [max-size sp/*max-repeat-size*]
    (v/validate-non-negative-count! n :n "repeat count cannot be negative")
    (v/validate-collection-size! n max-size :execution-error/repeat-too-large {:n n}
                                 (str "repeat count " n " exceeds max allowed " max-size))
    (vec (repeat n x))))


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


(defbase pair-fn
  "Creates a 2-element vector (tuple) from two values.
   Works with non-JSON-serializable values like functions.

   Example: (pair \"/health\" handler) => [\"/health\" handler]"
  {:args {:a :any, :b :any}
   :return-type :any}
  [a b])


(defbase triple-fn
  "Creates a 3-element vector from three values.
   Works with non-JSON-serializable values like functions."
  {:args {:a :any, :b :any, :c :any}
   :return-type :any}
  [a b c])


;; === Exports ===

(def collection-defs
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
