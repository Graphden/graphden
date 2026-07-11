(ns graphden.tenancy.grant-schema
  "Storage-backed grants (PLATFORM_PLAN §4.2). Adds a `:grant` entity via
   the `:db/schema` extension seam and a `GrantStore` that reads it, so
   grants live in the database (manageable, persistent) instead of a config
   map. `grant/can?` is unchanged — only the source of grants differs."
  (:require
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.grant :as grant]))


(def ^:private grant-entity-uuid
  #uuid "d7ba261e-9987-47f8-9f6d-b25ecb928857")


(def ^:private grant-subject-field-uuid
  #uuid "7216e834-c669-46d7-a750-fdd9bccf1cf2")


(def ^:private grant-capability-field-uuid
  #uuid "90c94e00-9a07-423c-aee5-454819c4a6bb")


(def ^:private grant-namespace-field-uuid
  #uuid "dfb5a1f7-2481-44e6-98dd-ec5c821f7767")


(def ^:private grant-subject-id-field-uuid
  #uuid "5c8f2a41-6b39-4e7d-8a02-9f1c3b6e0d84")


(defn extend-builder
  "Add the `:grant` entity — `(subject, subject-id, capability, namespace)`.
   `subject-id` is the STABLE authz key (the user's id) that enforcement
   matches on; `subject` (username) is kept for display + the personal-
   namespace path. Capability is plain text (`\"write\"`, not `\":write\"`)
   so the codec round-trips it cleanly; the store keywordizes on read."
  [builder]
  (ds/add-entity builder :grant grant-entity-uuid
                 {:subject {:uuid grant-subject-field-uuid :type :text :indexed? true}
                  ;; nullable for the additive migration (backfilled from
                  ;; `:subject`); indexed for enforcement lookup + delete cascade.
                  ;; :text (the id's string form), not :uuid — written as
                  ;; `(str user-id)` so the column carries both prod uuids and
                  ;; any test-supplied id uniformly.
                  :subject-id {:uuid grant-subject-id-field-uuid
                               :type :text
                               :nullable? true
                               :indexed? true}
                  :capability {:uuid grant-capability-field-uuid :type :text}
                  :namespace {:uuid grant-namespace-field-uuid
                              :type :text
                              :nullable? true}}))


(defrecord StorageBackedGrantStore
  [storage]

  grant/GrantStore

  (grants-for
    [_ subj]
    (mapv (fn [row]
            {:subject-id (:subject-id row)
             ;; stored as text → back to the keyword `grant-allows?` compares
             :capability (keyword (:capability row))
             :namespace (:namespace row)})
          (sp/query-entities storage :grant {:subject-id (:id subj)}))))


(defn storage-grant-store
  "A `GrantStore` reading `:grant` rows from `storage`."
  [storage]
  (->StorageBackedGrantStore storage))
