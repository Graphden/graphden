(ns graphden.types.check.markers
  "Structural preservation of known hide-result facts in authored claims."
  (:require
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(defn- unwrapped
  [t]
  (cond
    (types/marker-type? t) (recur (types/marker-inner t))
    (types/refine-type? t) (recur (types/refine-base t))
    :else t))


(defn- kind
  [t]
  (let [t (unwrapped t)]
    (cond
      (types/fn-type? t) :fn
      (or (types/list-type? t) (types/tuple-type? t)) :sequence
      (or (types/map-type? t) (types/record-type? t)) :map
      :else :scalar)))


(defn- corresponding-arm?
  [actual declared]
  (let [a (unwrapped actual)
        d (unwrapped declared)]
    (or (= :any d) (symbol? d)
        (and (= (kind a) (kind d))
             (or (not= :scalar (kind a))
                 (types/subtype? a d) (types/subtype? d a))))))


(declare preserved?)


(defn- fields-preserved?
  [actual declared]
  (every? (fn [[k v]]
            (or (not (types/contains-hide-result-marker? v))
                (and (contains? declared k) (preserved? v (get declared k))))) actual))


(defn- callable-preserved?
  [actual declared]
  (let [a (types/fn-args actual)
        d (types/fn-args declared)]
    (and (preserved? (types/fn-ret actual) (types/fn-ret declared))
         (if (and (= 1 (count a)) (= 1 (count d)))
           (preserved? (val (first a)) (val (first d)))
           (fields-preserved? a d)))))


(defn- sequence-preserved?
  [actual declared]
  (let [a (if (types/tuple-type? actual) (types/tuple-elems actual) [(types/list-elem actual)])
        d (if (types/tuple-type? declared) (types/tuple-elems declared) [(types/list-elem declared)])]
    (cond
      (types/list-type? declared) (every? #(preserved? % (first d)) a)
      (types/list-type? actual) (and (seq d) (every? #(preserved? (first a) %) d))
      :else (every? (fn [[i t]]
                      (or (not (types/contains-hide-result-marker? t))
                          (and (< i (count d)) (preserved? t (nth d i)))))
                    (map-indexed vector a)))))


(defn- mapping-preserved?
  [actual declared]
  (cond
    (and (types/record-type? actual) (types/record-type? declared))
    (fields-preserved? actual declared)

    (and (types/map-type? actual) (types/map-type? declared))
    (and (preserved? (types/map-key actual) (types/map-key declared))
         (preserved? (types/map-val actual) (types/map-val declared)))

    (types/record-type? actual)
    (every? #(preserved? % (types/map-val declared)) (vals actual))

    :else
    (and (not (types/contains-hide-result-marker? (types/map-key actual)))
         (seq declared)
         (every? #(preserved? (types/map-val actual) %) (vals declared)))))


(defn preserved?
  "True when a claim retains every known hide-result marker at its value
   path. Execution variables never authorize moving a marker to a sibling.
   Ambiguous union alternatives must all preserve the corresponding facts."
  [actual declared]
  (let [actual (types/resolve-alias actual)
        declared (types/resolve-alias declared)]
    (boolean
      (cond
        (or (= actual declared) (not (types/contains-hide-result-marker? actual))) true
        (types/refine-type? actual) (preserved? (types/refine-base actual) declared)
        (types/refine-type? declared) (preserved? actual (types/refine-base declared))
        (types/union-type? actual) (every? #(preserved? % declared) (types/union-members actual))
        (types/union-type? declared)
        (let [arms (filter #(corresponding-arm? actual %) (types/union-members declared))]
          (and (seq arms) (every? #(preserved? actual %) arms)))

        (shapes/hide-result-marker-type? actual)
        (and (types/marker-type? declared)
             (if (= (types/marker-tag actual) (types/marker-tag declared))
               (preserved? (types/marker-inner actual) (types/marker-inner declared))
               (preserved? actual (types/marker-inner declared))))

        (types/marker-type? actual) (preserved? (types/marker-inner actual) declared)
        (types/marker-type? declared) (preserved? actual (types/marker-inner declared))
        (and (types/fn-type? actual) (types/fn-type? declared)) (callable-preserved? actual declared)
        (and (= :sequence (kind actual)) (= :sequence (kind declared)))
        (sequence-preserved? actual declared)
        (and (= :map (kind actual)) (= :map (kind declared)))
        (mapping-preserved? actual declared)
        :else false))))
