(ns graphden.schema.queue.schema
  "Queue schema — the `:queue-message` entity (docs/SERVICES.md § Queues).

   A Postgres-backed message queue with the standard shape (Oban,
   graphile-worker, River, pgmq all do this): at-least-once delivery, a
   visibility timeout while a consumer holds a message, bounded retries
   with a delay, a dead-letter state, and a NOTIFY wake so consumers
   don't poll hot. One table, one `FOR UPDATE SKIP LOCKED` take. No
   extension — plain SQL over graphden's own datasource, which is why it
   sits next to `:service` / `:fn-execution`: control-plane data,
   NON-versioned, mutates in place, never in versioning's entity-config.

   The base-fns over it (`storage/queue`) are the PG backend of the
   graph-level `:queue-consumer` template; a broker-backed package
   (Kafka, NATS) binds the same template's `:take` / `:ack` / `:nack`
   slots to its own primitives — the graph contract doesn't change.

   States: `pending` (takeable once `available-at` has passed and no
   lock holds it), `dead` (retries exhausted — kept for inspection).
   A successful `:queue-ack` DELETES the row; the table holds work, not
   history."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private queue-message-entity-uuid
  #uuid "8c2f5a7e-4d19-4b6c-9e3a-7f1d0b4c8a25")


(def ^:private qm-queue-field-uuid
  #uuid "2d7b9e4c-6a1f-4e58-b3c7-9f0a5d2e8b16")


(def ^:private qm-payload-field-uuid
  #uuid "f1a4c8d2-3b7e-4c95-a6d1-0e8b5f3a9c72")


(def ^:private qm-org-id-field-uuid
  #uuid "7e3d1b9a-5c2f-4a86-8b4e-1d9c6f0a3e57")


(def ^:private qm-state-field-uuid
  #uuid "a9c5e2f7-1d8b-4f63-9a2c-6b4e0d7f1c38")


(def ^:private qm-attempts-field-uuid
  #uuid "4b8e2c6d-9f1a-4d75-8c3b-2e7a5d0f9b64")


(def ^:private qm-available-at-field-uuid
  #uuid "c6d9f3a1-2e5b-4c87-b9d4-8a1f7e3c5d02")


(def ^:private qm-locked-until-field-uuid
  #uuid "3f7a1d5c-8b2e-4e69-a1c8-5d0b9f4e7a23")


(def ^:private qm-error-field-uuid
  #uuid "d2e8b4f6-7a3c-4b91-8e5d-0c6f1a9b3d47")


(def ^:private qm-created-at-field-uuid
  #uuid "9b4d7f2a-6e1c-4a58-b7f3-3c8e5a0d2f16")


(defn extend-builder
  "Extend a schema builder with the `:queue-message` entity. Chain after
   the graph schema (no graph refs — a message names its queue by text).
   Non-versioned, like `:service`."
  [builder]
  (ds/add-entity builder :queue-message queue-message-entity-uuid
                 {:queue {:uuid qm-queue-field-uuid
                          :type :text
                          :indexed? true}
                  :payload {:uuid qm-payload-field-uuid
                            :type :jsonb}
                  ;; Tenant owner; NULL ≡ platform. Stamped by the org-scoped
                  ;; decorator on the cloud (an org sees its own messages).
                  :org-id {:uuid qm-org-id-field-uuid
                           :type :text
                           :nullable? true}
                  :state {:uuid qm-state-field-uuid
                          :type :text}
                  :attempts {:uuid qm-attempts-field-uuid
                             :type :int}
                  :available-at {:uuid qm-available-at-field-uuid
                                 :type :timestamptz}
                  :locked-until {:uuid qm-locked-until-field-uuid
                                 :type :timestamptz
                                 :nullable? true}
                  :error {:uuid qm-error-field-uuid
                          :type :text
                          :nullable? true}
                  :created-at {:uuid qm-created-at-field-uuid
                               :type :timestamptz}}))
