(ns graphden.input.context
  "Immutable inputs to offline structured-input analysis. No storage reads or
   registry publication occur after construction. Names resolve at the caller."
  (:require
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(defn reject!
  [code facts]
  (throw (ex-info "Structured input analysis could not resolve its contract"
                  (assoc facts :type :input/unresolved-contract :code code))))


(defn address-sort-key
  "Deterministic address ordering independent of ambient printer limits."
  [[scope path]]
  [(str (first scope)) (mapv str (second scope)) (mapv str path)])


(defn build-context
  "Accept branch-resolved maps (:fns/:fields/:members/:bindings), ID-keyed
   :type-bodies/:signatures and an already namespace-resolved :aliases map.
   Missing aliases never fall back to the process registry. Marker policy is
   captured once unless explicitly supplied. :snapshot-id identifies this
   graph/type-policy snapshot for a caller-owned cache, not only the fn row.
   Stored absent/nil required is false. Native source adapters either emit an
   explicit boolean on every field or set :field-default-required? true;
   an explicitly stored nil remains false in either context."
  [snapshot]
  (let [context (merge {:fns {} :fields {} :members {} :bindings {}
                        :type-bodies {} :signatures {} :aliases {} :alias-ids {}
                        :markers (shapes/markers-snapshot)
                        :field-default-required? false} snapshot)]
    (doseq [key [:fns :fields :members :bindings :type-bodies :signatures :aliases :alias-ids :markers]]
      (when-not (map? (get context key))
        (reject! :snapshot-map-required {:component key})))
    (when-not (boolean? (:field-default-required? context))
      (reject! :required-policy-required {}))
    context))


(defn call-with-types
  "Scope existing type primitives to this frozen snapshot. Fresh isolated
   atoms satisfy their registry API without permitting writes into the caller."
  [{:keys [aliases markers]} f]
  (binding [types/*type-aliases-override* (atom aliases)
            types/*alias-view* aliases
            shapes/*marker-registry-override* (atom markers)]
    (f)))
