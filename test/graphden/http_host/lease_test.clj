(ns ^:integration graphden.http-host.lease-test
  "Real transaction, deadline, capacity and authentication boundaries."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.accounts.core :as accounts]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.session-schema :as session-schema]
    [graphden.http-host.lease :as lease]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as schema]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as helpers]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.merge :as merge]))


(def ^:dynamic *container* nil)
(use-fixtures :once (helpers/create-container-fixture #'*container*))
(use-fixtures :each (helpers/create-clean-db-fixture #'*container*))


(defn- with-storage
  [f]
  (let [storage (pg/create-storage
                  (assoc (helpers/get-container-config *container*) :pool-size 6))]
    (try
      ;; Deliberately no accounts table: token-only self-host is supported.
      (sp/initialize storage (schema/build (session-schema/extend-builder (malli/create-builder))))
      (f storage)
      (finally (sp/close storage)))))


(defn- publication
  [org owner]
  {:id (random-uuid) :org org :owner owner
   :fn-id (random-uuid) :branch-id (random-uuid)})


(defn- attempt
  [f]
  (try (f) :created
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(deftest finite-publication-does-not-become-an-authentication-session
  (with-storage
    (fn [storage]
      (let [data (publication "public" "token:fingerprint")
            row (lease/create! storage data)
            credential "fixture-token-that-must-never-authenticate"]
        (is (= data (select-keys (lease/read-active storage (:id data)) (keys data))))
        (is (= lease/ttl-ms (- (:expires-at row) (:created-at row))))
        (testing "even knowledge of the raw token cannot authenticate this session kind"
          (sp/update-entity storage :session (:id row) {:token-hash (crypto/sha256-hex credential)})
          (is (nil? (accounts/authenticate-token storage credential))))
        (is (nil? (lease/revoke! storage "foreign" (:owner data) (:id row))))
        (is (nil? (lease/revoke! storage (:org data) "foreign-owner" (:id row))))
        (is (some? (lease/read-active storage (:id row))))
        (is (true? (lease/revoke! storage (:org data) (:owner data) (:id row))))
        (is (nil? (lease/read-active storage (:id row))))))))


(deftest deadline-and-branch-revocation-are-exact
  (with-storage
    (fn [storage]
      (let [first-data (publication "one" "one-owner")
            other-data (publication "two" "two-owner")
            first-row (lease/create! storage first-data)
            other-row (lease/create! storage other-data)
            login (sp/create-entity storage :session
                                    {:account-id "one-owner" :created-at 0
                                     :token-hash (crypto/sha256-hex "fixture-login")})]
        (lease/revoke-branch! storage (:branch-id first-data))
        (is (nil? (sp/read-entity storage :session (:id first-row))))
        (is (some? (lease/read-active storage (:id other-row))))
        (is (some? (sp/read-entity storage :session (:id login))))
        (sp/update-entity storage :session (:id other-row) {:expires-at 1})
        (is (nil? (lease/read-active storage (:id other-row))))
        (lease/reap! storage)
        (is (nil? (sp/read-entity storage :session (:id other-row))))
        (is (some? (sp/read-entity storage :session (:id login))))))))


(deftest failed-outer-transaction-releases-capacity-without-publishing
  (with-storage
    (fn [storage]
      (let [data (publication "one" "owner")]
        (is (= :fixture/rollback
               (attempt #(tx/in-transaction
                           storage
                           (fn [st]
                             (lease/create! st data)
                             (throw (ex-info "after reservation" {:type :fixture/rollback})))))))
        (is (nil? (lease/read-active storage (:id data))))
        (is (= :created (attempt #(lease/create! storage data))))))))


(deftest concurrent-org-admissions-never-exceed-two
  (with-storage
    (fn [storage]
      (let [start (promise)
            jobs (mapv (fn [n]
                         (future @start
                                 (attempt #(lease/create! storage (publication "same" (str "owner-" n))))))
                       (range 4))]
        (deliver start true)
        (let [outcomes (mapv #(deref % 10000 :timeout) jobs)]
          (is (= {:created 2 :http-host/capacity 2} (frequencies outcomes))))))))


(deftest concurrent-orgs-share-one-global-capacity
  (with-storage
    (fn [storage]
      (doseq [n (range (dec lease/global-capacity))]
        (lease/create! storage (publication (str "org-" n) (str "owner-" n))))
      (let [start (promise)
            jobs (mapv (fn [n]
                         (future @start
                                 (attempt #(lease/create! storage (publication (str "last-" n) (str "last-owner-" n))))))
                       (range 2))]
        (deliver start true)
        (is (= {:created 1 :http-host/capacity 1}
               (frequencies (mapv #(deref % 10000 :timeout) jobs))))
        (is (= lease/global-capacity (count (sp/query-entities storage :session {:kind lease/kind}))))))))


(deftest owner-budget-spans-orgs-and-proposed-id-never-reassigns
  (with-storage
    (fn [storage]
      (let [first-row (lease/create! storage (publication "one" "same-owner"))]
        (lease/create! storage (publication "two" "same-owner"))
        (is (= :http-host/capacity
               (attempt #(lease/create! storage (publication "three" "same-owner")))))
        (is (= :http-host/conflict
               (attempt #(lease/create! storage
                                        (assoc (publication "foreign" "other") :id (:id first-row))))))
        (is (= first-row (sp/read-entity storage :session (:id first-row))))))))


(deftest cleanup-and-branch-writer-lock-order-makes-progress
  (with-storage
    (fn [storage]
      (let [data (publication "one" "owner")
            _ (lease/create! storage data)
            capacity-held (promise)
            release-capacity (promise)
            branch-held (promise)
            cleaner (future
                      (lease/call-with-capacity-lock
                        storage
                        (fn [st]
                          (deliver capacity-held true)
                          (when (= :timeout (deref release-capacity 10000 :timeout))
                            (throw (ex-info "fixture barrier timed out" {})))
                          (lease/reap! st))))]
        (try
          (is (true? (deref capacity-held 10000 :timeout)))
          (let [deletion (future
                           (writer/call-with-write
                             storage :branch
                             (fn [st]
                               (merge/lock-branches! st (:branch-id data))
                               (deliver branch-held true)
                               (lease/revoke-branch! st (:branch-id data)))))]
            (is (true? (deref branch-held 10000 :timeout)))
            (deliver release-capacity true)
            (is (not= :timeout (deref cleaner 10000 :timeout)))
            (is (not= :timeout (deref deletion 10000 :timeout)))
            (is (nil? (lease/read-active storage (:id data)))))
          (finally (deliver release-capacity true)))))))
