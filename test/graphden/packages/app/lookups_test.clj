(ns ^:integration graphden.packages.app.lookups-test
  "Unit tests for the Phase-0 server-side lookup primitives in
   `resources/packages/app/lookups/fns.edn` — `:fn-row-by-id`,
   `:request-authed?` (and later `:fn-ns-path`, `:fn-usage-count`).

   Tagged `^:integration` because each test exercises a real fn-def
   via the executor against a PostgreSQL-backed storage — the
   `:storage-query-call` chain doesn't work without a real DB.

   Bootstrap uses the golden-DB clone path (`bootstrap-crud-graph-
   from-golden!`) so this NS pays only the ~1 s per-test-ns clone
   cost, not the full ~14 s package sync."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  exec/with-clean-registry
  (fn [f]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (f))))


(defn- fn-id-of
  "Look up the fn-id for the named fn-def by keyword. `all-name->id`
   is keyword-keyed both for base-fns and fn-defs."
  [nm]
  (get (:all-name->id *bootstrap*) nm))


(defn- exec-name
  "Execute the named fn-def `nm` (keyword) with `args`, auto-
   injecting `:storage-query` when the fn-def propagates it."
  [nm args]
  (let [{:keys [ctx storage]} *bootstrap*
        fn-id (fn-id-of nm)]
    (when-not fn-id
      (throw (ex-info (str "No fn-id for " nm) {:nm nm})))
    (setup/exec-with-storage ctx storage fn-id args)))


;; =============================================================================
;; :fn-row-by-id — single fn entity by id
;; =============================================================================

(deftest fn-row-by-id-returns-decoded-row
  (testing "given a known fn-id, returns the decoded fn row"
    (let [add-id (fn-id-of :add)
          row (exec-name :fn-row-by-id {:fn-id add-id})]
      (is (map? row) "non-nil decoded map")
      (is (= "add" (:name row))
          "row's :name matches the fn-def's name")
      (is (= add-id (:id row))
          "row's :id round-trips the input fn-id")
      (is (keyword? (first (keys row)))
          ":decode-row converts string column keys to keywords"))))


(deftest fn-row-by-id-is-version-resolved
  (testing "post-update field values are visible (protocol read, not raw base-row HSQL)"
    ;; Regression: the original implementation read the base :fn table
    ;; via raw HSQL and returned CREATE-TIME values — an updated
    ;; description (or declared-effects contract) never showed up in
    ;; any partial built on this reader.
    (let [{:keys [storage]} *bootstrap*
          add-id (fn-id-of :add)
          _ (sp/update-entity storage :fn add-id
                              {:description "updated-by-lookups-test"})
          row (exec-name :fn-row-by-id {:fn-id add-id})]
      (is (= "updated-by-lookups-test" (:description row))
          "reader sees the version row, not the create-time base row"))))


(deftest fn-row-by-id-returns-nil-for-unknown-id
  (testing "unknown id → nil (NOT an exception)"
    (let [bogus-id #uuid "00000000-0000-0000-0000-000000000000"]
      (is (nil? (exec-name :fn-row-by-id {:fn-id bogus-id}))
          "no row → :first returns nil → :decode-row returns nil"))))


(deftest fn-row-by-id-includes-description-field
  (testing "decoded row carries the :description field — needed by row-actions partial"
    (let [add-id (fn-id-of :add)
          row (exec-name :fn-row-by-id {:fn-id add-id})]
      ;; :add has a description in its fn-def declaration; the
      ;; canonical row should reflect it (even if string is short).
      (is (contains? row :description)
          ":description must round-trip — partial reads it for tooltip body"))))


(deftest fn-row-by-id-includes-namespace-id-field
  (testing "decoded row carries :namespace-id — needed for fn-ns-path"
    (let [add-id (fn-id-of :add)
          row (exec-name :fn-row-by-id {:fn-id add-id})]
      ;; :namespace-id may be nil (root-namespaced fns) or a uuid;
      ;; either way the KEY must be present so downstream consumers
      ;; can switch on it.
      (is (contains? row :namespace-id)
          ":namespace-id key always present — value is nil-or-uuid"))))


;; =============================================================================
;; :fn-ns-path — walk the ns parent-id chain
;; =============================================================================

(deftest fn-ns-path-nil-id-returns-empty-string
  (testing "nil ns-id → empty string (the implicit root namespace has no path)"
    (is (= "" (exec-name :fn-ns-path {:ns-id nil})))))


(deftest fn-ns-path-unknown-id-returns-empty-string
  (testing "unknown ns-id → empty string (sp/read-entity returns nil → fall-through)"
    (let [bogus #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"]
      (is (= "" (exec-name :fn-ns-path {:ns-id bogus}))))))


(deftest fn-ns-path-builds-dotted-path-from-fn-namespace
  (testing "Given the ns-id of a real fn, builds the canonical dotted path"
    ;; `:_partial-effect-fragment` lives in `app.editor` — use its
    ;; row to fetch a real namespace-id, then verify the walker
    ;; recovers the canonical `app.editor` string. Drives the same
    ;; data flow the row-actions partial will use: read fn → take
    ;; its :namespace-id → resolve to a display path.
    (let [{:keys [storage]} *bootstrap*
          row (exec-name :fn-row-by-id
                         {:fn-id (fn-id-of :_partial-effect-fragment)})
          ns-id (:namespace-id row)
          path  (exec-name :fn-ns-path {:ns-id ns-id})]
      ;; Sanity — the seed graph DOES have a parent namespace.
      (is (some? ns-id) ":_partial-effect-fragment must have a namespace-id")
      (is (= "app.editor" path)
          "ns-path walker recovers the canonical dotted name from the parent chain")
      ;; The walker also tolerates being handed a nil even when storage is live.
      (is (= "" (exec-name :fn-ns-path {:ns-id nil}))
          "nil short-circuit still works after a successful previous call (no state leak)")
      ;; Sanity check on storage availability in scope.
      (is (some? storage) "storage wired into bootstrap"))))
