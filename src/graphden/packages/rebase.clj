(ns graphden.packages.rebase
  "Move an ordinary fn bundle while preserving references within the bundle.
   Only reference positions are rewritten; literals and external dependencies
   keep their identities. Parsing and authorization remain the importer's job."
  (:require
    [graphden.packages.records.types :as types]))


(defn- update-present
  [m k f]
  (if (contains? m k) (update m k f) m))


(declare map-type-refs map-definition-refs)


(defn- map-variant-refs
  "Variant tags are values; only each tag's following type is a reference."
  [ref-fn members]
  (mapv (fn [index member]
          (if (odd? index) (map-type-refs ref-fn member) member))
        (range) members))


(defn- map-type-refs
  [ref-fn type]
  (cond
    (keyword? type) (ref-fn type)
    (types/type-spec-map? type) (update type :type #(map-type-refs ref-fn %))
    (map? type) (update-vals type #(map-type-refs ref-fn %))
    (vector? type)
    (case (first type)
      :refine (update type 1 #(map-type-refs ref-fn %))
      :fn (-> type
              (update 1 #(map-type-refs ref-fn %))
              (update 2 #(map-type-refs ref-fn %)))
      :variant (into [:variant] (map-variant-refs ref-fn (rest type)))
      ;; Lists, tuples, maps, unions and information-flow markers contain
      ;; types. A marker name can itself be a named type in this bundle.
      (mapv #(map-type-refs ref-fn %) type))
    :else type))


(defn- map-argument-refs
  [ref-fn value]
  (cond
    (keyword? value) (ref-fn value)
    (vector? value) (mapv #(map-argument-refs ref-fn %) value)
    (map? value)
    (-> (map-definition-refs ref-fn value)
        (update-present :ref ref-fn)
        (update-present :resolver ref-fn)
        (update-present :append #(mapv (partial map-argument-refs ref-fn) %)))
    :else value))


(defn- map-definition-refs
  "Follow the fn-def grammar, never a general postwalk: :value, :as,
   descriptions, field names, variant tags and refinement constraints are data."
  [ref-fn definition]
  (-> definition
      (update-present :parent ref-fn)
      (update-present :parents #(mapv ref-fn %))
      (update-present :args #(update-vals % (partial map-argument-refs ref-fn)))
      (update-present :return-type #(map-type-refs ref-fn %))
      (update-present :type #(map-type-refs ref-fn %))
      (update-present :list #(map-type-refs ref-fn %))
      (update-present :tuple #(mapv (partial map-type-refs ref-fn) %))
      (update-present :union #(mapv (partial map-type-refs ref-fn) %))
      (update-present :variant #(map-variant-refs ref-fn %))
      (update-present :refine #(update % :base (partial map-type-refs ref-fn)))
      (update-present :map #(-> %
                                (update :key (partial map-type-refs ref-fn))
                                (update :value (partial map-type-refs ref-fn))))
      (update-present :fn-type #(-> %
                                    (update 0 (partial map-type-refs ref-fn))
                                    (update 1 (partial map-type-refs ref-fn))))))


(defn rebase-bundle
  "Map each definition's namespace with `namespace-fn`, and rewrite exact
   qualified references to members of this bundle. Bare references keep their
   normal import resolution. A qualified reference to a nonmember is external,
   even when its namespace shares the moved prefix."
  [definitions namespace-fn]
  (let [remap (into {}
                    (map (fn [{:keys [namespace name]}]
                           [(keyword (or namespace "") (clojure.core/name name))
                            (keyword (or (namespace-fn namespace) "") (clojure.core/name name))]))
                    definitions)
        ref-fn #(get remap % %)]
    (mapv #(-> (map-definition-refs ref-fn %)
               (update :namespace namespace-fn))
          definitions)))
