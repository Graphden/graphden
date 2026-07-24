(ns graphden.tenancy.users-test
  "Pure password-hashing unit tests (no storage). The create-user!/login!
   storage round-trip lives in the addon-active integration suite
   (`graphden.integration.faas-app-test`)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as ap]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.users :as users]))


(deftest password-hashing-roundtrip
  (testing "verify accepts the right password, rejects others"
    (let [h (users/hash-password "correct horse battery staple")]
      (is (true? (users/verify-password "correct horse battery staple" h)))
      (is (false? (users/verify-password "wrong" h)))
      (is (false? (users/verify-password "" h)))))
  (testing "a fresh salt per call — same password hashes to different strings"
    (is (not= (users/hash-password "pw") (users/hash-password "pw"))))
  (testing "the stored value is a bcrypt hash ($2…)"
    (is (str/starts-with? (users/hash-password "pw") "$2")))
  (testing "garbage / nil never throws — just false"
    (is (false? (users/verify-password "pw" "not-a-hash")))
    (is (false? (users/verify-password "pw" nil)))
    (is (false? (users/verify-password nil "$2a$12$abcdefghijklmnopqrstuv")))))


(deftest rate-limiter-allows-then-blocks
  (let [limit (users/make-rate-limiter 3 60000)]
    (testing "allows up to max per window, then blocks the same key"
      (is (true? (limit "ip1")))
      (is (true? (limit "ip1")))
      (is (true? (limit "ip1")))
      (is (false? (limit "ip1")))
      (is (false? (limit "ip1"))))
    (testing "a different key has its own independent window"
      (is (true? (limit "ip2"))))))


(deftest rate-limiter-window-resets
  (let [limit (users/make-rate-limiter 1 50)]   ; 1 attempt per 50ms
    (is (true? (limit "k")))
    (is (false? (limit "k")))
    (Thread/sleep 70)
    (is (true? (limit "k")) "after the window elapses the key is allowed again")))


(deftest client-ip-extraction
  (testing "first X-Forwarded-For hop wins, else :remote-addr, else unknown"
    (is (= "1.2.3.4" (users/client-ip {:headers {"x-forwarded-for" "1.2.3.4, 5.6.7.8"}})))
    (is (= "9.9.9.9" (users/client-ip {:remote-addr "9.9.9.9"})))
    (is (= "unknown" (users/client-ip {})))))


