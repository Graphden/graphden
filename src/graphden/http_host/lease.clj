(ns graphden.http-host.lease
  "Durable finite HTTP publications in the existing session table.
   This kind never authenticates a login, API or MCP request. Its random row
   UUID addresses an intentionally public HTTP endpoint; token-hash is random
   and no raw authentication token is returned or persisted.

   Callers authorize the graph and supply platform storage. Mutations take
   one short global capacity lock, AFTER any semantic writer/branch locks;
   nothing inside that lock acquires a graph lock or executes tenant code."
  (:require
    [cheshire.core :as json]
    [graphden.accounts.crypto :as crypto]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [next.jdbc :as jdbc]))


(def kind "temporary-http")
(def ttl-ms (* 30 60 1000))
(def global-capacity 64)
(def org-capacity 2)
(def owner-capacity 2)


(def default-limits {:global global-capacity :org org-capacity :owner owner-capacity})


(defn descriptor
  "Decode only this version of the internal lease payload. Malformed rows
   fail closed; strings become UUIDs only after complete shape validation."
  [row]
  (when (= kind (:kind row))
    (try
      (let [{:keys [version org fn-id branch-id owner] :as data}
            (json/parse-string (:scopes row) true)]
        (when (and (= 1 version) (string? org) (string? owner)
                   (= owner (:account-id row))
                   (string? fn-id) (string? branch-id))
          (assoc data :fn-id (java.util.UUID/fromString fn-id)
                 :branch-id (java.util.UUID/fromString branch-id)
                 :id (:id row) :expires-at (:expires-at row))))
      (catch Exception _ nil))))


(defn active?
  "A lease always has a finite server deadline; nil cannot mean forever."
  [row now]
  (and (descriptor row) (integer? (:expires-at row))
       (> (:expires-at row) now)
       (integer? (:created-at row))
       (<= (:expires-at row) (+ (:created-at row) ttl-ms))))


(defn call-with-capacity-lock
  "Serialize lease capacity and adapter route cleanup within one transaction.
   The callback must not acquire graph/branch locks or execute a handler."
  [storage f]
  (when-not (tx/datasource storage)
    (throw (ex-info "Temporary HTTP hosting requires transactional storage"
                    {:type :http-host/storage-required})))
  (tx/in-transaction
    storage
    (fn [st]
      ;; PG advisory RPC, parameterized; shared across ALL executor pods.
      (jdbc/execute! (tx/datasource st)
                     ["SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
                      "graphden|temporary-http-capacity"])
      (f st))))


(defn- rows
  [storage]
  (sp/query-entities storage :session {:kind kind}))


(defn- delete-rows!
  [storage lease-rows]
  (when (seq lease-rows)
    (sp/delete-entities storage :session (mapv :id lease-rows))))


(defn reap!
  "Delete expired/malformed leases only. Called on startup and periodically;
   serving independently checks deadlines, so a delayed sweep grants nothing."
  [storage]
  (call-with-capacity-lock storage
                           (fn [st]
                             (let [now (System/currentTimeMillis)]
                               (delete-rows! st (remove #(active? % now) (rows st)))))))


(defn create!
  "Reserve a server-bounded lease after graph authorization. `owner` is an
   authenticated account UUID or a server-derived token fingerprint, never
   client input. Optional route metadata belongs to the platform adapter.
   `id` is create-only, so a lost response can be cleaned up by exact UUID."
  ([storage data] (create! storage data nil))
  ([storage {:keys [id org owner fn-id branch-id] :as data} limits]
   (let [limits (merge default-limits limits)]
     (when-not (every? #(and (integer? %) (pos? %)) (vals limits))
       (throw (ex-info "Invalid HTTP host capacity configuration" {:type :http-host/config})))
     (when-not (and (uuid? id) (uuid? fn-id) (uuid? branch-id)
                    (string? org) (seq org) (string? owner) (seq owner))
       (throw (ex-info "Invalid HTTP publication" {:type :http-host/invalid})))
     (call-with-capacity-lock
       storage
       (fn [st]
         (let [now (System/currentTimeMillis)
               all (rows st)
               live (filterv #(active? % now) all)
               grants (keep descriptor live)]
           (when (sp/read-entity st :session id)
             (throw (ex-info "Publication identity is unavailable" {:type :http-host/conflict})))
           (when (or (>= (count live) (:global limits))
                     (>= (count (filter #(= org (:org %)) grants)) (:org limits))
                     (>= (count (filter #(= owner (:owner %)) grants)) (:owner limits)))
             (throw (ex-info "Temporary HTTP capacity is full" {:type :http-host/capacity})))
           (delete-rows! st (remove #(active? % now) all))
           (sp/create-entity st :session
                             {:id id :kind kind :label "Temporary HTTP"
                              :account-id owner :created-at now :expires-at (+ now ttl-ms)
                              :token-hash (crypto/sha256-hex (crypto/random-token))
                              :scopes (json/generate-string
                                        (assoc (select-keys data [:org :owner :fn-id :branch-id :route-id :host :origin
                                                                  :api-token? :token-scopes :source-session-id])
                                               :version 1))})))))))


(defn read-active
  "Look up a PUBLIC endpoint identity. Authorization at mint, active-account
   and branch checks at serve are the caller's responsibility."
  [storage id]
  (let [row (sp/read-entity storage :session id)]
    (when (active? row (System/currentTimeMillis)) (descriptor row))))


(defn active-for
  "Current publications for an authenticated owner, or for a fn endpoint
   lookup within an already authorized org/branch. Returned data stays inside
   the platform; callers select the public response fields explicitly."
  [storage criteria]
  (let [now (System/currentTimeMillis)]
    (into [] (comp (filter #(active? % now))
                   (keep descriptor)
                   (filter #(every? (fn [[k v]] (= v (get % k))) criteria)))
          (rows storage))))


(defn revoke!
  "Revoke only the exact lease owned by this authenticated org/principal.
   An unknown, foreign or non-host session yields the same false result."
  [storage org owner id]
  (call-with-capacity-lock storage
                           (fn [st]
                             (let [grant (descriptor (sp/read-entity st :session id))]
                               (when (and grant (= org (:org grant)) (= owner (:owner grant)))
                                 (sp/delete-entity st :session id)
                                 true)))))


(defn revoke-branch!
  "Revoke exact lease rows for a deleted branch; never touch login sessions.
   Run after the branch commit, or under graph→branch→capacity lock order."
  [storage branch-id]
  (call-with-capacity-lock storage
                           (fn [st]
                             (delete-rows! st (filter #(= branch-id (:branch-id (descriptor %))) (rows st))))))
