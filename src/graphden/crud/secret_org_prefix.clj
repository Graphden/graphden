(ns graphden.crud.secret-org-prefix
  "Boot migration: move tenant secrets created before the per-org vault
   prefix (docs/SECRETS.md § Per-org vault paths) under `org/<org-id>/`.

   The vault client refuses a tenant-context op on a path outside the
   org's prefix, so a secret binding a tenant made at a bare path
   (`db/password`) would 403 for that tenant from the release on. For
   every `:vault-get` resolver binding — identity row AND per-branch
   version rows — whose owning org is a tenant and whose path is bare,
   this copies the vault value (every live KV v2 version, in order, plus
   `custom_metadata`) to `org/<org-id>/<path>`, deletes the bare path,
   and re-points the rows.

   Crash-safety comes from the ORDER and a marker, not a transaction (the
   vault is outside graphden's):

   1. copy — the marker `graphden-migrated-from = <bare path>` lands in
      the target's `custom_metadata` LAST, so a marked target is a
      complete copy;
   2. delete the bare path — only once every binding row referencing it
      is a tenant row whose copy is marked (a platform binding, or a
      conflicting target, keeps it);
   3. re-point the rows.

   A re-run after a crash anywhere finishes the job: a marked target
   skips the copy, a gone source skips the delete, the rows still read
   the bare path until step 3 lands. An unmarked target nobody references
   is a half-finished copy — it is wiped and copied again; an unmarked
   target a binding DOES reference is a tenant's own secret, so that
   path is left alone and reported as a conflict for an operator.

   Runs on the RAW base storage (every org, every branch) with the
   platform token, before the compiled registry captures the paths.
   Platform-tier bindings (NULL / `public` org) are never touched."
  (:require
    [clojure.tools.logging :as log]
    [graphden.clients.vault :as vault]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]))


(def ^:private marker
  "`custom_metadata` key on a migrated target naming the bare path it
   was copied from (the client keywordizes metadata keys on read)."
  :graphden-migrated-from)


(defn- vault-get-id
  "Row id of the `:vault-get` base-fn (the secret resolver), nil when the
   vault package was never synced — then no secret binding exists."
  [storage]
  (:id (first (filter :return-type-fn-id
                      (sp/query-entities storage :fn {:name "vault-get"})))))


(defn- secret-rows
  "Every `:vault-get` resolver binding row — identity rows and version
   rows — as `{:entity :id :value :org}`; `:org` is the identity row's
   `:org-id` (version rows carry none of their own)."
  [storage vg-id]
  (let [idents (sp/query-entities storage :binding {:resolver-fn-id vg-id})
        versions (sp/query-entities storage :binding-version {:resolver-fn-id vg-id})
        missing (remove (set (map :id idents)) (distinct (map :binding-id versions)))
        org-of (into {}
                     (map (juxt :id :org-id))
                     (concat idents
                             (when (seq missing)
                               (sp/query-entities storage :binding {:id (vec missing)}))))]
    (into []
          (comp (filter (comp string? :value))
                (map (fn [{:keys [id value binding-id org-id]}]
                       {:entity (if binding-id :binding-version :binding)
                        :id id
                        :value value
                        :org (if binding-id (org-of binding-id) org-id)})))
          (concat idents versions))))


(defn- plan-row
  "`row` + `:source` (its path, normalised) and, for a tenant row stored
   outside its org prefix, `:target`. A malformed path gets `:invalid`."
  [{:keys [value org] :as row}]
  (try
    (let [source (tctx/with-org tctx/public-org (vault/scoped-path value))
          target (when-not (tctx/platform-tier? org)
                   (tctx/with-org org (vault/scoped-path value)))]
      (cond-> (assoc row :source source)
        (and target (not= target value)) (assoc :target target)))
    (catch clojure.lang.ExceptionInfo e
      (log/warn "secret binding path is not a valid vault path — left as is"
                {:entity (:entity row) :id (:id row) :type (:type (ex-data e))})
      (assoc row :invalid true))))


