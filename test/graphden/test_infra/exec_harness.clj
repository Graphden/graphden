(ns graphden.test-infra.exec-harness
  "Shared fixture + helpers for package tests that drive fn-defs
   through the EXECUTOR over a golden clone (`*context*`/`*storage*`
   pair — the executor-level sibling of `test-infra.graph-harness`,
   which serves the via-graph HTTP-handler family instead). Ten NSes
   carried byte-identical copies of the fixture + `fn-id` + `sync!`.

   `ns-ident` names the per-NS clone DB — pass `(str (ns-name *ns*))`
   FROM THE TEST FILE; sibling NSes must never share a clone under the
   parallel runner (see `bootstrap-crud-graph-from-golden!`)."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


(defn exec-fixture
  "`:once` fixture over a golden clone of `package-names` (default
   `[core web app]`), wrapped in `with-clean-registry`."
  ([ns-ident] (exec-fixture ns-ident ["core" "web" "app"]))
  ([ns-ident package-names]
   (fn [t]
     (exec/with-clean-registry
       #(let [graph (setup/bootstrap-crud-graph-from-golden!*
                      ns-ident package-names)]
          (try
            (binding [*context* (:ctx graph)
                      *storage* (:storage graph)]
              (t))
            (finally (setup/close-graph! graph))))))))


(defn fn-id
  "Id of the named fn in the golden clone."
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(defn sync!
  "Sync `fn-defs` and DELTA-invalidate just them (+ dependents)."
  [fn-defs]
  (setup/sync-and-invalidate! *context* *storage* fn-defs))
