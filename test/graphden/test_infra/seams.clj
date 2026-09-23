(ns graphden.test-infra.seams
  "Test-local copies of the process-global INSTALLABLE seams — the tenancy
   policy seams (`tc/install-*-fn!`), the deploy-config snapshot
   (`deploy-config/install!`) and the loaded-package roster
   (`loaded/install!`).

   A namespace that installs one of them for the length of a test used to
   write the process global, so under the parallel runner a sibling
   namespace's `finally` reset landed mid-test and every installer had to
   run `^:serial`. With this fixture the installs and reads of the
   namespace's own thread go to fresh atoms seeded from the globals.

   Only the thread the fixture runs on (and what inherits its bindings:
   `future`, `bound-fn`) sees the copies. A seam read on an http-kit
   worker or a bare executor thread reads the global — a namespace whose
   installs must reach such a thread stays `^:serial`."
  (:require
    [graphden.packages.loaded :as loaded]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.tenancy.context :as tc]))


(defn isolated-seams-fixture
  "`:once` (or `:each`) fixture: run `f` with private copies of the
   installable seams."
  [f]
  (binding [tc/*seams-override* (atom (tc/seams-isolation-seed))
            deploy-config/*snapshot-override* (atom (deploy-config/snapshot-isolation-seed))
            loaded/*roster-override* (atom (loaded/roster-isolation-seed))]
    (f)))
