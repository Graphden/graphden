(ns graphden.packages.app-base.prefs.impls
  "Impls for the per-user editor preferences (`:ui-pref`, docs/MARKETPLACE.md
   § Preferences). Native reads return current-owner rows and writes
   upsert one key; graph composition projects rows into a key/value map —
   both keyed on the OWNER the tenancy seam reports
   (`tenancy.context/current-user-id`), which is the whole reason they are
   impls and not `:query-entities` / `:create-entity` compositions: the
   owner is stamped + filtered HERE, never taken from the request. Every
   envelope, guard and key-vocabulary check around them is graph
   (`prefs/fns.edn`)."
  (:require
    [graphden.crud.request :as request]
    [graphden.editor.preference-write :as preference-write]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(defbase current-user-id
  "The current request's user id as text — the accounts principal's
   `:user-id`, or `anonymous` on a deployment without per-user identity
   (`tenancy.context/current-user-id`, the seam)."
  []
  (tc/current-user-id))


(defbase ui-pref-rows
  "Current-owner storage boundary; projection is graph policy."
  []
  (cr/record-effect! :db)
  (sp/query-entities (request/require-storage ctx) :ui-pref
                     {:owner-id (tc/current-user-id)}))


(defbase ui-pref-write!
  "Upsert the current user's preference `pref-key` → `pref-value` (any
   JSON value; nil clears the stored document but keeps the row). Returns
   the stored value."
  [pref-key pref-value]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (let [status (preference-write/write! (request/require-storage ctx) pref-key pref-value false nil)]
    (when-not (= :saved status)
      (throw (ex-info "Preference write was refused" {:type :prefs/write-refused :status status})))
    pref-value))


(defbase ui-pref-store-status
  "Store one current-owner document with optional atomic expected-state.
   Returns a finite status and never leaks the current preference value."
  [pref-key pref-value conditional? expected]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (name (preference-write/write! (request/require-storage ctx) pref-key pref-value conditional? expected)))


(defbase ui-pref-compare-write!
  "Atomically compare presence/value and write the current owner's preference.
   Returns a finite status; never returns the current document."
  [pref-key pref-value expected]
  (cr/record-effect! :db)
  (cr/record-effect! :time)
  (name (preference-write/write! (request/require-storage ctx) pref-key pref-value true expected)))


(def impls
  {:current-user-id current-user-id
   :_ui-pref-rows ui-pref-rows
   ;; taint-propagate: returns the caller's own document back
   :ui-pref-write! {:impl ui-pref-write! :taint-propagate? true}
   :ui-pref-store-status {:impl ui-pref-store-status :taint-propagate? true}
   ;; The comparison status depends on caller-selected content.
   :ui-pref-compare-write! {:impl ui-pref-compare-write! :taint-propagate? true}})
