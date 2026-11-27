(ns graphden.input.compile
  "Checked-plan fixture compiler. Deliberately not installed in production
   registry construction: unsupported expression kinds fail before execution."
  (:require
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.input.analysis :as analysis]
    [graphden.input.arguments :as arguments]
    [graphden.input.context :as context]
    [graphden.input.frame :as frame]
    [graphden.input.ir :as ir]
    [graphden.input.stored-implementations :as stored]
    [graphden.types.check :as check]
    [graphden.types.validator :as validator]))


(defn- check-record-keys
  [{:keys [required key-type]}]
  (fn [value]
    (when-not (and (map? value) (or (= :any key-type) (every? keyword? (keys value))) (every? #(contains? value %) required))
      (throw (ex-info "Value does not match the required type"
                      {:type :execution-error/type-mismatch :errors [{:path [] :code :type-mismatch}]})))
    value))


(defn- field-validator
  [types]
  (let [checks (mapv validator/build (distinct types))]
    (fn [value]
      (doseq [check checks] (check value))
      value)))


(defn- compile-frame
  [analysis {:keys [context implementations detached-closures?]}]
  (let [plan (ir/lower-checked analysis {:snapshot-id (:snapshot-id context)
                                         :target-names (update-vals (:fns context) :name)
                                         :implementations implementations
                                         :detached-closures? detached-closures?})
        checks (merge-with into
                           (into {} (keep (fn [[address {:keys [op type]}]]
                                            (when (= :supplied op) [address [type]])))
                                 (:expressions plan))
                           (:value-obligations plan))
        validators (context/call-with-types
                     context
                     #(into (update-vals (:container-obligations plan) check-record-keys)
                            (update-vals checks field-validator)))]
    {:whole-record-inputs (:whole-record-inputs plan)
     :invoke (fn [supplied ctx]
               (let [ctx (eager/invocation-context ctx)
                     frame (frame/instantiate plan validators supplied ctx)]
                 (frame/invoke-root frame ctx)))}))


(defn compile-checked
  "Return the executor's ordinary (fn [address-values ctx]) closure. Supply
   frozen :context and explicit :implementations (see ir/lower-checked).
   This fixture API accepts leaf addresses and presence maps at checked record
   addresses. A wholly supplied record consumed as a keyword dictionary can
   carry its additional entries at that record address. The named adapter routes public input. Execute still applies
   its authorization and cancellation guards.
   Primitive field-read obligations use the existing validator. With
   :detached-closures? only closures with no external inputs can outlive
   their creating invocation. Unsupported obligations fail during lowering."
  [analysis options]
  (:invoke (compile-frame analysis options)))


(defn compile-named
  "Compile a checked frozen surface with named structured invocation inputs.
   References expose their residual inputs at their use-site name. The result
   uses the executor's ordinary two-argument closure ABI; callers must retain
   its normal authorization, effects and cancellation boundary."
  [analysis options]
  (let [{:keys [invoke whole-record-inputs]} (compile-frame analysis options)
        surface (assoc (:public-input analysis) :whole-record-inputs whole-record-inputs)]
    (executor/named-input-closure
      (fn [supplied ctx]
        (invoke (arguments/route-values surface supplied) ctx)))))


(defn analyze-stored
  "Resolve trusted implementations and check their frozen stored input."
  [ctx owner trusted]
  (let [implementations (stored/resolve-closure ctx owner trusted)]
    {:implementations implementations
     :analysis (analysis/analyze-effective-input ctx owner {:implementations implementations
                                                            :check-expression check/check-input-binding
                                                            :infer-signature check/infer-input-signature})}))


(defn compile-stored
  "Prepare one authorized branch-resolved input snapshot for named invocation.
   trusted maps base UUIDs to native implementations and frozen signatures.
   The returned closure still uses the ordinary executor entrypoint."
  [ctx owner trusted]
  (let [{:keys [analysis implementations]} (analyze-stored ctx owner trusted)]
    {:analysis analysis
     :invoke (compile-named analysis {:context ctx :implementations implementations})}))
