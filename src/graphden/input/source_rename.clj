(ns graphden.input.source-rename
  "Source identities and directional type checks for inherited field renames."
  (:require
    [graphden.input.context :as context]
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as records]
    [graphden.packages.type-declaration :as type-declaration]
    [graphden.types.check.markers :as markers]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.types.expansion :as expansion]))


(defn- visible-aliases
  [{:keys [defs-by-name name->id]}]
  (into {} (keep (fn [[ref d]]
                   ;; Variants normalize their alternatives at declaration time.
                   ;; Only retain bodies that can be frozen before expansion.
                   (when (and (not (types/primitive? ref)) (some d [:type :list :map :union :refine]))
                     (let [id (ids/fn-id (:namespace d) (:name d))]
                       (when (= id (get name->id ref) (:fn-id d id))
                         (when-let [body (type-declaration/type-row-alias-body d)] [ref body]))))))
        defs-by-name))


(defn- known-type?
  "Check nested references before union normalization can absorb an unknown arm.
   Call only after bounded expansion has established acyclicity."
  [aliases t]
  (and (types/well-formed? t)
       (if-let [body (when (and (keyword? t) (not (types/primitive? t))) (get aliases t))]
         (known-type? aliases body)
         (every? (partial known-type? aliases) (types/child-types t)))))


(defn- named-union-pin?
  [snapshot target pin]
  (context/call-with-types
    snapshot
    (fn []
      (try
        (expansion/assert-finite! target)
        (expansion/assert-finite! pin)
        (let [expected (types/resolve-alias target)
              actual (types/resolve-alias pin)
              vars (filter types/type-var? (tree-seq #(seq (types/child-types %)) types/child-types expected))]
          (and (contains? (:aliases snapshot) pin)
               (every? (partial known-type? (:aliases snapshot)) [target pin])
               (not (types/type-any? types/type-var? actual))
               (= (count vars) (count (distinct vars)))
               (markers/preserved? expected actual)
               (types/subtype? actual expected)))
        (catch clojure.lang.ExceptionInfo _ false)))))


(defn prepare-context
  "Cache directional pin checks within one immutable prepared package."
  [prepared]
  (let [policy (shapes/markers-snapshot)
        frozen (delay {:aliases (visible-aliases prepared) :markers policy})]
    (assoc prepared ::named-pin? (memoize #(named-union-pin? @frozen %1 %2)))))


(defn- compatible-pin?
  [context target alias-type]
  (or (= target alias-type) (= :any target) (types/type-var? target)
      (and (types/union-type? target) (keyword? alias-type)
           (contains? (:name->id context) alias-type)
           ((::named-pin? context) target alias-type))))


(defn origin
  "Match the true inherited field, alias, membership and authored rich type.
   A shared storage primitive alone cannot justify a rich field projection."
  [{:keys [name->id defs-by-name] :as context} definition owner records field spec]
  (when (and (= 1 (count (:parent-ids owner))) (map? spec) (contains? #{#{:as} #{:as :type}} (set (keys spec)))
             (simple-keyword? (:as spec)) (not= field (:as spec)))
    (let [author (slots/own-ref definition defs-by-name)
          prepared (get-in definition [:args field])
          [target key] (slots/resolve-slot-owner author field defs-by-name prepared)
          target-type (slots/slot-type-of target key defs-by-name)
          exposed (:as spec)
          alias-id (ids/slot-id (:id owner) exposed)
          target-id (get name->id target)
          field-id (when (uuid? target-id) (ids/slot-id target-id key))
          slot (some #(when (= alias-id (:id %)) %) (:slot records))
          alias-type (or (:type prepared) (slots/ancestor-type-pin author field defs-by-name) :any)
          pin? (contains? spec :type)]
      (when (and field-id (= field key) (some #{target} (rest (slots/chain-of author defs-by-name)))
                 (format/projection-input-type? alias-type)
                 (if pin? (and (format/configuration-type? (:type spec)) (compatible-pin? context target-type alias-type))
                     (= target-type alias-type))
                 (= field-id (:source-slot-id slot))
                 (= (:type-fn-id slot) (records/resolve-type-ref alias-type name->id))
                 (= (name exposed) (:name slot)) (false? (:required slot))
                 (some #(and (= alias-id (:slot-id %)) (= (:id owner) (:fn-id %))
                             (= (ids/fn-slot-id (:id owner) alias-id) (:id %))) (:fn-slot records)))
        (cond-> {:alias exposed :alias-id alias-id :field-id field-id
                 :declaration {:type (if pin? (:type spec) alias-type) :required false}}
          pin? (assoc :pin-id (:type-fn-id slot)))))))


(defn legacy-binding
  "Invert a verified projection only through the authored alias-slot FK.
   All other rows use the existing shallow storage inverse."
  [declarations row]
  (if (= "projection" (:expression-kind row))
    (let [[exposed-name] (:projection-path row)
          [field] (:target-path row)
          owner (:fn-id row)
          slot (some #(when (and (= :slot (:kind %)) (= exposed-name (:id %))) %) declarations)]
      (when-not (and (= [] (:scope-path row) (:projection-scope-path row))
                     (= [exposed-name] (:projection-path row)) (= [field] (:target-path row))
                     (= field (:source-slot-id slot))
                     (= exposed-name (ids/slot-id owner (:name slot))) (= (:id row) (ids/binding-id owner field)))
        (throw (ex-info "Projection differs from the legacy rename" {:code :rename-origin-mismatch})))
      (migration/legacy-binding (-> row (assoc :expression-kind nil) (dissoc :projection-path :projection-scope-path))))
    (migration/legacy-binding row)))
