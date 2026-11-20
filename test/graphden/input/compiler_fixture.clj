(ns graphden.input.compiler-fixture
  "Small stored declarations shared by IR and native compiler regressions."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(defn id
  [value]
  (java.util.UUID/nameUUIDFromBytes (String/.getBytes (pr-str value) java.nio.charset.StandardCharsets/UTF_8)))


(defn- field-id
  [owner path origins]
  (id [(get origins path owner) path]))


(defn field-path
  ([owner path] (field-path owner path {}))
  ([owner path origins]
   (mapv #(field-id owner (subvec (vec path) 0 %) origins) (range 1 (inc (count path))))))


(defn address
  [root use-path owner path]
  [[(id root) (mapv id use-path)] (field-path owner path)])


(defn- type-records
  [owner path rich optional origins]
  (if (map? rich)
    (let [type-id (id [owner :type path])
          children (mapv (fn [[name type]]
                           (let [p (conj path name)]
                             {:field {:id (field-id owner p origins) :name (clojure.core/name name)
                                      :required (not (contains? optional p))
                                      :type-fn-id (if (map? type) (id [owner :type p])
                                                      (or (get (ids/primitive-fn-ids) type) (id [:type type])))}
                              :records (type-records owner p type optional origins)})) rich)]
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
   :field-origins maps shared paths to the owning declaration's identity.
   Only test graph construction lives here; production analysis does the work."
  ([definitions root]
   (model definitions root (requiring-resolve 'graphden.types.check/check-input-binding)))
  ([definitions root checker]
   (let [snapshot (reduce-kv
                    (fn [out owner {:keys [input return optional bindings field-origins lambda-params] :as definition}]
                      (let [records (type-records owner [] input optional field-origins)
                            rows (map (fn [[path expression]]
                                        (merge {:id (id (or (:id expression) [owner :binding path]))
                                                :fn-id (id owner) :scope-path [] :target-path (field-path owner path field-origins)}
                                               (if (contains? expression :ref)
                                                 {:expression-kind "ref" :ref-fn-id (id (:ref expression))}
                                                 {:expression-kind "value" :value-present true :value (:value expression)}))) bindings)]
                        (-> out
                            (assoc-in [:fns (id owner)] (cond-> {:id (id owner) :name (name owner)
                                                                 :input-type-fn-id (id [owner :type []])}
                                                          (contains? definition :lambda-params)
                                                          (assoc :lambda-params lambda-params)))
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
                  (let [{:keys [input return field-origins]} (get definitions owner)]
                    [(id owner) {:base-fn-id (id owner) :impl impl
                                 :root-entry {:name owner :args input :return return}
                                 :argument-paths (into {} (map (fn [name] [name (field-path owner [name] field-origins)]))
                                                       (keys input))}]))) functions))
