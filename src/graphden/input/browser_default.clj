(ns graphden.input.browser-default
  "Source evidence for sending an occurrence-owned record default to the browser."
  (:require
    [graphden.input.descriptor :as descriptor]
    [graphden.input.field-default :as field-default]
    [graphden.input.inheritance :as inheritance]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]))


(defn- same-public-record?
  [declared configured [outer leaf] fallback public-scalar?]
  (let [parent (descriptor/find-node declared [outer])
        nested (descriptor/find-node parent [leaf])
        effective (descriptor/find-node configured [outer])
        same-field (juxt :id :name :type-id :type :required)]
    (and (map? (:type parent)) (false? (:required parent))
         (= (same-field parent) (same-field effective))
         (= (mapv same-field (:fields parent)) (mapv same-field (:fields effective)))
         (= field-default/nullable-text (:type nested))
         (= {:default fallback} (select-keys nested [:default]))
         (every? public-scalar? (vals (dissoc (:type parent) (:name nested)))))))


(defn- shared-member?
  [context record leaf child member]
  (and (string? (:name child)) (= leaf (:id child))
       (= record (:fn-id member)) (= leaf (ids/slot-id record (:name child)))
       (= (:id member) (ids/fn-slot-id record leaf)) (nil? (:input-default member))
       (false? (:required child)) (= field-default/nullable-text-id (:type-fn-id child))
       (= field-default/nullable-text (get-in context [:fns field-default/nullable-text-id :constraint]))))


(defn- owns-fallback?
  [owner input outer field-name occurrence leaf fallback]
  (and (= input (migration/derived-id :input-type owner))
       (= outer (ids/slot-id owner field-name)) (= input (:fn-id occurrence))
       (= (:id occurrence) (migration/derived-id :membership owner (ids/fn-slot-id owner outer)))
       (= {:target-path [(str leaf)] :value fallback} (:input-default occurrence))))


(defn- stored-contract
  [context owner]
  (try (descriptor/resolve-contract context owner)
       (catch clojure.lang.ExceptionInfo error
         ;; A malformed sibling also invalidates this owner's disclosure proof.
         (when-not (= :input/unresolved-contract (:type (ex-data error)))
           (throw error)))))


(defn occurrence-checker
  "Check each root-frame record use against the authorized frozen membership.
   Contracts and membership indexes are shared across defaults in this export."
  [{:keys [context allow-fn? snapshot-id]} public-scalar?]
  (let [contract (memoize (partial stored-contract context))
        lineage (memoize (partial inheritance/declaration-order context))
        members (memoize #(group-by :slot-id (get-in context [:members %])))
        member-at (fn [type slot]
                    (let [rows (get (members type) slot)]
                      (when (= 1 (count rows)) (first rows))))
        loaded? #(= % (get-in context [:fns % :id]))
        visible? #(and (loaded? %) (true? (allow-fn? %)))]
    (fn [{:keys [fn-id declaration-order configured-contract]} [outer leaf :as path] fallback]
      (let [{field-name :name record :type-fn-id :as field} (get-in context [:fields outer])]
        (and (= snapshot-id (:snapshot-id context)) (loaded? fn-id)
             (= outer (:id field)) (string? field-name)
             (shared-member? context record leaf (get-in context [:fields leaf]) (member-at record leaf))
             (every? loaded? declaration-order) (= declaration-order (lineage fn-id))
             (some (fn [owner]
                     (let [input (get-in context [:fns owner :input-type-fn-id])]
                       (and (owns-fallback? owner input outer field-name (member-at input outer) leaf fallback)
                            (every? visible? [owner input record field-default/nullable-text-id])
                            (same-public-record? (contract owner) configured-contract path fallback public-scalar?))))
                   declaration-order))))))
