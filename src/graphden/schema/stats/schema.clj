(ns graphden.schema.stats.schema
  "Usage-stat rollups — the PRE-AGGREGATED observability table (Phase C1).

   One row per `(bucket-start, org-id, fn-id, status)`: how many executions
   of `fn-id` finished with `status` for `org-id` in that clock hour, and the
   summed wall-clock duration. Fed by an atomic SQL upsert-increment at every
   execution's terminal transition (`graphden.crud.fn-execution.stats/bump!`),
   so it grows with DISTINCT (hour × org × fn × status) — never with traffic —
   and never stores args / results / errors: counts and durations only, safe
   to aggregate across orgs without touching tenant data.

   Non-versioned (aggregate-shaped, mutated only by the counter upsert).
   Retention is its own sweep in `system.init.cleanup` (`stats-retention-days`,
   default 90) — the raw `:fn-execution` TTLs (7/30d) don't apply, which is
   the point: trends outlive the audit rows."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private usage-stat-entity-uuid #uuid "56acae4c-2e76-485e-b3a5-59871ae6ac16")
(def ^:private bucket-start-field-uuid #uuid "4d0a3895-e12c-45dd-afe7-7b1b60a099ba")
(def ^:private org-id-field-uuid #uuid "6c3ad513-f718-40eb-b0eb-ff85f16c15e7")
(def ^:private fn-id-field-uuid #uuid "91738e50-da00-4361-aee2-b3e00323bb9b")
(def ^:private status-field-uuid #uuid "4c567598-b7d1-4a85-89aa-339333d66d60")
(def ^:private count-field-uuid #uuid "d86fb6a0-ccf4-4014-8022-97b99d7e2d0d")
(def ^:private duration-ms-sum-field-uuid #uuid "4ea78941-3841-4921-8b61-b4f85a406a01")


(defn extend-builder
  "Add the `:usage-stat` entity — see the ns docstring for the model."
  [builder]
  (-> builder
      (ds/add-entity :usage-stat usage-stat-entity-uuid
                     {;; Hour bucket (UTC, truncated) the counters cover.
                      :bucket-start {:uuid bucket-start-field-uuid
                                     :type :timestamptz
                                     :indexed? true}
                      ;; Org the executions ran AS — \"public\" for platform /
                      ;; service / single-tenant runs (explicit, never NULL, so
                      ;; the unique key needs no NULLS-NOT-DISTINCT care).
                      :org-id {:uuid org-id-field-uuid
                               :type :text
                               :indexed? true}
                      :fn-id {:uuid fn-id-field-uuid
                              :type :uuid
                              :indexed? true}
                      ;; Terminal status the bucket counts — \"succeeded\" /
                      ;; \"failed\" / \"cancelled\" (plain text, like
                      ;; :fn-execution's codec value).
                      :status {:uuid status-field-uuid :type :text}
                      :count {:uuid count-field-uuid :type :int}
                      :duration-ms-sum {:uuid duration-ms-sum-field-uuid
                                        :type :int
                                        :nullable? true}})
      ;; The upsert-increment's conflict target.
      (ds/add-constraint :usage-stat
                         {:type :unique
                          :fields [:bucket-start :org-id :fn-id :status]})))
