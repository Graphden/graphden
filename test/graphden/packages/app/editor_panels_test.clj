(ns graphden.packages.app.editor-panels-test
  "Unit tests for the `app.editor-panels` boundary impl —
   `:type-diagnostics-list` joins the in-memory per-branch diagnostics
   store with an ORG-SCOPED fn-name read. The store itself has no org
   dimension (branch×fn only), and on a multi-tenant pod every org
   shares the default branch — so the join is the org boundary: a
   fn-id the scoped read doesn't return must be DROPPED, not emitted
   UUID-named with its diagnostic body (expected/actual types +
   source file/line = a cross-org metadata leak). Loader-pattern
   direct invocation, mirrors `core.logic-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.diagnostics :as diag])
  (:import
    (java.util
      UUID)))


(def ^:dynamic *impls* nil)


(defn- load-panels-impls-fixture
  [f]
  (binding [*impls* ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                     "app" "editor-panels")]
    (f)))


(use-fixtures :once load-panels-impls-fixture)


(defn- impl-of
  [kw]
  (let [entry (get *impls* kw)]
    (or (and (map? entry) (:impl entry))
        (and (fn? entry) entry)
        (throw (ex-info (str "No impl for " kw) {:available (keys *impls*)})))))


(defn- stub-storage
  "Answers `read-entities :fn` from `rows-by-id` — the org-scoped view
   (foreign ids simply absent, exactly like OrgScopedStorage's read
   filter). `(:branch-id this)` → nil for `current-branch-id`."
  [rows-by-id]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify sp/StorageBatchCRUD
    (read-entities
      [_ _ ids]
      (select-keys rows-by-id ids))))


(deftest foreign-org-diagnostics-are-dropped
  (let [own-id (UUID/randomUUID)
        foreign-id (UUID/randomUUID)
        ctx {:storage (stub-storage {own-id {:id own-id :name "my-broken-fn"}})}
        f (impl-of :type-diagnostics-list)]
    (binding [diag/*diagnostics-override* (atom {})]
      ;; Both orgs' recorders write into the SAME nil-branch bucket.
      (diag/record! nil own-id [{:message "Type mismatch on arg :x" :arg-name :x}])
      (diag/record! nil foreign-id [{:message "foreign org's expected/actual detail"}])
      (let [rows (f {} ctx)]
        (testing "own fn's diagnostics render with its name"
          (is (= ["my-broken-fn"] (mapv :fn-name rows)))
          (is (= "x" (:arg (first rows)))))
        (testing "the foreign fn-id is gone entirely — no UUID row, no message"
          (is (= 1 (count rows)))
          (is (not-any? #(re-find #"foreign" (:message %)) rows)))))))


(deftest anonymous-own-fn-keeps-uuid-fallback
  (let [anon-id (UUID/randomUUID)
        ctx {:storage (stub-storage {anon-id {:id anon-id :name nil}})}
        f (impl-of :type-diagnostics-list)]
    (binding [diag/*diagnostics-override* (atom {})]
      (diag/record! nil anon-id [{:message "broken anonymous"}])
      (is (= [(str anon-id)] (mapv :fn-name (f {} ctx)))
          "an own-org anonymous fn still rows up, UUID-labelled — the
           fallback is for nil :name, not for missing rows"))))
