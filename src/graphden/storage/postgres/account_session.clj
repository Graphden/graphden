(ns graphden.storage.postgres.account-session
  "Paired physical lookup for the standard accounts schema. The original
   datasource or transaction owns the snapshot and row visibility."
  (:require
    [clojure.string :as str]
    [graphden.accounts.session-read :as session-read]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.util :as pg]))


(def ^:private session-fields
  {:auth_session_id :id :auth_account_id :account-id :auth_kind :kind
   :auth_expires_at :expires-at :auth_last_used_at :last-used-at
   :auth_created_at :created-at :auth_scopes :scopes})


(def ^:private lookup-sql
  "SELECT a.*, s.id AS auth_session_id, s.account_id AS auth_account_id,
          s.kind AS auth_kind, s.expires_at AS auth_expires_at,
          s.last_used_at AS auth_last_used_at, s.created_at AS auth_created_at,
          s.scopes AS auth_scopes
     FROM session s LEFT JOIN account a ON a.id = CASE
       WHEN s.account_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       THEN s.account_id::uuid END
    WHERE s.token_hash = ?")


(defn lookup
  [pool account-schema session-schema token-hash]
  (if (and (seq account-schema)
           (= :text (get-in session-schema [:account-id :type]))
           (= :text (get-in session-schema [:token-hash :type]))
           (every? #(= :text (get-in session-schema [% :type])) [:kind :scopes])
           (every? #(= :int (get-in session-schema [% :type])) [:expires-at :last-used-at :created-at])
           (not-any? #(contains? session-fields (keyword (pg/kw->snake-case %))) (keys account-schema))
           (not (junction/has-ref-many? account-schema))
           (not (junction/has-ref-many? session-schema)))
    (when-let [row (pg/exec-one! pool [lookup-sql token-hash])]
      (let [session (reduce-kv (fn [result column field] (assoc result field (get row column)))
                               {} session-fields)
            id (:account-id session)
            canonical? (when-let [parsed (some-> id parse-uuid)]
                         (= (str parsed) (str/lower-case id)))]
        ;; parse-uuid accepts some noncanonical UUID strings that PostgreSQL
        ;; rejects. Those retain the ordinary lookup with the parsed UUID.
        (cond-> {:session session}
          (or (:id row) canonical?)
          (assoc :account (when (:id row)
                            (codec/row->entity (apply dissoc row (keys session-fields)) account-schema))))))
    ::session-read/unsupported))
