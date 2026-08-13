(ns graphden.packages.app.editor-panels-test
  "Unit tests for the `app.editor-panels` boundary impl —
   `:branch-diagnostics-flat` joins the in-memory per-branch
   diagnostics store with an ORG-SCOPED fn-name read. The store itself
   has no org dimension (branch×fn only), and on a multi-tenant pod
   every org shares the default branch — so the join is the org
   boundary: a fn-id the scoped read doesn't return must be DROPPED,
   not emitted UUID-named with its diagnostic body (expected/actual
   types + source file/line = a cross-org metadata leak).
   Loader-pattern direct invocation, mirrors `core.logic-test`. The
   display reshape over the flat entries is graph composition —
   covered by `type-diagnostics-graph-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.impls :as impls]
    [graphden.types.diagnostics :as diag])
  (:import
    (java.util
      UUID)))


(use-fixtures :once (impls/impls-fixture "app" "editor-panels"))


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
        f (impls/impl-of :branch-diagnostics-flat)]
    (binding [diag/*diagnostics-override* (atom {})]
      ;; Both orgs' recorders write into the SAME nil-branch bucket.
      (diag/record! nil own-id [{:message "Type mismatch on arg :x" :arg-name :x}])
      (diag/record! nil foreign-id [{:message "foreign org's expected/actual detail"}])
      (let [rows (f {} ctx)]
        (testing "own fn's diagnostics join with its name; the diag rides whole"
          (is (= ["my-broken-fn"] (mapv :fn-name rows)))
          (is (= :x (get-in (first rows) [:diag :arg-name]))))
        (testing "the foreign fn-id is gone entirely — no entry, no message"
          (is (= 1 (count rows)))
          (is (not-any? #(re-find #"foreign" (str (:message (:diag %)))) rows)))))))


(deftest anonymous-own-fn-entry-survives-with-nil-name
  (let [anon-id (UUID/randomUUID)
        ctx {:storage (stub-storage {anon-id {:id anon-id :name nil}})}
        f (impls/impl-of :branch-diagnostics-flat)]
    (binding [diag/*diagnostics-override* (atom {})]
      (diag/record! nil anon-id [{:message "broken anonymous"}])
      (let [rows (f {} ctx)]
        (is (= [(str anon-id)] (mapv :fn-id rows)))
        (is (= [nil] (mapv :fn-name rows))
            "an own-org anonymous fn still rows up — nil :fn-name; the
             uuid display label is the graph reshape's coalesce")))))
