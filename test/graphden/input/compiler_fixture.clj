(ns graphden.input.compiler-fixture
  "Small stored declarations shared by IR and native compiler regressions."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(defn id
  [value]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (pr-str value) "UTF-8")))


(defn field-path
  [owner path]
  (mapv #(id [owner (subvec (vec path) 0 %)]) (range 1 (inc (count path)))))


(defn address
  [root use-path owner path]
  [[(id root) (mapv id use-path)] (field-path owner path)])


(defn- type-records
  [owner path rich optional]
  (if (map? rich)
    (let [type-id (id [owner :type path])
          children (mapv (fn [[name type]]
                           (let [p (conj path name)]
                             {:field {:id (id [owner p]) :name (clojure.core/name name)
                                      :required (not (contains? optional p))
                                      :type-fn-id (if (map? type) (id [owner :type p])
                                                      (or (get (ids/primitive-fn-ids) type) (id [:type type])))}
                              :records (type-records owner p type optional)})) rich)]
      (reduce (fn [out {:keys [field records]}]
                (-> out (update :fields assoc (:id field) field)
                    (update :fields merge (:fields records))
                    (update :members merge (:members records))
                    (update :type-bodies merge (:type-bodies records))))
              {:fields {} :members {type-id (mapv (fn [index child]
                                                    {:slot-id (get-in child [:field :id]) :position index})
                                                  (range) children)}
               :type-bodies {type-id rich}} children))
    {:type-bodies {(or (get (ids/primitive-fn-ids) rich) (id [:type rich])) rich}}))


(defn model
  "Each definition supplies :input, :return and optional :bindings keyed by
   name-path. A binding is {:ref target :id symbolic-use} or {:value value}.
   Only test graph construction lives here; production analysis does the work."
  ([definitions root]
   (model definitions root (requiring-resolve 'graphden.types.check/check-input-binding)))
  ([definitions root checker]
   (let [snapshot (reduce-kv
                    (fn [out owner {:keys [input return optional bindings]}]
                      (let [records (type-records owner [] input optional)
                            rows (map (fn [[path expression]]
                                        (merge {:id (id (or (:id expression) [owner :binding path]))
                                                :fn-id (id owner) :scope-path [] :target-path (field-path owner path)}
                                               (if (contains? expression :ref)
                                                 {:expression-kind "ref" :ref-fn-id (id (:ref expression))}
                                                 {:expression-kind "value" :value-present true :value (:value expression)}))) bindings)]
                        (-> out
                            (assoc-in [:fns (id owner)] {:id (id owner) :name (name owner)
                                                         :input-type-fn-id (id [owner :type []])})
                            (assoc-in [:signatures (id owner)] {:args input :return return :effects #{}})
                            (update :fields merge (:fields records))
                            (update :members merge (:members records))
                            (update :type-bodies merge (:type-bodies records))
                            (update :bindings into (map (juxt :id identity) rows)))))
                    {:bindings {}} definitions)
         ctx (context/build-context snapshot)]
     {:context ctx :analysis (analysis/analyze-effective-input
                               ctx (id root) {:check-expression checker})})))


(defn implementations
  [definitions functions]
  (into {} (map (fn [[owner impl]]
                  [(id owner) {:base-fn-id (id owner) :impl impl
                               :argument-paths (into {} (map (fn [name] [name (field-path owner [name])]))
                                                     (keys (get-in definitions [owner :input])))}])) functions))
