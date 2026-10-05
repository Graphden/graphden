(ns ^:serial graphden.executor.browser-preview-test
  "Serial because the security sentinels replace process-wide boundary vars."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-preview :as preview]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.tenancy.context :as tenancy]))


(defn- reason
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))


(deftest tenancy-refusal-precedes-privileged-reads
  (let [reads (atom [])]
    (with-redefs [tenancy/tenancy-addon-active? (constantly true)
                  snapshot/read-snapshot #(swap! reads conj [:snapshot %])]
      (is (= :tenancy-disabled (reason #(preview/export-current {} {}))))
      (is (empty? @reads)))))


(deftest non-transactional-storage-fails-closed
  (with-redefs [tenancy/tenancy-addon-active? (constantly false)]
    (is (= :snapshot-storage-unsupported
           (reason #(preview/export-current {} {:view (random-uuid)}))))))


(deftest malformed-entry-identities-cannot-read-graph-or-classify-policy
  (let [reads (atom [])]
    (with-redefs [tenancy/tenancy-addon-active? (constantly false)
                  snapshot/capture-policy #(swap! reads conj [:policy %])
                  snapshot/read-snapshot #(swap! reads conj [:snapshot %])]
      (doseq [entries [{} {:view nil} {:view "not-a-uuid"}
                       {:view (str (random-uuid))} {:unknown (random-uuid)}]]
        (is (= :invalid-entries (reason #(preview/export-current {} entries)))))
      (is (empty? @reads)))))
