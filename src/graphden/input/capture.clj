(ns graphden.input.capture
  "Nonforcing admission to optional diagnostic value capture. A serializer
   must not be the first consumer of a deferred graph value."
  (:import
    (clojure.lang
      ArrayChunk
      ChunkedCons
      Cons
      LazySeq
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


(def ^:private sequence-classes #{LazySeq Cons ChunkedCons})
(def ^:private empty-list-class (class '()))


(defn- realized-sequence?
  [value admit-node! admit-element?]
  (loop [cursor value]
    (when (admit-node!)
      (let [kind (class cursor)]
        (cond
          (or (nil? cursor) (= empty-list-class kind)) true
          ;; Realization of a LazySeq head says nothing about its tail.
          (= LazySeq kind) (when (realized? cursor) (recur (seq cursor)))
          (or (= Cons kind) (= PersistentList kind))
          (when (admit-element? (first cursor))
            ;; rest exposes the tail for these classes. next would realize it.
            (recur (rest cursor)))
          (= ChunkedCons kind)
          (let [chunk (chunk-first cursor)]
            ;; ChunkedCons can contain a caller-provided IChunk implementation.
            (when (and (= ArrayChunk (class chunk))
                       (every? #(admit-element? (nth chunk %)) (range (count chunk))))
              (recur (chunk-rest cursor))))
          :else false)))))


(defn safe-value?
  "Admit plain data and fully realized Clojure sequences under a shape budget.
   Inspect only known collection classes. Deferred values and unknown objects
   fail closed. The existing serializer still enforces its byte cap."
  [value]
  (let [remaining (volatile! 10000)
        admit-node! #(not (neg? (vswap! remaining dec)))]
    (letfn [(visit
              [value depth]
              (and (<= depth 64) (admit-node!)
                   (cond
                     (or (nil? value) (boolean? value) (number? value)
                         (string? value) (keyword? value) (symbol? value)
                         (char? value) (uuid? value) (inst? value) (bytes? value)) true
                     (contains? sequence-classes (class value))
                     (realized-sequence? value admit-node! #(visit % (inc depth)))
                     (contains? eager-collection-classes (class value))
                     (if (map? value)
                       (every? (fn [[key child]]
                                 (and (visit key (inc depth)) (visit child (inc depth)))) value)
                       (every? #(visit % (inc depth)) value))
                     :else false)))]
      (boolean (visit value 0)))))
