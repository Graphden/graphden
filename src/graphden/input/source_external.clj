(ns graphden.input.source-external
  "Check the relationship between callable parameters, external inputs and source bindings."
  (:require
    [graphden.input.descriptor :as descriptor]
    [graphden.types.core :as types]))


(defn- leaves
  [routes]
  (let [groups (into #{} (comp (filter seq) (map pop)) (keys routes))]
    (remove (fn [[path _]] (or (empty? path) (contains? groups path))) routes)))


(defn matching-inputs?
  "Match every free field of the original use by scoped address, source path,
   field identity, read type and requiredness against its exposed capture."
  [original after [owner prefix] public-path captures rename-type value-path]
  (let [source-routes (get-in original [:public-input :routes])
        after-routes (get-in after [:public-input :routes])
        source (leaves source-routes)
        installed (into {} (keep (fn [[path route]]
                                   (when (and (<= (count public-path) (count path))
                                              (= public-path (subvec path 0 (count public-path))))
                                     [(subvec path (count public-path)) route]))) after-routes)
        facts [:name :kind :origin-field-id :whole-bindable? :required :required-now?]
        rebased (fn [[[root path] field-path]]
                  (when (= root (:fn-id original))
                    [[owner (into prefix path)] field-path]))
        expected (mapv (fn [[path {:keys [address]}]]
                         {:target (rebased address) :value-path (value-path path)}) source)]
    (and (map? source-routes) (map? after-routes)
         (= (set (map key source)) (set (map key (leaves installed))))
         (= (count captures) (count (set (map :target captures))))
         (= (frequencies expected) (frequencies captures))
         (every? (fn [[path route]]
                   (let [nested (get installed path)]
                     (and (= :input-field (:kind route)) (true? (:whole-bindable? route))
                          (uuid? (:origin-field-id route))
                          (= (peek (second (:address route))) (:origin-field-id route))
                          (some? (:address nested)) (= (rebased (:address route)) (:address nested))
                          (= (select-keys route facts) (select-keys nested facts))
                          (= (rename-type (:completion-type route)) (:completion-type nested))
                          (= (rename-type (:type route)) (:type nested)))))
                 source))))


(defn same-inputs?
  "A callable's argument group contributes its fields beneath the parameter."
  [original after use-scope public-path captures rename-type]
  (and (seq captures) (matching-inputs? original after use-scope public-path captures rename-type #(subvec % 1))))


(defn- original-call-site?
  [graph snapshot {:keys [from to binding-id target-fn-id]} address {:keys [parameters call-sites external-captures]}]
  (when-let [readers (:source-callback-readers snapshot)]
    (let [[[_ caller-path] [slot :as fields]] from
          {:keys [positional captured] original-parameters :parameters}
          (when (and (empty? caller-path) (= fields [slot]))
            (readers slot target-fn-id binding-id))
          [slot-id :as path] (second address)
          positional-uses (into {} (map (fn [{:keys [target parameter]}] [(first (second target)) parameter])) call-sites)
          name (get captured slot-id)
          same-name (fn [{[[_ scope] path] :target}]
                      (= name (:name (descriptor/find-node (get-in graph [:scopes scope :configured-contract]) path))))
          [{:keys [target value-path]} & others] (filter same-name external-captures)]
      (and (= parameters original-parameters) (= path [slot-id]) (uuid? slot-id)
           (every? (fn [{[scope path] :target value-path :value-path}]
                     (and (= 1 (count path)) (uuid? (first path)) (vector? (second scope)) (= [] value-path))) call-sites)
           (= (count call-sites) (count positional-uses)) (= positional positional-uses)
           ;; One env name can supply multiple physical callback readers. A
           ;; single scoped write must cover the complete translated reader set.
           (simple-keyword? name)
           (or (= name (peek value-path))
               (and (= [] value-path) (= to (first address))))
           (= address target) (empty? others)
           (= [slot-id] (into [] (keep (fn [[id spelling]] (when (= name spelling) id))) captured))))))


(defn- matched-callable?
  [expected callable original-use?]
  (and (types/fn-type? callable)
       (or (= callable expected)
           (and original-use? (types/fn-type? expected)
                (not-any? #(types/type-any? types/type-var? %) [expected callable])
                (types/subtype? callable expected))
           (and original-use? (types/fn-type? expected)
                (= (types/fn-args expected) (types/fn-args callable))
                (= (types/fn-effects expected) (types/fn-effects callable))
                (or (= (types/fn-ret expected) (types/fn-ret callable)) (= :any (types/fn-ret expected)))
                (or (= (types/fn-execution expected) (types/fn-execution callable))
                    (and (= :unknown (types/fn-execution expected)) (set? (types/fn-execution callable))))))))


(defn captured-input?
  "Check a captured field against explicit parameters or the original
   compiler's exact positional and external readers for this call site."
  [graph snapshot {:keys [from to mode target-fn-id] :as edge} address]
  (let [{:keys [parameters call-sites captures external-captures callable-type] :as closure} (get-in graph [:closures from])
        declaration (get-in snapshot [:fns target-fn-id :lambda-params])
        bound (get-in graph [:public-input :bindings from])]
    (and address (= :graph-callable mode (get-in bound [:reference :mode]))
         (true? (get-in bound [:reference :capture-resolved?]))
         (uuid? target-fn-id) (= target-fn-id (get-in graph [:scopes (second to) :fn-id]))
         (= [] captures) (not-any? #(= address (:target %)) call-sites)
         (= 1 (count (filter #(= address (:target %)) external-captures)))
         (= (set parameters) (set (map :parameter call-sites)))
         (= callable-type (get-in bound [:reference :callable-type]))
         (let [original-use? (and (nil? declaration) (original-call-site? graph snapshot edge address closure))]
           (and (or original-use? (and (vector? declaration) (= parameters (mapv keyword declaration))))
                (matched-callable? (:expected-type bound) callable-type original-use?))))))


(defn preserves-capture-inputs?
  "Installing scoped rows consumes just their external capture addresses.
   Any inputs exposed by an installed value reference need independent source
   evidence. Positional uses, lexical sources and invocation types stay equal."
  [before after certificates replacement-inputs?]
  (let [by-address (into {} (map (juxt :address identity)) certificates)
        by-edge (group-by :from (filter :captured-input (mapcat :edges certificates)))]
    (every? (fn [[from edges]]
              (let [old (get-in before [:closures from])
                    new (get-in after [:closures from])
                    readers (set (map :captured-input edges))
                    targets (into #{} (filter #(get-in by-address [% :row :expression-kind])) readers)
                    remaining (remove #(contains? targets (:target %)) (:external-captures old))
                    installed (:external-captures new)
                    extras (remove (set remaining) installed)
                    [target & more] targets
                    previous (some #(when (= target (:target %)) %) (:external-captures old))
                    certificate (get by-address target)
                    bound (get-in after [:public-input :bindings from])
                    original-bound (get-in before [:public-input :bindings from])]
                (and (every? by-address readers) (= :graph-callable (get-in bound [:reference :mode]))
                     (true? (get-in bound [:reference :capture-resolved?]))
                     (= (:expected-type original-bound) (:expected-type bound))
                     (= (:callable-type old) (get-in bound [:reference :callable-type]))
                     (= (dissoc old :external-captures) (dissoc new :external-captures))
                     (= (frequencies remaining) (frequencies (filter (set remaining) installed)))
                     (if (some #(get-in by-address [% :row :ref-fn-id]) targets)
                       (and (empty? more) previous
                            (replacement-inputs? certificate (:value-path previous) extras))
                       (empty? extras))))) by-edge)))
