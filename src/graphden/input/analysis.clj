(ns graphden.input.analysis
  "Offline structural analysis with explicit checker and closure coverage.
   Never evaluates references, reconstructs flat :args, or mutates registries."
  (:require
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.rows :as rows]
    [graphden.input.scopes :as scopes]
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(defn- expression-of
  [row]
  (case (:expression-kind row)
    "value" {:kind :value :value (:value row)}
    "ref" {:kind :ref :fn-id (:ref-fn-id row)}
    "projection" {:kind :projection :scope [(:fn-id row) (:projection-scope-path row)]
                  :path (:projection-path row)}))


(defn- target-node
  [contract path]
  (reduce (fn [node field-id]
            (or (some #(when (= field-id (:id %)) %) (:fields node))
                (context/reject! :unknown-field {:path path}))) contract path))


(defn- classify-entry
  [ctx scope contract row]
  (let [path (:target-path row)
        expected (:type (target-node contract path))
        expression (expression-of row)
        dispatch (case (:kind expression)
                   :ref (if (contains? (:fns ctx) (:fn-id expression))
                          (reference/classify expected (get-in ctx [:signatures (:fn-id expression)]))
                          {:mode :unresolved :reason :missing-ref-target})
                   :value (if (:resolver-fn-id row)
                            {:mode :unresolved :reason :resolver-analysis-required}
                            {:mode :literal :actual-type (literals/classify-literal (:value expression))})
                   :projection {:mode :unresolved :reason :projection-analysis-required})]
    {:address [scope path] :binding-id (:id row) :origin-fn-id (:origin-fn-id row)
     :expression expression
     :expected-type expected :reference dispatch}))


(defn- check-entry
  [check-expression substitution {:keys [address expression expected-type reference]}]
  (cond
    (= :unresolved (:mode reference))
    {:status :deferred :reason (:reason reference) :substitution substitution}
    (= :graph-callable (:mode reference))
    {:status :deferred :reason :capture-analysis-required :substitution substitution}
    (nil? check-expression)
    {:status :deferred :reason :type-checker-required :substitution substitution}
    :else
    (check-expression {:address address :expected expected-type
                       :actual (or (:callable-type reference) (:actual-type reference))
                       :expression expression :substitution substitution})))


(defn- check-entries
  [entries check-expression]
  (reduce (fn [{:keys [substitution] :as result} {:keys [address] :as entry}]
            (let [proof (check-entry check-expression substitution entry)]
              (when-not (contains? #{:checked :deferred} (:status proof))
                (context/reject! :invalid-checker-result {:address address}))
              (-> result
                  (assoc :substitution (:substitution proof))
                  (assoc-in [:proofs address] proof))))
          {:substitution {} :proofs {}} entries))


(defn- container-obligations
  [node scope path]
  (when-not (= :bound (:state node))
    (into (if (and (:fields node) (not (types/record-type? (:type node))))
            [{:address [scope path] :type (:type node) :reason :container-type-obligation}]
            [])
          (mapcat #(container-obligations % scope (conj path (:id %)))) (:fields node))))


(defn- substitute-node
  [node substitution]
  (cond-> (update node :type #(types/resolve substitution %))
    (:fields node) (update :fields #(mapv (fn [field] (substitute-node field substitution)) %))
    (:alternatives node) (update :alternatives #(mapv (partial types/resolve substitution) %))))


(defn analyze-input
  "Analyze a declared input and its local rows. Supply the real checker as
   :check-expression (graphden.types.check/check-input-binding). Its absence
   is explicit deferred evidence. This first slice reports closure coverage
   gaps for scoped overlays and evaluated refs instead of claiming
   recursive availability. Missing required fields and incomplete checking
   are separate outputs. All effective/residual values remain recomputable."
  ([ctx fn-id] (analyze-input ctx fn-id {}))
  ([ctx fn-id {:keys [check-expression scope additional-rows]}]
   (context/call-with-types
     ctx
     (fn []
       (let [{:keys [contract bindings declaration-order]} (inheritance/declared-view ctx fn-id)
             scope (or scope [fn-id []])
             owned (mapv #(rows/normalize (assoc % :fn-id fn-id)) (concat bindings additional-rows))
             local (filterv #(empty? (:scope-path %)) owned)
             entries (mapv #(classify-entry ctx scope contract %) (filter rows/occupied? local))
             state (into {} (map (fn [{:keys [address binding-id expression expected-type]}]
                                   [address {:binding-id binding-id :expression expression
                                             :expected-type expected-type}])) entries)
             ;; Re-insert, rather than map-merging, so duplicate paths are
             ;; rejected even when they carry equal literal values.
             _ (rows/state local)
             remaining (input/remaining-input contract state scope)
             checked (check-entries entries check-expression)
             obligations (container-obligations (:descriptor remaining) scope [])
             closure-gaps (cond-> []
                            (some #(seq (:scope-path %)) owned) (conj :scoped-analysis-required)
                            (some #(some (fn [k] (some? (get % k)))
                                         [:required :terminal :type-override-fn-id]) owned)
                            (conj :metadata-analysis-required)
                            (some #(contains? #{:value :callable-producer :graph-callable}
                                              (get-in % [:reference :mode])) entries)
                            (conj :reference-availability-required))
             diagnostics (into (into (cond-> (mapv #(hash-map :code %) closure-gaps)
                                       (and (nil? check-expression) (empty? entries))
                                       (conj {:code :type-checker-required}))
                                     (map #(hash-map :code (:reason %) :address (:address %))) obligations)
                               (keep (fn [[address proof]]
                                       (when (= :deferred (:status proof))
                                         {:code (:reason proof) :address address})))
                               (:proofs checked))]
         {:fn-id fn-id :declared-contract contract :declaration-order declaration-order
          :scoped-rows (filterv #(seq (:scope-path %)) owned)
          :effective-contract (when (empty? closure-gaps) (substitute-node contract (:substitution checked)))
          :remaining-contract (substitute-node (:descriptor remaining) (:substitution checked))
          :required-addresses (mapv #(vector scope %) (:missing remaining))
          :bindings (into {} (map (fn [entry]
                                    [(:address entry)
                                     (update entry :expected-type #(types/resolve (:substitution checked) %))])) entries)
          :substitution (:substitution checked) :proofs (:proofs checked)
          :obligations obligations
          :diagnostics diagnostics
          :status (if (empty? diagnostics) :checked :incomplete)
          :coverage {:declared-structure true :local-occupancy true
                     :recursive-availability (empty? closure-gaps)
                     :type-checker (boolean check-expression)}})))))


(defn analyze-effective-input
  "Resolve value-producing reference uses into a derived scope graph. Each
   occurrence retains the shared target declaration and binding-ID use path.
   Scoped overlays are checked against target occupancy. Callable capture
   routing and recursive cycles remain explicit incomplete coverage; identity
   references never traverse their target. This is an offline analysis API."
  ([ctx fn-id] (analyze-effective-input ctx fn-id {}))
  ([ctx fn-id options]
   (scopes/analyze
     fn-id
     (fn [target scope overlays]
       (analyze-input ctx target (assoc options :scope scope :additional-rows overlays))))))
