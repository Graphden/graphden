(ns graphden.packages.app-base.prefs.impls
  "Impls for the per-user editor preferences (`:ui-pref`, docs/MARKETPLACE.md
   § Preferences). Two thin boundary base-fns: the read is the current
   user's rows as a `{key value}` map, the write an upsert of one key —
   both keyed on the OWNER the tenancy seam reports
   (`tenancy.context/current-user-id`), which is the whole reason they are
   impls and not `:query-entities` / `:create-entity` compositions: the
   owner is stamped + filtered HERE, never taken from the request. Every
   envelope, guard and key-vocabulary check around them is graph
   (`prefs/fns.edn`)."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(defbase ui-prefs-read
  "The current user's preferences as `{key value}` (string keys) — an
   empty map for a user with none."
  []
  (cr/record-effect! :db)
  (let [rows (sp/query-entities (request/require-storage ctx) :ui-pref
                                {:owner-id (tc/current-user-id)})]
    (into {} (map (juxt :key :value) rows))))


(defbase ui-pref-write!
  "Upsert the current user's preference `pref-key` → `pref-value` (any
   JSON value; nil clears the stored document but keeps the row). Returns
   the stored value."
  [pref-key pref-value]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [storage (request/require-storage ctx)
        owner (tc/current-user-id)
        existing (first (sp/query-entities storage :ui-pref
                                           {:owner-id owner :key (str pref-key)}))
        now (java.time.Instant/now)]
    (if existing
      (sp/update-entity storage :ui-pref (:id existing)
                        {:value pref-value :updated-at now})
      (sp/create-entity storage :ui-pref
                        {:owner-id owner
                         :key (str pref-key)
                         :value pref-value
                         :org-id (tc/current-org)
                         :updated-at now}))
    pref-value))


(def impls
  {:ui-prefs-read ui-prefs-read
   ;; taint-propagate: returns the caller's own document back
   :ui-pref-write! {:impl ui-pref-write! :taint-propagate? true}})
