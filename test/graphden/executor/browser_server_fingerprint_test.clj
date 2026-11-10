(ns ^:serial graphden.executor.browser-server-fingerprint-test
  "Exercise request-local digest reuse through real descriptor and invocation
   boundaries. Source/auth are captured twice; only the outer digest may reuse."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.editor.components :as components]
    [graphden.executor.browser-plan :as wire]
    [graphden.executor.browser-server :as server]
    [graphden.executor.browser-server-source :as server-source]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile-eager :as eager]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.storage.binding-read :as binding-read]
    [graphden.storage.remote.core :as remote]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]))


(defn- fixture
  [value consume]
  (let [root (random-uuid)
        target (random-uuid)
        slot capabilities/server-call-slot-id
        literal-slot (random-uuid)
        site [root slot]
        graph (atom {:fns [{:id root} {:id target}]
                     :slots [{:id slot :name "func"} {:id literal-slot :name "literal"}]
                     :fn-slots [{:id (random-uuid) :fn-id root :slot-id slot :position 0}
                                {:id (random-uuid) :fn-id root :slot-id literal-slot :position 1}]
                     :bindings [{:id (random-uuid) :fn-id root :slot-id slot :ref-fn-id target}
                                {:id (random-uuid) :fn-id root :slot-id literal-slot
                                 :value-present true :value value}]
                     :list-items []})
        anchor (atom {:id (random-uuid) :fn-id (random-uuid)
                      :slot-id (random-uuid) :ref-fn-id root})
        digest (atom "fresh target digest")
        allowed? (atom true)
        after-execute (atom (constantly nil))
        events (atom [])
        fingerprint provenance/fingerprint-data
        record! #(swap! events conj [% (.getId (Thread/currentThread))])
        authorize! (fn [& _]
                     (record! :authorize)
                     (when-not @allowed?
                       (throw (ex-info "Revoked" {:type :authz/forbidden}))))
        storage (assoc (remote/from-bundle {}) :branch-id (random-uuid))
        ctx {:storage storage :invalidation-count (atom 0)
             :request-scope (fn [_ _ thunk] (thunk)) :base-fns {}}
        request {:headers {"authorization" "Bearer fingerprint-fixture"}}]
    (binding [tenancy/*current-org* tenancy/public-org tenancy/*current-principal* nil]
      (with-redefs [binding-read/owner-bindings (fn [& _] [@anchor])
                    source/with-snapshot (fn [storage thunk]
                                           (record! :snapshot)
                                           (thunk storage authorize!))
                    source/collect-client-closure
                    (fn [_ _ fresh-authorize!]
                      (record! :source)
                      (doseq [row (:fns @graph)] (fresh-authorize! row))
                      ;; Fresh maps, not a shared graph identity, on every pass.
                      (into {} (map (fn [[k rows]] [k (mapv #(into {} %) rows)])) @graph))
                    source/collect-namespaces (fn [& _] [])
                    server-source/capture
                    (fn [& _]
                      (record! :target)
                      {:graph @graph :signature {:args {} :return :text}
                       :fingerprint @digest :input-type {:query :text} :output-type :text})
                    snapshot/export-snapshot
                    (fn [captured _ _ _] (get-in captured [:server-plan-options :server-descriptors]))
                    snapshot/capture-context-policy (constantly {})
                    components/assert-current-policy! (constantly nil)
                    recheck/call-with-ctx-slices (fn [_ thunk] (thunk))
                    eager/compile-all (constantly {})
                    persist/acquire-execution-slot! (fn [& _] (constantly nil))
                    persist/redact-outcome (fn [_ result] result)
                    runtime/execute (fn [& _]
                                      (record! :execute)
                                      (@after-execute)
                                      "result")
                    provenance/fingerprint-data
                    (fn [input]
                      (when (and (map? input) (= #{:graph :anchor :targets} (set (keys input))))
                        (record! :outer-digest))
                      (fingerprint input))]
        (let [descriptors (server/export-client-plan ctx storage authorize! {} {:root root} request @anchor)
              input {:descriptor (get descriptors site) :input (wire/encode-value {:query ""})}]
          (is (some? (:descriptor input)))
          (reset! events [])
          (consume {:invoke #(server/invoke-current ctx request input)
                    :graph graph :anchor anchor :digest digest :allowed? allowed?
                    :after-execute after-execute :events events}))))))


(defn- event-count
  [events event]
  (count (filter #(= event (first %)) @events)))


(deftest unchanged-input-reuses-only-the-outer-digest-within-one-invocation
  (fixture
    {:nested [1 2] :flags #{:one :two}}
    (fn [{:keys [invoke events]}]
      (dotimes [i 2]
        (is (= {:ok true :value (wire/encode-value "result")} (invoke)))
        (is (= (inc i) (event-count events :outer-digest)))
        (is (= (* 2 (inc i)) (event-count events :snapshot)))
        (is (= (* 2 (inc i)) (event-count events :source)))
        (is (= (* 2 (inc i)) (event-count events :target))))
      (let [owner (.getId (Thread/currentThread))
            threads (set (map second (filter #(= :source (first %)) @events)))]
        (is (contains? threads owner))
        (is (seq (disj threads owner)) "Actual bounded worker and owner postcheck both ran")))))


(deftest changes-to-any-evidence-part-recompute-and-refuse-the-response
  (doseq [part [:graph :anchor :target]]
    (testing (name part)
      (fixture
        :original
        (fn [{:keys [invoke graph anchor digest after-execute events]}]
          (reset! after-execute
                  #(case part
                     :graph (swap! graph assoc-in [:bindings 1 :value] :changed)
                     :anchor (swap! anchor assoc :description "changed")
                     :target (reset! digest "changed target digest")))
          (is (false? (:ok (invoke))))
          (is (= 2 (event-count events :outer-digest)))
          (is (= 2 (event-count events :target))))))))


(deftest clojure-equal-values-cannot-reuse-different-fingerprints
  (doseq [[before after] [[[1] '(1)] [1 1N] [0.0 -0.0] [1M 1.00M]
                          [{:nested [1]} {:nested '(1)}]
                          [#{[1]} #{'(1)}] [{1 :key} {1N :key}]]]
    (is (= before after))
    (is (not= (provenance/fingerprint-data before) (provenance/fingerprint-data after)))
    (fixture
      before
      (fn [{:keys [invoke graph after-execute events]}]
        (reset! after-execute #(swap! graph assoc-in [:bindings 1 :value] after))
        (is (false? (:ok (invoke))))
        (is (= 2 (event-count events :outer-digest)))))))


(deftest printed-map-key-collisions-never-reuse-order-sensitive-evidence
  (doseq [[a b] [[(keyword nil "a/b") (keyword "a" "b")]
                 [(keyword "a/b" "c") (keyword "a" "b/c")]
                 [(symbol nil "a/b") (symbol "a" "b")]]]
    (let [before (array-map a 1 b 2)
          after (array-map b 2 a 1)]
      (is (not= a b))
      (is (= (pr-str a) (pr-str b)))
      (is (= before after))
      (is (not= (provenance/fingerprint-data before) (provenance/fingerprint-data after)))
      (fixture
        before
        (fn [{:keys [invoke graph after-execute events]}]
          (reset! after-execute #(swap! graph assoc-in [:bindings 1 :value] after))
          (is (false? (:ok (invoke))))
          (is (= 2 (event-count events :outer-digest))))))))


(deftest injective-map-keys-and-set-print-ties-retain-canonical-bytes
  (let [id (random-uuid)
        keys-and-values [":a/b" 1 :a/b 2 id 3 (pr-str id) 4]
        mapping (apply array-map keys-and-values)
        symbols [(symbol nil "a/b") (symbol "a" "b")]]
    (doseq [[before after] [[mapping (into (array-map) (reverse mapping))]
                            [(set symbols) (set (reverse symbols))]]]
      (is (= (provenance/fingerprint-data before) (provenance/fingerprint-data after)))
      (fixture
        before
        (fn [{:keys [invoke graph after-execute events]}]
          (reset! after-execute #(swap! graph assoc-in [:bindings 1 :value] after))
          (is (:ok (invoke)))
          (is (= 1 (event-count events :outer-digest))))))))


(deftest unsupported-values-always-miss-including-identical-mutable-values
  (doseq [value [(java.util.Date. 0) (sorted-map :value 1) {1 :unsupported-key} {'symbol :unsupported-key}]]
    (fixture value
             (fn [{:keys [invoke events]}]
               (is (:ok (invoke)))
               (is (= 2 (event-count events :outer-digest)))))))


(deftest mutating-an-identical-leaf-still-retires-the-response
  (let [value (java.util.Date. 0)]
    (fixture
      value
      (fn [{:keys [invoke after-execute events]}]
        (reset! after-execute #(java.util.Date/.setTime value 1000))
        (let [result (invoke)]
          (is (false? (:ok result)))
          (is (not (contains? result :value)))
          (is (= 2 (event-count events :outer-digest))))))))


(deftest revocation-after-execution-is-still-freshly-checked
  (fixture
    :unchanged
    (fn [{:keys [invoke allowed? after-execute events]}]
      (reset! after-execute #(reset! allowed? false))
      (let [result (invoke)]
        (is (false? (:ok result)))
        (is (= "forbidden" (:failure-type result)))
        (is (not (contains? result :value)))
        (is (= 2 (event-count events :source)))
        (is (= 1 (event-count events :execute)))))))
