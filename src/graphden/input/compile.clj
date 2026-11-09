(ns graphden.input.compile
  "Checked-plan fixture compiler. Deliberately not installed in production
   registry construction: unsupported expression kinds fail before execution."
  (:require
    [graphden.executor.compile-eager :as eager]
    [graphden.input.context :as context]
    [graphden.input.frame :as frame]
    [graphden.input.ir :as ir]
    [graphden.types.validator :as validator]))


(defn compile-checked
  "Return the executor's ordinary (fn [address-values ctx]) closure. Supply
   frozen :context and explicit :implementations (see ir/lower-checked).
   This fixture API accepts leaf addresses, not public authoring names or
   arbitrary whole-record parameters. It does not bypass execute's guards.
   Primitive field-read obligations use the existing validator. Container
   obligations, graph-callable closures and list builders are unsupported
   cases, not fallback to the legacy compiler."
  [analysis {:keys [context implementations]}]
  (let [plan (ir/lower-checked analysis {:snapshot-id (:snapshot-id context)
                                         :target-names (update-vals (:fns context) :name)
                                         :implementations implementations})
        validators (context/call-with-types
                     context
                     #(into {} (map (fn [[address type]] [address (validator/build type)]))
                            (merge (into {} (map (fn [address]
                                                   [address (get-in plan [:expressions address :type])]))
                                         (:parameters plan))
                                   (:value-obligations plan))))]
    (fn [supplied ctx]
      (let [ctx (eager/invocation-context ctx)
            frame (frame/instantiate plan validators supplied ctx)]
        (frame/invoke-scope frame (:root-scope plan) ctx)))))
