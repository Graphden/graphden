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


(defn- presence
  [expressions supplied root]
  (let [result (volatile! {})]
    (letfn [(present?
              [address]
              (if-let [entry (find @result address)]
                (val entry)
                (let [{:keys [op source fields]} (get expressions address)
                      found (case op
                              :supplied (contains? supplied address)
                              :read (present? source)
                              :record (or (= address [root []])
                                          (boolean (some #(present? (second %)) fields)))
                              true)]
                  (vswap! result assoc address found)
                  found)))]
      (doseq [address (keys expressions)] (present? address))
      @result)))


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


(defn- resolve-expression
  [frame address ctx]
  (let [{:keys [op value source fields scope target-id]} (get-in frame [:ir :expressions address])]
    (case op
      :literal value
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


(defn instantiate
  "Allocate a complete frame before exposing any resolver. Forward reads
   observe the frozen cell table. Memo keys never dereference those cells."
  [ir validators supplied ctx]
  (when-not (map? supplied) (ir/reject! :address-map-required {}))
  (when (some #(not (contains? (:parameters ir) %)) (keys supplied))
    (ir/reject! :unknown-supplied-address {}))
  (let [present (presence (:expressions ir) supplied (:root-scope ir))
        missing (filterv #(not (get present %)) (:required-addresses ir))]
    (when (seq missing) (ir/reject! :missing-required-input {:addresses missing}))
    (let [frozen (volatile! nil)
          cells (into {} (map (fn [address]
                                [address (input/field-cell
                                           #(let [value (resolve-expression @frozen address ctx)]
                                              (if-let [check (get validators address)] (check value) value)))]))
                      (keys (:expressions ir)))
          frame {:ir ir :validators validators :supplied supplied :present present :cells cells}
          frame (assoc frame :input-keys (memo/scope-keys frame))]
      (vreset! frozen frame)
      frame)))
