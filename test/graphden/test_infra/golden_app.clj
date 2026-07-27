(ns graphden.test-infra.golden-app
  "Shared `:once` fixture stack for integration tests that execute
   app-layer partials / handlers against the golden DB with a
   PRODUCTION-shaped registry: shared PG container + clean base-fn
   registry + isolated rich-types + a per-NS golden clone + the
   cached full type-check sweep overlaid (`ensure-swept-rich-types!`
   — the plain golden bootstrap seeds only base-fn entries, and
   chains like the rule-owner walk read COMPOSED fn-defs' entries).

   Used by the editor-partials suites (return-type-rule, layout
   strip-facts, execute/datalist shells); extracted from three
   copies of the same 5-clause stack."
  (:require
    [clojure.test :as t]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(defn fixture
  "Composed `:once` fixture. `ns-ident` names the per-NS golden-clone
   database (pass `(ns-name *ns*)` from the test ns — sibling NSes
   must not share a DB under the parallel runner).

   `package-names` (default `[\"core\" \"web\" \"app\"]`) is the bundle the
   golden + the cached sweep are built from. A suite that exercises an
   OPTIONAL package no longer pulled in by `app` (e.g. `mcp` after its
   extraction — its route installs via the route-collection seam, so
   `app` doesn't depend on it) passes it explicitly, e.g.
   `[\"core\" \"web\" \"app\" \"mcp\"]`; that keys its own golden clone."
  ([ns-ident] (fixture ns-ident ["core" "web" "app"]))
  ([ns-ident package-names]
   (t/join-fixtures
     [(pth/create-container-fixture #'*container*)
      exec/with-clean-registry
      exec/with-isolated-rich-types
      (fn [f]
        (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!* ns-ident package-names)]
          (reset! registry-core/*rich-types-override*
                  (sb/ensure-swept-rich-types! package-names))
          (f)))])))


(defn fn-id
  "Fn-id for the named fn/handler from the golden bootstrap."
  [fn-name]
  (get (:all-name->id *bootstrap*) fn-name))


(defn exec-handler
  "Execute the named handler fn-def with `request` and return the
   response map."
  [handler-name request]
  (let [{:keys [ctx storage]} *bootstrap*]
    (setup/exec-with-storage ctx storage (fn-id handler-name)
                             {:request request})))
