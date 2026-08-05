(ns graphden.executor.path-trace-test
  "Unit tests for the Debug-P1 execution-path capture seam in
   `compile-eager/call-with-cache` (driven through the private var —
   the seam is the single choke point every `:ref` invocation passes,
   so testing it directly covers every compile-path that calls it).

   Covers: records only when `*path-trace*` is bound AND the fn-id is
   in `*traced-fn-ids*`; the zero-work claim when unbound (structural:
   a counting redef of `record-path-entry!` observes NO calls); the
   cache-hit vs fresh-call entry shapes; the capture-time `:secret`
   skip; the capture-side entry cap; and that a THROWING frame still
   records (the failing call is the one being debugged).

   No DB, no compile pipeline — `call-with-cache` reads only
   `::call-cache` from ctx, so a bare map (or one carrying a HashMap)
   is a complete fixture."
  (:require
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


(deftest records-fresh-call-when-bound-and-in-set-test
  (let [fn-id (random-uuid)
        trace (atom [])]
    (binding [cr/*path-trace* trace
              ce/*traced-fn-ids* (atom #{fn-id})]
      (testing "cache miss records {:fn-id :cache-hit? false :duration-ms}"
        (is (= 42 (call-with-cache fn-id #{} (fn [_fa _ctx] 42) {} (fresh-ctx))))
        (let [[entry :as entries] @trace]
          (is (= 1 (count entries)))
          (is (= fn-id (:fn-id entry)))
          (is (false? (:cache-hit? entry)))
          (is (nat-int? (:duration-ms entry)))))
      (testing "absent cache (nil ::call-cache) is still a traced fresh call"
        (reset! trace [])
        (is (= 7 (call-with-cache fn-id #{} (fn [_fa _ctx] 7) {} {})))
        (is (= [false] (mapv :cache-hit? @trace)))))))


(deftest records-hit-without-duration-test
  (let [fn-id (random-uuid)
        trace (atom [])
        ctx (fresh-ctx)]
    (binding [cr/*path-trace* trace
              ce/*traced-fn-ids* (atom #{fn-id})]
      (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)
      (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} ctx)
      (let [[miss hit] @trace]
        (is (= 2 (count @trace)))
        (is (false? (:cache-hit? miss)))
        (testing "hit entry carries no :duration-ms — absence, not 0"
          (is (true? (:cache-hit? hit)))
          (is (not (contains? hit :duration-ms))))))))


(deftest silent-when-fn-not-in-traced-set-test
  (let [trace (atom [])]
    (binding [cr/*path-trace* trace
              ce/*traced-fn-ids* (atom #{})]
      (is (= 1 (call-with-cache (random-uuid) #{} (fn [_fa _ctx] 1) {} (fresh-ctx))))
      (is (empty? @trace)))))


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
        trace (atom [])
        ctx (fresh-ctx)]
    (with-redefs [registry/touches-secret? (fn [id] (= id fn-id))]
      (binding [cr/*path-trace* trace
                ce/*traced-fn-ids* (atom #{fn-id})]
        (call-with-cache fn-id #{} (fn [_fa _ctx] :s) {} ctx)
        (call-with-cache fn-id #{} (fn [_fa _ctx] :s) {} ctx)))
    (testing "both the fresh call AND the cache hit hide behind :secret"
      (is (= 2 (count @trace)))
      (doseq [entry @trace]
        (is (= {:fn-id fn-id :hidden :secret} entry))
        (is (not (contains? entry :duration-ms)))
        (is (not (contains? entry :cache-hit?)))))))


(deftest capture-cap-stops-recording-test
  (let [fn-id (random-uuid)
        trace (atom (vec (repeat ce/max-path-trace-entries {:fn-id fn-id})))]
    (binding [cr/*path-trace* trace
              ce/*traced-fn-ids* (atom #{fn-id})]
      (is (= :v (call-with-cache fn-id #{} (fn [_fa _ctx] :v) {} {})))
      (testing "at the cap the call still runs but records nothing"
        (is (= ce/max-path-trace-entries (count @trace)))))))


(deftest throwing-frame-still-records-test
  (let [fn-id (random-uuid)
        trace (atom [])]
    (binding [cr/*path-trace* trace
              ce/*traced-fn-ids* (atom #{fn-id})]
      (is (thrown? clojure.lang.ExceptionInfo
            (call-with-cache fn-id #{}
                             (fn [_fa _ctx] (throw (ex-info "boom" {})))
                             {} (fresh-ctx))))
      (let [[entry] @trace]
        (is (= fn-id (:fn-id entry)))
        (is (false? (:cache-hit? entry)))))))
