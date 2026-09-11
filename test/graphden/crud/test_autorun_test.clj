(ns ^:serial graphden.crud.test-autorun-test
  "Pure-selection tests for the write-triggered test auto-run. The
   debounced runner + invalidate! wiring is exercised end-to-end by
   the live stack; here we pin the selection semantics — reverse-
   closure membership, the purity gate, and the cold-index no-op —
   plus the scheduler bookkeeping (queue drain, claim release, cap,
   debounce) that rides the process-global `pending` atom.

   `^:serial`: the queue tests mutate that `defonce` atom and the two
   debounce tests `with-redefs` `crud.test-runs` (a root rebind seen by
   the runner future)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.test-autorun :as autorun]
    [graphden.crud.test-runs :as test-runs]
    [graphden.executor.registry.core :as registry]))


(def ^:private reverse-deps
  ;; :util ← :mid ← :test-a ; :util ← :test-b ; :other ← :test-c
  {:util #{:mid :test-b}
   :mid #{:test-a}
   :other #{:test-c}})


(def ^:private test-rows
  [{:id :test-a} {:id :test-b} {:id :test-c}])


(deftest affected-test-ids-walks-the-reverse-closure
  (testing "a deep dependency edit reaches tests through intermediaries"
    (is (= [:test-a :test-b]
           (autorun/affected-test-ids reverse-deps test-rows #{:util}
                                      (constantly true)))))
  (testing "an unrelated edit reaches only its own dependents"
    (is (= [:test-c]
           (autorun/affected-test-ids reverse-deps test-rows #{:other}
                                      (constantly true)))))
  (testing "editing a test itself re-runs it (seeds are in the blast)"
    (is (= [:test-a]
           (autorun/affected-test-ids reverse-deps test-rows #{:test-a}
                                      (constantly true))))))


(deftest affected-test-ids-gates-on-purity
  (testing "non-pure tests never auto-run"
    (is (= [:test-b]
           (autorun/affected-test-ids reverse-deps test-rows #{:util}
                                      #{:test-b}))
        "purity predicate filters the blast intersection")))


(deftest affected-test-ids-no-ops-safely
  (testing "cold index / empty seeds / no tests → empty, never a throw"
    (is (= [] (autorun/affected-test-ids nil test-rows #{:util} (constantly true))))
    (is (= [] (autorun/affected-test-ids reverse-deps test-rows nil (constantly true))))
    (is (= [] (autorun/affected-test-ids reverse-deps [] #{:util} (constantly true))))))


;; =============================================================================
;; The purity gate
;;
;; `pure-test?` is the ONLY thing standing between a graph write and an
;; effectful test firing unattended. Its default source is the rich-types
;; registry, reached here through the thread-local isolation override.
;; =============================================================================

(def ^:private pure-test? @#'autorun/pure-test?)


(defn- rich-types
  "Registry view holding the given `{id → effects}` entries."
  [effects-by-id]
  (atom {:by-id (into {} (map (fn [[id fx]] [id {:effects fx}])) effects-by-id)}))


(deftest pure-test?-counts-an-unknown-closure-as-impure
  ;; Regression guard: if an un-type-checked fn ever read as PURE, the very
  ;; first write after a boot would auto-run tests whose effects nobody has
  ;; classified yet — `:network` / `:db` firing off a CRUD write.
  (binding [registry/*rich-types-override*
            (rich-types {:pure #{} :effectful #{:db}})]
    (is (true? (pure-test? :pure)) "empty recorded closure → auto-runnable")
    (is (false? (pure-test? :effectful)) "a declared effect is never auto-run")
    (is (not (pure-test? :never-type-checked))
        "no registry entry is NOT pure — the conservative arm")))


(deftest affected-test-ids-defaults-to-the-registry-purity-gate
  ;; The 3-arity is what `run-pending!` calls; it must gate on the registry,
  ;; not pass everything through.
  (binding [registry/*rich-types-override*
            (rich-types {:test-b #{} :test-a #{:network}})]
    (is (= [:test-b] (autorun/affected-test-ids reverse-deps test-rows #{:util}))
        "effectful :test-a and never-checked :test-c stay out of the blast")))


;; =============================================================================
;; Queue bookkeeping — drain! / try-release! / cancel-all!
;; =============================================================================

(def ^:private pending-atom @#'autorun/pending)
(def ^:private drain! @#'autorun/drain!)
(def ^:private try-release! @#'autorun/try-release!)


(use-fixtures :each
  (fn [f]
    (autorun/cancel-all!)
    (try (f)
         (finally (autorun/cancel-all!)))))


(deftest drain!-empties-the-queue-and-keeps-the-claim
  (reset! pending-atom {[nil :b] {:seeds #{:x :y} :runner? true}})
  (is (= #{:x :y} (drain! [nil :b])) "the drained set is what the pass selects on")
  (is (= #{} (get-in @pending-atom [[nil :b] :seeds]))
      "seeds cleared in the SAME swap — a follower queueing now is not lost with them")
  (is (true? (get-in @pending-atom [[nil :b] :runner?]))
      "draining must not drop the runner claim, or a second runner spawns")
  (is (= #{} (drain! [nil :never-scheduled])) "unknown scope drains empty, never nil"))


(deftest try-release!-never-orphans-a-follower
  ;; If the release dropped the key while seeds sat under the still-set
  ;; `:runner?` flag, those seeds would wait for a runner that will never
  ;; come — the write's tests silently never run.
  (testing "nothing queued → claim dropped, scope forgotten"
    (reset! pending-atom {[nil :b] {:seeds #{} :runner? true}})
    (is (true? (try-release! [nil :b])))
    (is (not (contains? @pending-atom [nil :b]))))
  (testing "seeds arrived under the live claim → claim KEPT, runner loops again"
    (reset! pending-atom {[nil :b] {:seeds #{:late} :runner? true}})
    (is (false? (try-release! [nil :b])) "false = more work waits")
    (is (= #{:late} (get-in @pending-atom [[nil :b] :seeds]))
        "the follower's seeds survive the attempted release")))


(deftest cancel-all!-interrupts-runners-and-forgets-every-queue
  ;; The shutdown / fixture hook: a runner left alive past the storage it
  ;; reads from logs a failed auto-run pass.
  (let [gate (promise)
        runner (future (deref gate 10000 :timeout))]
    (reset! pending-atom {[nil :b1] {:seeds #{:x} :runner? true :runner runner}
                          [nil :b2] {:seeds #{:y} :runner? true}})
    (is (= 1 (autorun/cancel-all!)) "counts the scopes that actually held a runner handle")
    (is (= {} @pending-atom) "every queue forgotten, claim-without-handle included")
    (is (future-cancelled? runner) "the live runner is interrupted, not left to wake up")
    (deliver gate :done)))


;; =============================================================================
;; schedule-affected! — the write-path hook
;; =============================================================================

(defn- hot-ctx
  "Ctx with a warm reverse-dependency index (what `invalidate!` passes)."
  []
  {:compile-deps (atom {:reverse-deps reverse-deps})})


(deftest schedule-affected!-no-ops-queue-nothing
  (testing "off-switch"
    (binding [autorun/*auto-run?* false]
      (is (nil? (autorun/schedule-affected! (hot-ctx) #{:util} :b)))
      (is (= {} @pending-atom))))
  (testing "nil / empty seeds — an unclassified write must NOT run the whole suite"
    (is (nil? (autorun/schedule-affected! (hot-ctx) nil :b)))
    (is (nil? (autorun/schedule-affected! (hot-ctx) #{} :b)))
    (is (= {} @pending-atom)))
  (testing "cold ctx (no compile-deps reverse index) → no-op, like the service blast"
    (is (nil? (autorun/schedule-affected! {} #{:util} :b)))
    (is (nil? (autorun/schedule-affected! {:compile-deps (atom {})} #{:util} :b)))
    (is (= {} @pending-atom) "no runner is spawned against an index that can't select")))


(defn- await-calls
  "Poll `calls` until it holds `n` entries or `deadline-ms` elapses."
  [calls n deadline-ms]
  (let [end (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (cond
        (>= (count @calls) n) true
        (> (System/currentTimeMillis) end) false
        :else (do (Thread/sleep 25) (recur))))))


(defn- recording-runs
  "Run `f` with the storage-reading half of `crud.test-runs` stubbed:
   every test row is visible and each run records `{:fn-ids :allowed-effects}`."
  [calls f]
  (with-redefs [test-runs/test-fn-rows (fn [_ctx] test-rows)
                test-runs/run-tests! (fn [ctx opts]
                                       (swap! calls conj
                                              {:fn-ids (vec (:fn-ids opts))
                                               :allowed-effects (:allowed-effects ctx)})
                                       {:total (count (:fn-ids opts))})]
    (f)))


(deftest schedule-affected!-coalesces-a-burst-into-one-debounced-pass
  ;; The write path is O(1): each call only queues seeds. If a follower
  ;; spawned its own runner, an edit burst would run the suite once per
  ;; keystroke-sized write (and each pass reads storage).
  (let [calls (atom [])]
    (recording-runs
      calls
      (fn []
        (binding [registry/*rich-types-override*
                  (rich-types {:test-a #{} :test-b #{} :test-c #{}})]
          (is (= 1 (autorun/schedule-affected! (hot-ctx) #{:util} :b))
              "returns the number of seeds queued")
          (is (= 1 (autorun/schedule-affected! (hot-ctx) #{:other} :b)))
          (is (= 1 (count @pending-atom)) "ONE claim per [org branch] scope")
          (is (await-calls calls 1 6000) "the debounced runner fired")
          (Thread/sleep 900)
          (is (= 1 (count @calls)) "the burst coalesced into exactly one pass")
          (is (= #{:test-a :test-b :test-c} (set (:fn-ids (first @calls))))
              "the pass covers the seeds of every follower, not just the first")
          (is (= #{} (:allowed-effects (first @calls)))
              "auto-runs execute with the effect gate shut — a hidden effect throws"))))))


(deftest run-pending!-caps-the-blast-and-leaves-the-rest-stale
  ;; The cap bounds a wide write (edit a leaf everything depends on). Dropped
  ;; tests must simply NOT run — no re-queue, no second pass, stale status.
  (let [calls (atom [])]
    (recording-runs
      calls
      (fn []
        (binding [autorun/*max-auto-run* 2
                  registry/*rich-types-override*
                  (rich-types {:test-a #{} :test-b #{} :test-c #{}})]
          (autorun/schedule-affected! (hot-ctx) #{:util :other} :b)
          (is (await-calls calls 1 6000))
          (is (= 2 (count (:fn-ids (first @calls)))) "capped at *max-auto-run*")
          (is (every? #{:test-a :test-b :test-c} (:fn-ids (first @calls))))
          (Thread/sleep 900)
          (is (= 1 (count @calls))
              "the dropped test is not re-queued — it keeps its stale status")
          (is (= {} @pending-atom) "the scope released once the queue ran dry"))))))
