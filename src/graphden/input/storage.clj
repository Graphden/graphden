(ns graphden.input.storage
  "Offline edit/undo primitives with an explicit version precondition. Public
   v2 writers stay disabled; later API callers can reuse this atomic boundary."
  (:require
    [graphden.input.access :as access]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [graphden.versioning.storage.uniqueness :as uniqueness]))


(defn- change!
  [storage id expected-version operation]
  (access/assert-write! :input-binding {})
  (when-not (and (vs/versioned-storage? storage) (uuid? expected-version))
    (throw (ex-info "Input edits require a branch and a version precondition"
                    {:type :input/version-required})))
  (writer/with-write [storage {:entity :input-binding :ids [id]}]
                     (let [base (vs/unwrap storage)
                           _ (uniqueness/xact-lock! (tx/datasource base) ["structured-input-bindings"])
                           current (resolution/resolve-version base :input-binding id (vs/current-branch-id storage))]
                       (when-not (and (= expected-version (:id current))
                                      (not (resolution/tombstone? current)))
                         (throw (ex-info "Input binding changed since it was read"
                                         {:type :input/stale-version})))
                       (operation storage))))


(defn replace-binding!
  [storage id expected-version patch]
  (change! storage id expected-version #(sp/update-entity % :input-binding id patch)))


(defn remove-binding!
  [storage id expected-version]
  (change! storage id expected-version
           #(binding [vs/*tombstone-delete?* true]
              (sp/delete-entity % :input-binding id))))
