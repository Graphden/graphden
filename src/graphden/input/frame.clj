(ns graphden.input.frame
  "Invocation-local cells for the canonical fixture IR. Dependencies enter
   the existing executor memo seam; this namespace does not execute roots."
  (:require
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.runtime :as runtime]
    [graphden.input.arguments :as arguments]
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


(declare ^:private instantiate-frame ^:private invoke-frame)


(defn- callback-supplies
  [{:keys [parameters call-sites parameter-surface]} args]
  (when-not (if (seq parameters) (map? args) (nil? args))
    (ir/reject! :callback-arguments-required {}))
  (let [known (set parameters)]
    (when-not (every? known (keys args))
      (ir/reject! :unknown-callback-parameter {})))
  (if parameter-surface
    ;; Presence routing needs a supplied address. Its deferred source
    ;; remains separate from eager values and is only read by the new cell.
    (let [input-sources (volatile! {})
          supplied (arguments/route-values
                     parameter-surface args
                     (fn [record {:keys [name address]}]
                       (if (input/record-view? record)
                         (do (vswap! input-sources assoc address (get (input/cache-token record) name))
                             nil)
                         (get record name))))]
      [supplied @input-sources])
    [(into {} (keep (fn [{:keys [name address]}]
                      (when (contains? args name) [address (get args name)]))) call-sites) nil]))


(defn- active-callback?
  [{:keys [thread open?]}]
  (and (identical? thread (Thread/currentThread)) @open?))


(defn- check-callback-lifetime!
  [lifetime scope]
  (when-not (active-callback? lifetime)
    (ir/reject! :callback-outside-invocation {:scope scope})))


(defn- lexical-callable
  [frame address target-id ctx]
  (let [{:keys [scope parameters detachable?] :as plan} (get-in frame [:ir :closures address])]
    (eager/tagged-callable
      target-id parameters
      (fn [args]
        (let [active? (active-callback? (:lifetime frame))
              _ (when-not (or active? detachable?) (check-callback-lifetime! (:lifetime frame) scope))
              _ (executor/check-cancel!)
              [supplied input-sources] (callback-supplies plan args)
              parent (when active? frame)
              ctx (if parent ctx (eager/independent-invocation-context ctx))
              local (instantiate-frame (:ir frame) (:validators frame) scope supplied input-sources parent ctx)]
          (if parent (invoke-scope local scope ctx) (invoke-frame local scope ctx)))))))


(defn- resolve-expression
  [frame address ctx]
  (let [{:keys [op value source fields scope target-id default nil-when-absent?]}
        (get-in frame [:ir :expressions address])]
    (case op
      :literal value
      :closure (lexical-callable frame address target-id ctx)
      :supplied (if-let [cell (get (:input-sources frame) address)]
                  @cell
                  (get (:supplied frame) address default))
      :read (when (or (not nil-when-absent?) (get-in frame [:present source]))
              @(get-in frame [:cells source]))
      :record (let [view (if (= :any (get-in frame [:ir :whole-record-inputs address]))
                           input/dictionary-view input/record-view)
                    extras (get (:supplied frame) address {})
                    extra-cells (if (input/record-view? extras) (input/cache-token extras) (update-vals extras input/value-cell))]
                (view (into extra-cells (keep (fn [[name child]]
                                                (when (get-in frame [:present child])
                                                  [name (get-in frame [:cells child])])))
                            fields)))
      :invoke (let [{:keys [base-fn-id argument-paths name]} (get-in frame [:ir :scopes scope])]
                (executor/check-cancel!)
                (eager/invoke-compiled
                  {:target-id target-id :name name :specialization [base-fn-id argument-paths]
                   :input-key (get-in frame [:input-keys scope])}
                  #(invoke-scope frame scope %) ctx)))))


(defn- required-on-entry
  [program supplied]
  (into #{} (comp (filter #(and (= :supplied-record (:kind %)) (contains? supplied (:address %))))
                  (mapcat :required-fields))
        program))


(defn- instantiate-frame
  [ir validators scope supplied input-sources parent ctx]
  (when-not (map? supplied) (ir/reject! :address-map-required {}))
  (let [{:keys [scopes parameters record-inputs presence-program required-addresses]} (get-in ir [:frame-layouts scope])
        _ (when-not (every? (fn [[address value]]
                              (or (contains? parameters address)
                                  (and (contains? record-inputs address)
                                       (or (= {} value)
                                           (and (contains? (:whole-record-inputs ir) address)
                                                (map? value) (or (= :any (get-in ir [:whole-record-inputs address]))
                                                                 (every? keyword? (keys value)))
                                                (not-any? #(contains? value (first %))
                                                          (get-in ir [:expressions address :fields])))))))
                            supplied)
            (ir/reject! :unknown-supplied-address {}))
        present (ir/present-addresses presence-program supplied (:present parent {}))
        required (into required-addresses (required-on-entry presence-program supplied))
        missing (filterv #(not (get present %)) required)]
    (when (seq missing) (ir/reject! :missing-required-input {:addresses missing}))
    (let [frozen (volatile! nil)
          cell (fn [{:keys [address]}]
                 (let [read-value (fn []
                                    (when parent (check-callback-lifetime! (:lifetime parent) scope))
                                    (let [value (resolve-expression @frozen address ctx)]
                                      (if-let [check (get validators address)] (check value) value)))]
                   [address (input/field-cell read-value)]))
          cells (into (:cells parent {}) (map cell) presence-program)
          frame {:ir ir :validators validators :supplied (into (:supplied parent {}) supplied)
                 :input-sources (into (:input-sources parent {}) input-sources)
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
  (instantiate-frame ir validators (:root-scope ir) supplied nil nil ctx))


(defn- invoke-frame
  [frame scope ctx]
  (if-let [open? (get-in frame [:lifetime :open?])]
    (try (invoke-scope frame scope ctx)
         (finally (vreset! open? false)))
    (invoke-scope frame scope ctx)))


(defn invoke-root
  "Close the synchronous callback lifetime as the enclosing invocation exits."
  [frame ctx]
  (invoke-frame frame (get-in frame [:ir :root-scope]) ctx))
