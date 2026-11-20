(ns graphden.input.source-snapshot
  "Offline package-declaration adapter for unique direct legacy references."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.migration :as migration]
    [graphden.input.source :as source]
    [graphden.input.source-scope :as scope]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check :as check]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(def ^:private primitives (ids/primitive-fn-ids))


(defn- reject!
  [code id]
  (throw (ex-info "Cannot freeze source declaration closure"
                  {:type :input/source-snapshot :code code :fn-id id})))


(defn- definition-id
  [definition]
  (ids/fn-id (:namespace definition) (:name definition)))


(defn package-index
  "Index original source forms of the package and its visible dependencies.
   prepared is the existing package preparation, including imported names.
   Call in the marker policy scope of preparation. Duplicate originals are
   ambiguous. Unreachable source need not be lowered."
  [prepared originals]
  {:prepared prepared
   :markers (shapes/markers-snapshot)
   :declarations (into {} (map (juxt definition-id identity)) (vals (:defs-by-name prepared)))
   :originals (group-by definition-id originals)})


(defn- plan-entry
  [{:keys [prepared declarations originals]} id]
  (let [[original & more] (get originals id)
        definition (get declarations id)]
    (when-not original (reject! :missing-original-declaration id))
    (when (seq more) (reject! :ambiguous-original-declaration id))
    (when-not (= id (:fn-id definition)) (reject! :missing-prepared-declaration id))
    (let [context (assoc prepared :fn-defs [definition])]
      (first (:definitions (source/verify-module (source/plan-module [original] context) context))))))


