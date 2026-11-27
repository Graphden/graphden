(ns graphden.input.source-collection-checks
  "Corroborate deferred collection reads specialized by scoped values."
  (:require
    [graphden.input.lookup-abi :as lookup]
    [graphden.input.source-read-checks :as reads]
    [graphden.types.core :as types]))


(defn- inside-use?
  [[owner prefix] [root path]]
  (and (= owner root) (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))


(defn- values-type
  [old new]
  (cond
    (and (types/list-type? old) (types/list-type? new) (= :any (types/list-elem old))) (types/list-elem new)
    (and (types/map-type? old) (types/map-type? new) (= (types/map-key old) (types/map-key new))
         (= :any (types/map-val old))) (types/map-val new)))


(defn- reaches?
  [graph use-scope source]
  (or (inside-use? use-scope (first source))
      (some (fn [{:keys [from] origin :source}]
              (and (= source origin) (inside-use? use-scope (first from)))) (:input-routes graph))
      (some (fn [[path node]]
              (when (inside-use? use-scope [(:fn-id graph) path])
                (some (fn [{:keys [expression]}]
                        (and (= :projection (:kind expression)) (= source [(:scope expression) (:path expression)])))
                      (vals (:bindings node))))) (:scopes graph))))


(defn- stable-variable?
  [before after use-scope variable]
  (some (fn [[path node]]
          (when (inside-use? use-scope [(:fn-id after) path])
            (let [old (get-in before [:scopes path])]
              (and (uuid? (:fn-id node)) (= (:fn-id old) (:fn-id node))
                   (map? (:declared-contract node)) (= (:declared-contract old) (:declared-contract node))
                   (seq (:declaration-order node)) (= (:declaration-order old) (:declaration-order node))
                   (types/type-any? #{variable} (get-in old [:configured-contract :type]))
                   (types/type-any? #{variable} (get-in node [:configured-contract :type])))))) (:scopes after)))


(defn- installed-scalar-type
  [checked-target after {:keys [address row] :as certificate}]
  (or (reads/installed-scalar-type after certificate)
      (let [[[owner path]] address
            {:keys [id ref-fn-id expression-kind]} row
            bound (reads/binding-at after address)
            use-scope [owner (conj path id)]
            edge {:from address :to use-scope :mode :value :target-fn-id ref-fn-id :binding-id id}
            type (get-in bound [:reference :actual-type])]
        (when (and (= "ref" expression-kind) (uuid? ref-fn-id) (uuid? id) (= id (:binding-id bound))
                   (= {:kind :ref :fn-id ref-fn-id} (:expression bound)) (= :value (get-in bound [:reference :mode]))
                   (types/primitive? type) (not= :any type) (some #{edge} (:dependencies after))
                   (= ref-fn-id (get-in after [:scopes (second use-scope) :fn-id]))
                   (= type (get-in after [:scopes (second use-scope) :signature :return])))
          (let [independent (checked-target ref-fn-id)]
            (when (and (= ref-fn-id (:fn-id independent)) (= type (get-in independent [:scopes [] :signature :return])))
              type))))))


(defn refined-read?
  "Retain the same deferred read on the same value reference. Only its value
   or element type can change from :any to the checked union of the installed
   scalar types and one surviving variable of the original target."
  [checked-target before after certificates old current]
  (when (and (reads/field-read? current) (= old (assoc current :expected-type (:expected-type old))))
    (let [address (:address current)
          previous (:expected-type old)
          required (:expected-type current)
          arms (types/union-members (values-type previous required))]
      (when (seq arms)
        (let [original (reads/binding-at before address)
              bound (reads/binding-at after address)
              status (fn [g] ((juxt :status :reason) (get-in g [:proofs address])))
              use-scope (reads/value-use before after address)
              returns (fn [g] (get-in g [:scopes (second use-scope) :signature :return]))
              scalar-types (mapv #(installed-scalar-type checked-target after %) certificates)
              [variable & more] (remove (set scalar-types) arms)]
          (and (seq certificates) (every? some? scalar-types)
               (types/type-var? variable) (empty? more) (= (set arms) (conj (set scalar-types) variable))
               use-scope
               (= [:deferred :runtime-type-obligation] (status before) (status after))
               (= previous (:expected-type original) (get-in original [:reference :actual-type]) (returns before))
               (= required (:expected-type bound) (get-in bound [:reference :actual-type]) (returns after))
               (every? (fn [{:keys [address]}] (and (reaches? before use-scope address) (reaches? after use-scope address))) certificates)
               (stable-variable? before after use-scope variable)))))))


(defn captured-lookup
  "A captured dictionary can specialize the private key variable on the
   native lookup's collection and its exact local projection. Its producer's
   value type remains independently checked, including nested binders."
  [before after address original expected actual]
  (let [[scope] address
        node (get-in before [:scopes (second scope)])
        {:keys [base-fn-id root-entry argument-paths]} (:signature-source node)
        {:keys [argument path]} (lookup/collection-argument root-entry 'a)
        physical [scope path]
        mapped (filter types/map-type? (types/union-members original))
        variable (when (= 1 (count mapped)) (types/map-key (first mapped)))
        projection (fn [graph]
                     (let [binding (reads/binding-at graph physical)]
                       (and (= :projection (get-in binding [:expression :kind]) (get-in binding [:reference :mode]))
                            (= address (get-in binding [:reference :source])))))]
    (when (and (= base-fn-id (:fn-id root-entry)) (= :coll argument) (= path (get argument-paths argument))
               (= (:signature-source node) (get-in after [:scopes (second scope) :signature-source]))
               (projection before) (projection after)
               (types/type-var? variable) (= :any (types/map-val (first mapped)))
               (types/map-type? actual) (= :any (types/map-key actual)) (types/subtype? actual expected)
               (= expected (types/resolve {variable :any} original) (types/resolve (:substitution after) original))
               (= expected (get-in after [:scopes (second scope) :bindings physical :expected-type])))
      physical)))
