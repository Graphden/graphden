(ns graphden.packages.mathx.ops.impls
  "Base-fn impls for the external `mathx` package. Proves that an
   out-of-tree impl-package's `defbase` impls register through the same
   loader path (`load-impls-via-eval`) as the built-in `core`/`web`/`app`."
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase gcd
  "Greatest common divisor (Euclid). A single self-contained algorithm —
   not composable from `core` arithmetic — so it's a genuine base-fn.
   NB: the loop vars are x/y, NOT a/b — `defbase` rewrites every occurrence
   of an arg symbol (`a`/`b`) into a `resolve-arg` call, so shadowing an arg
   name in a `loop`/`let` binding would corrupt the recur."
  [a b]
  (loop [x (abs (long a)) y (abs (long b))]
    (if (zero? y) x (recur y (mod x y)))))


;; The loader reads each module's `impls` var — a `{fn-name {:impl fn}}` map
;; linking the fns.edn declaration to its Clojure impl (same shape as every
;; built-in module's impls.clj). Without it the `:gcd` declaration has no impl.
(def impls
  {:gcd {:impl gcd}})
