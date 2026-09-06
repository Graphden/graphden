(ns graphden.packages.storage.queue.impls
  "Implementations for the storage/queue base functions — the Postgres
   backend of the graph-level queue (docs/SERVICES.md § Queues): one
   `queue_message` table, `FOR UPDATE SKIP LOCKED` for the take, a
   visibility timeout while a consumer holds a message, bounded retries
   with a delay, a dead-letter state, and a NOTIFY (`queue:publish:<name>`)
   that wakes a waiting taker. Each defbase is one statement; the
   consumer LOOP is graph composition in fns.edn."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.sql.pg :as pg]
    [graphden.tenancy.context :as tc]))


(defn- now
  []
  (java.time.Instant/now))


(defn- plus-ms
  [^java.time.Instant t ms]
  (java.time.Instant/.plusMillis t (long (or ms 0))))


(defn- take-hsql
  "UPDATE … RETURNING over the oldest `batch` takeable messages of `queue`:
   pending, due, and not held by a live lock — claimed with
   `FOR UPDATE SKIP LOCKED` so concurrent takers never share a row. The
   claim sets the visibility lock and counts the attempt."
  [queue batch visibility-ms]
  {:update :queue-message
   :set {:locked-until [:+ [:now] [:raw (str "interval '" (long visibility-ms) " milliseconds'")]]
         :attempts [:+ :attempts [:inline 1]]}
   :where [:in :id {:select [:id]
                    :from [:queue-message]
                    :where [:and [:= :queue (str queue)]
                            [:= :state "pending"]
                            [:<= :available-at [:now]]
                            [:or [:= :locked-until nil] [:< :locked-until [:now]]]]
                    :order-by [:available-at :created-at]
                    :limit (long batch)
                    :for [:update :skip-locked]}]
   :returning [:*]})


