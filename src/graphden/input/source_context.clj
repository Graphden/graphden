(ns graphden.input.source-context
  "Frozen declaration context for the offline v1-to-v2 source transition."
  (:require
    [graphden.input.format :as format]
    [graphden.input.legacy-use :as legacy-use]
    [graphden.input.list-positions :as positions]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.migration :as migration]
    [graphden.input.source-annotations :as annotations]
    [graphden.input.source-legacy-readers :as legacy-readers]
    [graphden.input.source-positional-type :as positional-type]
    [graphden.input.source-rename :as rename]
    [graphden.input.source-rich-types :as rich]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as types]))


(defn- reject!
  [code path]
  (throw (ex-info "Cannot resolve structured source context"
                  {:type :input/source-context :code code :path path})))


(defn occupied-reference-alias
  "Resolve an occupied source view only through the verified legacy reader."
  [context definition]
  (when-let [resolve-alias (::reference-alias-witness context)]
    (let [[field & more] (keep (fn [[k v]] (when (and (map? v) (= #{:as :ref} (set (keys v)))) k))
                               (:args definition))]
      (when (and field (empty? more))
        (resolve-alias context definition field)))))


(defn compact-reference
  [definition witness]
  (if witness (update-in definition [:args (:field witness)] dissoc :as) definition))


(defn- group-names-by-id
  [name->id]
  (group-by val (filter (comp uuid? val) name->id)))


(defn- indexed-values
  [source [stored-source indexed]]
  (when (identical? source stored-source)
    indexed))


(defn with-declaration-index
  "Reuse the package's canonical declaration and alias indexes during
   per-definition replay. Check the prepared map identity before reuse."
  [{:keys [defs-by-name name->id] :as context} declarations]
  (let [aliases (or (indexed-values name->id (::alias-index context)) (group-names-by-id name->id))]
    (assoc context ::declaration-index [defs-by-name declarations] ::alias-index [name->id aliases])))


(defn environment
  "Capture the package's resolved dependency index and reuse legacy parses."
  [{:keys [defs-by-name name->id fn-defs] :as context}]
  (let [indexed (indexed-values defs-by-name (::declaration-index context))
        definitions (if indexed fn-defs (concat (vals defs-by-name) fn-defs))]
    {:context (rename/prepare-context context)
     :names-by-id (or (indexed-values name->id (::alias-index context)) (group-names-by-id name->id))
     :definition-by-id (into (or indexed {}) (map (fn [d] [(ids/fn-id (:namespace d) (:name d)) d]))
                             definitions)
     :legacy-reader (memoize (fn [owner] (legacy-use/select-binding (legacy-readers/owner-index context owner) owner)))
     :records-of (memoize (fn [definition]
                            (parse/parse-prepared-module (assoc context :fn-defs [definition]))))}))


(defn- resolved-names
  "Only known origins participate in the replay. Other refs, even to a
   real package function, cannot be introduced by this migration."
  [{:keys [context names-by-id]} module-ns binding-origins]
  (let [aliases (mapcat names-by-id (into #{} (keep :ref-fn-id) binding-origins))]
    (reduce (fn [resolved [ref id]]
              (let [resolved (assoc resolved ref id)
                    bare (keyword (name ref))]
                (if (and (= (or module-ns "") (namespace ref))
                         (= types/ambiguous-name (get-in context [:name->id bare])))
                  (assoc resolved bare id)
                  resolved)))
            {} aliases)))


(defn- scope-entry
  [definition records]
  (let [id (ids/fn-id (:namespace definition) (:name definition))
        rows (group-by :kind records)
        owner (some #(when (= id (:id %)) %) (:fn rows))]
    (when-not owner
      (reject! :missing-owner-declaration []))
    {:definition definition :records rows :owner owner}))


(defn- parent-order
  "Use row identities and declared parent order, visiting a diamond once."
  [{:keys [definition-by-id records-of]} own]
  (loop [pending (conj clojure.lang.PersistentQueue/EMPTY own) seen #{} result []]
    (if-let [entry (peek pending)]
      (let [id (get-in entry [:owner :id])]
        (if (contains? seen id)
          (recur (pop pending) seen result)
          (recur (into (pop pending)
                       (map (fn [parent-id]
                              (if-let [definition (get definition-by-id parent-id)]
                                (scope-entry definition (records-of definition))
                                (reject! :missing-parent-input [:parents]))))
                       (get-in entry [:owner :parent-ids]))
                 (conj seen id) (conj result entry))))
      result)))


(defn- ensure-acyclic!
  [entries]
  (loop [pending (into {} (map (fn [{:keys [owner]}] [(:id owner) (:parent-ids owner)])) entries)]
    (when (seq pending)
      (let [roots (keep (fn [[id parents]] (when-not (some #(contains? pending %) parents) id)) pending)]
        (when-not (seq roots) (reject! :cyclic-parent-input [:parents]))
        (recur (apply dissoc pending roots))))))


(defn- memberships
  [records owner-id]
  (->> (:fn-slot records)
       (filter #(= owner-id (:fn-id %)))
       (sort-by (juxt :position (comp str :id)))
       vec))


(defn- record-node
  [type-id fields]
  {:type (into {} (map (juxt :name :type)) fields)
   :type-id type-id :fields fields})


(declare ^:private fields)


(defn- field
  [records slots specs path rename-sources membership]
  (let [{:keys [id name type-fn-id required description source-slot-id]} (get slots (:slot-id membership))
        key (keyword name)
        field-path (conj path key)
        spec (get specs key)
        type (if (types/type-spec-map? spec) (:type spec) spec)]
    (when-not (and (uuid? id) (contains? specs key) (some? type))
      (reject! :missing-field-declaration field-path))
    (when-not (= source-slot-id (get rename-sources id))
      (reject! :field-projection-required field-path))
    (merge {:id id :name key :required required :description description :declaration spec}
           ;; Named and wrapped shapes retain their rich type. This bridge can
           ;; address inline record children whose rows the declaration emitted.
           (if (and (map? type) (seq type) (not (types/type-spec-map? type)))
             (record-node type-fn-id (fields records type-fn-id type field-path {}))
             {:type type :type-id type-fn-id}))))


(defn- fields
  [records owner-id specs path rename-sources]
  (let [slots (into {} (map (juxt :id identity)) (:slot records))
        members (memberships records owner-id)]
    (when-not (and (= (count specs) (count members)) (every? simple-keyword? (keys specs)))
      (reject! :unsupported-field-declarations path))
    (mapv #(field records slots specs path rename-sources %) members)))


(defn- field-specs
  [{list-type :list :keys [args type refine parent parents] :as definition}]
  (cond
    (or parent parents) (into {} (filter (comp types/own-slot-declaration? val)) args)
    type type
    list-type {:items :sequence}
    refine {:value (:base refine)}
    :else (get definition :input args)))


(defn- referenced-reader?
  [defs definition field-name]
  (some #(slots/resolve-slot-owner-strict % field-name defs #{})
        (slots/ref-targets-of definition defs)))


(defn- native-field-link
  [{:keys [defs-by-name name->id] :as context} definition parent owned [field-name spec]]
  (when (types/own-slot-declaration? spec)
    (let [base-spec (get-in parent [:args field-name])
          owner (ids/fn-id (:namespace definition) (:name definition))
          local (ids/slot-id owner field-name)
          native (ids/slot-id (:fn-id parent) field-name)
          child (get owned local)
          member (get owned (ids/fn-slot-id owner local))]
      (when (and (map? base-spec) (= :slot (:kind child)) (= native (:source-slot-id child))
                 (= (name field-name) (:name child)) (= local (:slot-id member))
                 (= owner (:fn-id member)) (= :fn-slot (:kind member))
                 (= (:type-fn-id child) (types/resolve-type-ref (:type spec) name->id))
                 (not (contains? base-spec :value))
                 (or (= (get base-spec :required true) (get spec :required true))
                     (and (false? (:required base-spec)) (true? (:required spec))
                          (true? (:required child))
                          (= [:null (:type spec)] (get-in defs-by-name [(:type base-spec) :union]))))
                 (rename/compatible-pin? context (:type base-spec) (:type spec))
                 (not (referenced-reader? defs-by-name definition field-name)))
        [local native]))))


(defn native-field-links
  "Match a local input to its same-name argument on the native parent.
   A ref-tree reader, an occupied native argument or a wider declaration
   cannot establish that this field has a single public input."
  [context definition records]
  (when (some :source-slot-id (:slot records))
    (let [parent (get-in context [:defs-by-name (:parent definition)])]
      (when (and (uuid? (:fn-id parent)) (:return-type parent)
                 (not (:parents parent)) (not (:parent parent)) (empty? (:parents definition)))
        (let [owned (into {} (map (juxt :id identity)) (concat (:slot records) (:fn-slot records)))]
          (into {} (keep (partial native-field-link context definition parent owned)) (:args definition)))))))


(defn- checked-native-field-links
  [{:keys [context]} ordered]
  (let [links (into {} (mapcat (fn [{:keys [definition records]}]
                                 (native-field-links context definition records))) ordered)]
    (when (seq links)
      (let [slots (into {} (map (juxt :id identity)) (mapcat #(get-in % [:records :slot]) ordered))
            members (into {} (map (juxt :slot-id identity)) (mapcat #(get-in % [:records :fn-slot]) ordered))]
        (doseq [[local native] links]
          (let [field-name (keyword (:name (get slots local)))
                source (get slots native)
                member (get members native)]
            (when-not (and (= (name field-name) (:name source))
                           (= native (ids/slot-id (:fn-id member) field-name))
                           (= (:id member) (ids/fn-slot-id (:fn-id member) native))
                           (not-any? #(referenced-reader? (:defs-by-name context) (:definition %) field-name) ordered)
                           (not-any? #(= native (:slot-id %)) (mapcat #(get-in % [:records :binding]) ordered)))
              (reject! :ambiguous-inherited-input [:input field-name]))))))
    links))


(defn- declared-input
  [source-environment own binding-origins]
  (let [{:keys [owner]} own
        ordered (mapv (fn [{:keys [definition] :as entry}]
                        (if-let [witness (occupied-reference-alias (:context source-environment) definition)]
                          (let [compact (compact-reference definition witness)]
                            (scope-entry compact ((:records-of source-environment) compact)))
                          entry))
                      (parent-order source-environment own))
        _ (ensure-acyclic! ordered)
        links (checked-native-field-links source-environment ordered)
        hidden (set (vals links))
        input-fields (into [] (mapcat (fn [{:keys [records definition owner]}]
                                        (let [aliases (keep (fn [[k v]] (rename/origin (:context source-environment) definition owner records k v)) (:args definition))
                                              anchors (positions/inputs (:id owner) records (get-in source-environment [:context :name->id]))
                                              specs (into (merge (field-specs definition) (update-vals anchors :declaration))
                                                          (map (juxt :alias :declaration)) aliases)
                                              checked-links (when (not= (:id owner) (get-in own [:owner :id]))
                                                              (get-in source-environment [:context ::forwarded-parent-links (:id owner)]))
                                              sources (into (merge checked-links links) (map (juxt :alias-id :field-id)) aliases)]
                                          (remove (comp hidden :id) (fields records (:id owner) specs [:input] sources)))))
                           ordered)
        members (into [] (mapcat #(memberships (:records %) (get-in % [:owner :id]))) ordered)
        id (:id owner)
        descriptor (migration/input-type-record owner)
        input-id (:id descriptor)
        mapping (migration/identity-mapping {id members} binding-origins)
        occupied (get-in source-environment [::occupied-reference-aliases id])
        mapping (cond-> mapping (seq links) (assoc :native-field-links links)
                        occupied (assoc :occupied-reference-aliases {(:id (:alias-slot occupied)) occupied}))]
    (when (or (not= (count input-fields) (count (set (map :id input-fields))))
              (not= (count input-fields) (count (set (map :name input-fields)))))
      (reject! :ambiguous-inherited-input [:input]))
    {:scope [id []] :input-type-id input-id
     :contract (record-node input-id input-fields)
     :declaration-records (into [(assoc descriptor :kind :fn)]
                                (map #(migration/input-membership-record owner %))
                                (remove (comp hidden :slot-id) members))
     :origin-ledger {:source-owner id :mapping mapping}
     :binding-id (partial migration/binding-origin {:mapping mapping})}))


(defn- original-self-pin
  [source-environment declared id binding-origins]
  (let [original (get-in source-environment [:definition-by-id id])]
    (when-let [[field-name] (rich/self-pin-source original)]
      (let [[binding & more] binding-origins
            target (some #(when (= field-name (:name %)) %) (get-in declared [:contract :fields]))]
        (when (and (empty? more) (= (:id target) (:slot-id binding)))
          (rich/self-pin original field-name ((:records-of source-environment) original) binding (:type target)))))))


(defn declaration-context
  "Resolve replayed source declarations and their inherited field membership.
   Input binding identities come exclusively from the frozen legacy ledger."
  [source-environment definition records binding-origins]
  (let [{:keys [owner] :as entry} (scope-entry definition records)
        id (:id owner)
        original (get-in source-environment [:definition-by-id id])
        grouped (group-by :kind records)
        field-annotations (into {} (keep (fn [field-name]
                                           (when-let [annotation (annotations/annotation (:context source-environment) original records field-name)]
                                             [[field-name] annotation])))
                                (keys (:args original)))
        binding-descriptions (into {}
                                   (keep (fn [[field-name spec]]
                                           (when-let [origin (rename/origin (:context source-environment) original owner
                                                                            grouped field-name spec)]
                                             (when (contains? origin :description)
                                               [[field-name] {:description (:description origin)
                                                              :target-path [(:field-id origin)]
                                                              :binding-id (ids/binding-id id (:field-id origin))}]))))
                                   (:args original))
        common {:fn-id id :declaration-records (into [] (filter #(contains? #{:fn :slot :fn-slot} (:kind %))) records)
                :field-annotations field-annotations
                :named-projection-type-id (get-in source-environment [:context ::rename/named-type-id])
                :binding-descriptions binding-descriptions
                :origin-ledger {:source-owner id}}]
    (if (or (:return-type-fn-id owner) (seq (:parent-ids owner)))
      (let [declared (declared-input source-environment entry binding-origins)
            input-id (:input-type-id declared)]
        (-> (merge common declared)
            (assoc :self-pin (original-self-pin source-environment declared id binding-origins))
            (assoc :name->id (resolved-names source-environment (:namespace definition) binding-origins))
            (assoc :resolve-config-type
                   (fn [type]
                     (let [names (get-in source-environment [:context :name->id])
                           resolved (rebase/rewrite-type-references
                                      type #(parse/normalize-ref % names (:namespace definition)))]
                       (when (seq (types/inline-fn-type-rows-from-form resolved))
                         (reject! :configuration-requires-inline-type-rows [:configure]))
                       {:type resolved :type-override-fn-id (types/resolve-type-ref resolved names)})))
            (update :declaration-records into
                    (map #(if (and (= :fn (:kind %)) (= id (:id %)))
                            (assoc % :input-type-fn-id input-id)
                            %))
                    (:declaration-records common))))
      common)))


(defn has-reference-use?
  "An external input needs a reference that survives removal of the candidate
   binding. Check actual references from the owner and its inherited rows."
  [prepared definition]
  (let [env (environment prepared)
        own (scope-entry definition ((:records-of env) definition))]
    (boolean
      (some (fn [{:keys [records]}]
              (some :ref-fn-id (concat (:binding records) (:binding-list-item records))))
            (parent-order env own)))))


(defn- certified-lineage
  [{:keys [records-of definition-by-id] :as env} fn-id]
  (if-let [definition (definition-by-id fn-id)]
    (parent-order env (scope-entry definition (records-of definition)))
    (reject! :missing-reference-declaration [])))


(defn- source-list-link
  [lineage {:keys [source-list-fragment] :as edge} public-path]
  (let [{:keys [owner-id target-id binding-id builder-id]} source-list-fragment
        origin (some #(when (= owner-id (get-in % [:owner :id])) %) lineage)
        [link & more] (filter #(= binding-id (:id %)) (get-in origin [:records :binding]))]
    (when-not (and (list-signature/source-link? edge) (empty? more)
                   (= owner-id (:fn-id link)) (= target-id (:slot-id link))
                   (true? (:list-append link)) (false? (:value-present link))
                   (nil? (:ref-fn-id link)) (nil? (:value link)))
      (reject! :reference-use-drift public-path))
    ;; The ordinary reader consumes the verified list builder as a ref.
    ;; This is derived context, never a replacement legacy binding row.
    (assoc link :ref-fn-id builder-id)))


(defn- check-list-operand!
  [env prior {:keys [source-list-operand binding-id target-fn-id] :as edge} public-path]
  (let [lineage (certified-lineage env (:origin-fn-id prior))
        link (source-list-link lineage prior public-path)
        origin (some #(when (= (:fn-id link) (get-in % [:owner :id])) %) lineage)
        [item & more] (filter #(= binding-id (:id %)) (get-in origin [:records :binding-list-item]))]
    (when-not (and (list-signature/source-operand? prior edge) (empty? more)
                   (= (:id link) (:binding-id item)) (= (:position source-list-operand) (:position item))
                   (= target-fn-id (:ref-fn-id item)) (nil? (:value item)) (not (:literal item)))
      (reject! :reference-use-drift public-path))))


(defn- scoped-origin
  [env edges {:keys [from origin-fn-id binding-id target-fn-id source-scope-path]} public-path]
  (let [[[root path] field-path] from
        start (- (count path) (count source-scope-path))
        owner (when (<= 0 start) (if (zero? start) root (:target-fn-id (get edges (dec start)))))
        certificates (keep :scope-certificate (get-in env [:inline-proofs origin-fn-id :arguments]))
        [certificate & more] (filter #(= binding-id (get-in % [:row :id])) certificates)
        row (:row certificate)]
    (when-not (and owner (seq source-scope-path) (= source-scope-path (subvec path start)) (uuid? (:snapshot-id certificate))
                   (= (:address certificate) [[origin-fn-id source-scope-path] field-path]) (empty? more)
                   (= origin-fn-id (:fn-id row))
                   (= source-scope-path (:scope-path row) (mapv :binding-id (:edges certificate)))
                   (= field-path (:target-path row)) (= "ref" (:expression-kind row)) (= target-fn-id (:ref-fn-id row)))
      (reject! :reference-use-drift public-path))
    (certified-lineage env owner)))


(defn- legacy-reader-link?
  [{:keys [legacy-reader]} {:keys [origin-fn-id from binding-id target-fn-id source-legacy-slot]} link]
  (when (and (uuid? source-legacy-slot) (= source-legacy-slot (:slot-id link)) (= origin-fn-id (:fn-id link)))
    (let [{:keys [binding stored-slot]} (legacy-reader origin-fn-id)]
      (and (= source-legacy-slot stored-slot) (= binding-id (:id binding) (ids/binding-id origin-fn-id stored-slot))
           (= (second from) (:target-path binding)) (= target-fn-id (:ref-fn-id binding))))))


(defn- check-descendants!
  [env {:keys [edges field-owner public-path row target-type-origin]}]
  (doseq [[prior {:keys [origin-fn-id from target-fn-id binding-id source-list-fragment source-list-operand] :as edge}]
          (partition 2 1 edges)]
    (if source-list-operand
      (check-list-operand! env prior edge public-path)
      (let [scope (if (:source-scope-path edge)
                    (scoped-origin env edges edge public-path)
                    (certified-lineage env (:target-fn-id prior)))]
        (if source-list-fragment
          (source-list-link scope edge public-path)
          (let [origin (some #(when (= origin-fn-id (get-in % [:owner :id])) %) scope)
                link (some #(when (= binding-id (:id %)) %) (get-in origin [:records :binding]))]
            (when-not (and (= (:ref-fn-id link) target-fn-id)
                           (if (:source-legacy-slot edge) (legacy-reader-link? env edge link) (= [(:slot-id link)] (second from))))
              (reject! :reference-use-drift public-path)))))))
  (let [scope (certified-lineage env (:target-fn-id (peek edges)))
        origin (some #(when (= field-owner (get-in % [:owner :id])) %) scope)]
    (when-not (some #(= (:target-path row) [(:slot-id %)]) (get-in origin [:records :fn-slot]))
      (reject! :reference-field-drift public-path))
    (when target-type-origin
      (let [definition (:definition origin)
            actual (positional-type/origin (:context env) definition definition
                                           ((:records-of env) definition) (peek public-path))]
        (when-not (and (= target-type-origin actual) (= (:target-path row) [(:slot-id actual)]))
          (reject! :reference-field-drift public-path))))))


(defn scoped-reference-context
  "Check inherited root links, descendant references and the leaf membership
   against the current prepared package's declaration and binding rows."
  [env definition records args]
  (let [certificates (mapcat #(or (:forwarded-certificates %)
                                  (when-let [primary (:scope-certificate %)] (into [primary] (:scoped-literal-copies %)))) args)]
    (when (seq certificates)
      (let [own (scope-entry definition records)
            lineage (rest (parent-order env own))
            links (into {} (comp (mapcat #(get-in % [:records :binding])) (filter :ref-fn-id) (map (juxt :id identity))) lineage)
            list-links (into {} (keep (fn [certificate]
                                        (let [edge (first (:edges certificate))]
                                          (when (:source-list-fragment edge)
                                            (let [owner (get-in own [:owner :id])
                                                  origins (if (= owner (:origin-fn-id edge))
                                                            (certified-lineage env owner)
                                                            lineage)
                                                  link (source-list-link origins edge (:public-path certificate))]
                                              [(:id link) link]))))) certificates)]
        (run! (partial check-descendants! env) certificates)
        {:scoped-inputs (into {} (map (juxt :public-path identity)) certificates)
         :verified-lexical-edges (into #{} (comp (mapcat (comp rest :edges)) (filter :source-legacy-slot)) certificates)
         :inherited-scoped-refs (merge links list-links)}))))


(defn own-field-declaration?
  "The composed field declaration forms currently supported by the source migrator."
  [spec]
  (and (types/own-slot-declaration? spec)
       (every? #{:type :required :description} (keys spec))))


(defn- restore-field-annotations
  [{:keys [context records-of definition-by-id]} definition source]
  (let [id (ids/fn-id (:namespace definition) (:name definition))
        original (get definition-by-id id)]
    (reduce (fn [source [[field-name :as path] spec]]
              (if (or (not= 1 (count path)) (contains? (:bind definition) field-name))
                source
                (if-let [annotation (when original
                                      (annotations/annotation context original (records-of original) field-name))]
                  (let [normalize #(cond-> % (contains? % :type)
                                           (update :type (fn [type]
                                                           (rebase/rewrite-type-references
                                                             type (fn [ref] (parse/normalize-ref ref (:name->id context) (:namespace definition)))))))]
                    (when-not (= (normalize spec) (normalize (annotations/configuration annotation)))
                      (reject! :unverified-field-annotation [:configure path]))
                    (assoc-in source [:args field-name] (get-in original [:args field-name])))
                  source)))
            source (:configure definition))))


(defn lower-declarations
  "Temporary offline compatibility with the existing declaration lowerer.
   The new syntax reader handles :bind separately. Remove this legacy
   lowering adapter when the v2 package loader replaces the v1 loader."
  [{:keys [context definition-by-id] :as source-environment} definition]
  (let [projections (into (array-map) (keep (fn [[arg form]]
                                              (when (and (simple-keyword? arg) (format/local-projection? form))
                                                [arg (first (second form))])))
                          (when (map? (:bind definition)) (:bind definition)))
        rename-form (fn [arg exposed]
                      (merge {:as exposed} (get-in definition [:configure [arg]])))
        source (dissoc definition :input :bind :configure)
        source (if (contains? definition :input)
                 (let [declaration (:input definition)]
                   (when-not (and (map? declaration)
                                  (or (not (or (:parent source) (:parents source)))
                                      (every? own-field-declaration? (vals declaration))))
                     (reject! :unsupported-input-declaration [:input]))
                   (assoc source :args (into (apply dissoc declaration (vals projections))
                                             (map (fn [[arg exposed]] [arg (rename-form arg exposed)])) projections)))
                 source)
        source (restore-field-annotations source-environment definition source)
        ;; Preserve the anonymous type declaration emitted by the legacy
        ;; owner. An unrelated authored shape cannot supply this evidence.
        original (get definition-by-id (ids/fn-id (:namespace definition) (:name definition)))
        [field-name spec] (rich/self-pin-source original)
        source (if (and (:parent source) field-name
                        (= {[field-name] (select-keys spec [:type])} (:configure definition))
                        (not-any? #(contains? definition %) [:bind :input]))
                 (assoc source :args (:args original))
                 source)
        normalized (rebase/rewrite-references
                     source #(parse/normalize-ref % (:name->id context) (:namespace source)))
        rows (parse/parse-prepared-module (assoc context :fn-defs [normalized]))
        projected (positions/projected-names (:bind definition))
        owner-id (ids/fn-id (:namespace normalized) (:name normalized))
        ids (set (map #(ids/slot-id owner-id %) projected))
        rows (if (seq ids)
               (mapv #(if (and (= :slot (:kind %)) (contains? ids (:id %)))
                        (do (when (:source-slot-id %) (reject! :positional-list-field-mismatch [:input]))
                            (assoc % :source-slot-id nil))
                        %) rows)
               rows)
        grouped (when (seq projections) (group-by :kind rows))
        own (some #(when (= (ids/fn-id (:namespace normalized) (:name normalized)) (:id %)) %) (:fn grouped))]
    (doseq [[arg exposed] projections]
      (let [origin (rename/origin context normalized own grouped arg (rename-form arg exposed))]
        (when-not (and origin (= (get-in definition [:input exposed]) (:declaration origin)))
          (reject! :rename-origin-mismatch [:bind arg]))))
    [normalized rows]))
