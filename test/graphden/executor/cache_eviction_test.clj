(ns graphden.executor.cache-eviction-test
  "L2: the per-execute `::call-cache` cap eviction must NOT drop a
   single-fire effectful entry. The cache exists so a fn-def that
   pulls a side-effecting ref twice (validation + success branch)
   fires it ONCE; a plain `.clear` on cap would drop the already-
   computed effectful entry so the second pull misses and the effect
   fires twice (e.g. :create-entity double-insert → unique-violation).

   No DB, no compile pipeline — the eviction policy
   (`evict-preserving-effectful!`), the effectful predicate
   (`effectful-ref?`, read via the rich-types registry), and the
   wired-through `call-with-cache` behaviour are all exercised
   directly. The registry read is isolated behind
   `*rich-types-override*` (parallel-plugin isolation-var)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry-core]))


(def ^:private evict-preserving-effectful! #'ce/evict-preserving-effectful!)
(def ^:private effectful-ref? #'ce/effectful-ref?)
(def ^:private call-with-cache #'ce/call-with-cache)
(def ^:private max-size-var #'ce/call-cache-max-size)

(def ^:private cache-key :graphden.executor.compile-eager/call-cache)


(deftest evict-preserving-effectful-drops-pure-keeps-effectful-test
  (let [effect-id (random-uuid)
        pure-ids (repeatedly 6 random-uuid)
        cache (java.util.HashMap.)]
    ;; Keys are `[ref-id projected-fa]` vectors, exactly as
    ;; `call-with-cache` builds them.
    (java.util.HashMap/.put cache [effect-id {}] :effect-value)
    (doseq [pid pure-ids] (java.util.HashMap/.put cache [pid {}] :pure-value))
    (is (= 7 (java.util.HashMap/.size cache)))
    (evict-preserving-effectful! cache #(= % effect-id))
    (testing "only the effectful entry survives"
      (is (= 1 (java.util.HashMap/.size cache)))
      (is (= :effect-value (java.util.HashMap/.get cache [effect-id {}])))
      (is (nil? (java.util.HashMap/.get cache [(first pure-ids) {}]))))))


(deftest effectful-ref-reads-single-fire-effects-test
  (binding [registry-core/*rich-types-override*
            (atom (registry-core/snapshot-for-isolation))]
    (let [db-id (random-uuid)
          env-id (random-uuid)
          pure-id (random-uuid)]
      (registry-core/record-rich-types-raw! db-id "db-writer"
                                            {:return :any :effects #{:db}})
      ;; `:env` is an idempotent read, deliberately NOT single-fire.
      (registry-core/record-rich-types-raw! env-id "env-reader"
                                            {:return :text :effects #{:env}})
      (registry-core/record-rich-types-raw! pure-id "pure-fn"
                                            {:return :int :effects #{}})
      (is (true? (effectful-ref? db-id)) ":db is a single-fire effect")
      (is (false? (effectful-ref? env-id)) ":env is idempotent, not single-fire")
      (is (false? (effectful-ref? pure-id)) "a pure fn is never effectful")
      (is (false? (effectful-ref? (random-uuid)))
          "an unregistered/stale id reads as non-effectful"))))


(deftest call-with-cache-single-fires-effectful-ref-across-eviction-test
  ;; End-to-end for the policy: register a :db ref, drive
  ;; `call-with-cache` past a lowered cap with distinct PURE keys so an
  ;; eviction fires, then re-pull the :db ref — it must HIT the cache
  ;; (child fires exactly once), the eviction having preserved it.
  (binding [registry-core/*rich-types-override*
            (atom (registry-core/snapshot-for-isolation))
            ce/*always-fresh-fn-ids* (atom #{})]
    (let [effect-id (random-uuid)
          pure-id (random-uuid)
          effect-calls (atom 0)
          ctx {cache-key (java.util.HashMap.)}
          orig-max @max-size-var]
      (registry-core/record-rich-types-raw! effect-id "db-writer"
                                            {:return :any :effects #{:db}})
      (alter-var-root max-size-var (constantly 4))
      (try
        ;; First pull of the effectful ref — a miss, child fires.
        (is (= :ok (call-with-cache effect-id #{}
                                    (fn [_fa _ctx] (swap! effect-calls inc) :ok)
                                    {} ctx)))
        ;; Flood the cache with > cap distinct PURE keys (distinct fa
        ;; under a projected free) to force at least one eviction.
        (doseq [i (range 20)]
          (call-with-cache pure-id #{:x} (fn [_fa _ctx] :pure) {:x i} ctx))
        ;; Re-pull the effectful ref with the SAME projected args — the
        ;; eviction must have kept its entry, so this is a cache hit.
        (is (= :ok (call-with-cache effect-id #{}
                                    (fn [_fa _ctx] (swap! effect-calls inc) :ok)
                                    {} ctx)))
        (testing "the side-effecting child fired exactly once"
          (is (= 1 @effect-calls)))
        (finally
          (alter-var-root max-size-var (constantly orig-max)))))))


;; ---------------------------------------------------------------------------
;; Per-scope call-cache isolation — the CME + cross-request-leak fix.
;;
;; A build-captured handler (`:http-server` `:handler`, a `:future` body)
;; reuses the ONE `::call-cache` HashMap it captured at build time on every
;; invocation. Concurrent invocations then read/evict/put the same
;; non-thread-safe map — the eviction walk races a concurrent put into a
;; ConcurrentModificationException. The fix: each new execution scope binds a
;; fresh `*request-call-cache*` (via `with-fresh-call-cache`), so `run`
;; installs a per-scope map and concurrent scopes never touch one HashMap.
;; ---------------------------------------------------------------------------

(deftest request-call-cache-unbound-by-default-test
  (is (nil? ce/*request-call-cache*)
      "unbound by default: a plain top-level execute keeps priming its own
       per-execute cache in ctx"))


(deftest with-fresh-call-cache-binds-a-fresh-distinct-map-test
  (let [a (cr/with-fresh-call-cache (fn [] ce/*request-call-cache*))
        b (cr/with-fresh-call-cache (fn [] ce/*request-call-cache*))]
    (testing "each scope sees a HashMap"
      (is (instance? java.util.HashMap a))
      (is (instance? java.util.HashMap b)))
    (testing "and a DISTINCT one per invocation — no shared map across scopes"
      (is (not (identical? a b))))
    (testing "the binding unwinds"
      (is (nil? ce/*request-call-cache*)))))


(deftest concurrent-scopes-with-own-caches-survive-eviction-test
  ;; The safety property the fix guarantees: many threads each driving
  ;; `call-with-cache` past the eviction cap CONCURRENTLY complete cleanly and
  ;; correctly, PROVIDED each uses its own cache (its own execution scope) —
  ;; which is exactly what `run` installs from a per-thread
  ;; `*request-call-cache*`. A shared HashMap here would race the eviction walk
  ;; into a ConcurrentModificationException.
  (binding [registry-core/*rich-types-override*
            (atom (registry-core/snapshot-for-isolation))
            ce/*always-fresh-fn-ids* (atom #{})]
    (let [pure-id (random-uuid)
          orig-max @max-size-var
          threads 8
          per-thread 400]
      (registry-core/record-rich-types-raw! pure-id "pure-fn"
                                            {:return :int :effects #{}})
      (alter-var-root max-size-var (constantly 16))
      (try
        (let [results
              ;; deref rethrows any thread's exception — so if a scope had
              ;; raced the eviction walk into a CME, this vector build throws
              ;; and the test fails. Clean completion of all N is the proof.
              (mapv deref
                    (mapv
                      (fn [_]
                        (future
                          ;; Own cache per thread — the per-scope isolation.
                          (let [ctx {cache-key (java.util.HashMap.)}]
                            (dotimes [i per-thread]
                              (call-with-cache pure-id #{:x}
                                               (fn [_fa _ctx] (* i 2))
                                               {:x i} ctx)))
                          :done))
                      (range threads)))]
          (testing "no ConcurrentModificationException, every scope completed"
            (is (= (repeat threads :done) results))))
        (finally
          (alter-var-root max-size-var (constantly orig-max)))))))
