(ns graphden.editor.root-migration
  "Explicit preview/apply API for a checked personal HOF root proposal."
  (:require
    [graphden.crud.request :as request]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.root-migration-plan :as plan]
    [graphden.storage.tx :as tx]))


(defn- refuse!
  [reason]
  (throw (ex-info "Personal editor root migration is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- with-current-source
  [ctx operation]
  (when-not (tx/datasource (request/require-storage ctx))
    (refuse! :snapshot-storage-unsupported))
  (activation/call-with-current-configuration ctx operation))


(defn preview!
  "Read-only proposal with new identities and current source witnesses."
  [ctx input]
  (when-not (= {} input) (refuse! :invalid-request))
  (let [nonce (random-uuid)]
    (with-current-source
      ctx
      (fn [storage policy]
        (let [{:keys [receipt scope seal]} (plan/proposal storage policy nonce)]
          {:request (activation/wire-value
                      {:nonce nonce :scope (activation/request-scope scope)
                       :expected-proposal seal})
           :receipt (activation/wire-value receipt)})))))


(defn- parse-apply
  [input]
  (when-not (and (map? input)
                 (= #{:nonce :scope :expected-proposal} (set (keys input)))
                 (activation/revision? (:expected-proposal input)))
    (refuse! :invalid-request))
  (let [nonce (if (uuid? (:nonce input)) (:nonce input)
                  (when (string? (:nonce input)) (parse-uuid (:nonce input))))]
    (when-not nonce (refuse! :invalid-request))
    (assoc input :nonce nonce :scope (activation/parse-scope (:scope input)))))


(defn apply!
  "Rebuild preview under current ACL and preference locks, then atomically
   select the new HOF cohort. A stale or replayed proposal writes nothing."
  [ctx input]
  (let [{:keys [nonce scope expected-proposal]} (parse-apply input)
        storage (request/require-storage ctx)
        result (with-current-source
                 ctx
                 (fn [storage policy]
                   (let [proposal (plan/proposal storage policy nonce)]
                     (when-not (and (= scope (activation/request-scope (:scope proposal)))
                                    (= expected-proposal (:seal proposal)))
                       (refuse! :stale-configuration))
                     (plan/commit! storage proposal))))]
    (preparation/publish! ctx storage result)))