(defn- metadata-or-nil
  "KV v2 metadata at `path`, nil when the path does not exist."
  [client path]
  (try (vault/get-metadata client path)
       (catch clojure.lang.ExceptionInfo e
         (when-not (= 404 (:status (ex-data e))) (throw e)))))


(defn- live-versions
  "The readable version numbers of a path's metadata, oldest first
   (soft-deleted and destroyed versions have no value to copy)."
  [path-meta]
  (->> (:versions path-meta)
       (keep (fn [[n {:keys [deletion_time destroyed]}]]
               (when (and (empty? deletion_time) (not destroyed))
                 (parse-long (name n)))))
       sort))


(defn- copy!
  "Copy every live version of `source` to `target`, then stamp the
   target's metadata (the source's, plus the marker) — the marker last."
  [client source target source-meta]
  (doseq [n (live-versions source-meta)]
    (vault/put-secret client target (vault/get-secret client source n)))
  (vault/put-metadata client target
                      (assoc (:custom_metadata source-meta) marker source)))


(defn- ensure-copied!
  "Make `target` a complete copy of `source`. Returns `:copied` (a marked
   copy now exists), `:missing` (nothing at either path — the binding was
   already broken; re-pointing it loses nothing) or `:conflict` (an
   unmarked `target` that a binding references — someone else's secret)."
  [client source target referenced?]
  (let [target-meta (metadata-or-nil client target)]
    (cond
      (= source (get-in target-meta [:custom_metadata marker])) :copied
      (and target-meta (referenced? target)) :conflict
      :else
      (if-let [source-meta (metadata-or-nil client source)]
        (do (when target-meta (vault/delete-secret client target))
            (copy! client source target source-meta)
            :copied)
        :missing))))


(defn- migrate-source!
  "Migrate every legacy row reading bare path `source` (steps 1–3 of the
   ns doc). `rows` are all secret rows on `source`; a row whose target IS
   `source` (an already-prefixed path spelled with a leading `/`) is only
   re-spelled. Returns `{:rows n :conflicts n}`."
  [storage client source rows referenced?]
  (let [targets (disj (set (keep :target rows)) source)
        outcome (into {source :copied}
                      (map (fn [target] [target (ensure-copied! client source target referenced?)]))
                      targets)
        movable (filter #(#{:copied :missing} (outcome (:target %))) rows)
        conflicts (count (filter #{:conflict} (vals outcome)))]
    (when (and (seq targets)
               (every? :target rows)
               (= (count movable) (count rows))
               (not-any? #(= source (:target %)) rows)
               (metadata-or-nil client source))
      (vault/delete-secret client source))
    (doseq [{:keys [entity id target]} movable]
      (sp/update-entity storage entity id {:value target}))
    (doseq [[target o] outcome :when (= o :conflict)]
      (log/error "tenant secret NOT migrated — the org-prefixed path is already bound to another secret"
                 {:source source :target target}))
    {:rows (count movable) :conflicts conflicts}))


(defn migrate!
  "Run the migration over raw `base-storage` with vault `client`.
   Idempotent — a run with nothing left to move writes nothing. Returns
   `{:rows n :paths n :conflicts n :failed n}` (`:paths` = bare vault
   paths handled; a path whose vault calls throw is `:failed` and retried
   on the next boot)."
  [base-storage client]
  (let [zero {:rows 0 :paths 0 :conflicts 0 :failed 0}]
    (if-let [vg-id (vault-get-id base-storage)]
      (let [rows (into [] (comp (map plan-row) (remove :invalid))
                       (secret-rows base-storage vg-id))
            referenced (set (map :source rows))
            by-source (group-by :source rows)]
        (reduce
          (fn [acc [source src-rows]]
            (try
              (let [{:keys [rows conflicts]}
                    (migrate-source! base-storage client source src-rows referenced)]
                (-> acc (update :rows + rows) (update :paths inc)
                    (update :conflicts + conflicts)))
              (catch Exception e
                (log/error e "tenant secret migration failed for one path — retried next boot"
                           {:source source})
                (update acc :failed inc))))
          zero
          (filter (fn [[_ rs]] (some :target rs)) by-source)))
      zero)))
