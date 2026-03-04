(ns ^:integration graphden.executor-runtime.interface-test
  "Tests for executor-runtime/interface.clj public API.

   These tests verify that the interface module correctly delegates
   to the core module, covering all interface functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor-runtime.core :as core]
    [graphden.executor-runtime.interface :as rt]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.interface :as sys]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *container* nil)

(use-fixtures :once (pth/create-container-fixture #'*container*))


;; =============================================================================
;; Delegation Tests
;; =============================================================================

(deftest start!-delegation-test
  (testing "start! with no args delegates to core/start!"
    (let [start-called? (atom false)
          profile-used (atom nil)]
      (with-redefs [core/start! (fn
                                  ([]
                                   (reset! start-called? true)
                                   (reset! profile-used :default)
                                   :mock-system)
                                  ([profile]
                                   (reset! start-called? true)
                                   (reset! profile-used profile)
                                   :mock-system))]
        (let [result (rt/start!)]
          (is @start-called? "core/start! should be called")
          (is (= :default @profile-used))
          (is (= :mock-system result))))))

  (testing "start! with profile delegates to core/start!"
    (let [profile-used (atom nil)]
      (with-redefs [core/start! (fn [profile]
                                  (reset! profile-used profile)
                                  :mock-system)]
        (let [result (rt/start! :dev)]
          (is (= :dev @profile-used))
          (is (= :mock-system result)))))))


(deftest stop!-delegation-test
  (testing "stop! delegates to core/stop!"
    (let [stop-called? (atom false)]
      (with-redefs [core/stop! (fn []
                                 (reset! stop-called? true)
                                 nil)]
        (let [result (rt/stop!)]
          (is @stop-called? "core/stop! should be called")
          (is (nil? result)))))))


(deftest restart!-delegation-test
  (testing "restart! with no args delegates to core/restart!"
    (let [restart-called? (atom false)
          profile-used (atom nil)]
      (with-redefs [core/restart! (fn
                                    ([]
                                     (reset! restart-called? true)
                                     (reset! profile-used :default)
                                     :mock-system)
                                    ([profile]
                                     (reset! restart-called? true)
                                     (reset! profile-used profile)
                                     :mock-system))]
        (let [result (rt/restart!)]
          (is @restart-called? "core/restart! should be called")
          (is (= :default @profile-used))
          (is (= :mock-system result))))))

  (testing "restart! with profile delegates to core/restart!"
    (let [profile-used (atom nil)]
      (with-redefs [core/restart! (fn [profile]
                                    (reset! profile-used profile)
                                    :mock-system)]
        (let [result (rt/restart! :test)]
          (is (= :test @profile-used))
          (is (= :mock-system result)))))))


;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest interface-integration-test
  (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
        original-read-config sys/read-config]
    (with-redefs [sys/read-config (fn [profile]
                                    (-> (original-read-config profile)
                                        (assoc-in [:db/postgres :jdbc-url] jdbc-url)))]
      (testing "start! via interface returns running system"
        (let [system (rt/start! :test)]
          (try
            (is (map? system))
            (is (some? (:db/schema system)))
            (is (some? (:db/postgres system)))
            (finally
              (rt/stop!)))))

      (testing "restart! via interface restarts system"
        (let [system1 (rt/start! :test)]
          (try
            (is (some? system1))
            (let [system2 (rt/restart! :test)]
              (is (some? system2))
              (is (not= system1 system2)))
            (finally
              (rt/stop!))))))))
