(ns graphden.input.signatures
  "Canonical input facts for the existing signature engine. Implementation
   argument paths are explicit ABI provenance, never inferred from field names."
  (:require
    [clojure.string :as str]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.surface :as surface]
    [graphden.input.variables :as variables]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.util
      UUID)))


(defn use-id
  "Internal lookup identity for one use. Never an authored or stored fn ID."
  [[owner use-path]]
  (UUID/nameUUIDFromBytes
    (String/.getBytes (str "graphden.input.signature/" owner "/" (str/join "/" use-path))
                      StandardCharsets/UTF_8)))


(defn local-type-rules?
  "A frozen closure can run audited callbacks that use its binding facts and
   frozen type/signature policy. Missing signature lookups must remain
   conservative. Callbacks with other state or I/O dependencies need proof."
  [signature]
  (every? (fn [[rule callback]]
            (or (nil? callback)
                (true? (:graphden.input/local-type-rule (meta callback)))
                (identical? (get-in signature [:local-type-rule-evidence rule]) callback)))
          (select-keys signature [:return-type-rule :slot-types-rule :nav-types-rule])))


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
  [graph {:keys [address expression reference dispatch-type expected-type]}]
  (let [mode (:mode reference)
        target-scope (:to (first (filter #(= address (:from %)) (:dependencies graph))))
        signature (get-in graph [:scopes (second target-scope) :signature])
        reference (cond
                    (= :resolved-value mode) (assoc reference :actual-type (:return signature))
                    (and signature (= :ref (:kind expression)) (not (:capture-resolved? reference)))
                    (reference/classify (or dispatch-type expected-type) signature)
                    :else reference)
        mode (:mode reference)
        expected-callable (reference/expected-callable (or dispatch-type expected-type))
        reference (cond-> reference
                    (and (= :graph-callable mode) (not (:capture-resolved? reference))
                         (types/fn-type? expected-callable))
                    (update :actual-type #(reference/call-site-contract expected-callable % signature)))
        actual (or (:callable-type reference) (:actual-type reference))]
    (case mode
      :literal {:type actual :value (:value expression) :value-present true :effects #{}}
      :identity {:type :fn-ref :value (:fn-id expression) :value-present true :effects #{}}
      (:value :callable-producer :graph-callable :resolved-value)
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
   A free argument has no value key. A bound nil has :value nil and
   :value-present true and is absent from :free-arguments.
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
                        resolved (some (fn [length]
                                         (let [prefix (subvec path 0 length)]
                                           (when-let [argument (get-in graph [:resolver-arguments [scope prefix]])]
                                             [prefix argument])))
                                       (range (count path) -1 -1))
                        result
                        (cond
                          resolved
                          (let [[prefix argument] resolved]
                            (project-fact {:type (:actual-type argument) :value (:value argument)
                                           :value-present true :effects #{}}
                                          (subvec (names-at contract path) (count prefix))))
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


(defn- declared-return
  [ctx {:keys [id return-type-fn-id]}]
  (if-let [witness (get-in ctx [:source-return-types [id return-type-fn-id]])]
    (if (descriptor/stored-source-return-type? witness return-type-fn-id (:alias-ids ctx))
      (types/resolve-alias witness)
      (context/reject! :return-type-witness-mismatch {:fn-id id :type-id return-type-fn-id}))
    (:type (descriptor/resolve-descriptor ctx return-type-fn-id))))


(defn- declared-binder-renaming
  [node scope implementation-scope root-entry argument-paths declarations]
  (reduce-kv
    (fn [renaming argument {:keys [id type]}]
      (let [path (get argument-paths argument)
            rich (types/resolve-alias type)
            declared (when (= [id] path) (descriptor/node-at (:declared-contract node) path))
            configured (when declared (descriptor/node-at (:configured-contract node) path))]
        ;; A frozen base field and its unchanged inherited declaration share
        ;; a binder. Shape equality alone, another field, or an overlay does
        ;; not prove that an implementation variable names that binder.
        (if (and declared (= rich (get-in root-entry [:args argument]) (:type declared))
                 (= (variables/type-in-scope scope rich) (:type configured)))
          (merge renaming (variables/value-renaming implementation-scope scope rich))
          renaming)))
    {} declarations))


(defn infer-use
  "Infer one use from resolved dependency signatures through the supplied
   existing-engine callback. The implementation descriptor is shared with the
   compiler; :impl is never read or invoked. Returned substitutions/proofs must
   join graph checking before this signature can be called checked."
  [ctx graph scope {:keys [base-fn-id root-entry argument-paths argument-declarations generated-list] :as implementation} infer-signature]
  (when-not (and (uuid? base-fn-id) (map? root-entry) (:return root-entry)
                 (fn? infer-signature))
    (context/reject! :signature-provider-required {:scope scope}))
  (let [node (get-in graph [:scopes (second scope)])
        implementation-scope [base-fn-id (conj (second scope) (first scope))]
        renaming (declared-binder-renaming node scope implementation-scope root-entry argument-paths argument-declarations)
        root-entry (->> (assoc root-entry :fn-id base-fn-id)
                        (variables/signature-in-scope implementation-scope)
                        (variables/resolve-signature renaming))
        declaration (get-in ctx [:fns (:fn-id node)])
        stored-return (when (:return-type-fn-id declaration) (declared-return ctx declaration))
        declaration (cond-> declaration
                      (and stored-return (not (:infer-return? generated-list)))
                      (assoc :return-type (types/resolve renaming (variables/type-in-scope implementation-scope stored-return))))
        facts (binding-facts graph scope argument-paths)
        result (infer-signature
                 (assoc facts :fn-id (:fn-id node) :root-entry root-entry
                        :signature-view (frozen-view ctx graph base-fn-id root-entry)
                        :declaration declaration))
        signature (-> result
                      (dissoc :substitution :argument-proofs)
                      (assoc :primary-parent (or (:name root-entry) base-fn-id)
                             :resolved-bindings (:bindings-info facts)
                             :args (into {} (map (juxt :name :type))
                                         (:fields (surface/input-at-scope graph (second scope)))))
                      (cond-> (contains? declaration :lambda-params)
                        (assoc :lambda-params (:lambda-params declaration))))]
    (when-not (and (:return signature) (set? (:effects signature))
                   (map? (:substitution result)) (map? (:argument-proofs result)))
      (context/reject! :invalid-signature-result {:scope scope}))
    {:signature signature :signature-source (select-keys implementation [:base-fn-id :root-entry :argument-paths])
     :substitution (:substitution result)
     :argument-proofs (:argument-proofs result)}))
