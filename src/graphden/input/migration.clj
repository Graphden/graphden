(ns graphden.input.migration
  "Offline v1 snapshot -> additive v2 artifact. No runtime dual writes.
   Unsupported semantic forms are explicit exceptions, never dropped values."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.input.rows :as input-rows]
    [graphden.schema.graph.schema :as graph-schema])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)
    (java.sql
      Timestamp)
    (java.time
      Instant)
    (java.util
      UUID)))


(def snapshot-entities
  [:ns :fn :slot :fn-slot :binding :binding-list-item
   :trait :binding-trait
   :branch :branch-merge :branch-approval :branch-comment
   :fn-version :fn-slot-version :binding-version :binding-list-item-version
   :input-binding :input-binding-version])


(defn stable-edn
  "Complete readable data, independent of a caller's REPL print settings."
  [value]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false *print-namespace-maps* false]
    (pr-str value)))


(defn derived-id
  "Pinned namespace spelling makes generated identities repeatable across hosts."
  [kind & origins]
  (UUID/nameUUIDFromBytes
    (String/.getBytes (stable-edn (into [:graphden/structured-input-v2 kind] origins)) StandardCharsets/UTF_8)))


(defn- canonical
  [value]
  (cond
    (map? value) [:map (vec (sort-by (comp stable-edn first)
                                     (map (fn [[k v]] [(canonical k) (canonical v)]) value)))]
    (set? value) [:set (vec (sort-by stable-edn (map canonical value)))]
    (vector? value) [:vector (mapv canonical value)]
    (sequential? value) [:sequence (mapv canonical value)]
    (instance? Timestamp value) [:timestamp (str (Timestamp/.toInstant value))]
    (instance? Instant value) [:instant (str value)]
    :else value))


