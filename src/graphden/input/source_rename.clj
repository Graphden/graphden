(ns graphden.input.source-rename
  "Source identities and directional type checks for inherited field renames."
  (:require
    [graphden.input.context :as context]
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.input.source-rich-types :as rich]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as records]
    [graphden.packages.type-declaration :as type-declaration]
    [graphden.types.check.markers :as markers]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.types.expansion :as expansion]))


(defn- visible-aliases
  [{:keys [defs-by-name name->id fn-defs ::ambiguous-type-ids]}]
  (let [occurrences (frequencies (map :fn-id fn-defs))]
    (into {} (keep (fn [[ref d]]
                     ;; Variants normalize their alternatives at declaration time.
                     ;; Only retain bodies that can be frozen before expansion.
                     (when (and (not (types/primitive? ref)) (some d [:type :list :map :union :refine]))
                       (let [id (ids/fn-id (:namespace d) (:name d))]
                         (when (and (= id (get name->id ref) (:fn-id d id)) (< (get occurrences id 0) 2)
                                    (not (contains? ambiguous-type-ids id)))
                           (when-let [body (type-declaration/type-row-alias-body d)] [ref body]))))))
          defs-by-name)))


(defn- known-type?
  "Check nested references before union normalization can absorb an unknown arm.
   Call only after bounded expansion has established acyclicity."
  [aliases t]
  (and (types/well-formed? t)
       (if-let [body (when (and (keyword? t) (not (types/primitive? t))) (get aliases t))]
         (known-type? aliases body)
         (every? (partial known-type? aliases) (types/child-types t)))))


(defn- closed-instance?
  [target actual]
  (let [substitution (types/unify target actual)]
    (when (map? substitution)
      (let [instantiated (types/resolve substitution target)]
        (and (not (types/type-any? types/type-var? instantiated))
             (types/subtype? actual instantiated))))))


(defn- source-pin?
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
          (and (every? (partial known-type? (:aliases snapshot)) [target pin])
               (not (types/type-any? types/type-var? actual))
               (markers/preserved? expected actual)
               (or (and (= (count vars) (count (distinct vars)))
                        (types/subtype? actual expected))
                   (closed-instance? expected actual))))
        (catch clojure.lang.ExceptionInfo _ false)))))


