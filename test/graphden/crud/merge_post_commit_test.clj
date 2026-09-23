(ns ^:integration ^:serial graphden.crud.merge-post-commit-test
  "The merge finisher runs on a raw thread after the commit. It reads the
   tenant's rows (the fns the merge touched), so it must run under the
   tenant's org and every binding a background thread carries — it used
   to run as the public org, saw none of a tenant's rows, and left the
   target serving its pre-merge closures.

   `^:serial`: `with-redefs` on a var other namespaces call, and a probe
   var registered for conveyance (a process-global set)."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.graph-harness :as gh :refer [*graph* json-req uniq]]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as mrg]))


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(def ^:dynamic *probe*
  "A binding registered for conveyance, like the tenancy addon's org."
  nil)


(defn- branch!
  [branch-name]
  (:branch (cheshire/parse-string
             (:body (gh/via :create-branch-handler
                            (json-req "/api/branches" {:name branch-name})))
             true)))


(deftest the-merge-finisher-runs-under-the-callers-org-and-bindings
  (cr/register-conveyed-var! #'*probe*)
  (let [storage (:storage *graph*)
        feat (branch! (uniq "feat"))
        on-feat (vs/switch-branch storage (parse-uuid (str (:id feat))))
        _ (sp/create-entity on-feat :fn {:name (uniq "merged") :parent-ids []})
        seen (atom [])
        real mrg/merge-affected-fn-ids]
    (with-redefs [mrg/merge-affected-fn-ids
                  (fn [base source-branch-id]
                    (swap! seen conj [(tc/current-org) *probe*])
                    (real base source-branch-id))]
      (binding [*probe* :carried]
        (tc/with-org "org-x"
                     (gh/via :merge-branch-handler
                             (json-req "/api/branches/main/merge" {:source (:name feat)})))))
    (is (seq @seen) "the finisher ran")
    (is (every? #{["org-x" :carried]} @seen)
        "it read the merge's rows as the caller's org, with the caller's bindings")))
