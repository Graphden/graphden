(ns graphden.accounts.session-read-parity-test
  "Public authentication parity for opt-in paired reads and ordinary decorators."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.accounts.core :as accounts]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.session-read :as session-read]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.storage-double :as storage-double]))


(defrecord ObservedStorage
  [delegate events]

  sp/StorageCRUD

  (read-entity
    [this entity id]
    (swap! events conj [:read entity id])
    (when-not (and (:hide-account? this) (= :account entity))
      (sp/read-entity delegate entity id)))


  (query-entities
    [_ entity where]
    (swap! events conj [:query entity where])
    (sp/query-entities delegate entity where))


  (query-entities
    [_ entity where opts]
    (swap! events conj [:query entity where opts])
    (sp/query-entities delegate entity where opts))


  (update-entity
    [_ entity id data]
    (swap! events conj [:update entity id data])
    data)


  (create-entity [_ entity data] (sp/create-entity delegate entity data))


  (delete-entity [_ entity id] (sp/delete-entity delegate entity id))


  (query-latest-per-group
    [_ entity where groups]
    (sp/query-latest-per-group delegate entity where groups)))


(defrecord PairedStorage
  [delegate result events]

  session-read/SessionAccountRead

  (session-and-account
    [_ token-hash]
    (swap! events conj [:paired token-hash])
    result)


  sp/StorageCRUD

  (read-entity [_ entity id] (sp/read-entity delegate entity id))


  (query-entities [_ entity where] (sp/query-entities delegate entity where))


  (query-entities [_ entity where opts] (sp/query-entities delegate entity where opts))


  (update-entity [_ entity id data] (sp/update-entity delegate entity id data))


  (create-entity [_ entity data] (sp/create-entity delegate entity data))


  (delete-entity [_ entity id] (sp/delete-entity delegate entity id))


  (query-latest-per-group
    [_ entity where groups]
    (sp/query-latest-per-group delegate entity where groups)))


(defn- fixture
  [session-changes account-changes]
  (let [token (crypto/random-token)
        account (merge {:id (random-uuid) :status "active" :display-name "Parity account"
                        :primary-email "parity@example.com" :totp-enabled? true}
                       account-changes)
        session (merge {:id (random-uuid) :account-id (str (:id account))
                        :token-hash (crypto/sha256-hex token)
                        :created-at (System/currentTimeMillis)}
                       session-changes)
        events (atom [])
        ordinary (->ObservedStorage
                   (storage-double/rows-storage {:account {(:id account) account}
                                                 :session {(:id session) session}})
                   events)]
    {:token token :account account :session session :events events :ordinary ordinary
     :paired (->PairedStorage ordinary {:session session :account account} events)}))


(deftest paired-authentication-preserves-account-and-scope-ceiling-test
  (doseq [[kind scopes ceiling] [[nil nil nil]
                                 ["api" "  read execute read  " #{:read :execute}]
                                 ["api" "   " nil]]]
    (testing (str "kind " kind " and scopes " (pr-str scopes))
      (let [{:keys [token account session events ordinary paired]}
            (fixture {:kind kind :scopes scopes} {})
            expected (assoc account :token-kind kind :token-scopes ceiling)]
        (is (= expected (accounts/authenticate-token ordinary token)))
        (is (= [[:query :session {:token-hash (:token-hash session)}]
                [:read :account (:id account)]]
               @events))
        (reset! events [])
        (is (= expected (accounts/authenticate-token paired token)))
        (is (= [[:paired (:token-hash session)]] @events))))))


(deftest paired-authentication-retains-rejections-test
  (doseq [[label session-changes account-changes]
          [["preview capsule" {:kind "preview"} {}]
           ["password reset" {:kind "pw-reset"} {}]
           ["pending second factor" {:kind "pending-2fa"} {}]
           ["unknown kind" {:kind "other"} {}]
           ["expired API token" {:kind "api" :expires-at 1} {}]
           ["disabled account" {} {:status "disabled"}]]]
    (testing label
      (let [{:keys [token ordinary paired session events]}
            (fixture session-changes account-changes)]
        (is (nil? (accounts/authenticate-token ordinary token)))
        (reset! events [])
        (is (nil? (accounts/authenticate-token paired token)))
        (is (= [[:paired (:token-hash session)]] @events)
            "rejected paired results do not touch or retry ordinary reads")))))


(deftest paired-account-presence-controls-fallback-test
  (let [{:keys [token session account ordinary events]} (fixture {} {})
        expected (assoc account :token-kind nil :token-scopes nil)]
    (doseq [[result answer expected-events]
            [[{:session session :account nil} nil [[:paired (:token-hash session)]]]
             [{:session session} expected [[:paired (:token-hash session)]
                                           [:read :account (:id account)]]]
             [nil nil [[:paired (:token-hash session)]]]
             [::session-read/unsupported expected [[:paired (:token-hash session)]
                                                   [:query :session {:token-hash (:token-hash session)}]
                                                   [:read :account (:id account)]]]]]
      (testing (pr-str result)
        (reset! events [])
        (is (= answer (accounts/authenticate-token
                        (->PairedStorage ordinary result events) token)))
        (is (= expected-events @events))))))


(deftest paired-authentication-preserves-touch-throttle-test
  (let [stale (- (System/currentTimeMillis) (* 2 accounts/session-touch-interval-ms))]
    (doseq [[label session-changes touch?]
            [["stale creation" {:created-at stale} true]
             ["stale last use" {:created-at stale :last-used-at stale} true]
             ["recent last use" {:created-at stale :last-used-at (System/currentTimeMillis)} false]]
            mode [:ordinary :paired]]
      (testing (str label " via " mode)
        (let [{:keys [token session events] :as f} (fixture session-changes {})
              before (System/currentTimeMillis)
              result (accounts/authenticate-token (get f mode) token)
              after (System/currentTimeMillis)
              writes (filterv #(= :update (first %)) @events)]
          (is (= (assoc (:account f) :token-kind nil :token-scopes nil) result))
          (is (= (if touch? 1 0) (count writes)))
          (when touch?
            (let [[op entity id data] (first writes)]
              (is (= [:update :session (:id session)] [op entity id]))
              (is (= #{:last-used-at} (set (keys data))))
              (is (<= before (:last-used-at data) after)))))))))


(deftest unknown-decorator-preserves-ordinary-read-policy-test
  (let [{:keys [token account session paired events]} (fixture {} {})
        decorator-events (atom [])
        ;; A map-shaped decorator exposes its paired-capable delegate but
        ;; retains its own ordinary account visibility policy.
        decorator (assoc (->ObservedStorage paired decorator-events) :hide-account? true)]
    (is (= (assoc account :token-kind nil :token-scopes nil)
           (accounts/authenticate-token paired token)))
    (reset! events [])
    (is (nil? (accounts/authenticate-token decorator token)))
    (is (= [[:query :session {:token-hash (:token-hash session)}]
            [:read :account (:id account)]] @decorator-events))
    (is (= [[:query :session {:token-hash (:token-hash session)}]] @events)
        "the decorator is never unwrapped to use its delegate's paired read")))