(defn prepare-context
  "Cache directional pin checks within one immutable prepared package."
  [prepared]
  (let [policy (shapes/markers-snapshot)
        frozen (delay {:aliases (visible-aliases prepared) :markers policy})]
    (assoc prepared ::source-pin? (memoize #(source-pin? @frozen %1 %2))
           ::named-type-id (fn [type] (when (contains? (:aliases @frozen) type) (get (:name->id prepared) type))))))


(defn- compatible-pin?
  [context target alias-type]
  (or (= target alias-type) (= :any target) (types/type-var? target)
      ((::source-pin? context) target alias-type)))


(defn- inherited-callable-pin?
  [{:keys [name->id defs-by-name] :as context} author target field alias-type]
  (let [target-id (get name->id target)
        definition (get defs-by-name author)
        parents (concat (when-let [parent (:parent definition)] [parent]) (:parents definition))
        target-implementation? (fn [parent]
                                 (some #(= target-id (get name->id %))
                                       (slots/chain-of parent defs-by-name)))
        branch-refs (remove #(= target-id (get name->id %)) (rest (slots/chain-of author defs-by-name)))
        [pin & more] (filter #(contains? (:args %) field) (map defs-by-name branch-refs))]
    (when (and (uuid? target-id) (seq parents) (every? target-implementation? parents)
               (= :any (slots/slot-type-of target field defs-by-name))
               (vector? alias-type) (= :fn (first alias-type))
               (:fn-id pin) (empty? more) (= alias-type (get-in pin [:args field :type])))
      (let [rows (parse/parse-prepared-module (assoc context :fn-defs [pin]))
            binding-id (ids/binding-id (:fn-id pin) (ids/slot-id target-id field))
            [binding & extra] (filter #(and (= :binding (:kind %)) (= binding-id (:id %))) rows)]
        (and binding (empty? extra)
             (= alias-type (:type (rich/self-pin pin field rows binding :any))))))))


(defn origin
  "Match the true inherited field, alias, membership and authored rich type.
   A shared storage primitive alone cannot justify a rich field projection."
  [{:keys [name->id defs-by-name] :as context} definition owner records field spec]
  (when (and (seq (:parent-ids owner)) (map? spec) (contains? spec :as)
             (every? #{:as :type :description} (keys spec))
             (or (not (contains? spec :description)) (string? (:description spec)))
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
          pin? (contains? spec :type)
          inherited? (and (not pin?) (not= target-type alias-type)
                          (inherited-callable-pin? context author target key alias-type))]
      (when (and field-id (= field key) (some #{target} (rest (slots/chain-of author defs-by-name)))
                 (format/projection-input-type? alias-type)
                 (or (= 1 (count (:parent-ids owner))) inherited?)
                 (if pin? (and (or (format/configuration-type? (:type spec))
                                   (and (rich/closed-callable-shape? alias-type)
                                        (rich/callable-field? (group-by :id (:fn records)) (:type-fn-id slot) alias-type (::named-type-id context))))
                               (compatible-pin? context target-type alias-type))
                     (or (= target-type alias-type) inherited?))
                 (= field-id (:source-slot-id slot))
                 (= (:type-fn-id slot) (records/resolve-type-ref alias-type name->id))
                 (= (name exposed) (:name slot)) (false? (:required slot)) (nil? (:description slot))
                 (some #(and (= alias-id (:slot-id %)) (= (:id owner) (:fn-id %))
                             (= (ids/fn-slot-id (:id owner) alias-id) (:id %))) (:fn-slot records)))
        (cond-> {:alias exposed :alias-id alias-id :field-id field-id
                 :declaration {:type (if pin? (:type spec) alias-type) :required false}}
          pin? (assoc :pin-id (:type-fn-id slot))
          (contains? spec :description) (assoc :description (:description spec)))))))


(defn- projection-of-slot?
  [row slot]
  (let [[field] (:target-path row)
        owner (:fn-id row)
        exposed (:id slot)]
    (and (= :slot (:kind slot)) (= "projection" (:expression-kind row))
         (= [] (:scope-path row) (:projection-scope-path row))
         (= [exposed] (:projection-path row)) (= [field] (:target-path row))
         (= field (:source-slot-id slot)) (= exposed (ids/slot-id owner (:name slot)))
         (= (:id row) (ids/binding-id owner field)))))


(defn legacy-binding
  "Invert a verified projection only through the authored alias-slot FK.
   All other rows use the existing shallow storage inverse."
  [declarations row]
  (if (= "projection" (:expression-kind row))
    (let [[exposed-name] (:projection-path row)
          slot (some #(when (and (= :slot (:kind %)) (= exposed-name (:id %))) %) declarations)]
      (when-not (projection-of-slot? row slot)
        (throw (ex-info "Projection differs from the legacy rename" {:code :rename-origin-mismatch})))
      (migration/legacy-binding (-> row (assoc :expression-kind nil) (dissoc :projection-path :projection-scope-path))))
    (migration/legacy-binding row)))


(defn preserved-pin?
  "Verify the unchanged pinned rename next to a different scoped binding."
  [snapshot owner {binding-origin :origin :keys [rename policy expression]}]
  (let [{exposed :alias :keys [field-id alias-id pin-id]} rename
        {:keys [binding-id]} binding-origin
        slot (get-in snapshot [:fields alias-id])
        row (get-in snapshot [:bindings binding-id])]
    (and (uuid? pin-id) (uuid? field-id) (simple-keyword? exposed)
         (= owner (:fn-id binding-origin) (:fn-id row))
         (= alias-id (:id slot)) (= (name exposed) (:name slot))
         (= field-id (:source-slot-id slot) (:slot-id binding-origin)) (= binding-id (:id row))
         (= pin-id (:type-fn-id slot) (:type-override-fn-id row))
         (= policy (cond-> {:type-override-fn-id pin-id}
                     (contains? rename :description) (assoc :description (:description rename))))
         (= (:description policy) (:description row))
         (= [:project [exposed]] expression) (projection-of-slot? row slot))))
