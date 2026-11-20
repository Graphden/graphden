(ns graphden.input.memo
  "Nonforcing input keys for the checked fixture IR. These keys describe
   computation provenance relative to one memoized target, never result values."
  (:require
    [graphden.input.capture :as capture]
    [graphden.input.ir :as ir]))


(defn- inside?
  [[root prefix] [owner path]]
  (and (= root owner) (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))


(defn scope-keys
  "For target S, local computations use authored binding identity plus their
   inputs. Computed captures OUTSIDE S use their invocation-local source cell.
   Thus repeated named S uses share local definitions, while a new enclosing
   invocation cannot reuse a stale captured result from a shared request memo.
   Known eager literals/parameters retain value equality through projections."
  [{:keys [ir supplied present cells]}]
  (let [result (volatile! {})]
    (letfn [(arguments
              [anchor scope]
              (into {} (map (fn [[name path]] [name (token anchor [scope path])]))
                    (get-in ir [:scopes scope :argument-paths])))
            (token
              [anchor address]
              (let [memo-address [anchor address]]
                (if-let [entry (find @result memo-address)]
                  (val entry)
                  (let [{:keys [op source value fields scope target-id binding-id type]}
                        (get-in ir [:expressions address])
                        known (if (= :supplied op) (get supplied address) value)
                        value (cond
                                (not (get present address)) [:absent]
                                (= :read op) (let [source-key (token anchor source)]
                                               (if (= :value (first source-key))
                                                 [:value type (last source-key)]
                                                 [:read type source-key]))
                                (= :record op)
                                [:record (into {} (map (fn [[name child]] [name (token anchor child)])) fields)]
                                (= :invoke op)
                                (if (inside? anchor (first address))
                                  [:computation binding-id target-id type (arguments anchor scope)]
                                  [:cell type (get cells address)])
                                (capture/safe-value? known) [:value type known]
                                :else (ir/reject! :opaque-input-key-required {:address address}))]
                    (vswap! result assoc memo-address value)
                    value))))]
      (into {} (map (fn [scope] [scope (arguments scope scope)])) (keys (:scopes ir))))))
