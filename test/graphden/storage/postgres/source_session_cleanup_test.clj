(ns ^:integration graphden.storage.postgres.source-session-cleanup-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.storage.postgres.source-session :as session]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/container-fixture))


(deftest fatal-cancellation-after-marker-acquisition-releases-the-pair
  (let [storage (setup/create-test-storage)
        manager (session/create-manager {:mode :direct :max-sessions 1})
        acquired? (atom false)
        failure (AssertionError. "fatal cancellation")]
    (try
      (with-open [connection (jdbc/get-connection (:pool storage))]
        (let [result
              (try
                (binding [pg/*jdbc-override*
                          {:execute! (fn [ds query opts]
                                       (let [rows (jdbc/execute! ds query opts)]
                                         (when (str/includes? (first query) "pg_try_advisory_lock")
                                           (reset! acquired? true))
                                         rows))}]
                  (session/ensure-marker! manager connection
                                          #(when @acquired? (throw failure))))
                (catch AssertionError error error))
              locks (jdbc/execute-one! connection
                                       ["SELECT count(*) AS n FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'"]
                                       (pg/query-opts))]
          (is (true? @acquired?))
          (is (identical? failure result))
          (is (zero? (:n locks)))
          (is (nil? (session/ensure-marker! manager connection (constantly nil)))
              "A failed physical registration remains retired")))
      (finally
        (session/close-manager! manager)
        (sp/close storage)))))