(defn- omit-argument
  "Replan the owner without the one flat binding under examination. The
   prepared package had already resolved names and assigned anonymous IDs.
   Update both lookup paths used by the legacy parser and v2 replay."
  [{:keys [declarations] :as package} owner field]
  (let [omit #(update % :args dissoc field)
        baseline (omit (get declarations owner))]
    (-> package
        (update-in [:originals owner] #(mapv omit %))
        (assoc-in [:declarations owner] baseline)
        (update-in [:prepared :defs-by-name]
                   #(update-vals % (fn [d] (if (= owner (:fn-id d)) baseline d)))))))


(defn- closure
  [package roots]
  (loop [pending (vec roots) entries {}]
    (if-let [id (peek pending)]
      (if (contains? entries id)
        (recur (pop pending) entries)
        (let [entry (plan-entry package id)
              v2 (:v2 entry)
              owner (some #(when (and (= :fn (:kind %)) (= id (:id %))) %) (:declaration-records v2))]
          (when-not (= :authored-source-equivalent (:verification entry))
            (throw (ex-info "Unverified dependency in source closure"
                            {:type :input/source-snapshot :code :unverified-source-declaration
                             :fn-id id :blockers (mapv :code (:blockers entry))})))
          (when-not (and (:input-type-id v2) (empty? (:inline-definitions v2))
                         (<= (count (:parent-ids owner)) 1))
            (reject! :unsupported-scope-declaration id))
          (recur (into (into (pop pending) (:parent-ids owner))
                       (keep :ref-fn-id) (:bindings v2))
                 (assoc entries id (assoc v2 :owner owner)))))
      entries)))


(defn- merge-record
  [records {:keys [kind id] :as row}]
  (let [prior (get-in records [kind id])]
    (when (and prior (not (migration/same-edn? prior row)))
      (reject! :conflicting-declaration-record id))
    (assoc-in records [kind id] row)))


(defn- snapshot
  [entries markers return-bodies]
  (let [{fns :fn fields :slot members :fn-slot bindings :input-binding}
        (reduce merge-record {} (mapcat #(concat (:declaration-records %) (:bindings %)) (vals entries)))
        inputs (into {} (map (fn [{:keys [input-type-id contract]}] [input-type-id (:type contract)])) (vals entries))]
    (context/build-context
      {:fns fns :fields (or fields {}) :bindings (or bindings {})
       :members (merge (zipmap (keys inputs) (repeat [])) (group-by :fn-id (vals members)))
       :type-bodies (merge return-bodies inputs) :aliases {} :markers markers})))


(defn- root-id
  [entries id]
  (loop [id id seen #{}]
    (when (contains? seen id) (reject! :cyclic-inheritance id))
    (if-let [parent (first (get-in entries [id :owner :parent-ids]))]
      (recur parent (conj seen id))
      id)))


(defn- exact-projection-pin?
  [contract bindings {:keys [target-path type type-override-fn-id]}]
  (let [row (some #(when (= target-path (:target-path %)) %) bindings)
        field-by-id (into {} (map (juxt :id identity)) (:fields contract))
        target (get field-by-id (first target-path))
        source (get field-by-id (first (:projection-path row)))]
    (and (= "projection" (:expression-kind row)) (= [] (:scope-path row) (:projection-scope-path row))
         (= [(:id target)] target-path) (= [(:id source)] (:projection-path row))
         (uuid? type-override-fn-id) (= type-override-fn-id (:type-override-fn-id row) (:type-id target) (:type-id source))
         (= type (:type target) (:type source)))))


(defn- named-return-id
  [package {:keys [source owner]}]
  (when-let [declared (:return-type source)]
    (when-not (contains? primitives declared)
      (let [id (:return-type-fn-id owner)
            resolved (get-in package [:declarations (:id owner) :return-type])]
        (when-not (and (uuid? id) (keyword? resolved)
                       (= id (get-in package [:prepared :name->id resolved])))
          (reject! :rich-type-source-required (:id owner)))
        id))))


(defn- primitive-union-body
  "Read the original source and replayed union row behind a named return FK.
   Restrict members to primitives so the body has no further dependencies."
  [package id]
  (let [{:keys [verification v2]} (plan-entry package id)
        branches (get-in v2 [:source :union])
        [row & others] (:declaration-records v2)]
    (when-not (and (= :authored-source-equivalent verification) (= id (:id row))
                   (empty? others) (vector? branches) (seq branches)
                   (every? primitives branches)
                   (= (into [:union] branches) (:constraint row)))
      (reject! :rich-type-source-required id))
    (context/call-with-types {:aliases {} :markers (:markers package)} #(types/make-union branches))))


(defn- check-type-provenance!
  [return-bodies {:keys [source contract owner configurations bindings]}]
  (let [return (:return-type source)]
    ;; A v1 storage primitive may erase an authored rich field or return.
    ;; Only freeze types whose declared forms match their exact row IDs.
    (when-not (and (every? #(exact-projection-pin? contract bindings %) (vals configurations))
                   (or (and (seq (:parent-ids owner)) (nil? return))
                       (and (contains? primitives return)
                            (= (get primitives return) (:return-type-fn-id owner)))
                       (and (keyword? return) (contains? return-bodies (:return-type-fn-id owner))))
                   (every? (fn [{:keys [type type-id declaration]}]
                             (and (or (keyword? declaration)
                                      (and (map? declaration)
                                           (every? #{:type :required :description} (keys declaration))))
                                  (contains? primitives type) (= type-id (get primitives type))))
                           (:fields contract)))
      (reject! :rich-type-source-required (:id owner)))))


(defn- implementation
  [entries roots return-bodies id]
  (let [base (root-id entries id)
        entry (get roots base)
        declaration (get entries base)
        fields (get-in declaration [:contract :fields])
        args (into {} (map (juxt :name :type)) fields)
        return (get return-bodies (get-in declaration [:owner :return-type-fn-id])
                    (:return-type (:source declaration)))]
    (when-not (and (= base (:fn-id entry)) (= (get-in declaration [:source :name]) (:name entry))
                   (= (:namespace declaration) (:namespace entry))
                   (= (set (:effects (:source declaration))) (:effects entry))
                   (= args (:args entry)) (= return (:return entry)))
      (reject! :implementation-signature-required base))
    ;; Custom rules may consult signatures outside parent/ref edges. A closed
    ;; rule registry is a separate prerequisite for running them offline.
    (when (some entry [:return-type-rule :slot-types-rule :nav-types-rule])
      (reject! :rule-signature-view-required base))
    {:base-fn-id base :root-entry entry
     :argument-paths (into {} (map (fn [{:keys [name id]}] [name [id]])) fields)}))


(defn freeze-closure
  "Build the immutable stage-4 context and checker options from the original
   package preparation, verified v2 rows, and frozen rich implementation
   entries keyed by base UUID. Standalone custom type rules require a full
   signature view and are rejected here. Include any ref introduced by an overlay in
   the starting function IDs. A closure or type evidence gap is an error."
  [package function-ids roots]
  (let [entries (closure package function-ids)
        return-ids (into #{} (keep (partial named-return-id package)) (vals entries))
        return-bodies (into {} (map (juxt identity (partial primitive-union-body package))) return-ids)]
    (doseq [entry (vals entries)] (check-type-provenance! return-bodies entry))
    {:context (snapshot entries (:markers package) return-bodies)
     :options {:implementations (into {} (map (fn [id] [id (implementation entries roots return-bodies id)])) (keys entries))
               :check-expression check/check-input-binding
               :infer-signature check/infer-input-signature}}))


(defn verify-binding
  "Certify one flat binding with a single ref-scope blocker in the original
   plan. Walk verified parent/ref declarations by UUID in a private baseline.
   roots are frozen registry entries keyed by base function ID, including
   implementation type rules. The existing signature and directional checkers
   analyze both snapshots. Returns evidence; does not promote a source plan."
  [package owner roots]
  (let [entry (plan-entry package owner)
        [blocker & others] (:blockers entry)
        path (:source-path blocker)]
    (when-not (and (= :ref-scope-required (:code blocker)) (empty? others)
                   (= 2 (count path)) (= :args (first path)))
      (reject! :unsupported-scope-source owner))
    (let [baseline (omit-argument package owner (second path))
          ;; The removed value may itself be a reference. Its declaration
          ;; must already belong to the frozen closure before overlay replay.
          targets (keep :ref-fn-id (filter #(= :binding (:kind %)) (get-in entry [:legacy :records])))
          {:keys [context options]} (freeze-closure baseline (into [owner] targets) roots)]
      (scope/verify-binding entry path context #(analysis/analyze-effective-input %1 %2 options)))))
