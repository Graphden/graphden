(ns graphden.packages.core.system-test
  "Unit tests for `core.system` base-fn impls — focused on the
   no-arg invocation primitive `:call-noargs` (companion to `:call` /
   `:invoke`; ships in the same module as part of the closure-capture
   work that re-composed `:schedule` as a fn-def).

   Mirrors `refinements_test` / `concurrency_test`: the package's
   impls.clj is slurp+eval'd via the loader's private
   `load-module-impls` so the defbase-generated symbols become
   reachable WITHOUT a normal require — same path the runtime takes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "core" "system"))


;; ============================================================================
;; :call-noargs — invoke a 0-arg callable
;;
;; Companion to `:call` / `:invoke` for the no-arg case. The slot's
;; structural type `[:fn {} a]` makes the binding-site hof-wrap with
;; variadic-ignore semantics (closure-capture; docs/CLOSURE_CAPTURE.md).
;; This unit test exercises the impl in isolation — the wrap mechanism
;; is covered end-to-end by `cron-schedule-service-test` (the full
;; closure-capture chain through `:schedule` → `:future` → `:_fire-target`
;; → `:call-noargs`).
;; ============================================================================

(deftest call-noargs-invokes-the-callable-test
  (testing "(func) is invoked; its return is the impl's return"
    (let [impl (impls/impl-of :call-noargs)
          callable (fn [] :ok)]
      (is (= :ok (impl {:func (delay callable)} nil))))))


(deftest call-noargs-propagates-callable-return-test
  (testing "return value is whatever the callable returns — any shape"
    (let [impl (impls/impl-of :call-noargs)]
      (is (= 42 (impl {:func (delay (fn [] 42))} nil)))
      (is (= [1 2 3] (impl {:func (delay (fn [] [1 2 3]))} nil)))
      (is (nil? (impl {:func (delay (fn [] nil))} nil)))
      (is (= {:a 1} (impl {:func (delay (fn [] {:a 1}))} nil))))))


(deftest call-noargs-propagates-callable-exception-test
  (testing "if the callable throws, the impl re-throws (no swallow)"
    (let [impl (impls/impl-of :call-noargs)
          thrown (try (impl {:func (delay (fn [] (throw (ex-info "boom" {:k 1}))))}
                            nil)
                      :no-throw
                      (catch clojure.lang.ExceptionInfo e
                        (ex-data e)))]
      (is (= {:k 1} thrown) "boom's ex-data reaches the caller"))))


(deftest call-noargs-invokes-fresh-each-time-test
  (testing "each invocation calls the callable again — no result caching"
    (let [impl (impls/impl-of :call-noargs)
          counter (atom 0)
          ticking (fn [] (swap! counter inc))]
      (impl {:func (delay ticking)} nil)
      (impl {:func (delay ticking)} nil)
      (impl {:func (delay ticking)} nil)
      (is (= 3 @counter)
          "3 invocations → 3 calls; impl itself doesn't memoise"))))


;; :render-prometheus is a GRAPH fn-def now (a `:fix` worklist over the
;; metrics map) — its behavioural tests drive the executor over a golden
;; clone in `graphden.packages.core.render-prometheus-test`.


;; ============================================================================
;; :digest-hex — hash algorithm as DATA (audit follow-up: the old
;; :sha256-hex base-fn pinned "SHA-256" in the impl; now the primitive
;; takes :algorithm and the pin lives in the :sha256-hex fn-def preset).
;; ============================================================================

(deftest digest-hex-known-answers-test
  (let [digest (impls/impl-of :digest-hex)]
    (testing "NIST known-answer vectors per algorithm"
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
             (digest {:algorithm "SHA-256" :s "abc"} nil)))
      (is (= "a9993e364706816aba3e25717850c26c9cd0d89d"
             (digest {:algorithm "SHA-1" :s "abc"} nil)))
      (is (= (str "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                  "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f")
             (digest {:algorithm "SHA-512" :s "abc"} nil))))
    (testing "nil input stays nil (pre-split behaviour)"
      (is (nil? (digest {:algorithm "SHA-256" :s nil} nil))))
    (testing "unknown algorithm surfaces the JVM's error"
      (is (thrown? java.security.NoSuchAlgorithmException
            (digest {:algorithm "NOPE-9000" :s "abc"} nil))))))


;; ============================================================================
;; :parse-json — malformed input must surface a TYPED error (→400), never a
;; bare JsonParseException that the error boundary maps to 500 + pages on
;; :http/server-error.
;; ============================================================================

