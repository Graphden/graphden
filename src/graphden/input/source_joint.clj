(ns graphden.input.source-joint
  "Exact ledger boundaries between one owner's forwarding and scoped proofs."
  (:require
    [graphden.input.migration :as migration]
    [graphden.input.source-forward :as forward]
    [graphden.packages.records.ids :as ids]))


(defn- reject!
  []
  (throw (ex-info "Joint source proof differs from its original owner"
                  {:type :input/source-joint :code :joint-source-mismatch})))


(defn groups
  "Require two complete, disjoint groups of direct owner arguments."
  [{:keys [blockers]}]
  (let [by-code (group-by :code blockers)
        paths (mapv :source-path blockers)]
    (when-not (and (= #{:field-projection-required :ref-scope-required} (set (keys by-code)))
                   (= (count paths) (count (set paths)))
                   (every? #(and (= 2 (count %)) (= :args (first %)) (simple-keyword? (second %))) paths))
      (reject!))
    {:fields (mapv (comp second :source-path) (:field-projection-required by-code))
     :paths (mapv :source-path (:ref-scope-required by-code))}))


(defn- without-scoped-source
  [original paths]
  (update (:source original) :args #(apply dissoc % (map second paths))))


(defn check-full-reference-origins!
  "Omitting a scoped reference must not hide another occurrence of a forwarded
   field. Check the structural origins against both the full owner and the owner without scoped bindings."
  [prepared original reduced-prepared baseline-entry]
  (let [{:keys [fields paths]} (groups original)
        field-view (assoc original :blockers (filterv #(= :field-projection-required (:code %)) (:blockers original)))]
    (when-not (and (migration/same-edn? (without-scoped-source original paths) (:source baseline-entry))
                   (migration/same-edn? (mapv #(forward/verify-forwarded-uses prepared field-view %) fields)
                                        (mapv #(forward/verify-forwarded-uses reduced-prepared baseline-entry %) fields)))
      (reject!))))


(defn scope-first-view
  "Return the original scoped arguments and prove which links their omission hides."
  [original reduced]
  (let [{:keys [fields paths]} (groups original)
        scope-paths (set paths)
        known-links (into #{} (map #(vector [:args %] :field-projection-required)) fields)]
    (when-not (and (migration/same-edn? (without-scoped-source original paths) (:source reduced))
                   (every? known-links (map (juxt :source-path :code) (:blockers reduced))))
      (reject!))
    (-> original
        (assoc :blockers (filterv (comp scope-paths :source-path) (:blockers original)))
        (update-in [:legacy :arguments] #(filterv (comp scope-paths :source-path) %)))))


(defn rebase-on-scoped-references
  "Keep every authored reader and insert the checked reference's host path."
  [proofs certificates]
  (let [by-id (into {} (map (juxt (comp :id :row) identity)) certificates)]
    (mapv (fn [{:keys [owner-id root-use-id target-address target-field-id target-fn-id] :as proof}]
            (let [{:keys [row edges]} (get by-id root-use-id)
                  scope-path (:scope-path row)
                  old-path (or (some-> target-address first second) [root-use-id])
                  uses (into scope-path old-path)
                  first-target (:target-fn-id (first edges))]
              (when-not (and (= "ref" (:expression-kind row)) (= owner-id (:fn-id row))
                             (= target-fn-id (:ref-fn-id row))
                             (seq scope-path) (= root-use-id (first old-path)) (uuid? first-target))
                (reject!))
              (assoc proof :nested-reader? true :root-use-id (first uses) :target-fn-id first-target
                     :target-address [[owner-id uses] [target-field-id]]
                     :projection-id (migration/derived-id :forwarded-field owner-id uses target-field-id))))
          proofs)))


(defn- projected-records
  [original fields]
  (let [field-ids (set (map #(ids/slot-id (:identity original) %) fields))
        declared (set (keep #(when (= :slot (:kind %)) (:id %)) (get-in original [:legacy :records])))]
    (when-not (every? declared field-ids) (reject!))
    (mapv #(cond-> % (and (= :slot (:kind %)) (field-ids (:id %))) (dissoc :source-slot-id))
          (get-in original [:legacy :records]))))


(defn- valid-projection?
  [owner field target {:keys [source-names row address nested-reader?]}]
  (let [uses (:scope-path row)]
    (and (uuid? target) (seq uses)
         (= [field] source-names) (= "projection" (:expression-kind row))
         (= owner (:fn-id row)) (= [] (:projection-scope-path row))
         (= [(ids/slot-id owner field)] (:projection-path row))
         (= [target] (:target-path row)) (= [[owner uses] [target]] address)
         (= (migration/derived-id :forwarded-field owner (if nested-reader? uses (first uses)) target)
            (:id row)))))


(defn- projection-certificates
  [projected fields]
  (let [owner (:identity projected)
        by-path (into {} (map (juxt :source-path identity)) (get-in projected [:legacy :arguments]))
        certificates (into []
                           (mapcat (fn [field]
                                     (let [{:keys [source-slot-id scope-certificate forwarded-certificates]}
                                           (get by-path [:args field])
                                           reads (or forwarded-certificates (some-> scope-certificate vector))]
                                       (when-not (and (seq reads)
                                                      (not (and scope-certificate forwarded-certificates))
                                                      (every? #(valid-projection? owner field source-slot-id %) reads))
                                         (reject!))
                                       reads)))
                           fields)]
    (when-not (= (count certificates) (count (set (map (comp :id :row) certificates))))
      (reject!))
    certificates))


(defn- snapshot-row
  [snapshot {:keys [kind id fn-id]}]
  (case kind
    :fn (get-in snapshot [:fns id])
    :slot (get-in snapshot [:fields id])
    :fn-slot (some #(when (= id (:id %)) %) (get-in snapshot [:members fn-id]))
    nil))


(defn scope-view
  "Validate the whole projected declaration before exposing the remaining
   binding group to the existing scope checker. Returns no altered snapshot."
  [original projected snapshot]
  (let [{:keys [fields paths]} (groups original)
        owner (:identity original)
        path-set (set paths)
        field-paths (set (for [field-name fields] [:args field-name]))
        arguments (get-in original [:legacy :arguments])
        omitted (filter #(path-set (:source-path %)) arguments)
        ids (set (map #(get-in % [:origin :binding-id]) omitted))
        certificates (projection-certificates projected fields)
        v2 (:v2 projected)
        rows (into {} (map (juxt :id identity)) (:bindings v2))]
    (when-not (and (= :authored-source-equivalent (:verification projected))
                   (= owner (:identity projected) (:fn-id v2))
                   (= (count paths) (count omitted) (count ids))
                   (every? uuid? ids)
                   (migration/same-edn? (without-scoped-source original paths) (:source projected))
                   (migration/same-edn? (vec (remove #(and (= :binding (:kind %)) (ids (:id %)))
                                                     (projected-records original fields)))
                                        (get-in projected [:legacy :records]))
                   (migration/same-edn? (vec (remove #(path-set (:source-path %)) arguments))
                                        (mapv #(dissoc % :scope-certificate :forwarded-certificates) (get-in projected [:legacy :arguments])))
                   (every? #(migration/same-edn? % (snapshot-row snapshot %)) (:declaration-records v2))
                   (migration/same-edn? (get-in v2 [:contract :type]) (get-in snapshot [:type-bodies (:input-type-id v2)]))
                   (every? (fn [{:keys [row]}]
                             (and (= owner (:fn-id row))
                                  (migration/same-edn? row (get rows (:id row)))
                                  (migration/same-edn? row (get-in snapshot [:bindings (:id row)])))) certificates))
      (reject!))
    {:projections certificates
     :entry (-> original
                (assoc :blockers (filterv #(= :ref-scope-required (:code %)) (:blockers original)))
                (update-in [:legacy :arguments]
                           #(filterv (fn [arg] (not (field-paths (:source-path arg)))) %)))}))


(defn replay-entry
  "Join both checked groups while preserving every original argument and row.
   The caller must still verify this candidate through the complete v2 reader."
  [original projected scoped]
  (let [{:keys [fields paths]} (groups original)
        _ (projection-certificates projected fields)
        field-paths (into #{} (map #(vector :args %)) fields)
        fields-by-path (into {} (comp (filter (comp field-paths :source-path)) (map (juxt :source-path identity)))
                             (get-in projected [:legacy :arguments]))
        scoped-by-path (into {} (map (juxt :source-path identity)) scoped)
        arguments (mapv (fn [{:keys [source-path] :as argument}]
                          (if-let [certificate (get scoped-by-path source-path)]
                            (assoc argument :scope-certificate certificate)
                            (merge argument (select-keys (get fields-by-path source-path)
                                                         [:scope-certificate :forwarded-certificates]))))
                        (get-in original [:legacy :arguments]))]
    (when-not (and (= (set paths) (set (map :source-path scoped))) (= (count paths) (count scoped)))
      (reject!))
    (let [proposed (reduce (fn [proposal {:keys [source-path public-path]}]
                             (when (contains? (:bind proposal) public-path) (reject!))
                             (let [argument (some #(when (= source-path (:source-path %)) %) arguments)]
                               (assoc-in proposal [:bind public-path] (:expression argument))))
                           (:proposed projected) scoped)]
      (-> original
          (assoc :status :supported :blockers [] :proposed proposed)
          (assoc-in [:legacy :records] (projected-records original fields))
          (assoc-in [:legacy :arguments] arguments)))))
