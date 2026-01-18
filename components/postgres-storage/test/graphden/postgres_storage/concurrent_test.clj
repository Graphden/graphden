(ns graphden.postgres-storage.concurrent-test
  "Tests for PostgreSQL storage concurrent access and migration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.postgres-storage.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th])
  (:import
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === Concurrent access tests ===

(deftest concurrent-access-test
  (testing "concurrent reads are thread-safe"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)
          errors (atom [])
          num-threads 10
          iterations 50]
      (try
        (sp/initialize storage schema)
        ;; Launch multiple threads reading concurrently
        (let [futures (doall
                        (for [_ (range num-threads)]
                          (future
                            (try
                              (dotimes [_ iterations]
                                (sp/current-entities storage)
                                (sp/current-fields storage :user)
                                (sp/schema-metadata storage))
                              (catch Exception e
                                (swap! errors conj e))))))]
          ;; Wait for all threads to complete
          (doseq [f futures]
            (deref f 5000 :timeout)))
        ;; No errors should have occurred
        (is (empty? @errors) (str "Errors during concurrent access: " @errors))
        (finally
          (sp/close storage)))))

  (testing "concurrent initialize and read are thread-safe"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)
          errors (atom [])
          num-readers 5
          num-iterations 20
          ;; Use CountDownLatch to start all threads simultaneously
          start-latch (CountDownLatch. 1)
          ;; Track completion
          done-latch (CountDownLatch. (inc num-readers))]
      (try
        ;; First initialize
        (sp/initialize storage schema)
        ;; Start reader threads - wait for start signal
        (doseq [_ (range num-readers)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (dotimes [_ num-iterations]
                (sp/schema-metadata storage))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Writer thread that re-initializes
        (future
          (try
            (CountDownLatch/.await start-latch)
            (dotimes [_ 3]
              (sp/initialize storage schema))
            (catch Exception e
              (swap! errors conj e))
            (finally
              (CountDownLatch/.countDown done-latch))))
        ;; Start all threads at once
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")
        (is (empty? @errors) (str "Errors during concurrent read/write: " @errors))
        (finally
          (sp/close storage))))))


;; === Concurrent migration tests ===

(deftest concurrent-migration-test
  (testing "concurrent initializations are handled safely"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (th/make-schema :entity-uuid entity-uuid
                                 :fields {:name {:uuid field-uuid :type :text}})
          results (atom [])
          errors (atom [])
          num-threads 5
          start-latch (CountDownLatch. 1)
          done-latch (CountDownLatch. num-threads)]
      (try
        ;; Create threads that wait for start signal
        (doseq [_ (range num-threads)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (let [result (sp/initialize storage schema)]
                (swap! results conj result))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Start all threads simultaneously
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")

        ;; All threads should complete without errors
        (is (empty? @errors) (str "Got errors: " (map #(Exception/.getMessage %) @errors)))

        ;; At least one thread should have created the table
        (is (pos? (count @results)))

        ;; Database state should be consistent
        (is (= #{:user} (sp/current-entities storage)))
        (is (= #{:name} (set (keys (sp/current-fields storage :user)))))

        (finally
          (sp/close storage)))))

  (testing "concurrent migrations with schema changes"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000002"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000003"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field1-uuid :type :text}})
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field1-uuid :type :text}
                                           :email {:uuid field2-uuid :type :text}})
          _ (sp/initialize storage schema1)
          results (atom [])
          errors (atom [])
          num-threads 3
          start-latch (CountDownLatch. 1)
          done-latch (CountDownLatch. num-threads)]
      (try
        ;; Create threads that wait for start signal
        (doseq [_ (range num-threads)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (let [result (sp/initialize storage schema2)]
                (swap! results conj result))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Start all threads simultaneously
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")

        ;; All threads should complete without errors
        (is (empty? @errors) (str "Got errors: " (map #(Exception/.getMessage %) @errors)))

        ;; Database state should reflect schema2
        (is (= #{:user} (sp/current-entities storage)))
        (is (= #{:name :email} (set (keys (sp/current-fields storage :user)))))

        (finally
          (sp/close storage))))))
