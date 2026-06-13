(ns graphden.packages.core.refinements.impls
  "All runtime narrowers (`:ensure-positive-int`, `:ensure-url`, …) are
   now graph fn-defs composing the generic `:_refinement-narrow`
   template — see `fns.edn`. No defbase impls live here anymore; the
   `impls` map stays so the package loader's `load-module-impls` finds
   the expected sentinel (a map keyed by base-fn name).")


(def impls {})
