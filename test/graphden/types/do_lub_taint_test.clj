(ns graphden.types.do-lub-taint-test
  "Regression tests for two taint-model holes closed 2026-08-17
   (pre-release security audit):

   1. `:do` silently DECLASSIFIED a secret. It returns its last step's
      value but declared `:return-type :any` with no taint propagation,
      so `(:do :steps [1 secret-ref])` returned the real secret while
      its registered type stayed `:any` → `tainted-fn?` false → no
      `/api/execute` redaction. Fixed by marking `:do :taint-propagate?
      true` (see `core/concurrency/impls`) — which, combined with fix
      2, taints a `:do` whose `:steps` list carries a secret.

   2. A `[:secret …]` element inside a heterogeneous LIST argument was
      dropped by the coarse lub: the binding's `:type` collapses to
      `[:list :any]`, so `taint-with-secret-if-tainted` (which scanned
      `:type` only) missed it — a `:str` over `[\"Bearer \" secret-ref]`
      returned plain `:text`. Fixed by also scanning `:elem-types`.
      This same fix is what lets `:do`'s markerless `[:list :any]`
      `:steps` slot still surface a secret element.

   Both are pure type-rule tests — no DB, unit suite. The flag itself
   is anti-drift-pinned in `taint-propagate-guard-test`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.core :as types]))


(def ^:private secret-text [:secret :text])


(deftest taint-scans-list-elem-types
  (testing "secret element inside a heterogeneous list taints the result"
    ;; `:type` is the coarse-lubbed `[:list :any]` (marker already gone);
    ;; the secret survives only in `:elem-types`.
    (is (types/contains-secret?
          (types/taint-with-secret-if-tainted
            {:parts {:type [:list :any]
                     :elem-types [:text secret-text]}}
            :text))
        "a [\"Bearer \" secret] list arg must propagate taint to the result"))
  (testing "no secret anywhere → result stays plain"
    (is (not (types/contains-secret?
               (types/taint-with-secret-if-tainted
                 {:parts {:type [:list :text]
                          :elem-types [:text :text]}}
                 :text))))))
