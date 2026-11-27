(ns graphden.input.storage
  "Offline edit/undo primitives with an explicit version precondition. Public
   v2 writers stay disabled; later API callers can reuse this atomic boundary."
  (:require
    [graphden.input.access :as access]
    [graphden.input.authority :as authority]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [graphden.versioning.storage.uniqueness :as uniqueness]))


(defn- lock-bindings!
  "Join the structured binding lock in the caller's write transaction."
  [storage]
  (uniqueness/xact-lock! (tx/datasource storage) ["structured-input-bindings"]))


(defn with-binding-transaction!
  "Run a structured binding operation on the decorated writer connection."
  [storage operation]
  (when-not (and (vs/versioned-storage? storage) (uuid? (vs/current-branch-id storage)))
    (throw (ex-info "Input writes require a versioned branch"
                    {:type :input/version-required})))
  (tx/assert-owns-commit! storage)
  ;; The winning version supplies the owner and can differ by branch. Start
  ;; with the global semantic admission; a pre-lock owner read cannot narrow it.
  (writer/with-write [storage :graph]
                     (lock-bindings! storage)
                     (binding [resolution/*branch-chain-cache* (atom {})
                               branch-local/*storage-caches-override* (atom {})]
                       (resolution/call-with-fresh-memos #(operation storage)))))


(defn with-binding-writer!
  [storage operation]
  (access/assert-write! :input-binding {})
  (with-binding-transaction! storage operation))


(defn binding-versions
  "Return winning version IDs only for bindings from the authorized snapshot."
  [storage id-owners]
  (let [base (vs/unwrap storage)
        branch (vs/current-branch-id storage)]
    (if-let [read-ids (:version-ids (authority/operations base))]
      (authority/call base branch {:operation :versions :bindings id-owners}
                      #(read-ids base branch id-owners))
      (resolution/resolve-version-ids base :input-binding (vec (keys id-owners)) branch))))


(defn authorized-call
  [storage operation f]
  (authority/call (vs/unwrap storage) (vs/current-branch-id storage) operation f))


(defn binding-call
  ([storage phase owner id f] (binding-call storage phase owner id nil f))
  ([storage phase owner id proof f]
   (authorized-call storage {:operation :binding :phase phase :owner owner :id id :proof proof} f)))


(defn binding-proof
  "Identify the checked semantic expression and its branch winners for one named write."
  [storage action row binding-version]
  (let [target (when (not= :remove action) (:ref-fn-id row))
        branch (vs/current-branch-id storage)
        target-version (when target (get (resolution/resolve-version-ids (vs/unwrap storage) :fn [target] branch) target))]
    (when (and target (nil? target-version))
      (throw (ex-info "The selected reference is unavailable" {:type :authz/forbidden})))
    {:action action :binding-version binding-version :target-version target-version
     :expression (select-keys row [:expression-kind :value-present :value :ref-fn-id :scope-path :target-path])}))


(defn current-binding!
  "Read the expected winner inside the caller's authorized binding transaction."
  [storage id expected-version]
  (let [current (resolution/resolve-version (vs/unwrap storage) :input-binding id (vs/current-branch-id storage))]
    (when-not (and current (= expected-version (:id current)) (not (resolution/tombstone? current)))
      (throw (ex-info "Input binding changed since it was read" {:type :input/stale-version})))
    current))


(defn with-current-binding!
  "Run an operation with the live branch winner under the owned graph writer
   transaction and structured binding lock. The callback receives decorated
   storage and the validated version. Throwing rolls back its writes."
  [storage id expected-version operation]
  (when-not (and (uuid? id) (uuid? expected-version))
    (throw (ex-info "Input edits require a version precondition"
                    {:type :input/version-required})))
  (with-binding-writer! storage
    (fn [storage] (operation storage (current-binding! storage id expected-version)))))


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
