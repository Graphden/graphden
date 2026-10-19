(ns graphden.types.expansion
  "Bound source type expansion before alias/union normalization."
  (:require
    [graphden.types.core :as types]))


(defn- reject!
  [path]
  (throw (ex-info "Type expansion is recursive or exceeds its limit"
                  {:type :types/non-finite-expansion :path path})))


(defn assert-finite!
  "Reject alias cycles before core normalization can unfold recursive unions.
   Bound expansion as well as depth; literal constraint operands are not types."
  [t]
  (let [aliases (types/aliases-snapshot)
        remaining (volatile! 10000)]
    (letfn [(visit
              [t path seen depth]
              (when (or (> depth 64) (neg? (vswap! remaining dec))) (reject! path))
              (if (and (keyword? t) (not (types/primitive? t)) (contains? aliases t))
                (do (when (contains? seen t) (reject! path))
                    (visit (get aliases t) path (conj seen t) (inc depth)))
                (let [children (if (and (vector? t) (= :variant (first t)))
                                 ;; Variant operands are alternating literal
                                 ;; tags and type payloads. Desugaring calls
                                 ;; union normalization, so visit payloads first.
                                 (take-nth 2 (drop 2 t))
                                 (types/child-types t))]
                  (doseq [[i child] (map-indexed vector children)]
                    (visit child (conj path i) seen (inc depth))))))]
      (visit t [] #{} 0))))
