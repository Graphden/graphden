(ns graphden.input.analysis
  "Offline structural analysis with explicit checker and closure coverage.
   Never evaluates references, reconstructs flat :args, or mutates registries."
  (:require
    [graphden.input.captures :as captures]
    [graphden.input.checking :as checking]
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.inference :as inference]
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


(defn- literal-type
  [value]
  (if (and (vector? value) (empty? value))
    [:list :never]
    (literals/classify-literal value)))


(defn- reference-mode
  [ctx [root use-path] binding-id target expected]
  (if (contains? (:fns ctx) target)
    (reference/classify expected (variables/signature-in-scope
                                   [root (conj use-path binding-id)] (get-in ctx [:signatures target])))
    {:mode :unresolved :reason :missing-ref-target}))


(defn- classify-entry
  [ctx scope contract row]
  (let [path (:target-path row)
        expected (:type (descriptor/node-at contract path))
        expression (expression-of row scope)
        dispatch (case (:kind expression)
                   :ref (reference-mode ctx scope (:id row) (:fn-id expression) expected)
                   :value (if-let [target (:resolver-fn-id row)]
                            (let [signature (variables/signature-in-scope
                                              [(first scope) (conj (second scope) (:id row))]
                                              (get-in ctx [:signatures target]))]
                              (if (and (contains? (:fns ctx) target) (:return signature))
                                {:mode :resolved-value :actual-type (:return signature)}
                                {:mode :unresolved :reason :missing-resolver-signature}))
                            {:mode :literal :actual-type (literal-type (:value expression))})
                   :projection {:mode :unresolved :reason :projection-analysis-required})]
    {:address [scope path] :binding-id (:id row) :origin-fn-id (:origin-fn-id row)
     :expression expression
     :dispatch-type expected :expected-type expected :reference dispatch}))


(defn- resolve-ambiguous-value
  [ctx substitution {:keys [address binding-id expression expected-type reference] :as entry}]
  (if (= :ambiguous-reference-mode (:reason reference))
    (let [solved (types/resolve substitution expected-type)
          dispatch (reference-mode ctx (first address) binding-id (:fn-id expression) solved)]
      (if (= :value (:mode dispatch))
        (assoc entry :dispatch-type solved :reference dispatch)
        entry))
    entry))


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
  ([ctx fn-id {:keys [check-expression scope additional-rows defer-reference-checks? legacy-list-ends]}]
   (context/call-with-types
     ctx
     (fn []
       (let [{:keys [contract bindings declaration-order]} (inheritance/declared-view ctx fn-id)
             scope (or scope [fn-id []])
             declared contract
             owned (mapv #(rows/normalize (assoc % :fn-id fn-id
                                                 :origin-fn-id (or (:origin-fn-id %) (:fn-id %))))
                         (concat (map #(assoc % :origin-use-path (second scope)) bindings) additional-rows
                                 (get-in ctx [::use-bindings fn-id])))
             terminal (get legacy-list-ends scope)
             local (filterv #(empty? (:scope-path %)) owned)
             local (if (and (= fn-id (:fn-id terminal)) (not-any? #(= (:target-path terminal) (:target-path %)) local))
                     (conj local terminal)
                     local)
             policy (metadata/apply-overlays ctx (variables/descriptor-in-scope scope declared) local
                                             {:scope scope :declaration-order declaration-order
                                              :check-expression check-expression})
             contract (:contract policy)
             entries (mapv #(classify-entry ctx scope contract %) (filter rows/occupied? local))
             independent (filter #(contains? #{:literal :identity} (get-in % [:reference :mode])) entries)
             ambiguous? (some #(= :ambiguous-reference-mode (get-in % [:reference :reason])) entries)
             independent-check (when (or ambiguous? defer-reference-checks?)
                                 (checking/check-entries independent check-expression (:substitution policy)))
             entries (if ambiguous?
                       (mapv (partial resolve-ambiguous-value ctx (:substitution independent-check)) entries)
                       entries)
             state (into {} (map (fn [{:keys [address binding-id expression expected-type]}]
                                   [address {:binding-id binding-id :expression expression
                                             :expected-type expected-type}])) entries)
             ;; Re-insert, rather than map-merging, so duplicate paths are
             ;; rejected even when they carry equal literal values.
             _ (rows/state local)
             remaining (input/remaining-input contract state scope)
             checked (if defer-reference-checks?
                       independent-check
                       (checking/check-entries entries check-expression (:substitution policy)))
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


(def ^:private use-binding-columns
  #{:kind :id :fn-id :org-id :scope-path :target-path :expression-kind :value-present :value :ref-fn-id})


(defn- replay-use-bindings
  [ctx target bindings]
  (let [lineage (set (inheritance/declaration-order ctx target))]
    (when-not (and (vector? bindings) (seq bindings) (= (count bindings) (count (set (map :id bindings)))))
      (context/reject! :invalid-use-bindings {:target target}))
    (mapv (fn [binding]
            (let [row (rows/normalize binding)]
              (when-not (and (every? use-binding-columns (keys binding))
                             (= :input-binding (:kind row))
                             (contains? lineage (:fn-id row))
                             (= (:org-id row) (get-in ctx [:fns (:fn-id row) :org-id]))
                             (empty? (:scope-path row)) (= 1 (count (:target-path row)))
                             (contains? #{"value" "ref"} (:expression-kind row))
                             (not (contains? (:bindings ctx) (:id row))))
                (context/reject! :invalid-use-binding {:target target :binding-id (:id row)}))
              row))
          bindings)))


(defn with-use-bindings
  "Freeze trusted value/reference rows for specific final functions in an
   offline comparison snapshot. A row is visible only when visiting its final
   function. A repeated reference keeps its own use path and the original row
   identity. Ordinary stored bindings and occupancy checks still apply.
   The caller separately proves that each row represents the original source."
  [ctx by-function]
  (when (or (not (map? by-function)) (empty? by-function) (contains? ctx ::use-bindings))
    (context/reject! :invalid-use-bindings {}))
  (assoc ctx :snapshot-id (random-uuid)
         ::use-bindings (reduce-kv (fn [result target bindings]
                                     (assoc result target (replay-use-bindings ctx target bindings)))
                                   {} by-function)))


(defn analyze-effective-input
  "Resolve value-producing reference uses into a derived scope graph. Each
   occurrence retains the shared target declaration and binding-ID use path.
   Scoped overlays are checked against target occupancy. Callable captures and
   projections retain explicit routes; identity references never traverse their
   target. Runtime obligations are separate from incomplete coverage. No graph
   execution or signature publication occurs in this offline API."
  ([ctx fn-id] (analyze-effective-input ctx fn-id {}))
  ([ctx fn-id options]
   (let [infer? (some? (:infer-signature options))
         ctx (if infer? (inference/with-root-signatures ctx (:implementations options)) ctx)]
     (context/call-with-types
       ctx
       (fn []
         (-> (scopes/analyze
               fn-id
               (fn [target scope overlays]
                 (analyze-input ctx target (assoc options :scope scope :additional-rows overlays
                                                  :defer-reference-checks? infer?))))
             (projections/prepare (:source-optional-renames ctx))
             (projections/apply-demands)
             (as-> graph (if infer?
                           (inference/analyze ctx graph options)
                           (captures/prepare ctx (assoc-in (resolvers/mark-boundaries graph)
                                                           [:coverage :signatures] false))))
             (projections/prepare (:source-optional-renames ctx))
             (checking/check-graph (:check-expression options))
             (projections/apply-demands)
             (obligations/separate)
             (as-> graph (assoc graph :public-input (surface/project ctx graph)))))))))
