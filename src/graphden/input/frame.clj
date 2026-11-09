(ns graphden.input.frame
  "Invocation-local cells for the canonical fixture IR. Dependencies enter
   the existing executor memo seam; this namespace does not execute roots."
  (:require
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.runtime :as runtime]
    [graphden.input.capture :as capture]
    [graphden.input.ir :as ir]
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


(defn- input-key
  [frame scope]
  ;; Project to the explicitly resolved base ABI, not all visible fields.
  ;; Scope/use-path is binding identity, never a blanket call-cache partition.
  (into {} (map (fn [[name path]]
                  [name (get-in frame [:tokens [scope path]])]))
        (get-in frame [:ir :scopes scope :argument-paths])))


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
                   :input-key (input-key frame scope)}
                  #(invoke-scope frame scope %) ctx)))))


(defn- tokens
  [{:keys [ir supplied present]}]
  (let [result (volatile! {})]
    (letfn [(token
              [address]
              (if-let [entry (find @result address)]
                (val entry)
                (let [{:keys [op source value fields scope target-id binding-id type]} (get-in ir [:expressions address])
                      known (if (= :supplied op) (get supplied address) value)
                      value (cond
                              (not (get present address)) [:absent]
                              (= :read op) (let [source-key (token source)]
                                             (if (= :value (first source-key))
                                               [:value type (last source-key)]
                                               [:read type source-key]))
                              (= :record op) [:record (into {} (map (fn [[name child]] [name (token child)])) fields)]
                              (= :invoke op)
                              ;; A declaration's local binding has the same
                              ;; computational identity at repeated ref uses.
                              ;; Fresh per-use cells here would repeat effects
                              ;; in a named parent whose free inputs are equal.
                              [:computation binding-id target-id type
                               (into {} (map (fn [[name path]] [name (token [scope path])]))
                                     (get-in ir [:scopes scope :argument-paths]))]
                              (capture/safe-value? known) [:value type known]
                              :else (ir/reject! :opaque-input-key-required {:address address}))]
                  (vswap! result assoc address value)
                  value)))]
      (doseq [address (keys (:expressions ir))] (token address))
      @result)))


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
          frame (assoc frame :tokens (tokens frame))]
      (vreset! frozen frame)
      frame)))
