(ns graphden.input.source-annotations
  "Exact metadata-only assertions on an existing inherited source field."
  (:require
    [graphden.input.format :as format]
    [graphden.input.source-rename :as rename]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as records]
    [graphden.types.core :as types]))


(def ^:private primitive-ids (ids/primitive-fn-ids))


(defn source-form?
  [field spec]
  (and (simple-keyword? field) (map? spec) (= field (:as spec))
       (every? #{:as :type :description} (keys spec))
       (or (not (contains? spec :type)) (format/configuration-type? (:type spec)))
       (or (not (contains? spec :description)) (string? (:description spec)))))


(defn- generic-parent-alias
  [context definition declaring field slot-id type]
  (let [defs (:defs-by-name context)
        parent (get defs declaring)
        parent-id (:fn-id parent)
        leaf (get primitive-ids type)]
    (when (and leaf (uuid? parent-id) (= parent-id (get-in context [:name->id (:parent definition)]))
               (types/type-var? (slots/rename-source-type declaring field defs)))
      (let [[source & more] (keep (fn [[name spec]] (when (= field (:as spec)) name)) (:args parent))
            records (group-by :kind (parse/parse-prepared-module (assoc context :fn-defs [parent])))
            owner (some #(when (= parent-id (:id %)) %) (:fn records))
            origin (when (and source (empty? more)) (rename/origin context parent owner records source (get-in parent [:args source])))
            variable (get-in origin [:declaration :type])]
        (when (and (= slot-id (:alias-id origin)) (types/type-var? variable))
          {:parent-id parent-id :alias-id slot-id :source-id (:field-id origin) :variable variable})))))


(defn- annotation-origin
  [{:keys [name->id defs-by-name] :as context} definition rows field]
  (let [spec (get-in definition [:args field])]
    (when (source-form? field spec)
      (let [own-ref (slots/own-ref definition defs-by-name)
            prepared (get defs-by-name own-ref)
            value (get-in prepared [:args field])
            [declaring key] (slots/resolve-slot-owner own-ref field defs-by-name value)
            declaring-id (get name->id declaring)
            owner-id (ids/fn-id (:namespace definition) (:name definition))
            slot-id (when (uuid? declaring-id) (ids/slot-id declaring-id key))
            type-id (when (contains? spec :type) (records/resolve-type-ref (:type value) name->id))
            authored-type (when type-id
                            (rebase/rewrite-type-references
                              (:type spec) #(parse/normalize-ref % name->id (:namespace definition))))
            declared-type (when type-id (slots/slot-type-of declaring key defs-by-name))
            generic (when (and slot-id authored-type (nil? declared-type) (= authored-type (:type value)))
                      (generic-parent-alias context prepared declaring key slot-id authored-type))
            id (when slot-id (ids/binding-id owner-id slot-id))
            matches (filter #(and (= :binding (:kind %)) (= id (:id %))) rows)
            expected (cond-> {:kind :binding :id id :fn-id owner-id :slot-id slot-id :value-present false}
                       type-id (assoc :type-override-fn-id type-id)
                       (contains? spec :description) (assoc :description (:description spec)))]
        (when (and (uuid? slot-id) (= field key) (= 1 (count matches))
                   (or (not (contains? spec :type))
                       (and (uuid? type-id)
                            (or generic (= authored-type (:type value) declared-type))))
                   (= expected (into {} (remove (comp nil? val)) (first matches))))
          (cond-> {:declaring-id declaring-id
                   :inherited? (boolean (some #{declaring} (rest (slots/chain-of own-ref defs-by-name))))
                   :target-path [slot-id]
                   :row (-> expected (dissoc :slot-id) (assoc :kind :input-binding
                                                              :scope-path [] :target-path [slot-id]
                                                              :expression-kind nil))}
            generic (assoc :generic-parent-alias generic)
            type-id (assoc :type (:type spec) :type-override-fn-id type-id)
            (contains? spec :description) (assoc :description (:description spec))))))))


(defn annotation
  "Keep the exact empty binding and unchanged type of an inherited field."
  [context definition rows field]
  (when-let [{:keys [inherited?] :as evidence} (annotation-origin context definition rows field)]
    (when inherited? (dissoc evidence :declaring-id :inherited?))))


(defn reference-annotation
  "Identify the exact metadata row of a same-name binding on a referenced
   field. This origin does not certify the route or its unoccupied state."
  [context definition rows field]
  (when-let [{:keys [inherited?] :as evidence} (annotation-origin context definition rows field)]
    (when (and (not inherited?) (not= (:declaring-id evidence) (get-in evidence [:row :fn-id])))
      (dissoc evidence :inherited?))))


(defn configuration
  [evidence]
  (select-keys evidence [:type :description]))


(defn exact?
  "Check the original metadata row and its declared or specialized field type.
   A specialized primitive needs its direct parent's checked generic rename."
  [owner field {:keys [row type target-path type-override-fn-id] :as evidence}]
  (let [id (:id field)
        {:keys [parent-id alias-id source-id variable] :as generic} (:generic-parent-alias evidence)
        specialized? (and generic (uuid? parent-id) (simple-keyword? (:name field))
                          (= [parent-id] (:parent-ids owner)) (uuid? source-id)
                          (= (ids/slot-id parent-id (:name field)) alias-id id) (not= source-id id)
                          (types/type-var? variable) (= variable (:type field))
                          (= (ids/primitive-fn-id :any) (:type-id field))
                          (some? (get primitive-ids type))
                          (= (get primitive-ids type) type-override-fn-id))
        field-type-id (if specialized? type-override-fn-id (:type-id field))
        expected (when (and (uuid? (:id owner)) (uuid? id))
                   (cond-> {:kind :input-binding :id (ids/binding-id (:id owner) id)
                            :fn-id (:id owner) :scope-path [] :target-path [id]
                            :expression-kind nil :value-present false}
                     (contains? evidence :type) (assoc :type-override-fn-id field-type-id)
                     (contains? evidence :description) (assoc :description (:description evidence))))]
    (and (uuid? (:id owner)) (uuid? id)
         (or specialized? (not (contains? evidence :type)) (= type (:type field)))
         (= [id] target-path)
         (= (when (contains? evidence :type) field-type-id) type-override-fn-id)
         (= expected row))))


(defn exact-scope?
  "Check the shape of a previously proved scoped metadata row."
  [owner {:keys [address row legacy-path target-path field-owner type-override-fn-id] :as evidence}]
  (let [owner-id (:id owner)
        [name] legacy-path
        [slot] target-path
        uses (:scope-path row)]
    (and (every? uuid? [owner-id field-owner slot])
         (= [name] legacy-path) (simple-keyword? name)
         (= [slot] target-path) (= slot (ids/slot-id field-owner name))
         (vector? uses) (seq uses) (every? uuid? uses)
         (= [[owner-id uses] target-path] address)
         (= (cond-> {:kind :input-binding :id (ids/binding-id owner-id slot) :fn-id owner-id
                     :scope-path uses :target-path target-path :expression-kind nil :value-present false}
              (:org-id row) (assoc :org-id (:org-id row))
              (contains? evidence :type) (assoc :type-override-fn-id type-override-fn-id)
              (contains? evidence :description) (assoc :description (:description evidence)))
            row)
         (or (not (contains? evidence :type)) (uuid? type-override-fn-id)))))
