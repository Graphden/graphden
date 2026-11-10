(ns graphden.executor.checked-source-runtime
  "Bounded checker artifacts owned by one frozen executor runtime. Branches
   share this budget. Development reloads must halt the runtime first."
  (:require
    [graphden.executor.checked-source-data :as data]
    [graphden.executor.checked-source-memo :as memo]))


(def ^:private approved-rules
  ;; Exact built-in identities, not arbitrary registry callbacks. Missing
  ;; optional packages simply leave their rules ineligible for memoization.
  {'graphden.packages.core.logic.impls
   '[assert-some-return-rule case-return-rule coalesce-return-rule cond-return-rule
     ensure-type-return-rule if-return-rule]
   'graphden.packages.core.collections.impls
   '[assoc-in-return-rule assoc-return-rule concat-return-rule conj-return-rule
     dissoc-return-rule flatten-return-rule get-in-return-rule get-return-rule
     into-return-rule keys-return-rule list-return-rule merge-return-rule
     pairs->map-return-rule select-keys-return-rule update-in-nav-rule
     update-in-return-rule update-in-slot-rule update-keys-return-rule
     update-vals-return-rule vals-return-rule zipmap-return-rule]
   'graphden.packages.core.strings.impls '[name-return-rule str-to-keyword-return-rule]
   'graphden.packages.core.arithmetic.impls
   '[abs-return-rule add-return-rule mod-return-rule mul-return-rule neg-return-rule sub-return-rule]
   'graphden.packages.core.system.impls '[invoke-return-rule]
   'graphden.packages.web.html.impls '[encode-view-return-rule]})


(defn create-cache
  "Create after built-ins load. No eager reservation or global instance.
   Fixed limits cover retained artifacts AND concurrent checker reservations;
   overflow always performs the ordinary fresh check. Halt before code reload."
  []
  (memo/create-memo
    {:max-bytes (* 512 1024 1024) :entry-bytes (* 256 1024 1024)
     :max-entries 16 :max-pending 4 :max-families 32 :max-leases 32
     :max-ttl-ms 600000 :ttl-ms 600000 :max-nodes 4000000
     :clock-ms #(quot (System/nanoTime) 1000000)
     ;; No persistence or sharing across runtime instances/builds. Changes to
     ;; checker semantics require a new runtime, not an in-place code reload.
     :checker-abi "builtin-checker-runtime-v1"
     :callbacks (into []
                      (for [[ns-sym names] approved-rules
                            :let [loaded (find-ns ns-sym)]
                            :when loaded
                            rule names
                            :let [v (ns-resolve loaded rule)]
                            :let [callback (when (and v (bound? v)) @v)]
                            :when (data/static-callback? callback)]
                        callback))}))
