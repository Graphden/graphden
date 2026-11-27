(ns graphden.input.source-external
  "Check the relationship between callable parameters, external inputs and source bindings."
  (:require
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


(defn captured-input?
  "Only an explicitly parameterized callable's external input may be bound.
   Its positional and lexical inputs belong to the call site and the caller."
  [graph snapshot {:keys [from to mode target-fn-id]} address]
  (let [{:keys [parameters call-sites captures external-captures callable-type]} (get-in graph [:closures from])
        declaration (get-in snapshot [:fns target-fn-id :lambda-params])
        bound (get-in graph [:public-input :bindings from])]
    (and address (= :graph-callable mode (get-in bound [:reference :mode]))
         (true? (get-in bound [:reference :capture-resolved?]))
         (uuid? target-fn-id) (= target-fn-id (get-in graph [:scopes (second to) :fn-id]))
         (vector? declaration) (= parameters (mapv keyword declaration))
         (types/fn-type? callable-type) (= callable-type (:expected-type bound) (get-in bound [:reference :callable-type]))
         (= (set parameters) (set (map :parameter call-sites)))
         (= [] captures) (not-any? #(= address (:target %)) call-sites)
         (= 1 (count (filter #(= address (:target %)) external-captures))))))


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
                    targets (set (map :captured-input edges))
                    remaining (remove #(contains? targets (:target %)) (:external-captures old))
                    installed (:external-captures new)
                    extras (remove (set remaining) installed)
                    [target & more] targets
                    previous (some #(when (= target (:target %)) %) (:external-captures old))
                    certificate (get by-address target)
                    bound (get-in after [:public-input :bindings from])]
                (and (every? by-address targets) (= :graph-callable (get-in bound [:reference :mode]))
                     (true? (get-in bound [:reference :capture-resolved?]))
                     (= (:callable-type old) (:expected-type bound) (get-in bound [:reference :callable-type]))
                     (= (dissoc old :external-captures) (dissoc new :external-captures))
                     (= (frequencies remaining) (frequencies (filter (set remaining) installed)))
                     (if (some #(get-in by-address [% :row :ref-fn-id]) targets)
                       (and (empty? more) previous
                            (replacement-inputs? certificate (:value-path previous) extras))
                       (empty? extras))))) by-edge)))
