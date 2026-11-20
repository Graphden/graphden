(ns graphden.input.source-scope-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.source :as source]
    [graphden.input.source-scope :as scope]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.check :as check]))


(defn- demo-id
  [name]
  (ids/fn-id "demo" name))


(defn- fixture
  ([remote-value duplicate?] (fixture remote-value duplicate? :text))
  ([remote-value duplicate? result]
   (let [owner (demo-id :host)
         producer (demo-id :producer)
         sources [{:name :returned :type {:deep :text}}
                  {:name :sink :args {:left result :right result} :return-type :text}
                  {:name :reader :args {:deep :text} :return-type result}
                  {:name :producer :args {} :return-type :text}
                  {:name :fixed-reader :parent :reader :args {:deep "already bound"}}
                  {:name :host :parent :sink :args (cond-> {:left :reader :deep remote-value}
                                                     duplicate? (assoc :right (if (= :occupied duplicate?) :fixed-reader :reader)))}]
         sources (mapv #(assoc % :namespace "demo") sources)
         [entry] (:definitions (source/plan-module [(peek sources)] (parse/prepare-module sources {} {})))
         baseline (update-in sources [(dec (count sources)) :args] dissoc :deep)
         prepared (parse/prepare-module baseline {} {})
         validated (source/verify-module (source/plan-module baseline prepared) prepared)
         _ (assert (= {:supported 6 :blocked 0} (:counts validated)))
         records (->> (:definitions validated) (map :v2) (mapcat #(concat (:declaration-records %) (:bindings %))) distinct)
         {fns :fn fields :slot members :fn-slot bindings :input-binding} (group-by :kind records)
         snapshot (context/build-context
                    {:fns (into {} (map (juxt :id identity)) fns)
                     :fields (into {} (map (juxt :id identity)) fields)
                     :aliases {:returned {:deep :text}} :alias-ids {:returned (demo-id :returned)}
                     :type-bodies (into {} (map (fn [{:keys [v2]}] [(:input-type-id v2) (get-in v2 [:contract :type])])) (:definitions validated))
                     :members (merge (into {} (map #(vector (get-in % [:v2 :input-type-id]) [])) (:definitions validated))
                                     (group-by :fn-id members))
                     :bindings (into {} (map (juxt :id identity)) bindings)
                     :signatures {(demo-id :reader) {:return (if (= result :returned) {:deep :text} result) :args {:deep :text} :effects #{}}
                                  (demo-id :fixed-reader) {:return :text :args {} :effects #{}}
                                  producer {:return :text :args {} :effects #{}}}})]
     {:owner owner :producer producer :entry entry :snapshot snapshot
      :edge (ids/binding-id owner (ids/slot-id (demo-id :sink) :left))
      :slot (ids/slot-id (demo-id :reader) :deep)})))


(defn- analyze
  [snapshot owner]
  (analysis/analyze-effective-input snapshot owner {:check-expression check/check-input-binding}))


(defn- code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (or (:code (ex-data e)) (:type (ex-data e))))))


(deftest source-records-survive-the-stage-four-occurrence
  (doseq [value ["yes" :producer]]
    (let [{:keys [entry owner slot edge snapshot producer]} (fixture value false)
          certificate (scope/verify-binding entry [:args :deep] snapshot analyze)
          row (:row certificate)]
      (is (= :blocked (:status entry)))
      (is (= [:left :deep] (:public-path certificate)))
      (is (= [[owner [edge]] [slot]] (:address certificate)))
      (is (= (ids/binding-id owner slot) (:id row)))
      (is (= owner (:fn-id row)))
      (is (= [edge] (:scope-path row)))
      (is (= [slot] (:target-path row)))
      (if (keyword? value)
        (is (= producer (:ref-fn-id row)))
        (is (= value (:value row))))
      (is (not (contains? (:bindings snapshot) (:id row)))))))


(deftest result-fields-and-constructor-inputs-keep-distinct-addresses
  (let [{:keys [entry owner snapshot edge slot]} (fixture "data" false :returned)
        graph (analyze snapshot owner)
        old (some #(when (= [:args :deep] (:source-path %)) %) (get-in entry [:legacy :arguments]))]
    (is (= [:returned :deep] ((juxt :owner :field) (:target old))))
    (is (= (ids/slot-id (demo-id :returned) :deep) (get-in old [:origin :slot-id])))
    (is (= [[owner [edge]] [slot]] (get-in graph [:public-input :routes [:left :deep] :address])))
    (is (= :missing-reference-input (code #(scope/verify-binding entry [:args :deep] snapshot analyze))))))


(deftest a-single-free-route-cannot-hide-a-second-occupied-occurrence
  (let [{:keys [entry owner snapshot]} (fixture "text" :occupied)
        graph (analyze snapshot owner)
        routes (get-in graph [:public-input :routes])]
    (is (contains? routes [:left :deep]))
    (is (not (contains? routes [:right :deep])))
    (is (= :nonunique-reference-input (code #(scope/verify-binding entry [:args :deep] snapshot analyze))))))


(deftest ambiguous-occupied-stale-and-incompatible-origins-are-refused
  (let [{:keys [entry snapshot]} (fixture "text" true)]
    (is (= :nonunique-reference-input (code #(scope/verify-binding entry [:args :deep] snapshot analyze)))))
  (let [{:keys [entry snapshot owner edge slot]} (fixture "text" false)
        verify (fn [s analyzer] (scope/verify-binding entry [:args :deep] s analyzer))
        stale (fn [s id] (assoc-in (analyze s id) [:public-input :snapshot-id] :different))
        extra-binding (fn [s id]
                        (let [graph (analyze s id)]
                          (if (= (:snapshot-id s) (:snapshot-id snapshot))
                            graph
                            (assoc-in graph [:public-input :bindings [[owner []] [slot]]]
                                      {:binding-id (random-uuid) :expression {:kind :value :value "extra"}}))))
        ignored-overlay (fn [s id] (assoc-in (analyze snapshot id) [:public-input :snapshot-id] (:snapshot-id s)))
        incomplete (fn [s id] (assoc (analyze s id) :status :incomplete))
        occupied-id (ids/binding-id (demo-id :reader) slot)
        occupied {:id occupied-id :fn-id (demo-id :reader) :scope-path [] :target-path [slot]
                  :expression-kind "value" :value-present true :value "already bound"}]
    (is (= :stale-input-surface (code #(verify snapshot stale))))
    (doseq [replay [ignored-overlay extra-binding]]
      (is (= :scope-replay-mismatch (code #(verify snapshot replay)))))
    (is (= :incomplete-reference-analysis (code #(verify snapshot incomplete))))
    (is (= :source-snapshot-mismatch
           (code #(verify (assoc-in snapshot [:bindings edge :description] "different policy") analyze))))
    (is (= :source-snapshot-mismatch (code #(verify (update snapshot :bindings dissoc edge) analyze))))
    (is (= :source-snapshot-mismatch
           (code #(verify (assoc-in snapshot [:fns owner :parent-ids] []) analyze))))
    (is (= :unavailable-reference-input
           (code #(verify (assoc-in snapshot [:bindings occupied-id] occupied) analyze)))))
  (let [{:keys [entry snapshot]} (fixture 4 false)]
    (is (= :types/check-failed (code #(scope/verify-binding entry [:args :deep] snapshot analyze))))))
