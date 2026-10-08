(ns graphden.crud.inheritance.command
  (:require [clojure.string :as str]))

(defn- malformed!
  [reason]
  (throw (ex-info reason {:type :validation-error/inheritance-command :reason reason})))

(defn- uuid-value
  [value key]
  (or (when (uuid? value) value)
      (when (string? value) (parse-uuid value))
      (malformed! (str key " must be a UUID"))))

(defn- uuid-vector
  [value key]
  (when-not (and (sequential? value) (<= (count value) 64))
    (malformed! (str key " must be a list of at most 64 UUIDs")))
  (let [ids (mapv #(uuid-value % key) value)]
    (when-not (= (count ids) (count (set ids)))
      (malformed! (str key " contains duplicate UUIDs")))
    ids))

(defn parse-command
  "Normalize the wire command. Action/kind remain strings for graph rendering.
   Proposed identity is server-generated during preview, then echoed by apply."
  [body]
  (when-not (map? body) (malformed! "An inheritance command must be an object"))
  (let [action (:action body)
        kind (or (:kind body) "parent-edge")
        required (case [action kind]
                   ["reparent" "parent-edge"] [:target-fn-id]
                   ["candidates" "parent-edge"] [:target-fn-id :source-fn-id]
                   ["variation" "parent-edge"] [:target-fn-id :source-fn-id :expected-parent-id]
                   ["variation" "own-ref"] [:owner-fn-id :source-fn-id :binding-id :slot-id :expected-old-ref-id]
                   (malformed! "Unknown inheritance action or use-site kind"))
        command (reduce (fn [m key] (assoc m key (uuid-value (get body key) key)))
                        {:action action :kind kind} required)]
    (when (and (= action "variation") (contains? body :proposed-name)
               (not (and (string? (:proposed-name body))
                         (not (str/blank? (:proposed-name body)))
                         (<= (count (:proposed-name body)) 160))))
      (malformed! "proposed-name must be a non-empty name of at most 160 characters"))
    (cond-> command
      (= action "reparent") (assoc :parent-ids (uuid-vector (:parent-ids body) :parent-ids))
      (:proposed-fn-id body) (assoc :proposed-fn-id (uuid-value (:proposed-fn-id body) :proposed-fn-id))
      (contains? body :namespace-id) (assoc :namespace-id (when (:namespace-id body)
                                                          (uuid-value (:namespace-id body) :namespace-id)))
      (contains? body :proposed-name) (assoc :proposed-name (:proposed-name body))
      (contains? body :expected-state) (assoc :expected-state (:expected-state body))
      (contains? body :accepted-orphan-binding-ids)
      (assoc :accepted-orphan-binding-ids (uuid-vector (:accepted-orphan-binding-ids body)
                                                      :accepted-orphan-binding-ids)))))
