(ns ^:integration ^:serial graphden.editor.preference-write-integration-test
  "Preference compare/write is atomic against both old and conditional writers."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.preference-write :as preferences]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- row
  [storage key]
  (first (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key key})))


(defn- put
  [body]
  (let [response (ga/exec-handler :_pref-put-handler
                                  {:request-method :put :uri "/api/prefs/components"
                                   :path-params {:key "components"}
                                   :headers {"content-type" "application/json"}
                                   :body (json/generate-string body)})]
    {:status (:status response) :body (json/parse-string (:body response) true)}))


(deftest optional-http-comparison-keeps-old-put-compatible-and-conflicts-private
  (let [storage (:storage ga/*bootstrap*)
        first-value {:fn-id (str (random-uuid)) :roots {:picker-id (str (random-uuid))}}
        changed-value {:fn-id (str (random-uuid))}]
    (is (= 200 (:status (put {:owner "anonymous" :value first-value}))))
    (let [expected {:present true :value (:value (row storage "components"))}]
      (is (= 200 (:status (put {:owner "anonymous" :value changed-value :expected expected}))))
      (let [stale (put {:owner "anonymous" :value first-value :expected expected})]
        (is (= 409 (:status stale)))
        (is (= "stale-preference" (get-in stale [:body :reason])))
        (is (not (contains? (:body stale) :value)))))
    (doseq [expected [nil {:present "yes" :value changed-value} {:present true}]]
      (let [response (put {:owner "anonymous" :value first-value :expected expected})]
        (is (= 400 (:status response)))
        (is (= "invalid-expected" (get-in response [:body :reason])))))
    (is (= changed-value (:value (row storage "components"))))))


(deftest legacy-put-cannot-reselect-after-an-ordinary-editor-exists
  (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
        home-name (str "pref-profile-" (random-uuid))
        value {:fn-id (str (random-uuid))}]
    (sp/create-entity storage :ns {:name home-name})
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (let [created (preparation/prepare! ctx {:create true})
            current (row storage "components")
            expected {:present (boolean current) :value (:value current)}]
        (is (true? (:created created)))
        (doseq [body [{:owner "anonymous" :value value}
                      {:owner "anonymous" :value value :expected expected}]]
          (let [response (put body)]
            (is (= 409 (:status response)))
            (is (= "stale-preference" (get-in response [:body :reason])))
            (is (not (contains? (:body response) :value)))))
        (is (= current (row storage "components")))
        (is (= "editor" (:name (sp/read-entity storage :fn (:id created)))))))))


(deftest json-roundtrip-and-row-presence-are-compared-without-returning-the-current-document
  (let [storage (:storage ga/*bootstrap*)
        key (str "compare-" (random-uuid))
        value {:fn-id (str (random-uuid)) :roots {:picker-id (str (random-uuid))}}]
    (is (= :saved (preferences/write! storage key value true {:present false :value nil})))
    (let [expected (json/parse-string (json/generate-string {:present true :value (:value (row storage key))}) true)]
      (is (= :saved (preferences/write! storage key nil true expected)))
      (is (= :stale (preferences/write! storage key value true {:present false :value nil})))
      (is (= :saved (preferences/write! storage key value true {:present true :value nil}))))))


(deftest two-absent-row-writers-have-one-successful-comparison
  (let [storage (:storage ga/*bootstrap*)
        key (str "absent-" (random-uuid))
        start (promise)
        writers (mapv (fn [n] (future @start (preferences/write! storage key {:choice n} true {:present false :value nil}))) [1 2])]
    (deliver start true)
    (is (= #{:saved :stale} (set (map #(deref % 10000 ::timeout) writers))))
    (is (= 1 (count (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key key}))))))


(deftest old-writer-and-conditional-writer-use-the-same-transaction-lock
  (let [storage (:storage ga/*bootstrap*)
        key (str "mixed-" (random-uuid))
        original pg/exec!
        acquired (promise)
        release (promise)
        waiting (promise)
        conditional (atom nil)
        first-lock? (atom true)]
    (is (= :saved (preferences/write! storage key {:choice "before"} false nil)))
    (binding [pg/*jdbc-override*
              {:execute! (fn [connection sql opts]
                           (let [result (binding [pg/*jdbc-override* nil] (original connection sql opts))]
                             (when (re-find #"pg_advisory_xact_lock" (first sql))
                               (is (identical? connection (:connection tx/*transaction-context*)))
                               (is (= {} opts))
                               (when (compare-and-set! first-lock? true false)
                                 (deliver acquired true)
                                 (deref release 10000 ::timeout)))
                             result))}]
      (let [old (future (preferences/write! storage key {:choice "old-writer"} false nil))]
        (is (true? (deref acquired 10000 ::timeout)))
        (reset! conditional (future (deliver waiting true)
                                    (preferences/write! storage key {:choice "conditional"} true
                                                        {:present true :value {:choice "before"}})))
        (is (true? (deref waiting 10000 ::timeout)))
        (deliver release true)
        (is (= :saved (deref old 10000 ::timeout)))
        (is (= :stale (deref @conditional 10000 ::timeout)))
        (is (= {:choice "old-writer"} (:value (row storage key))))))))


(deftest a-rolled-back-preference-write-does-not-consume-the-comparison
  (let [storage (:storage ga/*bootstrap*)
        key (str "rollback-" (random-uuid))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rollback proof"
          (tx/in-transaction storage
                             (fn [storage]
                               (is (= :saved (preferences/write! storage key {:choice "rolled-back"} true
                                                                 {:present false :value nil})))
                               (throw (ex-info "rollback proof" {}))))))
    (is (nil? (row storage key)))
    (is (= :saved (preferences/write! storage key {:choice "committed"} true {:present false :value nil})))))
