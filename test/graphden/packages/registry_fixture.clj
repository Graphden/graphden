(ns graphden.packages.registry-fixture
  "What the package-registry test namespaces share — `registry-publish-test`
   (publish / withdraw / list / export / the panel's publish + remote pulls)
   and `registry-install-test` (materialise / install / pin / update /
   fork). Both clone the SAME golden package set, so the split costs no
   extra bootstrap; each gets its own logical DB, so neither sees the
   other's rows."
  (:require
    [cheshire.core :as json]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]))


(def ^:dynamic *bootstrap* nil)


(defn bootstrap-fixture
  "`:once` fixture: bind `*bootstrap*` to a golden clone named `db-name`.
   registry is its own OPTIONAL package (installed via the route-collection
   seam), so `app` no longer pulls it — the golden carries `registry` so
   its publish/install/fork/export fn-defs are present."
  [db-name]
  (fn [t]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!
                            db-name ["core" "web" "app" "registry" "mcp"])]
      (t))))


(defn storage
  []
  (:storage *bootstrap*))


(defn run-named
  [fn-name args]
  (exec/execute-by-name (:ctx *bootstrap*) fn-name args))


(defn publish-req
  "A JSON POST ring request carrying `body` — the publish handlers' input."
  [body]
  {:request-method :post
   :body (json/generate-string body)
   :headers {"content-type" "application/json"}})
