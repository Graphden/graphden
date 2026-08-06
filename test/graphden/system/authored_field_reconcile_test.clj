(ns graphden.system.authored-field-reconcile-test
  "P0.3 regression — a declarative re-sync that DROPS an authored
   version-data field (`:lambda-params`, `:expects-effects`) must clear
   the stale value WITHOUT a DB reset. This is the 2026-08-06 outage
   mechanism: a handler's `:lambda-params [:request :limit]` was removed
   from the EDN, but the versioned upsert's `merge current data` kept the
   old value — so the fixed image kept compiling the stale params until a
   `DROP SCHEMA`.

   The fix lives in `records/parse.clj attach-fn-meta`: emit these fields
   UNCONDITIONALLY (explicit `nil` on omission) so the merge overwrites
   rather than preserves. Two layers are asserted: (1) the parse post-
   step carries the explicit clear, and (2) the versioned resolved read
   reflects it end-to-end."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.parse]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(def ^:private attach-fn-meta
  ;; private post-processing step under test
  @#'graphden.packages.records.parse/attach-fn-meta)


(deftest attach-fn-meta-emits-explicit-clear-on-removal
  (testing "declared → the vectorized value is emitted"
    (is (= ["request" "limit"]
           (:lambda-params (first (attach-fn-meta [{:name "h"}]
                                                  {:lambda-params [:request :limit]}))))))
  (testing "omitted → an explicit clear is emitted (so merge overwrites the stale value)"
    (let [row (first (attach-fn-meta [{:name "h"}] {}))]
      (is (contains? row :lambda-params) "key must be present, not absent")
      (is (nil? (:lambda-params row)) ":lambda-params clears to nil (its canonical absent)")
      (is (contains? row :expects-effects))
      (is (= [] (:expects-effects row)) ":expects-effects clears to [] (canonical no-effects)")))
  (testing "[] stays a meaningful declaration, distinct from omitted"
    (is (= [] (:lambda-params (first (attach-fn-meta [{:name "h"}]
                                                     {:lambda-params []})))))))


(deftest lambda-params-removal-reconciles-through-the-version-plane
  (let [storage (setup/create-test-storage)]
    (try
      (let [fid (random-uuid)
            read-lp #(vector (:lambda-params (sp/read-entity storage :fn fid))
                             (:lambda-params (first (sp/query-entities
                                                      storage :fn {:name "reconcile-probe"}))))]
        ;; version A — the fn-def declares :lambda-params [:request :limit]
        (sp/upsert-entities storage :fn
                            (attach-fn-meta [{:id fid :name "reconcile-probe"}]
                                            {:lambda-params [:request :limit]}))
        (testing "version A resolves the declared params on both read paths"
          (is (= [["request" "limit"] ["request" "limit"]] (read-lp))))
        ;; version B — a later sync DROPS the key (static handler)
        (sp/upsert-entities storage :fn
                            (attach-fn-meta [{:id fid :name "reconcile-probe"}] {}))
        (testing "version B clears it end-to-end — no DB reset needed"
          (is (= [nil nil] (read-lp)))))
      (finally (sp/close storage)))))
