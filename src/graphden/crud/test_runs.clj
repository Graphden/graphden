(ns graphden.crud.test-runs
  "Tests via the `tests` namespace convention (Roadmap Block 3.1).

   A TEST is a named fn living in a namespace whose dotted path contains
   the segment `tests` (`tests.parser`, `myproj.tests.api`, …). It
   PASSES when it executes without a throw — `:assert` / `:assert-eq`
   (core.logic) are the vocabulary for failing loudly. No new entity or
   field: discovery is a namespace-path predicate, results are ordinary
   `:fn-execution` rows, status is derived — see docs/PHILOSOPHY.md
   § \"Tests are not a new entity or field\".

   Three faces, all branch-scoped through the request ctx:
   - `run-tests!`       — execute tests sequentially through
     `fn-execution/apply-execute` (the standard pipeline: effect
     gating, redaction, caps, persistence) and collect per-test
     outcomes. Sequential on purpose: a burst of N parallel submits
     would eat the per-org execution slots that interactive runs share.
   - `latest-statuses`  — one `DISTINCT ON` read of the newest
     execution per CURRENT-branch version of each test fn. Keyed by the
     current version id, so an edited fn honestly reports \"no status\"
     until re-run — staleness-by-construction, no bookkeeping.
   - `test-fn-rows` / `test-namespace-ids` — the discovery predicate.

   The `matched by SEGMENT, not substring` rule matters: `tests.api`
   and `myproj.tests` are test namespaces; `testsuite` is not. Any-
   segment (not root-only) placement keeps a project's tests inside the
   project's own root namespace, where the workspace chip can see them."
  (:require
    [clojure.string :as str]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.types.core :as types]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def test-ns-segment
  "The reserved namespace segment that marks test namespaces."
  "tests")