(deftest backfill-auth-subject-ids-stamps-missing-ids
  ;; P1 name→id migration: pre-P1 rows carry a name but no stable id. The
  ;; backfill resolves the (immutable) username and stamps the id — idempotently
  ;; (existing ids untouched), skipping rows whose user no longer resolves.
  (let [alice-id #uuid "11111111-1111-1111-1111-111111111111"
        updates  (atom [])
        users    [{:id alice-id :username "alice"}]
        tokens   [{:id :t1 :user "alice" :user-id nil}          ; stamped
                  {:id :t2 :user "alice" :user-id "existing"}]  ; already set → left
        grants   [{:id :g1 :subject "alice" :subject-id nil}    ; stamped
                  {:id :g2 :subject "ghost" :subject-id nil}]   ; no such user → left
        storage  (reify sp/StorageCRUD
                   (query-entities
                     [_ ent where]
                     (case ent
                       :token tokens
                       :grant grants
                       :user  (filterv #(= (:username %) (:username where)) users)
                       nil))

                   (query-entities [_ _ _ _] nil)

                   (update-entity [_ ent id data] (swap! updates conj [ent id data]) data)

                   (create-entity [_ _ _] nil)

                   (read-entity [_ _ _] nil)

                   (delete-entity [_ _ _] nil)

                   (query-latest-per-group [_ _ _ _] nil))
        result   (users/backfill-auth-subject-ids! storage)]
    (testing "counts only the rows actually stamped"
      (is (= 1 (:tokens-backfilled result)) "t1 stamped; t2 already had an id")
      (is (nil? (:grants-backfilled result))
          "grants arm deleted with grant.subject — token arm only"))
    (testing "the stamped id is the user's id in STRING form"
      (is (= [:token :t1 {:user-id (str alice-id)}]
             (first (filter #(= :token (first %)) @updates))))
      (is (empty? (filter #(= :grant (first %)) @updates))
          "no grant writes from the backfill"))
    (testing "already-stamped + unresolvable rows are NOT written"
      (is (not-any? #(= :t2 (second %)) @updates))
      (is (not-any? #(= :g2 (second %)) @updates)))))


;; --- Invite flow (LAUNCH_PLAN stage 1.3) ---

(defn- mem-storage
  "Atom-backed StorageCRUD stub — enough surface for the invite round-trip
   (:token / :user / :org query, create, delete)."
  []
  (let [db (atom {})]
    (reify sp/StorageCRUD
      (create-entity
        [_ en data]
        (let [row (assoc data :id (or (:id data) (random-uuid)))]
          (swap! db update en (fnil conj []) row)
          row))

      (query-entities
        [_ en where]
        (filterv (fn [r] (every? (fn [[k v]] (= (get r k) v)) where))
                 (get @db en)))

      (query-entities [_ _ _ _] nil)

      (read-entity
        [_ en id]
        (first (filter #(= id (:id %)) (get @db en))))

      (update-entity [_ _ _ _] nil)

      (delete-entity
        [_ en id]
        (let [before (count (get @db en))]
          (swap! db update en (fn [rows] (filterv #(not= id (:id %)) rows)))
          (> before (count (get @db en)))))

      (query-latest-per-group [_ _ _ _] nil))))


(deftest invite-roundtrip
  (let [storage (mem-storage)
        ctx {:storage storage}
        {:keys [invite org]} (users/create-invite! ctx "alice" "alice-id" "acme")]
    (testing "mint: raw returned once, only the hash + kind stored"
      (is (string? invite))
      (is (= "acme" org))
      (let [row (first (sp/query-entities storage :token {:kind "invite"}))]
        (is (some? row))
        (is (not= invite (:token-hash row)) "raw never persisted")
        (is (some? (:expires-at row)))))
    (testing "an invite token does NOT authenticate as a session bearer"
      (let [p (tauth/storage-token-provider storage)]
        (is (not (:authenticated?
                   (ap/authenticate
                     p {:headers {"authorization" (str "Bearer " invite)}}))))))
    (testing "redeem creates the user INSIDE the invite's org + auto-login"
      (let [res (users/redeem-invite! ctx invite "bob" "pw-bob")]
        (is (some? res))
        (is (= "acme" (:org res)))
        (is (string? (:token res)))
        (is (= "acme" (:org (first (sp/query-entities storage :user {:username "bob"})))))
        (testing "…and the minted session token DOES authenticate"
          (let [p (tauth/storage-token-provider storage)
                session (ap/authenticate
                          p {:headers {"authorization" (str "Bearer " (:token res))}})]
            (is (:authenticated? session))
            (is (= "acme" (:org session)))))))
    (testing "single-use: a second redeem fails"
      (is (nil? (users/redeem-invite! ctx invite "carol" "pw"))))))


(deftest invite-rejects-expired-and-taken
  (let [storage (mem-storage)
        ctx {:storage storage}]
    (testing "expired invite → nil"
      (let [{:keys [invite]} (users/create-invite! ctx "alice" "alice-id" "acme")
            row (first (sp/query-entities storage :token {:kind "invite"}))]
        (sp/delete-entity storage :token (:id row))
        (sp/create-entity storage :token (assoc row :expires-at 1))
        (is (nil? (users/redeem-invite! ctx invite "bob" "pw")))))
    (testing "taken username → nil, invite already burned by the race guard design"
      (let [{:keys [invite]} (users/create-invite! ctx "alice" "alice-id" "acme")]
        (sp/create-entity storage :user {:username "bob" :org "other"})
        (is (nil? (users/redeem-invite! ctx invite "bob" "pw")))))
    (testing "blank inputs → nil"
      (is (nil? (users/redeem-invite! ctx "" "u" "p")))
      (is (nil? (users/redeem-invite! ctx "tok" "" "p")))
      (is (nil? (users/redeem-invite! ctx "tok" "u" ""))))))
