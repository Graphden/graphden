(ns graphden.crud.package-guard
  "Server-side write guard for PACKAGE-SYNCED fns.

   Package fns (base-fns, fn-defs, primitives shipped in
   `resources/packages/`) are declaratively synced from `fns.edn` on
   every boot — an API write against one is silently reverted by the
   next sync, and until that sync it mutates the behaviour of every
   descendant in the whole installation (2026-08-20 incident: one
   stray sequence-append onto `:add` broke the delete guard,
   `/api/execute` and URI parsing platform-wide). So the editor API
   refuses binding-family writes whose OWNER fn is package-synced,
   and deletes of package fn rows themselves. The legitimate paths
   remain: extend the fn into a child (bindings live on the child),
   or edit the package's `fns.edn`.

   Membership comes from `packages.owned` — the registry the boot
   sync fills with every fn-id it wrote (identity-based per
   ADR-identity-model, and exactly the set the next sync would
   restore). A bare test storage without a package bootstrap has no
   protected fns, so fixture-built graphs stay writable."
  (:require
    [graphden.packages.owned :as owned]
    [graphden.storage.protocol.core :as sp]))


(defn package-owned-fn?
  "True iff the fn row was written by this boot's package sync."
  [_storage fn-id]
  (boolean (and fn-id (owned/owned-fn-id? fn-id))))


(defn- rejection-reason
  [storage fn-id verb]
  (let [nm (:name (sp/read-entity storage :fn fn-id))]
    (str "Fn \"" nm "\" is package-owned (synced declaratively from "
         "resources/packages) — " verb " through the API would change "
         "every descendant in this installation and be reverted by the "
         "next sync. Extend it into a child fn instead, or edit the "
         "package's fns.edn.")))


(defn- owner-fn-id
  "Owner fn-id of a binding-family row: `:binding` / `:fn-slot` carry
   `:fn-id` directly, `:binding-list-item` resolves through its
   binding. nil for other entity-types (no guard applies)."
  [storage entity-type row]
  (case entity-type
    (:binding :fn-slot) (:fn-id row)
    :binding-list-item (some->> (:binding-id row)
                                (sp/read-entity storage :binding)
                                :fn-id)
    nil))


(defn write-rejection
  "Reason string when a create/update of `entity-type` with row data
   `row` (entity-data for creates, the pre-image for updates) targets
   a package-owned fn; nil when the write is fine. `entity-type` is a
   keyword."
  [storage entity-type row]
  (when-let [fid (owner-fn-id storage entity-type row)]
    (when (package-owned-fn? storage fid)
      (rejection-reason storage fid "editing its bindings"))))


(defn delete-rejection
  "Reason string when deleting `row` of `entity-type` would damage a
   package-owned fn — either a binding-family row owned by one, or
   the package fn row itself. nil when the delete is fine."
  [storage entity-type row]
  (case entity-type
    :fn (when (and (:id row) (package-owned-fn? storage (:id row)))
          (rejection-reason storage (:id row) "deleting it"))
    (:binding :fn-slot :binding-list-item)
    (when-let [fid (owner-fn-id storage entity-type row)]
      (when (package-owned-fn? storage fid)
        (rejection-reason storage fid "deleting its bindings")))
    nil))
