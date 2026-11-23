(ns graphden.types.get-in
  "Return inference for a literal get-in path over already resolved type facts."
  (:require
    [graphden.types.core :as types]))


(defn- walk
  [type path default-type fallback]
  (cond
    (empty? path) type
    (= :null type) default-type
    (types/union-type? type)
    (types/make-union (mapv #(walk % path default-type fallback) (types/union-members type)))
    (types/record-type? type)
    (if (contains? type (first path))
      (walk (get type (first path)) (rest path) default-type fallback)
      (types/make-union [fallback default-type]))
    (types/map-type? type)
    (types/make-union [default-type (walk (types/map-val type) (rest path) default-type fallback)])
    :else fallback))


(defn return-type
  "Use exact literal keys. A dictionary lookup can miss at every step; its
   result includes the supplied default type. A present nil leaf stays nil.
   An omitted default fact follows the primitive's two-argument nil default;
   a present fact without a known type remains conservative. No registry reads."
  [bindings-info fallback]
  (let [type (get-in bindings-info [:map :type])
        path (get-in bindings-info [:path :value])
        default-type (if (contains? bindings-info :default)
                       (or (get-in bindings-info [:default :type]) fallback)
                       :null)]
    (if (and type (sequential? path) (every? #(or (keyword? %) (string? %)) path))
      (walk type path default-type fallback)
      fallback)))
