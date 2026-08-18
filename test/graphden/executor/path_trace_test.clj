(ns ^:serial graphden.executor.path-trace-test
  "Unit tests for the Debug-P1/P3 execution-path capture seam in
   `compile-eager/call-with-cache` (driven through the private var —
   the seam is the single choke point every `:ref` invocation passes,
   so testing it directly covers every compile-path that calls it).

   Covers: records only when `*path-trace*` is bound AND the fn-id is
   in `*traced-fn-ids*`; the zero-work claim when unbound (structural:
   a counting redef of `record-path-entry!` observes NO calls); the
   cache-hit vs fresh-call entry shapes; the capture-time secret /
   fail-closed-unknown skip (`trace-capture-class`); the tree linkage
   (`:seq` / `:parent-seq`) and ancestor poisoning (`:value-hidden
   :secret-derived`); the capture-side entry cap; that a THROWING
   frame still records (the failing call is the one being debugged);
   and the Debug-P3 surfaces — value capture only in
   `:capture-values?` mode (per-entry 4 KB cap, total-budget
   oldest-first drop, the secret value renderer NEVER invoked) plus
   the ambient-sampling decision (`ambient-sample?`) and the
   `set-trace-sampling!` confirm guard.

   Frame classification is FAIL-CLOSED: a fn-id with no rich-types
   registry entry classifies `:unknown` and is hidden, so tests that
   exercise plain-capture behaviour pin the classification via
   `with-classes` (default `:plain`) instead of relying on the
   registry state.

   No DB, no compile pipeline — `call-with-cache` reads only
   `::call-cache` from ctx, so a bare map (or one carrying a HashMap)
   is a complete fixture."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]))


(def ^:private call-with-cache #'ce/call-with-cache)


(def ^:private cache-key
  "The ctx key compile-eager installs its per-execute memo under."
  :graphden.executor.compile-eager/call-cache)


(defn- fresh-ctx
  []
  {cache-key (java.util.HashMap.)})


(defn- entries
  "The recorded entries of a path-trace state atom."
  [trace]
  (:entries @trace))


(defmacro with-classes
  "Pin `registry/trace-capture-class` to a fixed `{fn-id → class}`
   lookup for the body — ids absent from the map classify `:plain`.
   The production classifier is registry-backed and FAIL-CLOSED
   (unknown id → `:unknown`), so behaviour tests that aren't about
   classification pin it here."
  [m & body]
  `(with-redefs [registry/trace-capture-class
                 (fn [id# _name#] (get ~m id# :plain))]
     (let [res# (do ~@body)] res#)))


(defn- frame-of
  "Entry with the frame-tree keys stripped — for exact-shape
   comparisons that aren't about the tree linkage."
  [entry]
  (dissoc entry :seq :parent-seq))


(deftest records-fresh-call-when-bound-and-in-set-test
  (let [fn-id (random-uuid)
        trace (ce/new-path-trace)]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom #{fn-id})]
        (testing "cache miss records {:fn-id :cache-hit? false :duration-ms}"
          (is (= 42 (call-with-cache fn-id #{} (fn [_fa _ctx] 42) {} (fresh-ctx))))
          (let [[entry :as es] (entries trace)]
            (is (= 1 (count es)))
            (is (= fn-id (:fn-id entry)))
            (is (false? (:cache-hit? entry)))
            (is (nat-int? (:duration-ms entry)))
            (testing "root frame carries :seq but no :parent-seq"
              (is (zero? (:seq entry)))
              (is (not (contains? entry :parent-seq))))
            (testing "plain trace mode captures NO value"
              (is (not (contains? entry :value))))))
        (testing "absent cache (nil ::call-cache) is still a traced fresh call"
          (reset! trace {:entries []})
          (is (= 7 (call-with-cache fn-id #{} (fn [_fa _ctx] 7) {} {})))
          (is (= [false] (mapv :cache-hit? (entries trace)))))))))


(deftest records-hit-without-duration-test
  (let [fn-id (random-uuid)
        trace (ce/new-path-trace)
        ctx (fresh-ctx)]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom #{fn-id})]
        (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)
        (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)
        (let [[miss hit] (entries trace)]
          (is (= 2 (count (entries trace))))
          (is (false? (:cache-hit? miss)))
          (testing "hit entry carries no :duration-ms — absence, not 0"
            (is (true? (:cache-hit? hit)))
            (is (not (contains? hit :duration-ms)))))))))


(deftest silent-when-fn-not-in-traced-set-test
  (let [trace (ce/new-path-trace)]
    (binding [cr/*path-trace* trace
              ce/*traced-fn-ids* (atom #{})]
      (is (= 1 (call-with-cache (random-uuid) #{} (fn [_fa _ctx] 1) {} (fresh-ctx))))
      (is (empty? (entries trace))))))


(deftest trace-all-sentinel-records-every-frame-test
  ;; Debug P2 — run-future binds `(atom ce/trace-all)` for `trace?`
  ;; submissions: every :ref frame of that execution records without a
  ;; per-fn membership test.
  (let [trace (ce/new-path-trace)
        a (random-uuid)
        b (random-uuid)]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (call-with-cache a #{} (fn [_fa _ctx] :x) {} (fresh-ctx))
        (call-with-cache b #{} (fn [_fa _ctx] :y) {} (fresh-ctx))
        (is (= [a b] (mapv :fn-id (entries trace))))
        (is (= [false false] (mapv :cache-hit? (entries trace))))))))


(deftest zero-work-when-var-unbound-test
  ;; Structural zero-alloc assertion: with `*path-trace*` nil (the
  ;; production default), the recorder must never be INVOKED — not
  ;; merely record nothing. Counting redef of the private recorder
  ;; proves the nil-check short-circuits before any trace work.
  (let [fn-id (random-uuid)
        calls (atom 0)]
    (with-redefs-fn {#'ce/record-path-entry! (fn [_ _] (swap! calls inc))}
      (fn []
        (binding [ce/*traced-fn-ids* (atom #{fn-id})]   ; in set, var nil
          (let [ctx (fresh-ctx)]
            (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)
            (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)))))
    (is (zero? @calls))))


(deftest secret-touching-fn-records-hidden-entry-test
  (let [fn-id (random-uuid)
        trace (ce/new-path-trace)
        ctx (fresh-ctx)]
    (with-classes {fn-id :secret-output}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom #{fn-id})]
        (call-with-cache fn-id #{} (fn [_fa _ctx] :s) {} ctx)
        (call-with-cache fn-id #{} (fn [_fa _ctx] :s) {} ctx)))
    (testing "both the fresh call AND the cache hit hide behind :secret"
      (is (= 2 (count (entries trace))))
      (doseq [entry (entries trace)]
        (is (= {:fn-id fn-id :hidden :secret} (frame-of entry)))
        (is (not (contains? entry :duration-ms)))
        (is (not (contains? entry :cache-hit?)))))))


(deftest stale-identity-secret-fn-still-recognized-test
  ;; L3: a fn carrying a historical/abandoned identity id has NO
  ;; `:by-id` rich-type entry — only its NAME resolves to the current
  ;; secret rich-type. The classification must route through the
  ;; stale-name rescue (given the row name) so the Debug-P3 capture
  ;; path still hides the return instead of rendering+storing it.
  (binding [registry/*rich-types-override*
            (atom (registry/snapshot-for-isolation))]
    (let [live-id (random-uuid)
          stale-id (random-uuid)         ; abandoned identity — no :by-id entry
          ;; by-name keys on keyword names in production (sync uses
          ;; `(:name fn-def)`); the rescue does `(keyword row-name)`, so
          ;; a string row name resolves against the keyword key.
          row-name "leaky-secret-fn"]
      ;; Current identity's rich-type: a secret return, keyed by name.
      (registry/record-rich-types-raw! live-id (keyword row-name)
                                       {:return [:secret :text] :effects #{}})
      (testing "id-only lookup misses the stale id (the pre-fix behaviour)"
        (is (not (registry/touches-secret? stale-id)))
        (testing "…and FAIL-CLOSED classifies it :unknown, not :plain"
          (is (= :unknown (registry/trace-capture-class stale-id nil)))))
      (testing "name-rescued lookup recognises the stale-id secret fn"
        (is (true? (registry/touches-secret? stale-id row-name)))
        (is (= :secret-output (registry/trace-capture-class stale-id row-name))))
      (testing "the trace seam hides the value when the ref-name is threaded"
        (let [trace (ce/new-path-trace {:capture-values? true})
              probe (atom 0)]
          (with-redefs [ce/render-captured-value (fn [_v] (swap! probe inc) {})]
            (binding [cr/*path-trace* trace
                      ce/*traced-fn-ids* (atom ce/trace-all)]
              (is (= :s3cret (call-with-cache stale-id #{} row-name
                                              (fn [_fa _ctx] :s3cret)
                                              {} (fresh-ctx))))))
          (is (zero? @probe) "renderer never saw the stale-id secret value")
          (is (= [{:fn-id stale-id :hidden :secret}]
                 (mapv frame-of (entries trace)))))))))


(deftest unknown-rich-type-fails-closed-test
  ;; No `with-classes` — the REAL classifier runs against a random id
  ;; with no registry entry and no name to rescue through. Absence of
  ;; type information must hide, not capture (the pre-fix default
  ;; captured).
  (binding [registry/*rich-types-override*
            (atom (registry/snapshot-for-isolation))]
    (let [fn-id (random-uuid)
          probe (atom 0)
          trace (ce/new-path-trace {:capture-values? true})]
      (with-redefs [ce/render-captured-value (fn [_v] (swap! probe inc) {})]
        (binding [cr/*path-trace* trace
                  ce/*traced-fn-ids* (atom ce/trace-all)]
          (is (= :v (call-with-cache fn-id #{} (fn [_fa _ctx] :v)
                                     {} (fresh-ctx))))))
      (is (zero? @probe) "renderer never saw the unknown-typed value")
      (is (= [{:fn-id fn-id :hidden :unknown-type}]
             (mapv frame-of (entries trace)))))))


;; ============================================================================
;; Frame tree (:seq / :parent-seq) + ancestor poisoning
;; ============================================================================

(deftest tree-linkage-nested-frames-test
  (let [outer (random-uuid)
        inner (random-uuid)
        trace (ce/new-path-trace)]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (call-with-cache outer #{}
                         (fn [fa ctx]
                           (call-with-cache inner #{} (fn [_fa _ctx] 1) fa ctx))
                         {} (fresh-ctx))))
    (testing "entries land in completion order but the tree links by :seq"
      (let [[e-inner e-outer] (entries trace)]
        (is (= inner (:fn-id e-inner)))
        (is (= outer (:fn-id e-outer)))
        (is (zero? (:seq e-outer)) "outer frame entered first")
        (is (= 1 (:seq e-inner)))
        (is (zero? (:parent-seq e-inner)) "inner nests under outer")
        (is (not (contains? e-outer :parent-seq)))))))


(deftest secret-descendant-poisons-consumer-value-test
  ;; The dynamic complement to the static taint rules: outer FORCED a
  ;; secret-output child, so outer's own return derives from it — its
  ;; value must not enter the capture buffer even though outer itself
  ;; classifies :plain.
  (let [outer (random-uuid)
        secret (random-uuid)
        trace (ce/new-path-trace {:capture-values? true})]
    (with-classes {secret :secret-output}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (is (= "derived-s3cret"
               (call-with-cache outer #{}
                                (fn [fa ctx]
                                  (str "derived-"
                                       (call-with-cache secret #{}
                                                        (fn [_fa _ctx] "s3cret")
                                                        fa ctx)))
                                {} (fresh-ctx))))))
    (let [[e-secret e-outer] (entries trace)]
      (is (= :secret (:hidden e-secret)))
      (is (zero? (:parent-seq e-secret)) "secret frame nests under outer")
      (testing "consumer's value replaced by the derived marker"
        (is (= :secret-derived (:value-hidden e-outer)))
        (is (not (contains? e-outer :value)))))))


(deftest secret-input-sink-does-not-poison-consumer-test
  ;; A trusted sink (secret ARG, declaredly-plain return — `:sql-exec`
  ;; shape): its own frame hides, but the checker verified its plain
  ;; return, so the consumer's value stays capturable.
  (let [outer (random-uuid)
        sink (random-uuid)
        trace (ce/new-path-trace {:capture-values? true})]
    (with-classes {sink :secret-input}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (call-with-cache outer #{}
                         (fn [fa ctx]
                           (inc (call-with-cache sink #{}
                                                 (fn [_fa _ctx] 1)
                                                 fa ctx)))
                         {} (fresh-ctx))))
    (let [[e-sink e-outer] (entries trace)]
      (is (= :secret (:hidden e-sink)) "sink's own frame still hides")
      (is (= 2 (:value e-outer)) "consumer's value captured")
      (is (not (contains? e-outer :value-hidden))))))


(deftest cache-hit-of-secret-fn-poisons-consumer-test
  ;; A memoised secret value flows to its consumer exactly like a
  ;; fresh one — the hit must poison the open frames too.
  (let [outer (random-uuid)
        secret (random-uuid)
        ctx (fresh-ctx)
        trace (ce/new-path-trace {:capture-values? true})]
    (with-classes {secret :secret-output}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        ;; prime the memo at root level
        (call-with-cache secret #{} (fn [_fa _ctx] "s") {} ctx)
        ;; consumer pulls the memoised secret
        (call-with-cache outer #{}
                         (fn [fa c]
                           (call-with-cache secret #{} (fn [_fa _ctx] "s") fa c))
                         {} ctx)))
    (let [by-fn (group-by :fn-id (entries trace))
          e-outer (first (get by-fn outer))]
      (is (= 2 (count (get by-fn secret))) "fresh prime + hit both recorded")
      (is (every? #(= :secret (:hidden %)) (get by-fn secret)))
      (is (= :secret-derived (:value-hidden e-outer)))
      (is (not (contains? e-outer :value))))))


(deftest poison-marker-recorded-without-capture-mode-test
  ;; The derivation marker is tree information, not value information —
  ;; it records in plain trace? mode too.
  (let [outer (random-uuid)
        secret (random-uuid)
        trace (ce/new-path-trace)]
    (with-classes {secret :secret-output}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (call-with-cache outer #{}
                         (fn [fa ctx]
                           (call-with-cache secret #{} (fn [_fa _ctx] :s) fa ctx))
                         {} (fresh-ctx))))
    (let [[_ e-outer] (entries trace)]
      (is (= :secret-derived (:value-hidden e-outer))))))


(deftest capture-cap-stops-recording-test
  (let [fn-id (random-uuid)
        trace (atom {:entries (vec (repeat ce/max-path-trace-entries
                                           {:fn-id fn-id}))})]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom #{fn-id})]
        (is (= :v (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} {})))
        (testing "at the cap the call still runs but records nothing"
          (is (= ce/max-path-trace-entries (count (entries trace)))))))))


(deftest throwing-frame-still-records-test
  (let [fn-id (random-uuid)
        trace (ce/new-path-trace)]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom #{fn-id})]
        (is (thrown? clojure.lang.ExceptionInfo
              (call-with-cache fn-id #{}
                               (fn [_fa _ctx] (throw (ex-info "boom" {})))
                               {} (fresh-ctx))))
        (let [[entry] (entries trace)]
          (is (= fn-id (:fn-id entry)))
          (is (false? (:cache-hit? entry))))))))


;; ============================================================================
;; Debug P3 — intermediate-value capture (PHILOSOPHY § Debugging
;; constraint 3, secret skip per constraint 4, budgets per constraint 5)
;; ============================================================================

(deftest value-captured-only-in-capture-values-mode-test
  (let [fn-id (random-uuid)]
    (with-classes {}
      (testing "capture-values mode records the fresh call's return"
        (let [trace (ce/new-path-trace {:capture-values? true})]
          (binding [cr/*path-trace* trace
                    ce/*traced-fn-ids* (atom ce/trace-all)]
            (is (= {:n 42} (call-with-cache fn-id #{} (fn [_fa _ctx] {:n 42}) {} (fresh-ctx)))))
          (let [[entry] (entries trace)]
            (is (= {:n 42} (:value entry)))
            (is (not (:value-truncated? entry)))
            (is (pos? (get entry ce/value-bytes-key))
                "internal byte accounting rides the entry"))))
      (testing "plain trace? mode (no flag) records NO value fields"
        (let [trace (ce/new-path-trace)]
          (binding [cr/*path-trace* trace
                    ce/*traced-fn-ids* (atom ce/trace-all)]
            (call-with-cache fn-id #{} (fn [_fa _ctx] {:n 42}) {} (fresh-ctx)))
          (let [[entry] (entries trace)]
            (is (not (contains? entry :value)))
            (is (not (contains? entry ce/value-bytes-key)))))))))


(deftest value-capture-cache-hit-carries-no-value-test
  ;; The fresh entry for the same [fn-id fa] already carries the value —
  ;; a hit re-capturing it would double the byte spend for zero info.
  (let [fn-id (random-uuid)
        trace (ce/new-path-trace {:capture-values? true})
        ctx (fresh-ctx)]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)
        (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)))
    (let [[miss hit] (entries trace)]
      (is (= :v (:value miss)))
      (is (true? (:cache-hit? hit)))
      (is (not (contains? hit :value))))))


(deftest secret-value-renderer-never-invoked-test
  ;; Constraint 4's wording is "never READ into the capture buffer" —
  ;; so the assertion is structural: the value renderer must not be
  ;; CALLED for a secret-touching fn, even in capture-values mode.
  (let [fn-id (random-uuid)
        probe (atom 0)
        trace (ce/new-path-trace {:capture-values? true})]
    (with-redefs [registry/trace-capture-class
                  (fn [id _name] (if (= id fn-id) :secret-output :plain))
                  ce/render-captured-value (fn [_v] (swap! probe inc) {})]
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (is (= :s3cret (call-with-cache fn-id #{} (fn [_fa _ctx] :s3cret)
                                        {} (fresh-ctx))))))
    (is (zero? @probe) "the renderer never saw the secret value")
    (is (= [{:fn-id fn-id :hidden :secret}]
           (mapv frame-of (entries trace))))))


(deftest per-entry-value-cap-truncates-test
  (let [fn-id (random-uuid)
        big (str/join (repeat (inc ce/max-captured-value-bytes) "x"))
        trace (ce/new-path-trace {:capture-values? true})]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (is (= big (call-with-cache fn-id #{} (fn [_fa _ctx] big) {} (fresh-ctx))))))
    (let [[entry] (entries trace)]
      (testing "oversize value → marker only, nothing partial leaks"
        (is (true? (:value-truncated? entry)))
        (is (not (contains? entry :value))))
      (testing "path fields still recorded"
        (is (false? (:cache-hit? entry)))
        (is (nat-int? (:duration-ms entry)))))))


(deftest unserializable-value-truncates-test
  ;; A Clojure callable (e.g. a produces-callable? ref's router) can't
  ;; JSON-encode — same marker as oversize, the run itself unaffected.
  (let [fn-id (random-uuid)
        trace (ce/new-path-trace {:capture-values? true})]
    (with-classes {}
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom ce/trace-all)]
        (is (fn? (call-with-cache fn-id #{} (fn [_fa _ctx] identity) {} (fresh-ctx))))))
    (let [[entry] (entries trace)]
      (is (true? (:value-truncated? entry)))
      (is (not (contains? entry :value))))))


(deftest total-value-budget-drops-oldest-first-test
  (binding [ce/*max-captured-value-total-bytes* 40
            cr/*path-trace* (ce/new-path-trace {:capture-values? true})
            ce/*traced-fn-ids* (atom ce/trace-all)]
    ;; Each "vvvvvvvvvv" JSON-encodes to 12 bytes; 3 fit in 40, the 4th
    ;; forces the OLDEST out (constraint 5: drop oldest entries first).
    (with-classes {}
      (let [ids (vec (repeatedly 4 random-uuid))]
        (doseq [id ids]
          (call-with-cache id #{} (fn [_fa _ctx] "vvvvvvvvvv") {} (fresh-ctx)))
        (let [st @cr/*path-trace*]
          (testing "oldest entry dropped, newest kept, marker set"
            (is (= (subvec ids 1) (mapv :fn-id (:entries st))))
            (is (true? (:values-dropped? st))))
          (testing "byte accounting reflects the surviving entries"
            (is (= 36 (:value-bytes st)))))))))


;; ============================================================================
;; Debug P3 — ambient session sampling (constraint 2)
;; ============================================================================

(deftest ambient-sample-rate-zero-never-samples-test
  (binding [ce/*traced-fn-ids* (atom #{})
            ce/*trace-sample-rate* (atom 0.0)]
    (let [fn-id (random-uuid)]
      (ce/set-traced-fn-ids! [fn-id])
      (is (every? false? (repeatedly 50 #(ce/ambient-sample? fn-id)))))))


(deftest ambient-sample-rate-full-always-samples-test
  (binding [ce/*traced-fn-ids* (atom #{})
            ce/*trace-sample-rate* (atom 0.01)]
    (let [fn-id (random-uuid)]
      (ce/set-traced-fn-ids! [fn-id])
      (ce/set-trace-sampling! 1.0 {:confirm-full true})
      (is (every? true? (repeatedly 50 #(ce/ambient-sample? fn-id)))))))


(deftest ambient-sample-requires-selective-set-membership-test
  (binding [ce/*traced-fn-ids* (atom #{})
            ce/*trace-sample-rate* (atom 0.01)]
    (ce/set-trace-sampling! 1.0 {:confirm-full true})
    (testing "fn outside the selective set never samples, at any rate"
      (is (false? (ce/ambient-sample? (random-uuid)))))
    (testing "the trace-all sentinel is the explicit-trace? path, not sampling"
      (binding [ce/*traced-fn-ids* (atom ce/trace-all)]
        (is (false? (ce/ambient-sample? (random-uuid))))))))


(deftest ambient-sample-statistical-smoke-test
  ;; Rate 0.5 over N=2000 draws: P(|hits − 1000| > 250) < 10⁻²⁸ — this
  ;; cannot flake before the heat death of CI.
  (binding [ce/*traced-fn-ids* (atom #{})
            ce/*trace-sample-rate* (atom 0.5)]
    (let [fn-id (random-uuid)]
      (ce/set-traced-fn-ids! [fn-id])
      (let [hits (count (filter true? (repeatedly 2000 #(ce/ambient-sample? fn-id))))]
        (is (< 750 hits 1250) (str "hits=" hits))))))


(deftest set-trace-sampling-guards-test
  (binding [ce/*trace-sample-rate* (atom 0.01)]
    (testing "full rate without confirm throws — constraint 2's 'never 100% silently'"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"confirm-full"
            (ce/set-trace-sampling! 1.0)))
      (is (= 0.01 @ce/*trace-sample-rate*) "rate unchanged after the refusal"))
    (testing "full rate WITH the explicit confirm is accepted"
      (ce/set-trace-sampling! 1.0 {:confirm-full true})
      (is (= 1.0 @ce/*trace-sample-rate*)))
    (testing "out-of-range rates rejected (the setter also guards non-numbers)"
      (is (thrown? clojure.lang.ExceptionInfo (ce/set-trace-sampling! -0.1)))
      (is (thrown? clojure.lang.ExceptionInfo (ce/set-trace-sampling! 1.5 {:confirm-full true}))))
    (testing "ordinary sub-1.0 rates need no confirm"
      (ce/set-trace-sampling! 0.25)
      (is (= 0.25 @ce/*trace-sample-rate*)))))
