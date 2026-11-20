(ns graphden.input.frame
  "Invocation-local cells for the canonical fixture IR. Dependencies enter
   the existing executor memo seam; this namespace does not execute roots."
  (:require
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.runtime :as runtime]
    [graphden.input.ir :as ir]
    [graphden.input.memo :as memo]
    [graphden.input.runtime :as input]))


(defn- legacy-arguments
  [frame scope paths]
  ;; The outer plain map is essential: defbase resolves each OUTER thunk
  ;; once. Passing a record view here would double-force Atom/Delay/thunk
  ;; values produced by a reference. Nested record values remain opaque.
  (into {} (keep (fn [[name path]]
                   (let [address [scope path]]
                     (when (get-in frame [:present address])
                       [name (runtime/thunk #(deref (get-in frame [:cells address])))])))) paths))


(defn invoke-scope
  "Invoke a resolved base implementation with the legacy outer-thunk ABI.
   Root orchestration remains graphden.executor.compile-runtime/execute."
  [frame scope ctx]
  (executor/check-cancel!)
  (let [{:keys [impl argument-paths]} (get-in frame [:ir :scopes scope])]
    (impl (legacy-arguments frame scope argument-paths) ctx)))


(declare ^:private instantiate-frame)


(defn- callback-supplies
  [{:keys [parameters call-sites]} args]
  (when-not (if (seq parameters) (map? args) (nil? args))
    (ir/reject! :callback-arguments-required {}))
  (let [known (set parameters)]
    (when-not (every? known (keys args))
      (ir/reject! :unknown-callback-parameter {})))
  (into {} (keep (fn [{:keys [name address]}]
                   (when (contains? args name) [address (get args name)]))) call-sites))


(defn- lexical-callable
  [frame address target-id ctx]
  (let [{:keys [scope parameters] :as plan} (get-in frame [:ir :closures address])
        {:keys [thread open?]} (:lifetime frame)]
    (eager/tagged-callable
      target-id parameters
      (fn [args]
        ;; Synchronous HOFs reuse their executor boundary and the captured
        ;; outer cells. A deferred invocation needs a separate lifetime proof.
        (when-not (and @open? (identical? thread (Thread/currentThread)))
          (ir/reject! :callback-outside-invocation {:scope scope}))
        (executor/check-cancel!)
        (let [supplied (callback-supplies plan args)
              local (instantiate-frame (:ir frame) (:validators frame) scope supplied frame ctx)]
          (invoke-scope local scope ctx))))))


(defn- resolve-expression
  [frame address ctx]
  (let [{:keys [op value source fields scope target-id]} (get-in frame [:ir :expressions address])]
    (case op
      :literal value
      :closure (lexical-callable frame address target-id ctx)
      :supplied (get-in frame [:supplied address])
      :read @(get-in frame [:cells source])
      :record (input/record-view
                (into {} (keep (fn [[name child]]
                                 (when (get-in frame [:present child])
                                   [name (get-in frame [:cells child])])) fields)))
      :invoke (let [{:keys [base-fn-id argument-paths name]} (get-in frame [:ir :scopes scope])]
                (executor/check-cancel!)
                (eager/invoke-compiled
                  {:target-id target-id :name name :specialization [base-fn-id argument-paths]
                   :input-key (get-in frame [:input-keys scope])}
                  #(invoke-scope frame scope %) ctx)))))


(defn- instantiate-frame
  [ir validators scope supplied parent ctx]
  (when-not (map? supplied) (ir/reject! :address-map-required {}))
  (let [{:keys [scopes parameters presence-program required-addresses]} (get-in ir [:frame-layouts scope])
        _ (when-not (every? parameters (keys supplied)) (ir/reject! :unknown-supplied-address {}))
        present (ir/present-addresses presence-program supplied (:present parent {}))
        missing (filterv #(not (get present %)) required-addresses)]
    (when (seq missing) (ir/reject! :missing-required-input {:addresses missing}))
    (let [frozen (volatile! nil)
          cells (into (:cells parent {}) (map (fn [{:keys [address]}]
                                                [address (input/field-cell
                                                           #(let [value (resolve-expression @frozen address ctx)]
                                                              (if-let [check (get validators address)] (check value) value)))]))
                      presence-program)
          frame {:ir ir :validators validators :supplied (into (:supplied parent {}) supplied)
                 :present present :cells cells :scopes scopes :invocation-key (when parent (Object.))
                 :lifetime (if parent (:lifetime parent)
                               (when (seq (:closures ir))
                                 {:thread (Thread/currentThread) :open? (volatile! true)}))}
          frame (assoc frame :input-keys (memo/scope-keys frame))]
      (vreset! frozen frame)
      frame)))


(defn instantiate
  "Freeze the outer frame before exposing any resolver. Callback cells are
   allocated only when the tagged callable is actually invoked."
  [ir validators supplied ctx]
  (instantiate-frame ir validators (:root-scope ir) supplied nil ctx))


(defn invoke-root
  "Close the synchronous callback lifetime as the enclosing invocation exits."
  [frame ctx]
  (let [scope (get-in frame [:ir :root-scope])]
    (if-let [open? (get-in frame [:lifetime :open?])]
      (try (invoke-scope frame scope ctx)
           (finally (vreset! open? false)))
      (invoke-scope frame scope ctx))))