(defn- decode-rows
  [ctx rows]
  (let [storage (request/require-storage ctx)
        field-specs (sp/current-fields storage :queue-message)]
    (mapv #(codec/row->entity % field-specs) rows)))


(defn- await-publish!
  "Block up to `wait-ms` for a `queue:publish:<queue>` NOTIFY (or return
   at once when the ctx has no listener — tests, a BYO pod). Honors
   Thread.interrupt: the consumer's stopper cuts the wait short."
  [ctx queue wait-ms]
  (if-let [listener (:notify-listener ctx)]
    (let [p (promise)
          cb (pg-notify/register! listener
                                  (fn [ev]
                                    (when (and (= :queue (:kind ev)) (= (str queue) (:id ev)))
                                      (deliver p :woken))))]
      (try
        (deref p (long wait-ms) :timeout)
        (finally (pg-notify/unregister! listener cb))))
    (Thread/sleep (long wait-ms))))


(defbase queue-publish
  "Enqueue `payload` on `queue`, visible after `delay-ms`. Returns the
   message id. Wakes waiting takers through the NOTIFY bus."
  [queue payload delay-ms]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        t (now)
        ;; The publisher's execution, when it runs under one: the
        ;; consumer's handler becomes its child in the trace. Outside a
        ;; persisted run the message opens a trace of its own — the
        ;; handling is still persisted and findable by trace id, it
        ;; just has no parent hop.
        ex cr/*execution*
        row (sp/create-entity storage :queue-message
                              (cond-> {:queue (str queue)
                                       :payload payload
                                       :state "pending"
                                       :attempts 0
                                       :available-at (plus-ms t delay-ms)
                                       :created-at t
                                       :trace-id (or (:trace-id ex) (:id ex) (random-uuid))}
                                (:id ex) (assoc :parent-execution-id (:id ex))))]
    (when-let [emit (:notify-emitter ctx)]
      (emit {:kind :queue :op :publish :id (str queue)}))
    (:id row)))


(defbase queue-take
  "Claim up to `batch` due messages of `queue` for `visibility-ms` (the
   attempt counts). When none is due, wait up to `wait-ms` for a publish
   and try once more — so an idle consumer blocks on the NOTIFY bus
   instead of polling hot. Returns the claimed messages (possibly none)."
  [queue batch visibility-ms wait-ms]
  (cr/record-effect! :db)
  (let [claim (fn [] (decode-rows ctx (pg/pg-query ctx (take-hsql queue batch visibility-ms))))
        first-try (claim)]
    (if (or (seq first-try) (not (pos? (long (or wait-ms 0)))))
      first-try
      (do (await-publish! ctx queue wait-ms)
          (claim)))))


(defbase queue-ack
  "The message was handled — delete it. Returns true when a row was
   deleted (false: already acked, or reclaimed by another taker after
   the visibility timeout and finished there)."
  [message-id]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)]
    (boolean (sp/delete-entity storage :queue-message message-id))))


(defbase queue-nack
  "The handler threw — release the message: retry after `retry-ms` while
   `attempts` < `max-attempts`, else mark it `dead` (kept for inspection,
   with `error`). Returns `:retry` or `:dead` (nil when the row is gone)."
  [message-id error retry-ms max-attempts]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)]
    (when-let [row (sp/read-entity storage :queue-message message-id)]
      (if (>= (long (:attempts row)) (long max-attempts))
        (do (sp/update-entity storage :queue-message message-id
                              {:state "dead" :locked-until nil :error (some-> error str)})
            :dead)
        (do (sp/update-entity storage :queue-message message-id
                              {:locked-until nil
                               :available-at (plus-ms (now) retry-ms)
                               :error (some-> error str)})
            :retry)))))


(defbase queue-extend
  "Renew the visibility lock on a claimed message for another
   `visibility-ms` — the lease heartbeat of a handler that outlives the
   claim. True when the row was still pending (a reclaimed or acked
   message is not extended)."
  [message-id visibility-ms]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)]
    (boolean
      (when-let [row (sp/read-entity storage :queue-message message-id)]
        (when (= "pending" (:state row))
          (sp/update-entity storage :queue-message message-id
                            {:locked-until (plus-ms (now) visibility-ms)})
          true)))))


(defbase queue-requeue
  "Put a dead-lettered message back on its queue: pending, attempts
   reset, takeable now, error cleared. True when the row was dead."
  [message-id]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)]
    (boolean
      (when-let [row (sp/read-entity storage :queue-message message-id)]
        (when (= "dead" (:state row))
          (sp/update-entity storage :queue-message message-id
                            {:state "pending" :attempts 0 :locked-until nil
                             :available-at (now) :error nil})
          (when-let [emit (:notify-emitter ctx)]
            (emit {:kind :queue :op :publish :id (str (:queue row))}))
          true)))))


(defn- org-filter
  "The org-scoped read's WHERE (own + public), for the aggregate paths
   that cannot go through the decorated entity read: under the tenancy
   addon a tenant sees its own rows plus the platform's (NULL org), the
   public org the platform's only; single-tenant self-host sees all."
  []
  (when (tc/tenancy-addon-active?)
    (let [org (tc/current-org)]
      (if (or (nil? org) (= org tc/public-org))
        [:= :org-id nil]
        [:or [:= :org-id org] [:= :org-id nil]]))))


(defbase queue-stats
  "One row per queue — `{:queue :pending :in-flight :dead}` — from a
   single aggregate query (no rows loaded), org-scoped like the entity
   read. `:in-flight` counts claims still under their visibility lock."
  []
  (cr/record-effect! :db)
  (let [pending-due [:and [:= :state "pending"]
                     [:or [:= :locked-until nil] [:< :locked-until [:now]]]]
        in-flight [:and [:= :state "pending"] [:> :locked-until [:now]]]
        cnt (fn [pred] [:count [:case pred 1 :else nil]])]
    (mapv (fn [r]
            {:queue (:queue r)
             :pending (long (or (:pending r) 0))
             :in-flight (long (or (:in_flight r) 0))
             :dead (long (or (:dead r) 0))})
          (pg/pg-query ctx (cond-> {:select [:queue
                                             [(cnt pending-due) :pending]
                                             [(cnt in-flight) :in_flight]
                                             [(cnt [:= :state "dead"]) :dead]]
                                    :from [:queue-message]
                                    :group-by [:queue]
                                    :order-by [:queue]}
                             (org-filter) (assoc :where (org-filter)))))))


(defbase queue-dead-letters
  "The newest `limit` dead letters (org-scoped like the entity read),
   as decoded message rows — what Operate → Queues lists."
  [limit]
  (cr/record-effect! :db)
  (decode-rows ctx (pg/pg-query ctx (cond-> {:select [:*]
                                             :from [:queue-message]
                                             :where [:= :state "dead"]
                                             :order-by [[:created-at :desc]]
                                             :limit (long limit)}
                                      (org-filter) (update :where (fn [w] [:and w (org-filter)]))))))


(def impls
  {:queue-publish queue-publish
   :queue-stats queue-stats
   :queue-dead-letters queue-dead-letters
   :queue-take queue-take
   :queue-ack queue-ack
   :queue-nack queue-nack
   :queue-extend queue-extend
   :queue-requeue queue-requeue})
