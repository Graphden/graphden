(ns graphden.storage-memory.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-memory.core :as sut]
    [graphden.storage.interface :as storage]))


;; === StorageInfo ===

(deftest storage-type-returns-memory
  (let [s (sut/create-storage)]
    (is (= :memory (storage/storage-type s)))))


(deftest storage-capabilities-returns-expected
  (let [s (sut/create-storage)]
    (is (= #{:crud :batch :watch} (storage/storage-capabilities s)))))


(deftest has-capability-check
  (let [s (sut/create-storage)]
    (is (storage/has-capability? s :crud))
    (is (storage/has-capability? s :watch))
    (is (not (storage/has-capability? s :transactions)))))


;; === Basic CRUD ===

(deftest put-and-get
  (let [s (sut/create-storage)]
    (testing "Put stores data, get retrieves it"
      (storage/put* s :user :user-1 {:name "Alice"})
      (is (= {:name "Alice"} (storage/get-by-id* s :user :user-1))))))


(deftest get-non-existent
  (let [s (sut/create-storage)]
    (testing "Get non-existent returns nil"
      (is (nil? (storage/get-by-id* s :user :non-existent))))))


(deftest delete-removes-data
  (let [s (sut/create-storage)]
    (testing "Delete removes entity"
      (storage/put* s :user :user-1 {:name "Alice"})
      (storage/delete* s :user :user-1)
      (is (nil? (storage/get-by-id* s :user :user-1))))))


(deftest update-entity-modifies-data
  (let [s (sut/create-storage)]
    (testing "Update applies function to entity"
      (storage/put* s :user :user-1 {:name "Alice" :age 30})
      (storage/update-entity* s :user :user-1 #(update % :age inc))
      (is (= {:name "Alice" :age 31} (storage/get-by-id* s :user :user-1))))))


(deftest exists-check
  (let [s (sut/create-storage)]
    (testing "exists? returns correct boolean"
      (is (not (storage/exists?* s :user :user-1)))
      (storage/put* s :user :user-1 {:name "Alice"})
      (is (storage/exists?* s :user :user-1)))))


;; === find-by ===

(deftest find-by-field
  (let [s (sut/create-storage)]
    (testing "find-by returns matching entities"
      (storage/put* s :user :u1 {:name "Alice" :role :admin})
      (storage/put* s :user :u2 {:name "Bob" :role :user})
      (storage/put* s :user :u3 {:name "Charlie" :role :admin})
      (let [admins (storage/find-by* s :user :role :admin)]
        (is (= 2 (count admins)))
        (is (every? #(= :admin (:role %)) admins))))))


(deftest find-by-no-matches
  (let [s (sut/create-storage)]
    (testing "find-by with no matches returns empty"
      (storage/put* s :user :u1 {:name "Alice" :role :admin})
      (is (empty? (storage/find-by* s :user :role :guest))))))


;; === get-all ===

(deftest get-all-entities
  (let [s (sut/create-storage)]
    (testing "get-all returns all entities of type"
      (storage/put* s :user :u1 {:name "Alice"})
      (storage/put* s :user :u2 {:name "Bob"})
      (storage/put* s :post :p1 {:title "Hello"})
      (is (= 2 (count (storage/get-all* s :user))))
      (is (= 1 (count (storage/get-all* s :post)))))))


;; === Initial data ===

(deftest create-with-initial-data
  (testing "Can create storage with initial data"
    (let [s (sut/create-storage {:user {:u1 {:name "Alice"}}})]
      (is (= {:name "Alice"} (storage/get-by-id* s :user :u1))))))


;; === Watchers ===

(deftest watcher-receives-put-events
  (let [s (sut/create-storage)
        events (atom [])]
    (sut/add-watcher s (fn [event] (swap! events conj event)))
    (storage/put* s :user :u1 {:name "Alice"})
    (is (= 1 (count @events)))
    (is (= {:op :put
            :entity-type :user
            :id :u1
            :data {:name "Alice"}}
           (first @events)))))


(deftest watcher-receives-delete-events
  (let [s (sut/create-storage)
        events (atom [])]
    (storage/put* s :user :u1 {:name "Alice"})
    (sut/add-watcher s (fn [event] (swap! events conj event)))
    (storage/delete* s :user :u1)
    (is (= 1 (count @events)))
    (let [event (first @events)]
      (is (= :delete (:op event)))
      (is (= {:name "Alice"} (:old-data event))))))


(deftest watcher-receives-update-events
  (let [s (sut/create-storage)
        events (atom [])]
    (storage/put* s :user :u1 {:name "Alice" :age 30})
    (sut/add-watcher s (fn [event] (swap! events conj event)))
    (storage/update-entity* s :user :u1 #(assoc % :age 31))
    (is (= 1 (count @events)))
    (let [event (first @events)]
      (is (= :update (:op event)))
      (is (= {:name "Alice" :age 30} (:old-data event)))
      (is (= {:name "Alice" :age 31} (:new-data event))))))


(deftest remove-watcher-stops-events
  (let [s (sut/create-storage)
        events (atom [])
        watcher-id (sut/add-watcher s (fn [event] (swap! events conj event)))]
    (storage/put* s :user :u1 {:name "Alice"})
    (is (= 1 (count @events)))
    (sut/remove-watcher s watcher-id)
    (storage/put* s :user :u2 {:name "Bob"})
    (is (= 1 (count @events)))))


(deftest multiple-watchers
  (let [s (sut/create-storage)
        events1 (atom [])
        events2 (atom [])]
    (sut/add-watcher s (fn [event] (swap! events1 conj event)))
    (sut/add-watcher s (fn [event] (swap! events2 conj event)))
    (storage/put* s :user :u1 {:name "Alice"})
    (is (= 1 (count @events1)))
    (is (= 1 (count @events2)))))


;; === Snapshot ===

(deftest get-snapshot-returns-immutable-copy
  (let [s (sut/create-storage)]
    (storage/put* s :user :u1 {:name "Alice"})
    (let [snapshot (sut/get-snapshot s)]
      (storage/put* s :user :u2 {:name "Bob"})
      (is (= 1 (count (get snapshot :user))))
      (is (= 2 (count (get (sut/get-snapshot s) :user)))))))


;; === Chaining ===

(deftest operations-return-storage-for-chaining
  (let [s (sut/create-storage)]
    (testing "Operations return self for chaining"
      (is (= s (storage/put* s :user :u1 {:name "Alice"})))
      (is (= s (storage/delete* s :user :u1)))
      (storage/put* s :user :u1 {:name "Alice"})
      (is (= s (storage/update-entity* s :user :u1 identity))))))
