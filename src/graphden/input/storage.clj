(ns graphden.input.storage
  "Offline edit/undo primitives with an explicit version precondition. Public
   v2 writers stay disabled; later API callers can reuse this atomic boundary."
  (:require
    [graphden.input.access :as access]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [graphden.versioning.storage.uniqueness :as uniqueness]))


(defn lock-bindings!
  "Join the structured binding lock in the caller's write transaction."
  [storage]
  (uniqueness/xact-lock! (tx/datasource storage) ["structured-input-bindings"]))


(defn with-current-binding!
  "Run an operation with the live branch winner under the owned graph writer
   transaction and structured binding lock. The callback receives decorated
   storage and the validated version. Throwing rolls back its writes."
  [storage id expected-version operation]
  (access/assert-write! :input-binding {})
  (when-not (and (vs/versioned-storage? storage) (uuid? (vs/current-branch-id storage))
                 (uuid? id) (uuid? expected-version))
    (throw (ex-info "Input edits require a branch and a version precondition"
                    {:type :input/version-required})))
  (tx/assert-owns-commit! storage)
  ;; The winning version supplies the owner and can differ by branch. Start
  ;; with the global semantic admission; a pre-lock owner read cannot narrow it.
  (writer/with-write [storage :graph]
                     (lock-bindings! storage)
                     (binding [resolution/*branch-chain-cache* (atom {})
                               branch-local/*storage-caches-override* (atom {})]
                       (resolution/call-with-fresh-memos
                         (fn []
                           (let [current (resolution/resolve-version (vs/unwrap storage) :input-binding id
                                                                     (vs/current-branch-id storage))]
                             (when-not (and (= expected-version (:id current))
                                            (not (resolution/tombstone? current)))
                               (throw (ex-info "Input binding changed since it was read"
                                               {:type :input/stale-version})))
                             (operation storage current)))))))


(defn replace-binding!
  [storage id expected-version patch]
  (with-current-binding! storage id expected-version
    (fn [storage _] (sp/update-entity storage :input-binding id patch))))


(defn remove-binding!
  [storage id expected-version]
  (with-current-binding! storage id expected-version
    (fn [storage _]
      (binding [vs/*tombstone-delete?* true]
        (sp/delete-entity storage :input-binding id)))))
