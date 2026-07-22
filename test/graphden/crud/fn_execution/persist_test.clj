(ns ^:serial graphden.crud.fn-execution.persist-test
  "Unit tests for the pure helpers in `graphden.crud.fn-execution.persist`.

   `^:serial` because `log-effect-drift-widened-warns` and its
   sibling redef the global `clojure.tools.logging/log*` via
   `with-redefs`. Under in-JVM parallel runs that redef leaks into
   any other test that emits a log message during the window,
   inflating the `(count @calls)` assertion. Forcing this NS into
   the sequential bucket (kaocha.plugin/parallel) keeps the redef
   scoped to this NS's tests only.
   The DB-touching write paths are covered indirectly by the
   integration tests in `graphden.crud.fn-execution-test` — this
   file focuses on truncation, futures-registry lifecycle,
   ref/scalar discrimination, and effect-drift logging that the
   integration suite mostly walks past."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; truncate-error — string clamp at max-error-chars
;; =============================================================================

(deftest truncate-error-within-limit
  (is (= "hello" (persist/truncate-error "hello"))
      "short strings pass through verbatim"))


(deftest truncate-error-coerces-non-string
  (is (= "42" (persist/truncate-error 42))
      "non-string inputs are stringified first")
  (is (= "" (persist/truncate-error nil))
      "nil → empty string (no NPE)"))


(deftest truncate-error-oversize-clamped-with-ellipsis
  (let [huge (str/join (repeat (+ persist/max-error-chars 10) \x))
        out (persist/truncate-error huge)]
    (is (= (inc persist/max-error-chars) (count out)))
    (is (str/ends-with? out "…"))))


;; =============================================================================
;; jsonize-result — [ok? value] tuple, rejects oversize
;; =============================================================================

(deftest jsonize-result-small-passes
  (let [[ok v] (persist/jsonize-result {:a 1 :b [2 3]})]
    (is ok)
    (is (= {:a 1 :b [2 3]} v))))


(deftest jsonize-result-oversize-rejected
  ;; Build a string genuinely past the 5 MB jsonb cap.
  (let [huge (str/join (repeat (inc persist/max-result-bytes) \x))
        [ok v] (persist/jsonize-result huge)]
    (is (not ok))
    (is (nil? v))))


(deftest json-bytes-within-uses-utf8-not-chars
  ;; The result-size gate is a BYTE budget. "é" serialises to the JSON
  ;; token `"é"` = quote + 2-byte é + quote = 4 UTF-8 bytes, but only 3
  ;; UTF-16 code units — so a char-count gate would wrongly pass at 3.
  (is (persist/json-bytes-within? "é" 4))
  (is (not (persist/json-bytes-within? "é" 3))
      "must reject on the 4th byte — proves UTF-8 semantics + early abort"))


(deftest json-bytes-within-unserializable-is-refused
  ;; A value cheshire can't encode is treated as oversize (false), not a
  ;; thrown exception that would crash the persist path.
  (is (not (persist/json-bytes-within? (Object.) persist/max-result-bytes))))


;; =============================================================================
;; jsonize-error-data — keep ed when small; fall back to {:type :truncated}
;; =============================================================================

(deftest jsonize-error-data-small-passes
  (is (= {:type :foo :extra 1} (persist/jsonize-error-data {:type :foo :extra 1}))))


(deftest jsonize-error-data-oversize-keeps-only-type
  (let [huge (str/join (repeat (inc persist/max-error-data-bytes) \x))
        out (persist/jsonize-error-data {:type :too-big :details huge})]
    (is (= {:type :too-big :truncated true} out)
        "oversize payload collapses to the bare canonical type tag")))


;; =============================================================================
;; args-bytes — JSON serialized length
;; =============================================================================

(deftest args-bytes-empty
  (is (= 2 (persist/args-bytes {}))))          ; "{}" — 2 UTF-8 bytes


(deftest args-bytes-scalar-map
  ;; ASCII: UTF-8 bytes == char count.
  (let [args {:a 1 :b "two"}]
    (is (= (count (json/generate-string args))
           (persist/args-bytes args)))))


(deftest args-bytes-counts-utf8-not-chars
  ;; Non-ASCII: the cap is a BYTE budget, so a multi-byte char must
  ;; count as its UTF-8 width, NOT one UTF-16 code unit. "café" JSON is
  ;; `{"x":"café"}` — 'é' is 2 UTF-8 bytes, so bytes = chars + 1.
  (let [args {:x "café"}
        json (json/generate-string args)]
    (is (= (inc (count json)) (persist/args-bytes args))
        "byte count must exceed the char count by the multi-byte width")))


;; =============================================================================
;; ref-arg? — true only for `{:ref "..."}` maps
;; =============================================================================

(deftest ref-arg?-recognises-ref-map
  (is (persist/ref-arg? {:ref "00000000-0000-0000-0000-000000000001"}))
  (is (persist/ref-arg? {:ref nil}) ":ref key alone triggers ref-arg? — value parsed separately"))


(deftest ref-arg?-rejects-non-ref-shapes
  (is (not (persist/ref-arg? {:a 1})))
  (is (not (persist/ref-arg? "str")))
  (is (not (persist/ref-arg? [1 2 3])))
  (is (not (persist/ref-arg? nil))))


;; =============================================================================
;; parse-ref-fn-id — UUID extraction + safe fallback
;; =============================================================================

(deftest parse-ref-fn-id-happy
  (let [u (random-uuid)]
    (is (= u (persist/parse-ref-fn-id {:ref (str u)})))))


(deftest parse-ref-fn-id-missing-or-blank
  (is (nil? (persist/parse-ref-fn-id {})))
  (is (nil? (persist/parse-ref-fn-id {:ref nil})))
  (is (nil? (persist/parse-ref-fn-id {:ref "   "}))
      "blank :ref short-circuits before UUID parse"))


(deftest parse-ref-fn-id-malformed-returns-nil
  ;; Updated when `parse-uuid-or-clear` started swallowing
  ;; `IllegalArgumentException` to stop the
  ;; `/api/execute {:args {:nums {:ref "not-a-uuid"}}}` regression
  ;; where the bare exception leaked through as the response body.
  ;; The malformed-ref path is now identical to the
  ;; missing-`:ref` and blank-`:ref` paths — all collapse to nil so
  ;; the downstream caller treats the binding as "no ref" and the
  ;; literal value (or absence) survives.
  (is (nil? (persist/parse-ref-fn-id {:ref "not-a-uuid"}))))


;; =============================================================================
;; snapshot-runtime-effects — read-side of the effect-trace atom
;; =============================================================================

(deftest snapshot-runtime-effects-nil-atom
  (is (nil? (persist/snapshot-runtime-effects nil))
      "no trace → nil so callers' (when …) doesn't fire"))


(deftest snapshot-runtime-effects-empty-set
  (is (nil? (persist/snapshot-runtime-effects (atom #{})))
      "empty trace also returns nil (no effect to record)"))


(deftest snapshot-runtime-effects-stringified
  (is (= ["db" "io"] (sort (persist/snapshot-runtime-effects (atom #{:db :io}))))
      "keywords come out as strings, ready for jsonb"))


;; =============================================================================
;; declared-effects-of — registry lookup
;; =============================================================================

(deftest declared-effects-of-nil-id
  (is (nil? (persist/declared-effects-of nil))))


(deftest declared-effects-of-no-entry
  (with-redefs [registry/rich-type-of-id (constantly nil)]
    (is (nil? (persist/declared-effects-of (random-uuid))))))


(deftest declared-effects-of-empty-effects
  (with-redefs [registry/rich-type-of-id (constantly {:effects #{}})]
    (is (nil? (persist/declared-effects-of (random-uuid)))
        "empty effects set means `pure` — same nil signal as missing entry")))


(deftest declared-effects-of-stringified
  (with-redefs [registry/rich-type-of-id (constantly {:effects #{:db :env}})]
    (is (= #{"db" "env"} (set (persist/declared-effects-of (random-uuid)))))))


;; =============================================================================
;; futures-registry — register / lookup / unregister
;; =============================================================================

(deftest futures-registry-roundtrip
  (let [eid (random-uuid)
        fut (future :noop)
        flag (atom false)]
    (try
      (is (nil? (persist/lookup-future eid))
          "fresh id starts unregistered")
      (persist/register-future! eid fut flag)
      (let [r (persist/lookup-future eid)]
        (is (= fut (:future r)))
        (is (= flag (:cancel-flag r))))
      (persist/unregister-future! eid)
      (is (nil? (persist/lookup-future eid))
          "after unregister the entry is gone")
      (finally
        (persist/unregister-future! eid)
        @fut))))


(deftest futures-registry-multiple-isolated
  ;; Two registered entries don't interfere with each other.
  (let [a (random-uuid) b (random-uuid)
        af (future :a) bf (future :b)]
    (try
      (persist/register-future! a af (atom false))
      (persist/register-future! b bf (atom false))
      (is (= af (:future (persist/lookup-future a))))
      (is (= bf (:future (persist/lookup-future b))))
      (persist/unregister-future! a)
      (is (nil? (persist/lookup-future a)))
      (is (some? (persist/lookup-future b))
          "removing one entry leaves the other intact")
      (finally
        (persist/unregister-future! a)
        (persist/unregister-future! b)
        @af @bf))))


;; =============================================================================
;; log-effect-drift! — fires WARN only when sets diverge
;; =============================================================================

(deftest log-effect-drift-aligned-skips
  (let [calls (atom [])]
    (with-redefs [log/log* (fn [& args] (swap! calls conj args))]
      (persist/log-effect-drift! "exec-id" ["db"] ["db"])
      (is (empty? @calls)
          "declared == runtime → no log line"))))


(deftest log-effect-drift-both-empty-skips
  (let [calls (atom [])]
    (with-redefs [log/log* (fn [& args] (swap! calls conj args))]
      (persist/log-effect-drift! "exec-id" nil nil)
      (is (empty? @calls)
          "both nil/empty → no drift to report"))))


(deftest log-effect-drift-widened-warns
  (let [calls (atom [])]
    (with-redefs [log/log* (fn [& args] (swap! calls conj args))]
      (persist/log-effect-drift! "exec-id" ["db"] ["db" "io"])
      (is (= 1 (count @calls))
          "runtime added :io that wasn't declared → one warn line"))))


(deftest log-effect-drift-unobserved-warns
  (let [calls (atom [])]
    (with-redefs [log/log* (fn [& args] (swap! calls conj args))]
      (persist/log-effect-drift! "exec-id" ["db" "env"] ["db"])
      (is (= 1 (count @calls))
          "declared :env never fired at runtime → warn"))))


(deftest size-caps-sanity
  (testing "size constants form an ascending ladder"
    (is (pos? persist/max-error-chars))
    (is (<= persist/max-error-chars persist/max-error-data-bytes))
    (is (<= persist/max-error-data-bytes persist/max-args-bytes))
    (is (<= persist/max-args-bytes persist/max-result-bytes))))


;; A storage stub whose `:fn-execution` pending-count is controllable — the
;; only method the fleet per-org cap calls. `pending-count` maps org → the
;; number of pending rows to report.
(defn- fake-exec-storage
  "StorageCRUD stub whose only meaningful method is the 4-arg query-entities
   the fleet per-org cap calls. `pending-count` maps org → pending-row count;
   `query-throws?` makes every query throw (for the fail-open test)."
  ([pending-count] (fake-exec-storage pending-count false))
  ([pending-count query-throws?]
   (reify sp/StorageCRUD
     (create-entity [_ _ _] nil)

     (read-entity [_ _ _] nil)

     (update-entity [_ _ _ _] nil)

     (delete-entity [_ _ _] nil)

     (query-latest-per-group [_ _ _ _] nil)

     (query-entities [_ _ _] [])

     (query-entities
       [_ _entity where opts]
       (when query-throws? (throw (ex-info "db blip" {})))
       (let [n (get pending-count (:org-id where) 0)
             lim (:limit opts)]
         (vec (repeat (cond-> n lim (min lim)) {:status :pending})))))))


(deftest acquire-execution-slot-caps-concurrency
  ;; The global (per-pod) + per-org concurrency caps gate execution futures so
  ;; one client can't pile unbounded compute onto the shared JVM. This test
  ;; drives the PUBLIC/platform path (tenant? = false), where the per-org cap
  ;; is the local atom — no storage needed.
  (reset! @#'persist/live-executions {:total 0 :by-org {}})
  (binding [persist/*max-concurrent-executions* 3
            persist/*max-concurrent-executions-per-org* 2]
    (let [acq (fn [org] (persist/acquire-execution-slot! nil org false))
          a1 (acq "orgA")
          a2 (acq "orgA")
          a3 (acq "orgA")]
      (testing "per-org cap: org A gets 2, the 3rd is rejected"
        (is (fn? a1))
        (is (fn? a2))
        (is (nil? a3) "org A's per-org cap of 2 blocks a 3rd slot"))
      (testing "a different org takes the last global slot, then all reject"
        (let [b1 (acq "orgB")]
          (is (fn? b1) "org B takes the 3rd (global) slot")
          (is (nil? (acq "orgB")) "global cap of 3 now blocks everyone")
          (b1)))
      (testing "release frees a slot for the capped org"
        (a1)
        (is (fn? (acq "orgA")) "after a release org A can acquire again"))))
  (reset! @#'persist/live-executions {:total 0 :by-org {}}))


(deftest acquire-execution-slot-fleet-per-org-cap
  ;; TENANT path (tenant? = true): the per-org cap counts pending rows in
  ;; shared storage, so N pods enforce ONE budget. Here a single call with a
  ;; storage stub reporting `cap` pending rows must be rejected, and one
  ;; reporting `cap - 1` must be admitted.
  (reset! @#'persist/live-executions {:total 0 :by-org {}})
  (binding [persist/*max-concurrent-executions* 100
            persist/*max-concurrent-executions-per-org* 3]
    (testing "org already at the fleet cap → rejected, and no global slot leaks"
      (let [storage (fake-exec-storage {"acme" 3})
            r (persist/acquire-execution-slot! storage "acme" true)]
        (is (nil? r) "3 pending across the fleet blocks a 4th")
        (is (zero? (:total @@#'persist/live-executions))
            "the global slot was released on the fleet-cap rejection")))
    (testing "org below the fleet cap → admitted"
      (let [storage (fake-exec-storage {"acme" 2})
            r (persist/acquire-execution-slot! storage "acme" true)]
        (is (fn? r) "2 pending leaves room for a 3rd")
        (r)))
    (testing "a storage error fails OPEN — the global cap is the safety net"
      (let [storage (fake-exec-storage {} true)
            r (persist/acquire-execution-slot! storage "acme" true)]
        (is (fn? r) "count failure admits rather than wrongly rejecting")
        (r))))
  (reset! @#'persist/live-executions {:total 0 :by-org {}}))


(deftest acquire-execution-slot-release-is-idempotent
  ;; apply-execute may release a slot on its error path AND the future's
  ;; `finally` may release the same slot — release MUST decrement exactly
  ;; once, or the counter under-counts and the cap drifts (eventually
  ;; rejecting real executions or never rejecting a DoS).
  (reset! @#'persist/live-executions {:total 0 :by-org {}})
  (let [release (persist/acquire-execution-slot! nil "orgA" false)]
    (is (= 1 (:total @@#'persist/live-executions)) "one slot held after acquire")
    (release)
    (release)
    (release)
    (is (zero? (:total @@#'persist/live-executions))
        "three release calls decrement the total exactly once")
    (is (zero? (get-in @@#'persist/live-executions [:by-org "orgA"]))
        "the per-org counter is also decremented exactly once"))
  (reset! @#'persist/live-executions {:total 0 :by-org {}}))


(deftest execution-deadline-flips-cancel-and-interrupts
  ;; The wall-clock watchdog hard-kills a runaway: after the deadline it
  ;; flips the cancel-flag (graph transitions observe *cancel-check*) AND
  ;; interrupts the future (interruptible IO / sleep responds).
  (let [cancel-flag (atom false)
        fut (future (Thread/sleep 5000) :never)
        wd (#'persist/arm-deadline! 40 cancel-flag fut)]
    (try
      (Thread/sleep 300) ; well past the 40ms deadline
      (is (true? @cancel-flag) "deadline flipped the cancel-flag")
      (is (future-cancelled? fut) "deadline interrupted the future")
      (finally (some-> wd (java.util.concurrent.ScheduledFuture/.cancel false))
               (future-cancel fut)))))


(deftest execution-deadline-cancelled-watchdog-does-not-fire
  ;; The normal path — the execution finishes first and the future's finally
  ;; cancels the watchdog, so it never touches the cancel-flag.
  (let [cancel-flag (atom false)
        fut (future :fast)
        wd (#'persist/arm-deadline! 80 cancel-flag fut)]
    (java.util.concurrent.ScheduledFuture/.cancel wd false)
    (Thread/sleep 250) ; past the 80ms deadline
    (is (false? @cancel-flag) "a cancelled watchdog never flips the flag")))


(deftest execution-deadline-disabled-when-nil-or-zero
  (let [cancel-flag (atom false)
        fut (future :x)]
    (is (nil? (#'persist/arm-deadline! nil cancel-flag fut)) "nil deadline → no watchdog")
    (is (nil? (#'persist/arm-deadline! 0 cancel-flag fut)) "zero deadline → no watchdog")
    (future-cancel fut)))


(deftest scrub-outcome-tenant-envelope
  (testing "off by default — full error passes through"
    (let [o {:status :failed :error "FATAL: password authentication failed"
             :error-data {:sql "SELECT secret"}}]
      (is (= o (persist/scrub-outcome :my-fn o)))))
  (testing "tenant scope: internal error gets the ref-envelope"
    (binding [cr/*scrub-internal-errors?* true]
      (let [o (persist/scrub-outcome :my-fn
                                     {:status :failed
                                      :error "FATAL: password authentication failed"
                                      :error-data {:sql "SELECT secret"}})]
        (is (re-matches #"Internal error, ref: [0-9a-f-]{36}" (:error o)))
        (is (= :internal (get-in o [:error-data :reason])))
        (is (string? (get-in o [:error-data :ref])))
        (is (not (re-find #"password" (:error o)))))))
  (testing "tenant scope: whitelisted user-level type passes verbatim"
    (binding [cr/*scrub-internal-errors?* true]
      (let [o {:status :failed :error "type mismatch: expected :int"
               :error-data {:type :validation-error/type-mismatch}}]
        (is (= o (persist/scrub-outcome :my-fn o))))))
  (testing "tenant scope: exception WITHOUT :type is scrubbed"
    (binding [cr/*scrub-internal-errors?* true]
      (let [o (persist/scrub-outcome :my-fn {:status :failed :error "NPE at Foo.java:42"
                                             :error-data nil})]
        (is (re-find #"Internal error, ref:" (:error o))))))
  (testing "tainted outcomes short-circuit (already redacted)"
    (binding [cr/*scrub-internal-errors?* true]
      (let [o {:status :failed :tainted? true
               :error "Result hidden: fn return-type carries :secret marker."
               :error-data {:reason :tainted}}]
        (is (= o (persist/scrub-outcome :my-fn o))))))
  (testing "succeeded outcomes untouched"
    (binding [cr/*scrub-internal-errors?* true]
      (let [o {:status :succeeded :result 42}]
        (is (= o (persist/scrub-outcome :my-fn o)))))))
