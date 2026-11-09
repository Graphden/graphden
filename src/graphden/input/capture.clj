(ns graphden.input.capture
  "Nonforcing admission to optional diagnostic value capture. A serializer
   must not be the first consumer of a deferred graph value."
  (:import
    (clojure.lang
      PersistentArrayMap
      PersistentHashMap
      PersistentHashSet
      PersistentList
      PersistentTreeMap
      PersistentTreeSet
      PersistentVector)))


(def ^:private eager-collection-classes
  (set [PersistentArrayMap PersistentHashMap PersistentTreeMap
        PersistentHashSet PersistentTreeSet PersistentList PersistentVector
        (class '()) (class (subvec [0 1] 1))]))


(defn safe-value?
  "Admit plain eager data under a shape budget, without dereferencing or
   traversing arbitrary collection implementations. Lazy record views, lazy
   sequences, cells and unknown objects fail closed. The existing serializer
   still enforces its byte cap after admission."
  [value]
  (let [remaining (volatile! 10000)]
    (letfn [(visit
              [value depth]
              (and (<= depth 64) (not (neg? (vswap! remaining dec)))
                   (cond
                     (or (nil? value) (boolean? value) (number? value)
                         (string? value) (keyword? value) (symbol? value)
                         (char? value) (uuid? value) (inst? value) (bytes? value)) true
                     (contains? eager-collection-classes (class value))
                     (if (map? value)
                       (every? (fn [[key child]]
                                 (and (visit key (inc depth)) (visit child (inc depth)))) value)
                       (every? #(visit % (inc depth)) value))
                     :else false)))]
      (boolean (visit value 0)))))
