(ns graphden.input.signatures
  "Canonical input facts for the existing signature engine. Implementation
   argument paths are explicit ABI provenance, never inferred from field names."
  (:require
    [clojure.string :as str]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.variables :as variables]
    [graphden.types.core :as types])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.util
      UUID)))


(defn use-id
  "Internal lookup identity for one use. Never an authored or stored fn ID."
  [[owner use-path]]
  (UUID/nameUUIDFromBytes
    (.getBytes (str "graphden.input.signature/" owner "/" (str/join "/" use-path))
               StandardCharsets/UTF_8)))


(defn- names-at
  [contract path]
  (mapv (fn [length] (:name (descriptor/node-at contract (subvec path 0 length))))
        (range 1 (inc (count path)))))


(defn- read-type
  [rich names]
  (if (empty? names)
    rich
    (let [base (cond
                 (types/marker-type? rich) (types/marker-inner rich)
                 (types/refine-type? rich) (types/refine-base rich)
                 :else rich)]
      (types/taint-with-secret-if-tainted
        {:container {:type rich}}
        (cond
          (not= base rich) (read-type base names)
          (types/record-type? base) (read-type (get base (first names) :null) (subvec names 1))
          (types/union-type? base) (types/make-union (map #(read-type % names) (types/union-members base)))
          (types/map-type? base) (read-type (types/map-val base) (subvec names 1))
          :else :any)))))


(defn- project-fact
  [fact names]
  (if (empty? names)
    fact
    (cond-> {:type (read-type (:type fact) names) :effects (:effects fact #{}) :free? (:free? fact)}
      (:value-present fact)
      (assoc :value (get-in (:value fact) names) :value-present true))))


(defn- reference-fact
  [graph {:keys [address expression reference]}]
  (let [mode (:mode reference)
        target-scope (:to (first (filter #(= address (:from %)) (:dependencies graph))))
        signature (get-in graph [:scopes (second target-scope) :signature])
        actual (or (:callable-type reference) (:actual-type reference))]
    (case mode
      :literal {:type actual :value (:value expression) :value-present true :effects #{}}
      :identity {:type :fn-ref :value (:fn-id expression) :value-present true :effects #{}}
      (:value :callable-producer :graph-callable)
      (do
        (when-not (and target-scope signature (set? (:effects signature)))
          (context/reject! :reference-signature-required {:address address}))
        {:type actual :effects (:effects signature) :reference-address address
         (if (= :graph-callable mode) :bound-ref :ref) (use-id target-scope)})
      (context/reject! :reference-mode-required {:address address :mode mode}))))


(defn binding-facts
  "Resolve rule facts for explicit ABI paths from canonical bindings and cell
   routes. Literal maps remain data. Whole-record reads retain content markers;
   partial records expose a literal only when every field is statically known.
   This function neither invokes references nor reads a registry."
  [graph scope argument-paths]
  (when-not (and (map? argument-paths) (every? keyword? (keys argument-paths))
                 (every? #(and (vector? %) (every? uuid? %)) (vals argument-paths)))
    (context/reject! :implementation-paths-required {:scope scope}))
  (let [routes (into {} (map (juxt :from :source)) (:input-routes graph))
        cache (volatile! {})]
    (letfn [(fact
              [[scope path :as address] pending]
              (when (contains? pending address)
                (context/reject! :signature-fact-cycle {:address address}))
              (or (get @cache address)
                  (let [node (get-in graph [:scopes (second scope)])
                        contract (:configured-contract node)
                        field (descriptor/node-at contract path)
                        pending (conj pending address)
                        bound (some (fn [length]
                                      (let [prefix (subvec path 0 length)
                                            entry (get-in node [:bindings [scope prefix]])]
                                        (when entry [prefix entry])))
                                    (range (count path) -1 -1))
                        routed (some (fn [length]
                                       (let [prefix (subvec path 0 length)]
                                         (when-let [source (get routes [scope prefix])]
                                           [prefix source])))
                                     (range (count path) -1 -1))
                        result
                        (cond
                          bound
                          (let [[prefix entry] bound
                                source (get-in entry [:reference :source])
                                base (if source (fact source pending) (reference-fact graph entry))
                                names (subvec (names-at contract path) (count prefix))]
                            (project-fact base names))
                          routed
                          (let [[prefix source] routed
                                names (subvec (names-at contract path) (count prefix))]
                            (project-fact (fact source pending) names))
                          (:fields field)
                          (let [children (mapv (fn [child]
                                                 [child (fact [scope (conj path (:id child))] pending)])
                                               (:fields field))
                                shaped (reduce (fn [tree [child info]]
                                                 (descriptor/update-node tree [(:id child)] #(assoc % :type (:type info))))
                                               field children)]
                            (cond-> {:type (:type shaped)
                                     :effects (reduce into #{} (map (comp :effects second) children))
                                     :free? (boolean (some (comp :free? second) children))}
                              (every? (comp :value-present second) children)
                              (assoc :value (into {} (map (fn [[child info]] [(:name child) (:value info)])) children)
                                     :value-present true)))
                          :else {:type (descriptor/read-type-at contract path) :effects #{} :free? true})]
                    (vswap! cache assoc address result)
                    result)))]
      (let [facts (into {} (map (fn [[argument path]] [argument (fact [scope path] #{})])) argument-paths)]
        {:bindings-info (into {} (map (fn [[argument info]]
                                       [argument (assoc (dissoc info :effects :free?)
                                                        :address [scope (get argument-paths argument)])])) facts)
         :argument-effects (update-vals facts :effects)
         :free-arguments (into #{} (keep (fn [[argument info]] (when (:free? info) argument))) facts)}))))


(defn- frozen-view
  [ctx graph base-fn-id root-entry]
  (let [view (or (:signature-view ctx) {:by-id (:signatures ctx) :by-name {}})
        name (:name root-entry)
        previous (get-in view [:by-name name])]
    (when (and name previous (not= previous base-fn-id))
      (context/reject! :ambiguous-signature-root {:base-fn-id base-fn-id}))
    (cond-> (update view :by-id into
                    (into {base-fn-id root-entry}
                          (keep (fn [[path node]]
                                  (when-let [signature (:signature node)]
                                    [(use-id [(first (:root-scope graph)) path]) signature])))
                          (:scopes graph)))
      name (assoc-in [:by-name name] base-fn-id))))


(defn infer-use
  "Infer one use from resolved dependency signatures through the supplied
   existing-engine callback. The implementation descriptor is shared with the
   compiler; :impl is never read or invoked. Returned substitutions/proofs must
   join graph checking before this signature can be called checked."
  [ctx graph scope {:keys [base-fn-id root-entry argument-paths]} infer-signature]
  (when-not (and (uuid? base-fn-id) (map? root-entry) (:return root-entry)
                 (fn? infer-signature))
    (context/reject! :signature-provider-required {:scope scope}))
  (let [node (get-in graph [:scopes (second scope)])
        root-entry (variables/signature-in-scope
                     [base-fn-id (conj (second scope) (first scope))]
                     (assoc root-entry :fn-id base-fn-id))
        declaration (get-in ctx [:fns (:fn-id node)])
        declaration (cond-> declaration
                      (:return-type-fn-id declaration)
                      (assoc :return-type (:type (descriptor/resolve-descriptor ctx (:return-type-fn-id declaration)))))
        facts (binding-facts graph scope argument-paths)
        result (infer-signature
                 (assoc facts :fn-id (:fn-id node) :root-entry root-entry
                        :signature-view (frozen-view ctx graph base-fn-id root-entry)
                        :declaration declaration))
        signature (-> result
                      (dissoc :substitution :argument-proofs)
                      (assoc :primary-parent (or (:name root-entry) base-fn-id)
                             :resolved-bindings (:bindings-info facts)))]
    (when-not (and (:return signature) (set? (:effects signature))
                   (map? (:substitution result)) (map? (:argument-proofs result)))
      (context/reject! :invalid-signature-result {:scope scope}))
    {:signature signature :substitution (:substitution result)
     :argument-proofs (:argument-proofs result)}))
