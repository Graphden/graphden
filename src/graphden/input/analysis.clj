(ns graphden.input.analysis
  "Offline structural analysis with explicit checker and closure coverage.
   Never evaluates references, reconstructs flat :args, or mutates registries."
  (:require
    [graphden.input.captures :as captures]
    [graphden.input.checking :as checking]
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.metadata :as metadata]
    [graphden.input.obligations :as obligations]
    [graphden.input.projections :as projections]
    [graphden.input.resolvers :as resolvers]
    [graphden.input.rows :as rows]
    [graphden.input.scopes :as scopes]
    [graphden.input.surface :as surface]
    [graphden.input.variables :as variables]
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(defn- expression-of
  [row scope]
  (case (:expression-kind row)
    "value" (cond-> {:kind :value :value (:value row)}
              (:resolver-fn-id row) (assoc :resolver-fn-id (:resolver-fn-id row)))
    "ref" {:kind :ref :fn-id (:ref-fn-id row)}
    "projection" {:kind :projection :scope [(first scope) (into (or (:origin-use-path row) []) (:projection-scope-path row))]
                  :path (:projection-path row)}))


(defn- classify-entry
  [ctx scope contract row]
  (let [path (:target-path row)
        expected (:type (descriptor/node-at contract path))
        expression (expression-of row scope)
        dispatch (case (:kind expression)
                   :ref (if (contains? (:fns ctx) (:fn-id expression))
                          (reference/classify
                            expected
                            (variables/signature-in-scope
                              [(first scope) (conj (second scope) (:id row))]
                              (get-in ctx [:signatures (:fn-id expression)])))
                          {:mode :unresolved :reason :missing-ref-target})
                   :value (if-let [target (:resolver-fn-id row)]
                            (let [signature (variables/signature-in-scope
                                              [(first scope) (conj (second scope) (:id row))]
                                              (get-in ctx [:signatures target]))]
                              (if (and (contains? (:fns ctx) target) (:return signature))
                                {:mode :resolved-value :actual-type (:return signature)}
                                {:mode :unresolved :reason :missing-resolver-signature}))
                            {:mode :literal :actual-type (literals/classify-literal (:value expression))})
                   :projection {:mode :unresolved :reason :projection-analysis-required})]
    {:address [scope path] :binding-id (:id row) :origin-fn-id (:origin-fn-id row)
     :expression expression
     :expected-type expected :reference dispatch}))


(defn- container-obligations
  [node scope path]
  (when-not (= :bound (:state node))
    (into (if (and (:fields node) (not (types/record-type? (:type node))))
            [{:address [scope path] :type (:type node) :reason :container-type-obligation}]
            [])
          (mapcat #(container-obligations % scope (conj path (:id %)))) (:fields node))))


(defn- substitute-node
  [node substitution]
  (variables/map-descriptor-types (partial types/resolve substitution) node))


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
             declared contract
             owned (mapv #(rows/normalize (assoc % :fn-id fn-id
                                                 :origin-fn-id (or (:origin-fn-id %) (:fn-id %))))
                         (concat (map #(assoc % :origin-use-path (second scope)) bindings) additional-rows))
             local (filterv #(empty? (:scope-path %)) owned)
             policy (metadata/apply-overlays ctx (variables/descriptor-in-scope scope declared) local
                                             {:scope scope :declaration-order declaration-order
                                              :check-expression check-expression})
             contract (:contract policy)
             entries (mapv #(classify-entry ctx scope contract %) (filter rows/occupied? local))
             state (into {} (map (fn [{:keys [address binding-id expression expected-type]}]
                                   [address {:binding-id binding-id :expression expression
                                             :expected-type expected-type}])) entries)
             ;; Re-insert, rather than map-merging, so duplicate paths are
             ;; rejected even when they carry equal literal values.
             _ (rows/state local)
             remaining (input/remaining-input contract state scope)
             checked (checking/check-entries entries check-expression (:substitution policy))
             obligations (container-obligations (:descriptor remaining) scope [])
             closure-gaps (cond-> []
                            (some #(seq (:scope-path %)) owned) (conj :scoped-analysis-required)
                            (some #(contains? #{:value :callable-producer :graph-callable :resolved-value}
                                              (get-in % [:reference :mode])) entries)
                            (conj :reference-availability-required))
             diagnostics (into (into (cond-> (mapv #(hash-map :code %) closure-gaps)
                                       (and (nil? check-expression) (empty? entries))
                                       (conj {:code :type-checker-required}))
                                     (map #(hash-map :code (:reason %) :address (:address %))) obligations)
                               (keep (fn [[address proof]]
                                       (when (= :deferred (:status proof))
                                         {:code (:reason proof) :address address})))
                               (concat (:proofs checked)
                                       (map (fn [[_ proof]] [(:address proof) proof]) (:proofs policy))))]
         {:fn-id fn-id :declared-contract declared :configured-contract contract :declaration-order declaration-order
          :scoped-rows (filterv #(seq (:scope-path %)) owned)
          :effective-contract (when (empty? closure-gaps) (substitute-node contract (:substitution checked)))
          :remaining-contract (substitute-node (:descriptor remaining) (:substitution checked))
          :required-addresses (mapv #(vector scope %) (:missing remaining))
          :bindings (into {} (map (fn [entry]
                                    [(:address entry)
                                     (update entry :expected-type #(types/resolve (:substitution checked) %))])) entries)
          :substitution (:substitution checked) :proofs (:proofs checked) :metadata-proofs (:proofs policy)
          :obligations obligations
          :diagnostics diagnostics
          :status (if (empty? diagnostics) :checked :incomplete)
          :coverage {:declared-structure true :local-occupancy true
                     :recursive-availability (empty? closure-gaps)
                     :type-checker (boolean check-expression)}})))))


(defn analyze-effective-input
  "Resolve value-producing reference uses into a derived scope graph. Each
   occurrence retains the shared target declaration and binding-ID use path.
   Scoped overlays are checked against target occupancy. Callable captures and
   projections retain explicit routes; identity references never traverse their
   target. Runtime obligations are separate from incomplete coverage. No graph
   execution or signature publication occurs in this offline API."
  ([ctx fn-id] (analyze-effective-input ctx fn-id {}))
  ([ctx fn-id options]
   (context/call-with-types
     ctx
     (fn []
       (-> (scopes/analyze
             fn-id
             (fn [target scope overlays]
               (analyze-input ctx target (assoc options :scope scope :additional-rows overlays))))
           (projections/prepare)
           (projections/apply-demands)
           (resolvers/mark-boundaries)
           (as-> graph (captures/prepare ctx graph))
           (projections/prepare)
           (checking/check-graph (:check-expression options))
           (projections/apply-demands)
           (obligations/separate)
           (as-> graph (assoc graph :public-input (surface/project ctx graph))))))))