(defn test-ns-path?
  "True iff dotted namespace path `path` contains the `tests` segment."
  [path]
  (boolean (some #{test-ns-segment} (str/split (str path) #"\."))))


(defn test-namespace-ids
  "Ids of every `:ns` row whose dotted path contains the `tests`
   segment. `:ns` rows are global (unversioned), org-scoped by the
   storage handle."
  [storage]
  (let [rows (sp/query-entities storage :ns {})
        by-id (into {} (map (juxt :id identity)) rows)]
    (into #{}
          (comp (filter #(test-ns-path? (ns-path/path-of by-id (:id %))))
                (map :id))
          rows)))


(defn test-fn-rows
  "Named fns of the current branch living in test namespaces — read
   DIRECTLY from the request storage (branch- and org-scoped by the
   handle), one query per test namespace, sorted by name. Deliberately
   NOT the ctx graph cache: the SSE panel stream is one long request
   whose per-request cache would freeze at its first tick, so every
   caller here pays the fresh (small — test namespaces only) read
   instead. `_`-prefixed fns are NOT tests: the `_`-private convention
   marks scaffolding, and a tests namespace needs private helpers like
   any other."
  [ctx]
  (let [storage (request/require-storage ctx)
        ns-ids (test-namespace-ids storage)]
    (when (seq ns-ids)
      (->> ns-ids
           (mapcat #(sp/query-entities storage :fn {:namespace-id %}))
           (filter #(and (:name %)
                         (not (str/starts-with? (str (:name %)) "_"))))
           (sort-by :name)
           vec))))


;; --- run ------------------------------------------------------------------

(def default-test-timeout-ms
  "Per-test deref timeout. A test overrunning it keeps running and
   reports `:pending`; the terminal status lands on the execution row
   asynchronously and `latest-statuses` picks it up."
  10000)


(defn nullable-type?
  "True iff type expression `t` explicitly admits nil — `:null` itself
   or a union containing it (aliases resolved first, so
   `:nullable-text` qualifies)."
  [t]
  (let [t (types/resolve-alias t)]
    (or (= :null t)
        (and (types/union-type? t)
             (boolean (some #{:null} (types/union-members t)))))))


(defn blocking-frees
  "Free args that BLOCK a zero-arg run: `free` (`{arg-name → slot-id}`)
   minus slots whose DECLARED type explicitly admits nil
   (`nullable-slot?` — a slot-id predicate) — the executor hands an
   unbound slot nil, so an explicitly-nullable free runs type-soundly
   as its absent case. Everything else (concrete types, bare
   typevars, `:any`) stays blocking — conservative: an `:any` free is
   usually a forgotten binding."
  [free nullable-slot?]
  (into {}
        (remove (fn [[_ slot-id]] (nullable-slot? slot-id)))
        free))


(defn- nullable-slot-pred
  "Batched slot-id → \"declared type admits nil\" predicate over the
   free-arg surface: two org/branch-scoped reads (slot rows, then
   their type-fn rows), then a set lookup. The slot's type-fn
   `:constraint` is the SAME type expression the checker resolves
   (`[:union :null …]` for a nullable alias/anon union row); primitive
   type-rows carry no constraint and stay blocking."
  [storage slot-ids]
  (let [slots (when (seq slot-ids) (sp/read-entities storage :slot slot-ids))
        type-ids (into [] (distinct) (keep :type-fn-id (vals slots)))
        type-rows (when (seq type-ids) (sp/read-entities storage :fn type-ids))
        nullable-slot-ids (into #{}
                                (keep (fn [[sid row]]
                                        (when (some-> (get type-rows (:type-fn-id row))
                                                      :constraint
                                                      nullable-type?)
                                          sid)))
                                slots)]
    (fn [slot-id] (contains? nullable-slot-ids slot-id))))


(defn- run-one!
  "Execute one test fn through the standard pipeline. A fn with
   BLOCKING free args isn't runnable as a test (nothing supplies
   them) — reported, never submitted; frees whose declared slot type
   explicitly admits nil default to nil (see `blocking-frees`)."
  [ctx fn-row timeout-ms]
  (let [storage (request/require-storage ctx)
        free (lookup/free-arg-slot-map-cached ctx (:id fn-row))
        blocking (if (seq free)
                   (blocking-frees free (nullable-slot-pred storage (vec (vals free))))
                   free)]
    (if (seq blocking)
      {:status :not-runnable
       :error (str "test has unbound args: "
                   (str/join ", " (sort (map name (keys blocking)))))}
      (-> (fn-exec/apply-execute ctx {:fn-id (:id fn-row) :args {}
                                      :timeout-ms timeout-ms :persist? true}
                                 fn-row)
          (select-keys [:status :execution-id :error :error-data])))))


(defn- coerce-fn-ids
  "Request-shape fn-ids (uuid strings) → set of UUIDs; invalid entries
   drop. nil/empty → nil (meaning \"all tests\")."
  [fn-ids]
  (when (seq fn-ids)
    (into #{} (keep #(if (uuid? %) % (parse-uuid (str %)))) fn-ids)))


(defn run-tests!
  "Run every test on the current branch (or the `:fn-ids` subset),
   sequentially, and summarise:
   `{:total N :passed N :failed N :other N :results [{…} …]}`.
   Pass = `:succeeded`; fail = `:failed`; everything else (`:pending`
   timeout-overrun, `:rejected` type-errors/capacity, `:not-runnable`
   free args) counts under `:other` with its own `:status`."
  [ctx {:keys [fn-ids timeout-ms]}]
  (let [wanted (coerce-fn-ids fn-ids)
        rows (cond->> (test-fn-rows ctx)
               wanted (filter (comp wanted :id)))
        ;; Nudge SSE listeners (the ✓ tests lens re-primes its status
        ;; cache on each ping) — best-effort, from EVERY run path (button,
        ;; API, auto-run). After EACH test, so a long suite's dots move
        ;; one by one; and once more after a settle delay — the terminal
        ;; execution row lands via the async `record-completion!` tail,
        ;; so a re-prime on the immediate ping can still read the previous
        ;; status (the 30 s keepalive tick remains the backstop).
        nudge! (when-let [emit (:notify-emitter ctx)]
                 (let [ev {:kind :test :op :updated :id ""
                           :branch-id (some-> (vs/current-branch-id
                                                (request/require-storage ctx))
                                              str)}]
                   (fn [] (try (emit ev) (catch Exception _ nil)))))
        results (mapv (fn [row]
                        (let [r (merge {:fn-id (str (:id row)) :fn-name (:name row)}
                                       (run-one! ctx row (or timeout-ms default-test-timeout-ms)))]
                          (when nudge! (nudge!))
                          r))
                      rows)
        by-status (frequencies (map :status results))]
    (when (and nudge! (seq results))
      (future (Thread/sleep 2000) (nudge!)))
    {:total (count results)
     :passed (get by-status :succeeded 0)
     :failed (get by-status :failed 0)
     :other (- (count results)
               (get by-status :succeeded 0)
               (get by-status :failed 0))
     :results results}))


;; --- status ---------------------------------------------------------------

(defn- latest-execution-rows
  "One row per version id — the newest `:fn-execution` for each, org-
   filtered (the raw-SQL contract of `fn-execution.errors`: executions
   are non-versioned, and `DISTINCT ON … ORDER BY started_at DESC`
   isn't expressible through the protocol reads)."
  [pool org version-ids]
  (when (and pool (seq version-ids))
    (jdbc/execute!
      pool
      (into [(str "SELECT DISTINCT ON (e.fn_version_id)"
                  " e.id, e.fn_version_id, e.status, e.error, e.started_at, e.finished_at"
                  " FROM \"fn_execution\" e"
                  " WHERE e.fn_version_id IN ("
                  (str/join "," (repeat (count version-ids) "?"))
                  ") AND coalesce(e.org_id, 'public') = ?"
                  " ORDER BY e.fn_version_id, e.started_at DESC")]
            (concat version-ids [(or org tc/public-org)]))
      {:builder-fn rs/as-unqualified-lower-maps})))


(defn latest-statuses
  "`{fn-id {:status :execution-id :error :started-at :finished-at}}` —
   the newest execution of each fn's CURRENT-branch version. A fn whose
   current version never ran is absent (the honest \"stale after edit\"
   signal)."
  [ctx fn-ids]
  (let [pool (:pool (:pg-storage ctx))
        vid->fid (into {}
                       (keep (fn [fid]
                               (when-let [vid (lookup/resolve-fn-version-id ctx fid)]
                                 [vid fid])))
                       fn-ids)]
    (into {}
          (map (fn [r]
                 [(get vid->fid (:fn_version_id r))
                  {:status (:status r)
                   :execution-id (:id r)
                   :error (:error r)
                   :started-at (some-> (:started_at r) str)
                   :finished-at (some-> (:finished_at r) str)}]))
          (latest-execution-rows pool (tc/current-org) (vec (keys vid->fid))))))


(defn tests-with-statuses
  "Discovery + status join, the GET /api/tests/status payload:
   `[{:fn-id :fn-name :namespace-id :status :error :execution-id
      :started-at :finished-at} …]`, name-sorted. `:status` nil = the
   current version has no recorded run."
  [ctx]
  (let [rows (test-fn-rows ctx)
        statuses (latest-statuses ctx (mapv :id rows))]
    (mapv (fn [row]
            (merge {:fn-id (str (:id row))
                    :fn-name (:name row)
                    :namespace-id (some-> (:namespace-id row) str)}
                   (get statuses (:id row))))
          rows)))
