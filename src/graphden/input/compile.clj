(ns graphden.input.compile
  "Checked-plan fixture compiler. Deliberately not installed in production
   registry construction: unsupported expression kinds fail before execution."
  (:require
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.input.arguments :as arguments]
    [graphden.input.context :as context]
    [graphden.input.frame :as frame]
    [graphden.input.ir :as ir]
    [graphden.types.validator :as validator]))


(defn compile-checked
  "Return the executor's ordinary (fn [address-values ctx]) closure. Supply
   frozen :context and explicit :implementations (see ir/lower-checked).
   This fixture API accepts leaf addresses, not public authoring names or
   arbitrary whole-record parameters. It does not bypass execute's guards.
   Primitive field-read obligations use the existing validator. Supported
   closures retain a synchronous invocation lifetime. Container obligations
   and list builders require separate lowering."
  [analysis {:keys [context implementations]}]
  (let [plan (ir/lower-checked analysis {:snapshot-id (:snapshot-id context)
                                         :target-names (update-vals (:fns context) :name)
                                         :implementations implementations})
        validators (context/call-with-types
                     context
                     #(into {} (map (fn [[address type]] [address (validator/build type)]))
                            (merge (into {} (keep (fn [[address {:keys [op type]}]]
                                                    (when (= :supplied op) [address type])))
                                         (:expressions plan))
                                   (:value-obligations plan))))]
    (fn [supplied ctx]
      (let [ctx (eager/invocation-context ctx)
            frame (frame/instantiate plan validators supplied ctx)]
        (frame/invoke-root frame ctx)))))


(defn compile-named
  "Compile a checked frozen surface with named structured invocation inputs.
   References expose their residual inputs at their use-site name. The result
   uses the executor's ordinary two-argument closure ABI; callers must retain
   its normal authorization, effects and cancellation boundary."
  [analysis options]
  (let [invoke (compile-checked analysis options)
        surface (:public-input analysis)]
    (executor/named-input-closure
      (fn [supplied ctx]
        (invoke (arguments/route-values surface supplied) ctx)))))
