(ns graphden.crud.views
  "Atomic Save view, with validation under the writer guard and publication
   only after the owned transaction commits."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.entities :as entities]
    [graphden.crud.request :as request]
    [graphden.crud.views.command :as command]
    [graphden.crud.views.write :as write]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.tx :as tx]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.storage.resolution :as resolution]
    [graphden.web.errors :as errors]))


(def parse-command command/parse-command)


(defn- transact!
  [ctx storage command]
  (tx/assert-owns-commit! storage)
  (write/assert-create-id-available! storage (:create-id command))
  (let [proposed (cond-> (select-keys command [:id :namespace-id])
                   (:create-id command) (assoc :id (:create-id command)))
        mutation (cond-> {:entity :fn :rows [proposed]}
                   (:id command) (assoc :ids [(:id command)]))]
    (writer/with-write [storage mutation]
                       (resolution/call-with-fresh-memos
                         #(write/apply-command! (assoc ctx :storage storage) command)))))


(defn- publish!
  [ctx storage {:keys [view publication-rows]}]
  (let [warnings (into []
                       (keep (fn [row]
                               (try (entities/publish-write! ctx storage :fn row)
                                    nil
                                    (catch Exception error
                                      (log/warn error "View saved; derived state needs refresh" {:fn-id (:id row)})
                                      {:fn-id (:id row) :reason "The view committed; refresh to reload derived state"}))))
                       publication-rows)]
    (cond-> {:ok true :committed true :view view}
      (seq warnings) (assoc :publication-warnings warnings))))


(defn save!
  [ctx command]
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            outcome (try {:result (transact! ctx storage command)}
                         (catch clojure.lang.ExceptionInfo error
                           {:error (if (and (:create-id command)
                                            (= :unique-violation (:type (ex-data error))))
                                     (ex-info "The proposed view identity is unavailable"
                                              {:type :constraint-violation/unique})
                                     error)}))]
        (if-let [error (:error outcome)]
          (let [body (errors/safe-error-body (:type (ex-data error)) (ex-message error))]
            (when (:ref body) (log/error error "Save view failed" {:ref (:ref body)}))
            (assoc body :committed false :http-status (errors/status-for-ex-data (ex-data error))))
          (publish! ctx storage (:result outcome)))))))