(deftest parse-json-well-formed-test
  (let [parse (impls/impl-of :parse-json)]
    (testing "valid JSON parses; keywordize flag honoured"
      (is (= {:a 1} (parse {:string "{\"a\":1}" :keywordize true} nil)))
      (is (= {"a" 1} (parse {:string "{\"a\":1}" :keywordize false} nil))))))


(deftest parse-json-malformed-is-typed-test
  (let [parse (impls/impl-of :parse-json)
        ex (try (parse {:string "{" :keywordize true} nil)
                :no-throw
                (catch clojure.lang.ExceptionInfo e e))]
    (testing "malformed input → ExceptionInfo with :validation-error/malformed-json"
      (is (instance? clojure.lang.ExceptionInfo ex)
          "not the raw com.fasterxml.jackson JsonParseException")
      (is (= :validation-error/malformed-json (:type (ex-data ex)))))))


(deftest sha256-hex-is-a-graph-preset-test
  ;; The old base-fn name survives as a fn-def pinning the algorithm —
  ;; the ladder must not silently flatten back into a hardcoded impl.
  (let [fd (->> (:fn-defs ((requiring-resolve 'graphden.packages.loader/load-packages) ["core"]))
                (some #(when (= :sha256-hex (:name %)) %)))]
    (is (some? fd) ":sha256-hex exists as a composed fn-def")
    (is (= :digest-hex (:parent fd)))
    (is (= "SHA-256" (get-in fd [:args :algorithm])))))


(deftest call-noargs-traced-calls-an-identity-less-callable-untraced-test
  (testing "a bare Clojure fn carries no graph identity → plain call, nothing persisted"
    (let [impl (impls/impl-of :call-noargs-traced)
          calls (atom 0)]
      (is (= :ok (impl {:func (delay (fn [] (swap! calls inc) :ok))} nil)))
      (is (= 1 @calls)))))


;; ============================================================================
;; parse-float / base64 / random-uuid / random / log / instant-* — the
;; stdlib batch: one JDK call each, checked at the contract.
;; ============================================================================

(deftest parse-float-and-base64
  (let [pf (impls/impl-of :parse-float) enc (impls/impl-of :base64-encode) decode (impls/impl-of :base64-decode)]
    (is (= 3.14 (pf {:s (delay "3.14")} nil)))
    (is (= -2000.0 (pf {:s (delay "-2e3")} nil)))
    (is (thrown? NumberFormatException (pf {:s (delay "abc")} nil)))
    (is (= "aGVsbG8=" (enc {:s (delay "hello")} nil)))
    (is (= "hello" (decode {:s (delay "aGVsbG8=")} nil)))
    (testing "round trip keeps non-ASCII text"
      (is (= "привет ✓" (decode {:s (delay (enc {:s (delay "привет ✓")} nil))} nil))))))


(deftest random-uuid-and-random
  (let [ru (impls/impl-of :random-uuid) rn (impls/impl-of :random)
        a (ru {} nil) b (ru {} nil) x (rn {} nil)]
    (is (uuid? a))
    (is (not= a b))
    (is (and (number? x) (<= 0.0 x) (< x 1.0)))))


(deftest instant-parse-format-plus
  (let [pa (impls/impl-of :instant-parse) fmt (impls/impl-of :instant-format) pl (impls/impl-of :instant-plus)
        ms (pa {:s (delay "2026-09-21T10:15:00Z")} nil)]
    (is (= 1789985700000 ms))
    (testing "an offset is honoured"
      (is (= ms (pa {:s (delay "2026-09-21T13:15:00+03:00")} nil))))
    (testing "format in a zone, default pattern is ISO with offset"
      (is (= "2026-09-21T10:15:00Z" (fmt {:ms (delay ms) :pattern (delay "yyyy-MM-dd'T'HH:mm:ssXXX") :zone (delay "UTC")} nil)))
      (is (= "13:15" (fmt {:ms (delay ms) :pattern (delay "HH:mm") :zone (delay "Europe/Moscow")} nil))))
    (testing "calendar arithmetic: a month is a month, negatives go back"
      (is (= "2026-10-21" (fmt {:ms (delay (pl {:ms (delay ms) :amount (delay 1) :unit (delay "months") :zone (delay "UTC")} nil))
                                :pattern (delay "yyyy-MM-dd") :zone (delay "UTC")} nil)))
      (is (= (- ms 86400000) (pl {:ms (delay ms) :amount (delay -1) :unit (delay "days") :zone (delay "UTC")} nil))))
    (is (thrown? Exception (pa {:s (delay "yesterday")} nil)))))


(deftest log-at-a-level-returns-nil
  (let [lg (impls/impl-of :log)]
    (is (nil? (lg {:level (delay "info") :message (delay "hello") :data (delay {:k 1})} nil)))))
