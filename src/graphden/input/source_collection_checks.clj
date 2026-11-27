(ns graphden.input.source-collection-checks
  "Corroborate deferred collection reads specialized by scoped literals."
  (:require
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


(defn refined-read?
  "Retain the same deferred read on the same value reference. Only its value
   or element type can change from :any to the checked union of the installed
   literal types and one surviving variable of the original target."
  [before after certificates old current]
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
              literal-types (mapv #(reads/installed-scalar-type after %) certificates)
              [variable & more] (remove (set literal-types) arms)]
          (and (seq certificates) (every? some? literal-types)
               (types/type-var? variable) (empty? more) (= (set arms) (conj (set literal-types) variable))
               use-scope
               (= [:deferred :runtime-type-obligation] (status before) (status after))
               (= previous (:expected-type original) (get-in original [:reference :actual-type]) (returns before))
               (= required (:expected-type bound) (get-in bound [:reference :actual-type]) (returns after))
               (every? (fn [{:keys [address]}] (and (reaches? before use-scope address) (reaches? after use-scope address))) certificates)
               (stable-variable? before after use-scope variable)))))))