(defn fingerprint
  "Ignore SQL's absent-column nils, but preserve nils INSIDE literal values."
  [snapshot]
  (let [rows (into (sorted-map)
                   (map (fn [[entity records]]
                          [entity (sort-by (comp str :id)
                                           (map #(into {} (remove (comp nil? val))
                                                       (if (contains? #{:input-binding :input-binding-version} entity)
                                                         (input-rows/normalize %)
                                                         %)) records))]))
                   snapshot)
        digest-bytes (MessageDigest/.digest (MessageDigest/getInstance "SHA-256")
                                            (String/.getBytes (stable-edn (canonical rows)) StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" (bit-and 255 %)) digest-bytes))))


(defn- closure
  [fns root]
  (loop [pending [root] seen #{}]
    (if-let [id (peek pending)]
      (recur (into (pop pending) (remove seen (:parent-ids (get fns id)))) (conj seen id))
      seen)))


(defn- executable?
  [row]
  (or (seq (:parent-ids row)) (:return-type-fn-id row)))


(defn- issue
  [code entity id]
  {:code code :entity entity :id id})


(defn- exceptions
  [snapshot fns executable-ids memberships]
  (let [bindings (concat (:binding snapshot) (:binding-version snapshot))
        items (set (map :binding-id (concat (:binding-list-item snapshot) (:binding-list-item-version snapshot))))
        fields (into {} (map (juxt :id identity)) (:slot snapshot))]
    (vec
      (distinct
        (concat
          (when (or (seq (:input-binding snapshot))
                    (seq (:input-binding-version snapshot))
                    (some :input-type-fn-id (:fn snapshot)))
            [(issue :already-structured-input :snapshot nil)])
          (for [association (:binding-trait snapshot)]
            (issue :binding-trait-policy :binding (:binding-id association)))
          (for [version (:fn-version snapshot)
                :when (and (:return-type-fn-id version)
                           (not (contains? executable-ids (:fn-id version))))]
            (issue :historical-contract-role :fn (:fn-id version)))
          (for [b bindings
                :when (or (:list-append b) (:list-closed b)
                          (contains? items (or (:binding-id b) (:id b))))]
            (issue :list-builder-required :binding (or (:binding-id b) (:id b))))
          (for [b bindings
                :when (not (contains? executable-ids (:fn-id b)))]
            (issue :non-executable-binding :binding (or (:binding-id b) (:id b))))
          (for [b bindings
                :when (or (and (:value-present b) (:ref-fn-id b))
                          (and (not (:value-present b))
                               (or (some? (:value b)) (some? (:resolver-fn-id b)))))]
            (issue :inconsistent-legacy-expression :binding (or (:binding-id b) (:id b))))
          (for [b bindings
                :let [available (set (map :slot-id (get memberships (:fn-id b))))]
                :when (not (contains? available (:slot-id b)))]
            (issue :cross-reference-input :binding (or (:binding-id b) (:id b))))
          (for [[owner ms] memberships
                m ms
                :when (:source-slot-id (get fields (:slot-id m)))]
            (issue :rename-projection-required :fn owner))
          (for [[owner ms] memberships
                [_ same] (group-by :slot-id ms)
                :when (> (count same) 1)]
            (issue :multiple-membership-origins :fn owner))
          (for [version (:fn-slot-version snapshot)
                :let [origin (some #(when (= (:id %) (:fn-slot-id version)) %) (:fn-slot snapshot))]
                :when (not= (select-keys origin [:fn-id :slot-id])
                            (select-keys version [:fn-id :slot-id]))]
            (issue :historical-membership-move :fn-slot (:fn-slot-id version)))
          (for [b (:binding snapshot)
                :when (not= (:org-id b) (:org-id (get fns (:fn-id b))))]
            (issue :owner-mismatch :binding (:id b))))))))


(defn- descriptor-row
  [owner]
  {:id (derived-id :input-type (:id owner)) :name nil
   :parent-ids [] :org-id (:org-id owner)})


(def ^:private version-stamps
  [:branch-id :change-id :change-revision :created-at :deleted-at :author-id :author-label])


(defn- descriptor-version
  [version]
  (assoc (select-keys version version-stamps)
         :id (derived-id :input-type-version (:id version))
         :fn-id (derived-id :input-type (:fn-id version))))


(defn- membership-copies
  [fns memberships]
  (mapcat (fn [[owner ms]]
            (map (fn [m]
                   (assoc m :id (derived-id :membership owner (:id m))
                          :fn-id (derived-id :input-type owner)
                          :org-id (:org-id (get fns owner)))) ms))
          memberships))


(defn- membership-versions
  [memberships source-versions]
  (let [by-origin (group-by :fn-slot-id source-versions)]
    (mapcat (fn [[owner ms]]
              (mapcat (fn [m]
                        (map (fn [v]
                               (assoc v
                                      :id (derived-id :membership-version owner (:id v))
                                      :fn-slot-id (derived-id :membership owner (:id m))
                                      :fn-id (derived-id :input-type owner)))
                             (get by-origin (:id m)))) ms))
            memberships)))


(defn- path-binding
  [row version?]
  (cond-> (-> row
              (dissoc :slot-id :list-append :list-closed)
              (assoc :scope-path [] :target-path [(:slot-id row)]
                     :expression-kind (cond
                                        (:value-present row) "value"
                                        (:ref-fn-id row) "ref"
                                        :else nil)))
    version? (-> (assoc :input-binding-id (:binding-id row)) (dissoc :binding-id))))


(defn plan
  "Prepare an additive artifact from RAW identity and version rows. Inherited
   memberships retain each original branch/revision; no latest-only rewrite.
   Unsupported cases block apply, while still producing an inventory report."
  [snapshot]
  (let [snapshot (into {} (map (fn [[k records]] [k (vec (sort-by (comp str :id) records))])) snapshot)
        fns (into {} (map (juxt :id identity)) (:fn snapshot))
        executable-ids (into #{} (comp (filter executable?) (map :id)) (:fn snapshot))
        by-owner (group-by :fn-id (:fn-slot snapshot))
        memberships (into (sorted-map) (map (fn [id] [id (vec (mapcat by-owner (sort-by str (closure fns id))))])) executable-ids)
        problems (vec (sort-by stable-edn (exceptions snapshot fns executable-ids memberships)))
        descriptors (mapv #(descriptor-row (get fns %)) (sort-by str executable-ids))
        descriptor-versions (mapv descriptor-version (filter #(contains? executable-ids (:fn-id %)) (:fn-version snapshot)))
        copied-members (vec (membership-copies fns memberships))
        copied-versions (vec (membership-versions memberships (:fn-slot-version snapshot)))
        changes {:fn (mapv #(assoc (get fns %) :input-type-fn-id (derived-id :input-type %))
                           (sort-by str executable-ids))
                 :fn-version (mapv #(assoc % :input-type-fn-id (derived-id :input-type (:fn-id %)))
                                   (filter #(contains? executable-ids (:fn-id %)) (:fn-version snapshot)))}
        additions {:fn descriptors :fn-version descriptor-versions
                   :fn-slot copied-members :fn-slot-version copied-versions
                   :input-binding (mapv #(path-binding % false) (:binding snapshot))
                   :input-binding-version (mapv #(path-binding % true) (:binding-version snapshot))}]
    {:format :graphden/structured-input-storage-v2
     :source-fingerprint (fingerprint snapshot)
     :exceptions problems :changes changes :additions additions
     :mapping {:binding-origins (into {} (map (fn [b] [[[(:fn-id b) []] [(:slot-id b)]] (:id b)]))
                                      (:binding snapshot))
               :input-types (into {} (map (fn [id] [(derived-id :input-type id) id])) executable-ids)
               :memberships (into {} (mapcat (fn [[owner ms]]
                                               (map (fn [m]
                                                      [(derived-id :membership owner (:id m))
                                                       {:id (:id m) :fn-id (:fn-id m)}]) ms)))
                                  memberships)}}))


(defn binding-origin
  "Parser callback lookup. Scope is [owner-fn-id use-path], never a global name."
  [migration-plan scope path]
  (get-in migration-plan [:mapping :binding-origins [scope path]]))


(defn target-snapshot
  "Expected raw rows after apply, used for exact no-op retries and restore."
  [source {:keys [changes additions]}]
  (reduce (fn [snapshot entity]
            (let [all (into {} (map (juxt :id identity)) (get source entity))
                  merged (merge all
                                (into {} (map (juxt :id identity)) (get changes entity))
                                (into {} (map (juxt :id identity)) (get additions entity)))]
              (assoc snapshot entity (vec (sort-by (comp str :id) (vals merged))))))
          source (set/union (set (keys changes)) (set (keys additions)))))


(defn legacy-binding
  "Read projection, never a second writer. New partial paths cannot be
   represented by a legacy slot and fail explicitly instead of flattening."
  [row]
  (when-not (and (empty? (:scope-path row)) (= 1 (count (:target-path row)))
                 (contains? #{nil "value" "ref"} (:expression-kind row))
                 (nil? (:projection-scope-path row)) (nil? (:projection-path row)))
    (throw (ex-info "This v2 binding has no legacy projection"
                    {:type :input/no-legacy-projection})))
  (assoc (select-keys row (conj (set (keys graph-schema/binding-fields)) :id))
         :slot-id (first (:target-path row))))
