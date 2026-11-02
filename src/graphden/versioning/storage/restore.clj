(ns graphden.versioning.storage.restore
  "Preview and atomic restore of one fn's owned versioned graph. Historical
   values stay server-side; the browser receives only redacted differences."
  (:require
    [clojure.set :as set]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.field-diff :as field-diff]
    [graphden.versioning.storage.history :as history])
  (:import
    (java.math
      BigInteger)
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)))


(def ^:private entity-kinds
  [[:fn-slot :fn-slots :slots [:id :fn-id :slot-id]]
   [:binding :bindings :bindings [:id :fn-id :slot-id]]
   [:binding-list-item :list-items :items [:id :binding-id]]])


(defn- rows-by-id
  [rows]
  (into {} (map (juxt :id identity)) rows))


(defn- state-token
  [fn-id branch-id revision {:keys [current current-anchors]}]
  (let [fn-identity [(:current-namespace-id current)
                     (:current-parent-ids current)]
        source (pr-str [fn-id branch-id revision fn-identity current-anchors])
        digest (MessageDigest/.digest
                 (MessageDigest/getInstance "SHA-256")
                 (String/.getBytes source StandardCharsets/UTF_8))]
    (format "%064x" (BigInteger. 1 digest))))


(defn- changed-secret-rows?
  [target current]
  (let [before (rows-by-id (:bindings current))
        after (rows-by-id (:bindings target))
        secret-ids (into #{}
                         (keep (fn [[id row]]
                                 (when (or (:resolver-fn-id row)
                                           (:resolver-fn-id (get before id)))
                                   id)))
                         after)
        secret-ids (into secret-ids
                         (keep (fn [[id row]]
                                 (when (:resolver-fn-id row) id)))
                         before)
        current-items (rows-by-id (:list-items current))
        target-items (rows-by-id (:list-items target))]
    (or (some #(not= (get before %) (get after %)) secret-ids)
        (some (fn [id]
                (let [old (get current-items id)
                      new (get target-items id)]
                  (and (contains? secret-ids (or (:binding-id old)
                                                 (:binding-id new)))
                       (not= old new))))
              (set/union (set (keys current-items))
                         (set (keys target-items)))))))


(defn- plan
  [storage fn-id branch-id revision pair]
  (let [{:keys [target current]} pair
        token (state-token fn-id branch-id revision pair)
        current-revision (apply max 0 (keep last (:current-anchors pair)))
        diff (history/diff-snapshots current target)
        slot-ids (into #{} (keep :slot-id)
                       (filter (set (map :id (:fn-slots target)))
                               (get-in pair [:identities :slots])))
        slot-rows (when (seq slot-ids)
                    (sp/read-entities storage :slot (vec slot-ids)))
        refs (field-diff/ref-ids
               (concat (when-let [row (:fn target)] [row])
                       (:bindings target) (:list-items target)
                       (vals slot-rows)))
        visible-refs (when (seq refs)
                       (sp/read-entities storage :fn (vec refs)))
        conflict (cond
                   (nil? (:fn current)) :history/current-deleted
                   (nil? (:fn target)) :history/target-deleted
                   (> revision current-revision) :history/future-revision
                   (not= (count slot-ids) (count slot-rows)) :history/missing-slot
                   (not= (count refs) (count visible-refs)) :history/missing-reference
                   (changed-secret-rows? target current) :history/secret-change)]
    {:status (cond conflict :conflict
                   (empty? (:entries diff)) :no-op
                   :else :ready)
     :reason conflict
     :fn-id (str fn-id)
     :branch-id (str branch-id)
     :revision revision
     :current-revision current-revision
     :expected-current token
     :changes (if conflict [] (:entries diff))
     :dependency-versions (if conflict [] (:to-dependency-versions diff))}))


(defn preview
  "A bounded redacted plan and a CAS token for the current owned graph."
  [storage fn-id branch-id revision]
  (when-not (= branch-id (vs/current-branch-id storage))
    (throw (ex-info "Restore must target the current branch"
                    {:type :history/branch-mismatch})))
  (history/with-restore-snapshots storage fn-id branch-id revision
                                  #(plan %1 fn-id branch-id revision %2)))


(defn- update-kind!
  [storage kind key identity-key identity-fields target current identities]
  (let [before (rows-by-id (get current key))
        after (rows-by-id (get target key))
        identity-by-id (rows-by-id (get identities identity-key))
        updates (into []
                      (keep (fn [[id row]]
                              (when (and (contains? before id)
                                         (not= row (get before id)))
                                row)))
                      after)
        creates (into []
                      (keep (fn [[id row]]
                              (when-not (contains? before id)
                                (merge (select-keys (get identity-by-id id)
                                                    identity-fields)
                                       row))))
                      after)]
    ;; A batch handles position permutations against the post-update view.
    (when (seq updates)
      (sp/update-entities storage kind updates))
    (doseq [row creates]
      (sp/create-entity storage kind row))))


(defn- remove-kind!
  [storage kind key target current]
  (let [keep-ids (set (map :id (get target key)))]
    (doseq [row (get current key)
            :when (not (contains? keep-ids (:id row)))]
      (sp/delete-entity storage kind (:id row)))))


(defn- write-plan!
  [storage {:keys [target current identities]}]
  (binding [vs/*tombstone-delete?* true]
    (doseq [[kind key] (reverse (map #(subvec % 0 2) entity-kinds))]
      (remove-kind! storage kind key target current)))
  (when (not= (:fn target) (:fn current))
    (sp/update-entity storage :fn (:id (:fn target))
                      (dissoc (:fn target) :id)))
  (doseq [[kind key identity-key identity-fields] entity-kinds]
    (update-kind! storage kind key identity-key identity-fields
                  target current identities)))


(defn apply-restore!
  "Recheck source/read access, dependencies and the exact current-version CAS
   under the graph writer lock, then append all changed rows in one commit."
  ([storage fn-id branch-id revision expected-current]
   (apply-restore! storage fn-id branch-id revision expected-current nil))
  ([storage fn-id branch-id revision expected-current validate!]
   (when-not (= branch-id (vs/current-branch-id storage))
     (throw (ex-info "Restore must target the current branch"
                     {:type :history/branch-mismatch})))
   (writer/with-write [storage :graph]
                      (when-let [pair (history/restore-snapshots-in-transaction
                                        storage fn-id branch-id revision)]
                        (let [proposal (plan storage fn-id branch-id revision pair)]
                          (cond
                            (not= expected-current (:expected-current proposal))
                            (assoc proposal :status :conflict :reason :history/stale-current)

                            (not= :ready (:status proposal)) proposal

                            :else
                            (do (write-plan! storage pair)
                                (let [type-warning? (boolean
                                                      (when validate!
                                                        (validate! storage fn-id)))]
                                  (assoc proposal :status :committed
                                         :type-warning? type-warning?)))))))))
