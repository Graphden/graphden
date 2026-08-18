(ns graphden.crud.fn-execution-test
  "Tests for `graphden.crud.fn-execution` — the /api/execute parse /
   validate / apply / cancel pipeline. Schema is implicitly verified
   by the storage fixture (sync would fail otherwise); end-to-end
   tests submit real fn-graphs through the executor and check that
   rows persist + the future-cancel path works.

   These tests need the FULL schema (graph + versioned + executions)
   because `fn_execution` rows ref :fn-version. The default
   `create-test-storage` includes only the graph slice — we
   re-initialise here against the full builder chain.

   PARALLELISM: tests call `apply-and-await!` (local helper) instead
   of `fn-exec/apply-execute` directly — under in-JVM parallel test
   load the 5 s `:timeout-ms` knob can expire before the future
   derefs, leaving `:status :pending` and the row's `:result` blank
   until `record-completion!` lands later. The helper polls for the
   row's `:result` column on `:pending`, transparently passing
   inline successes through. Plus `with-isolated-rich-types` and
   per-NS PG database (via `shared-container-fixture`) cover the
   shared-mutable surfaces, so this ns runs safely under
   `:kaocha/parallelism > 1`."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.tools.logging]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.errors :as exec-errors]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.crud.fn-execution.stats :as exec-stats]
    [graphden.crud.test-runs :as test-runs]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.runtime :as rt]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.init.cleanup :as cleanup]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.schemas :as schemas]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc]))


(defn- full-schema
  ;; :stats? — the Phase C1 rollups apply-execute bumps at every
  ;; terminal transition; without the :usage-stat table each bump
  ;; degrades to a warn-log and the rollup tests can't roundtrip.
  []
  (schemas/full-schema {:stats? true}))


;; Shared-storage fixture: 52 tests in this ns used to drop+recreate
;; the public schema and re-run sp/initialize PER TEST (~14 CREATE
;; TABLE + 25 indexes worth of DDL). Now the heavy init runs once for
;; the whole ns; tests get a fresh-data view via TRUNCATE + reseed.
;; The shared atom is wiped at ns-end so other ns's fixtures don't
;; observe leaked state. Stop ~10s of repeated DDL per `bb test`.

(def ^:private shared-storage (atom nil))


(defn- init-shared-storage!
  []
  (let [container @(resolve 'graphden.executor.test-setup/*container*)]
    (pth/clean-database-fast! container)
    (let [storage (pg/create-storage (pth/get-container-config container))]
      (sp/initialize storage (full-schema))
      (reset! shared-storage storage))))


(defn- close-shared-storage!
  []
  (when-let [s @shared-storage]
    (sp/close s)
    (reset! shared-storage nil)))


(use-fixtures :once
  (setup/create-container-fixture)
  ;; `record-rich-types-raw!` writes by this ns ("audit-composed",
  ;; "tainted-composed", suffix-driven names) restore to a stub
  ;; rather than dissoc — sticky stubs leak into sibling tests.
  ;; `with-isolated-rich-types` enforces clean teardown.
  exec/with-isolated-rich-types
  (fn [f]
    (init-shared-storage!)
    (try (f)
         (finally (close-shared-storage!)))))


(defn- truncate-data-tables!
  "Wipe all user-row data between tests, but keep the schema itself
   (`sp/initialize` already ran in the :once fixture). Cheaper than
   DROP SCHEMA + reinit. CASCADE handles FK chains."
  [storage]
  (let [{:keys [jdbc-url username password]} (pth/get-container-config
                                               @(resolve 'graphden.executor.test-setup/*container*))]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      ;; Truncate every user table. `pg_tables` filters out PG system
      ;; tables. RESTART IDENTITY is unnecessary here (we use uuid
      ;; ids) but doesn't cost anything to specify.
      (let [tables (->> (jdbc/execute! conn
                                       [(str "SELECT tablename FROM pg_tables "
                                             "WHERE schemaname = 'public' "
                                             "AND tablename != '_schema_metadata'")])
                        (map :pg_tables/tablename))]
        (when (seq tables)
          (let [joined (str/join ", " (map #(str "\"" % "\"") tables))]
            (jdbc/execute! conn [(str "TRUNCATE TABLE " joined
                                      " RESTART IDENTITY CASCADE")]))))))
  storage)


(defn- create-full-storage
  "Returns a fresh VersionedStorage wrapper pointed at a freshly-created
   test-branch. Reuses the ns-level shared base storage; only the row
   contents (and the wrapper's branch-id) change per test."
  []
  (let [storage (or @shared-storage (init-shared-storage!))]
    (truncate-data-tables! storage)
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    ;; Wrap in versioned-storage so fn-version rows get created on every
    ;; fn write — fn-execution refs them.
    (let [branch (sp/create-entity storage :branch
                                   {:name "test-branch"
                                    :created-at (java.time.Instant/now)})]
      (vs/->VersionedStorage storage (:id branch)))))


(defn- apply-and-await!
  "Drop-in replacement for `fn-exec/apply-execute` that guarantees
   `:result` is materialised before returning. `fn-exec/apply-execute`
   itself returns `:status :pending` when the in-process
   `:timeout-ms` expires before the future derefs — under parallel
   test load (DB-pool + executor warmup contention) even a 2 + 2
   future can be late. `record-completion!` fills `:result`
   asynchronously, so tests that read the row immediately race the
   writer.

   On `:pending` we poll the row's `:result` column until it lands
   or 10 s passes (plenty for any primitive op). Inline successes
   pass straight through, so this wrapper is cheap on the happy
   path."
  [ctx parsed]
  (let [out (fn-exec/apply-execute ctx parsed)
        eid (some-> (:execution-id out) parse-uuid)
        storage (:storage ctx)]
    (when (and eid storage (= :pending (:status out)))
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (loop []
          (let [row (sp/read-entity storage :fn-execution eid)]
            (when (and (nil? (:result row))
                       (not (#{:failed "failed"} (:status row)))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 50)
              (recur))))))
    out))


(defn- make-pure-add-fn!
  "Build a small base-fn + composed instance the executor can run.
   `my-add` takes free args `:a` and `:b` (both ints), returns their
   sum. The base-fn impl is registered globally; storage carries the
   two fn rows. Returns the composed fn-record."
  [storage suffix]
  (let [add-name (str "test-add-" suffix)
        composed-name (str "my-test-add-" suffix)
        impl-fn (fn [args _ctx]
                  ;; args are plain values for eagerly-resolved slots
                  ;; (no :lazy-seq-args declaration on the base-fn).
                  (+ (:a args) (:b args)))]
    (exec/register-base-fn! (keyword add-name) impl-fn)
    (let [base (setup/create-base-fn! storage add-name :int)
          slot-a (setup/create-slot! storage "a" :int)
          slot-b (setup/create-slot! storage "b" :int)]
      (setup/attach-slot! storage (:id base) (:id slot-a) 0)
      (setup/attach-slot! storage (:id base) (:id slot-b) 1)
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))
       :slot-a slot-a
       :slot-b slot-b})))


;; Stage-2 pre-flight rejection coverage (no-fn / fn-not-found /
;; timeout-out-of-range / args-too-large / already-running-as-service
;; / unknown-arg / malformed-ref) lives in
;; `graphden.integration.execute-http-test`. Validation is a graph
;; `:cond` (`:_execute-validation`) with no Clojure mirror, so it can
;; only be exercised against a fully bootstrapped package graph —
;; which the integration fixture provides and this fast, schema-only
;; unit fixture does not.


;; ============================================================================
;; query-param — raw-fallback URL decode must not throw on bad input
;; ============================================================================

(deftest query-param-test
  (testing "enriched :query-params (string + keyword key) win"
    (is (= "v" (fn-exec/query-param {:query-params {"p" "v"}} "p")))
    (is (= "v" (fn-exec/query-param {:query-params {:p "v"}} "p"))))

  (testing "raw :query-string fallback is URL-decoded"
    (is (= "hello world"
           (fn-exec/query-param {:query-string "p=hello%20world"} "p"))))

  (testing "malformed percent-escape in the raw fallback fails soft (no 500)"
    ;; A lone `%` / bad hex used to bubble IllegalArgumentException up as a
    ;; 500. `safe-url-decode` now returns the raw match instead.
    (is (= "a%" (fn-exec/query-param {:query-string "p=a%"} "p")))
    (is (= "%zz" (fn-exec/query-param {:query-string "p=%zz"} "p"))))

  (testing "absent param → nil"
    (is (nil? (fn-exec/query-param {:query-string "other=1"} "p")))
    (is (nil? (fn-exec/query-param {} "p")))))


;; ============================================================================
;; apply-execute — end-to-end via the executor
;; ============================================================================

(deftest apply-inline-for-fast-pure-fn-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "inline")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed)
                    :args {:a 1 :b 2}
                    :timeout-ms 5000 :persist? false})]
    (try
      (testing "fast pure fn returns inline without :execution-id"
        (is (= :succeeded (:status result)))
        (is (= 3 (:result result)))
        (is (nil? (:execution-id result)))
        (testing "no fn-execution row was persisted (pure + ¬persist?)"
          (is (empty? (sp/query-entities storage :fn-execution {})))))
      (finally nil))))


(deftest execute-refused-on-recorded-type-errors-test
  ;; Error-tolerance Phase 4 — recorded diagnostics on the current
  ;; branch refuse submission BEFORE any row / future / capacity slot
  ;; exists. Clearing the entry (what a fixing write's re-check does)
  ;; re-admits; an fn with NO recorded entry is allowed by absence —
  ;; the derived-store contract.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (create-full-storage)
          {composed :composed} (make-pure-add-fn! storage "typerr")
          branch-id (vs/current-branch-id storage)
          c (setup/default-registry-ctx storage)]
      (testing "recorded diagnostics → :rejected envelope, nothing persisted"
        (diag/record! branch-id (:id composed)
                      [{:message "Type mismatch on arg :a"
                        :expected :int :actual :text :arg-name :a}])
        (let [out (fn-exec/apply-execute c {:fn-id (:id composed)
                                            :args {:a 1 :b 2}
                                            :timeout-ms 5000 :persist? true})]
          (is (false? (:ok out)))
          (is (= :rejected (:status out)))
          (is (= 400 (:http-status out)))
          (is (= :unresolved-type-errors (get-in out [:error-data :reason])))
          (is (str/includes? (:error out) "my-test-add-typerr")
              "message names the fn")
          (is (str/includes? (:error out) "Type mismatch on arg :a")
              "message carries the first recorded error")
          (is (= 1 (count (get-in out [:error-data :diagnostics]))))
          (is (empty? (sp/query-entities storage :fn-execution {}))
              "refusal precedes persistence — no row even with :persist? true")))
      (testing "clearing the recorded entry (fn fixed) re-admits execution"
        (diag/clear-fn! branch-id (:id composed))
        (let [out (apply-and-await! c {:fn-id (:id composed)
                                       :args {:a 1 :b 2}
                                       :timeout-ms 5000 :persist? false})]
          (is (contains? #{:succeeded :pending} (:status out)))
          (when (= :succeeded (:status out))
            (is (= 3 (:result out))))))
      (testing "an fn never checked (fresh id, no recorded entry) is allowed"
        (let [{fresh :composed} (make-pure-add-fn! storage "typerr-fresh")
              ;; Fresh ctx: `test-ctx` snapshots the base-fn registry,
              ;; and the fresh base impl was registered after `c` above.
              out (apply-and-await! (setup/default-registry-ctx storage)
                                    {:fn-id (:id fresh)
                                     :args {:a 2 :b 3}
                                     :timeout-ms 5000 :persist? false})]
          (is (contains? #{:succeeded :pending} (:status out))))))))


(deftest usage-rollup-bump-and-read-roundtrip-test
  ;; Phase C1: the pre-aggregated :usage-stat counters. bump! upserts one row
  ;; per (hour, org, fn, status) and increments in place; fn-stats aggregates
  ;; a trailing window scoped to ONE org; sweep-stats! enforces retention.
  (let [storage (create-full-storage)
        pool (:pool @shared-storage)
        fn-id (random-uuid)
        now (System/currentTimeMillis)]
    (testing "three bumps → one aggregate: 2 succeeded + 1 failed, durations summed"
      (exec-stats/bump! pool {:org nil :fn-id fn-id :status :succeeded :duration-ms 10 :now-ms now})
      (exec-stats/bump! pool {:org nil :fn-id fn-id :status :succeeded :duration-ms 20 :now-ms now})
      (exec-stats/bump! pool {:org nil :fn-id fn-id :status :failed :duration-ms 5 :now-ms now})
      (is (= {:runs 3 :failed 1 :cancelled 0 :duration-ms-sum 35}
             (exec-stats/fn-stats pool nil fn-id 7))))
    (testing "org scoping — another org's bumps don't leak into public's read"
      (exec-stats/bump! pool {:org "acme" :fn-id fn-id :status :succeeded :duration-ms 1 :now-ms now})
      (is (= 3 (:runs (exec-stats/fn-stats pool nil fn-id 7))) "public unchanged")
      (is (= 1 (:runs (exec-stats/fn-stats pool "acme" fn-id 7))) "acme sees its own"))
    (testing "org-stats lists the busiest fns for one org"
      (let [rows (exec-stats/org-stats pool nil 7 10)]
        (is (= [fn-id] (mapv :fn-id rows)))
        (is (= 3 (:runs (first rows))))))
    (testing "org-summary — the Stats panel headline, run-weighted avg, org-scoped"
      (is (= {:runs 3 :failed 1 :avg-ms 11}     ; (10+20+5)/3 = 11
             (exec-stats/org-summary pool nil 7)))
      (is (= {:runs 1 :failed 0 :avg-ms 1} (exec-stats/org-summary pool "acme" 7))
          "acme sees only its own"))
    (testing "org-daily — per-day series, org-scoped, shaped avg + bar pct"
      (let [rows (exec-stats/org-daily pool nil 7)]
        (is (= 1 (count rows)) "all three bumps share today's bucket")
        (is (= {:runs 3 :failed 1 :avg-ms 11 :runs-pct 100}
               (select-keys (first rows) [:runs :failed :avg-ms :runs-pct])))
        (is (string? (:day (first rows))))))
    (testing "org-fn-stats-named — busiest fns with the name join + shaped avg"
      (let [rows (exec-stats/org-fn-stats-named pool nil 7 20)]
        (is (= [fn-id] (mapv :fn-id rows)))
        (is (= {:runs 3 :failed 1 :avg-ms 11 :runs-pct 100}
               (select-keys (first rows) [:runs :failed :avg-ms :runs-pct])))
        (is (contains? (first rows) :fn-name))))
    (testing "org-all-stats — cross-org operator read, busiest first, bar pct
              relative to the busiest org"
      (let [rows (exec-stats/org-all-stats pool 7 20)]
        (is (= ["public" "acme"] (mapv :org rows)))
        (is (= [100 33] (mapv :runs-pct rows))
            "acme's 1 run vs public's 3 → 33%")
        (is (= {:runs 3 :failed 1 :avg-ms 11}
               (select-keys (first rows) [:runs :failed :avg-ms])))))
    (testing "retention sweep deletes old buckets only"
      (let [old-ms (- now (* 120 24 60 60 1000))]
        (exec-stats/bump! pool {:org nil :fn-id fn-id :status :succeeded :now-ms old-ms})
        (is (= 1 (exec-stats/sweep-stats! pool 90)) "exactly the old bucket went")
        (is (= 3 (:runs (exec-stats/fn-stats pool nil fn-id 7))) "recent rows survive"))
      (is storage))))


(deftest apply-execute-bumps-the-usage-rollup-test
  ;; End-to-end: a normal apply-execute terminal transition writes the rollup —
  ;; the ctx carries :pg-storage (the raw pool), mirroring prod.
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "rollup")
        c (assoc (setup/default-registry-ctx storage) :pg-storage @shared-storage)
        result (apply-and-await!
                 c {:fn-id (:id composed)
                    :args {:a 2 :b 3}
                    :timeout-ms 5000 :persist? false})]
    (is (= :succeeded (:status result)))
    (let [stats (exec-stats/fn-stats (:pool @shared-storage) nil (:id composed) 7)]
      (is (= 1 (:runs stats)) "the inline arm bumped exactly once")
      (is (zero? (:failed stats))))))


(deftest recent-failures-lists-the-failed-run-test
  ;; Phase C2: a failed persisted execution shows up in the org-scoped
  ;; recent-failures listing with its (write-side scrubbed) error text.
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "errlog")
        c (assoc (setup/default-registry-ctx storage) :pg-storage @shared-storage)
        ;; :b "boom" → the impl's (+ a b) throws inline → :failed row
        ;; (persist? true so the row is written even for the inline arm).
        result (apply-and-await!
                 c {:fn-id (:id composed)
                    :args {:a 1 :b "boom"}
                    :timeout-ms 5000 :persist? true})]
    (is (= :failed (:status result)))
    (let [rows (exec-errors/recent-failures (:pool @shared-storage) nil 7 10)]
      (is (= 1 (count rows)) "exactly the one failure listed")
      (let [row (first rows)]
        (is (= (:id composed) (:fn-id row)) "joined back to the LOGICAL fn id")
        (is (str/includes? (str (:fn-name row)) "my-test-add-errlog")
            "display name joined from the fn row")
        (is (seq (str (:error row))) "carries the error text")))
    (testing "another org's read sees nothing (explicit org filter)"
      (is (empty? (exec-errors/recent-failures (:pool @shared-storage) "acme" 7 10))))))


(deftest apply-stamps-touched-secret-on-rows-that-feed-side-effecting-sinks-test
  ;; Followup-3 audit trail: a row carries `:touched-secret? true`
  ;; iff (a) the fn-def's rich-type contains a `:secret` marker
  ;; anywhere AND (b) the runtime observed at least one effect.
  ;; Both halves matter — a pure tainted-aware run isn't an audit
  ;; event; a runtime-side-effect on a fn that never saw a secret
  ;; isn't either.
  (let [storage (create-full-storage)
        base-name "audit-sink"
        composed-name "audit-composed"
        ;; The base-fn's impl records an :io effect AND is declared
        ;; with a `[:secret :text]` slot — so `touches-secret?`
        ;; returns true and the run will have non-empty
        ;; runtime-effects.
        _ (exec/register-base-fn! (keyword base-name)
                                  (fn [_args _ctx]
                                    (graphden.executor.compile-runtime/record-effect! :io)
                                    42))
        _ (registry/record-rich-types! (keyword base-name)
                                       {:args {:secret-arg {:type [:secret :text]}}
                                        :return-type :int
                                        :effects #{:io}})
        base (setup/create-base-fn! storage base-name :int)
        composed (setup/create-composed-fn! storage composed-name (:id base))
        _ (registry/record-rich-types! (keyword composed-name)
                                       {:args {:secret-arg {:type [:secret :text]}}
                                        :return-type :int
                                        :effects #{:io}})
        c (setup/default-registry-ctx storage)]
    (testing "execute → row carries :touched-secret? true"
      (let [r (apply-and-await!
                c {:fn-id (:id composed)
                   :args {:secret-arg "literal-auto-promoted-to-secret"}
                   :timeout-ms 5000 :persist? true})
            row (sp/read-entity storage :fn-execution
                                (java.util.UUID/fromString (:execution-id r)))]
        (is (= :succeeded (:status r)))
        (is (true? (:touched-secret? r))
            "the inline response must carry :touched-secret? true")
        (is (true? (:touched-secret? row))
            "the persisted row must carry :touched-secret? true")))))


(deftest apply-leaves-touched-secret-nil-for-non-secret-fns-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "no-secret")
        c (setup/default-registry-ctx storage)
        r (apply-and-await!
            c {:fn-id (:id composed)
               :args {:a 1 :b 2}
               :timeout-ms 5000 :persist? true})
        row (sp/read-entity storage :fn-execution
                            (java.util.UUID/fromString (:execution-id r)))]
    (testing "non-secret fn-defs don't trip the audit flag"
      (is (nil? (:touched-secret? r)))
      (is (nil? (:touched-secret? row))))))


(deftest tenant-execute-gates-submitted-fn-effects-test
  ;; Regression for the tenancy fix: a tenant's SUBMITTED fn runs
  ;; effect-restricted — `apply-execute` puts `default-cloud-allowed-effects`
  ;; on the exec ctx for a non-public org — so it can't reach the `:raw-sql`
  ;; escape hatch; a platform/public execution is unrestricted. (The blanket
  ;; request-wrap gate that used to do this 403'd the platform handler's own
  ;; storage reads — hence the two-layer gate in tenancy.addon.)
  (let [storage (create-full-storage)
        nm "rawsql-sink"
        composed-name "rawsql-composed"
        _ (exec/register-base-fn! (keyword nm)
                                  (fn [_args _ctx]
                                    (cr/record-effect! :raw-sql)
                                    :did-sql))
        _ (registry/record-rich-types! (keyword nm)
                                       {:args {} :return-type :any :effects #{:raw-sql}})
        base (setup/create-base-fn! storage nm :any)
        composed (setup/create-composed-fn! storage composed-name (:id base))
        _ (registry/record-rich-types! (keyword composed-name)
                                       {:args {} :return-type :any :effects #{:raw-sql}})
        c (setup/default-registry-ctx storage)
        run #(apply-and-await! c {:fn-id (:id composed) :args {}
                                  :timeout-ms 5000 :persist? true})]
    (testing "platform / public execution is unrestricted — the :raw-sql fn runs"
      (let [r (tc/with-org tc/public-org (run))]
        (is (= :succeeded (:status r)))))
    (testing "a tenant's submitted fn is effect-gated — :raw-sql refused"
      (let [r (tc/with-org "acme" (run))]
        (is (not= :succeeded (:status r))
            "the :raw-sql escape hatch must not run for a tenant")
        (is (re-find #"(?i)raw-sql|forbidden"
                     (str (:error r) " " (pr-str (:error-data r))))
            "the failure names the forbidden effect")))))


(deftest apply-hides-result-for-tainted-fn-test
  ;; A fn-def whose registered :return carries the `:secret` marker
  ;; must NOT leak its computed value through `/api/execute`. The
  ;; response shape becomes `{status: succeeded, result: nil,
  ;; tainted?: true}` — the metadata still confirms success, but
  ;; the value lives only inside the JVM.
  (let [storage (create-full-storage)
        ;; Register a base-fn whose impl returns the literal secret
        ;; value. Its rich-types signature pins return to
        ;; `[:secret :text]` so `tainted-fn?` sees the marker.
        base-name "tainted-base"
        composed-name "tainted-composed"
        _ (exec/register-base-fn! (keyword base-name)
                                  (fn [_ _ctx] "hunter2"))
        _ (registry/record-rich-types! (keyword base-name)
                                       {:args {}
                                        :return-type [:secret :text]})
        base (setup/create-base-fn! storage base-name :text)
        composed (setup/create-composed-fn! storage composed-name (:id base))
        _ (registry/record-rich-types! (keyword composed-name)
                                       {:args {}
                                        :return-type [:secret :text]})
        c (setup/default-registry-ctx storage)]
    (testing "inline succeeded response carries :tainted? without value"
      (let [r (apply-and-await!
                c {:fn-id (:id composed)
                   :args {}
                   :timeout-ms 5000 :persist? false})]
        (is (= :succeeded (:status r)))
        (is (nil? (:result r)) "secret value MUST NOT appear in response")
        (is (true? (:tainted? r)))))

    (testing "persisted execution row also stores nil + tainted marker"
      (let [r (apply-and-await!
                c {:fn-id (:id composed)
                   :args {}
                   :timeout-ms 5000 :persist? true})
            row (sp/read-entity storage :fn-execution
                                (java.util.UUID/fromString (:execution-id r)))]
        (is (nil? (:result row)) "persisted :result must be nil")
        (is (= :tainted (:reason (:error-data row))))))))


(deftest apply-persists-when-persist-flag-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "persist")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed)
                    :args {:a 4 :b 5}
                    :timeout-ms 5000 :persist? true})]
    (try
      (testing "persist?=true → row stored + execution-id returned"
        (is (= :succeeded (:status result)))
        (is (= 9 (:result result)))
        (is (some? (:execution-id result))))
      (testing "row exists in storage"
        (let [rows (sp/query-entities storage :fn-execution {})]
          (is (= 1 (count rows)))))
      (finally nil))))


(deftest apply-persists-args-rows-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "args")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed)
                    :args {:a 7 :b 8}
                    :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "each arg becomes one :fn-execution-arg row"
        (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                          {:execution-id exec-id})]
          (is (= 2 (count arg-rows)))
          (is (= #{7 8} (set (map :value arg-rows))))))
      (finally nil))))


;; ============================================================================
;; get-execution
;; ============================================================================

(deftest get-execution-returns-row-with-args-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "getex")
        c (setup/default-registry-ctx storage)
        sub (apply-and-await!
              c {:fn-id (:id composed) :args {:a 10 :b 20}
                 :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id sub) java.util.UUID/fromString)]
    (try
      (let [row (fn-exec/get-execution c exec-id)]
        (testing "row carries the bookkeeping fields"
          (is (some? row))
          (is (= :succeeded (:status row)))
          (is (= 30 (:result row)))
          (is (some? (:fn-version-id row))))
        (testing "nested args list reflects what was submitted"
          (is (= 2 (count (:args row))))
          (is (= #{10 20} (set (map :value (:args row)))))))
      (testing "missing id → nil"
        (is (nil? (fn-exec/get-execution c (random-uuid)))))
      (finally nil))))


;; ============================================================================
;; cancel-execution! — sets flag, future-cancel best-effort
;; ============================================================================

(deftest cancel-execution-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "cancel")
        c (setup/default-registry-ctx storage)
        sub (apply-and-await!
              c {:fn-id (:id composed) :args {:a 1 :b 2}
                 :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id sub) java.util.UUID/fromString)]
    (try
      (testing "cancel sets :cancel-requested? + returns :ok"
        (let [resp (fn-exec/cancel-execution! c exec-id)]
          (is (true? (:ok resp)))
          (is (true? (:cancel-requested resp))))
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (true? (:cancel-requested? row)))))
      (testing "cancel on a non-existent id is a no-op (nil)"
        (is (nil? (fn-exec/cancel-execution! c (random-uuid)))))
      (finally nil))))


;; ============================================================================
;; cancel-execution! when no future is registered (server-restart case
;; or already-reaped execution): the row's :cancel-requested? flag
;; still flips, but there's no in-process Future to interrupt. Should
;; be a benign no-op on the future side, not a crash.
;; ============================================================================

(deftest cancel-fans-out-to-siblings-when-future-is-elsewhere-test
  ;; `futures-registry` is per-process. Behind a load balancer the pod
  ;; that receives POST /api/execute/:id/cancel usually isn't the pod
  ;; running the execution — so it must fan the request out on
  ;; `graphden_events` instead of silently flipping a DB flag nobody
  ;; reads (`*cancel-check*` closes over the in-process atom, not the row).
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "cancel-fanout")
        vid (->> (sp/query-entities storage :fn-version {:fn-id (:id composed)})
                 first :id)
        row (sp/create-entity storage :fn-execution
                              {:fn-version-id vid
                               :started-at (java.time.Instant/now)
                               :status :pending})
        emitted (atom [])
        c (assoc (setup/default-registry-ctx storage) :notify-emitter #(swap! emitted conj %))]
    (testing "no local future → emit execution:cancel so the owning pod acts"
      (fn-exec/cancel-execution! c (:id row))
      (is (= [{:kind :execution :op :cancel :id (str (:id row))}] @emitted)))

    (testing "local future → cancel in-process, no fan-out needed"
      (reset! emitted [])
      (let [flag (atom false)
            fut (future (Thread/sleep 60000))]
        (persist/register-future! (:id row) fut flag)
        (try
          (fn-exec/cancel-execution! c (:id row))
          (is (true? @flag) "the in-process cancel-flag was flipped")
          (is (= [] @emitted) "owning pod doesn't need to tell anyone")
          (finally
            (persist/unregister-future! (:id row))
            (future-cancel fut)))))))


(deftest cancel-local-reports-whether-this-pod-owns-the-future-test
  (let [exec-id (random-uuid)]
    (is (false? (persist/cancel-local! exec-id))
        "a pod that isn't running it says so, rather than pretending success")
    (let [flag (atom false)
          fut (future (Thread/sleep 60000))]
      (persist/register-future! exec-id fut flag)
      (try
        (is (true? (persist/cancel-local! exec-id)))
        (is (true? @flag))
        (finally
          (persist/unregister-future! exec-id)
          (future-cancel fut))))))


(deftest cancel-execution-with-no-future-test
  (let [storage (create-full-storage)
        ;; Use make-pure-add-fn! solely to materialise a :fn + matching
        ;; :fn-version row; the actual fn-execution is hand-rolled to
        ;; bypass the run-future / register-future! path that the
        ;; restart / already-reaped case is supposed to lack.
        {composed :composed} (make-pure-add-fn! storage "cancel-no-fut")
        vid (->> (sp/query-entities storage :fn-version {:fn-id (:id composed)})
                 first :id)
        row (sp/create-entity storage :fn-execution
                              {:fn-version-id vid
                               :started-at (java.time.Instant/now)
                               :status :pending})
        c (setup/default-registry-ctx storage)]
    (try
      (testing "cancel returns :ok even when futures-registry has no entry"
        (let [resp (fn-exec/cancel-execution! c (:id row))]
          (is (true? (:ok resp)))
          (is (true? (:cancel-requested resp)))))
      (testing "row's :cancel-requested? flag flipped"
        (let [updated (sp/read-entity storage :fn-execution (:id row))]
          (is (true? (:cancel-requested? updated)))))
      (finally nil))))


;; ============================================================================
;; *cancel-check* — the executor dyn-var the future binds
;; ============================================================================

(deftest cancel-check-throws-when-flag-flipped-test
  (testing "*cancel-check* nil by default → no-op"
    (is (nil? (cr/check-cancel!))))
  (testing "bound to a throwing fn → throws on call"
    (binding [cr/*cancel-check* #(throw (InterruptedException. "stop"))]
      (is (thrown? InterruptedException (cr/check-cancel!))))))


;; ============================================================================
;; Cancel — end-to-end: future running a slow fn observes
;; `*cancel-check*` and writes :status :cancelled.
;; ============================================================================

(defn- make-slow-cancelable-fn!
  "Build a base-fn that loops 50 times sleeping 100ms between
   iterations, calling `cr/check-cancel!` on every iteration. Returns
   a composed fn-record. Registered globally so apply-execute's
   bound-fn picks it up. Run total ≈ 5 seconds; cancel fires on the
   next iteration after the flag flips."
  [storage suffix]
  (let [base-name (str "slow-cancelable-" suffix)
        composed-name (str "my-slow-cancelable-" suffix)
        impl-fn (fn [_args _ctx]
                  (dotimes [_ 50]
                    (cr/check-cancel!)
                    (Thread/sleep 100))
                  :done)]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :keyword)]
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))})))


(deftest cancel-actually-interrupts-running-future-test
  (let [storage (create-full-storage)
        {composed :composed} (make-slow-cancelable-fn! storage "real-cancel")
        c (setup/default-registry-ctx storage)
        ;; Short timeout — fn takes ~5s, timeout flips to pending fast.
        ;; Bypass the await-helper here: this test wants the :pending
        ;; intermediate state, polling the row to :cancelled itself.
        sub (fn-exec/apply-execute c {:fn-id (:id composed)
                                      :args {}
                                      :timeout-ms 300
                                      :persist? true})
        exec-id (some-> (:execution-id sub) java.util.UUID/fromString)]
    (try
      (testing "submit flipped to pending (fn still running)"
        (is (= :pending (:status sub)))
        (is (some? exec-id)))
      (testing "cancel sets flag + future-cancel; status flips to :cancelled"
        (Thread/sleep 200)   ; let future start its loop
        (fn-exec/cancel-execution! c exec-id)
        ;; Future polls *cancel-check* every 100ms — within ~200ms
        ;; the thrown InterruptedException reaches the reaper and
        ;; record-completion! writes :status :cancelled. Poll up to
        ;; 3s to give the storage write headroom.
        (let [deadline (+ (System/currentTimeMillis) 3000)]
          (loop []
            (let [row (sp/read-entity storage :fn-execution exec-id)
                  status (:status row)]
              (cond
                (#{:cancelled "cancelled"} status) :done
                (> (System/currentTimeMillis) deadline)
                (throw (ex-info (str "timeout waiting for :cancelled; saw "
                                     (pr-str status))
                                {:row row}))
                :else (do (Thread/sleep 100) (recur))))))
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (#{:cancelled "cancelled"} (:status row))
              "row's :status flipped to :cancelled after cancel")
          (is (some? (:finished-at row))
              "finished-at set when the future is reaped")))
      (finally nil))))


;; ============================================================================
;; list-executions-for-fn — history listing endpoint
;; ============================================================================

;; ============================================================================
;; Async pending → succeeded — the truly-async happy path. apply-execute
;; flips to :pending when the future doesn't resolve in time; the
;; record-completion! tail-future later writes :status :succeeded on
;; the same row. Other tests synchronously check write-finished!'s
;; output because their timeout-ms (5000) is much longer than the test
;; fn's runtime — this one exercises the tail-future write path that
;; the polling client actually depends on in production.
;; ============================================================================

(defn- make-slow-pure-fn!
  "Base-fn that sleeps `sleep-ms` then returns `:done`. Used to force
   apply-execute's deref-timeout branch."
  [storage suffix sleep-ms]
  (let [base-name (str "slow-pure-" suffix)
        composed-name (str "my-slow-pure-" suffix)
        impl-fn (fn [_args _ctx]
                  (Thread/sleep ^long sleep-ms)
                  :done)]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :keyword)]
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))})))


(deftest apply-pending-then-tail-writes-succeeded-test
  (let [storage (create-full-storage)
        ;; Fn sleeps 600ms. timeout-ms 100 forces the pending flip;
        ;; tail-future writes :succeeded ~500ms later.
        {composed :composed} (make-slow-pure-fn! storage "tail-write" 600)
        c (setup/default-registry-ctx storage)
        ;; Bypass the await-helper: this test EXPECTS :pending and
        ;; polls the tail-write itself.
        sub (fn-exec/apply-execute c {:fn-id (:id composed)
                                      :args {}
                                      :timeout-ms 100
                                      :persist? true})
        exec-id (some-> (:execution-id sub) java.util.UUID/fromString)]
    (try
      (testing "submit flipped to :pending — future still running"
        (is (= :pending (:status sub)))
        (is (some? exec-id)))
      (testing "row carries :pending status pre-resolution"
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (#{:pending "pending"} (:status row)))
          (is (nil? (:finished-at row))
              ":finished-at is nil until the reaper runs")))
      (testing "tail-future eventually writes :succeeded + :result + :finished-at"
        ;; Wait up to 3s for the reaper to update the row.
        (let [deadline (+ (System/currentTimeMillis) 3000)]
          (loop []
            (let [row (sp/read-entity storage :fn-execution exec-id)
                  status (:status row)]
              (cond
                (#{:succeeded "succeeded"} status) :done
                (> (System/currentTimeMillis) deadline)
                (throw (ex-info (str "timeout waiting for :succeeded; saw "
                                     (pr-str status))
                                {:row row}))
                :else (do (Thread/sleep 100) (recur))))))
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (#{:succeeded "succeeded"} (:status row)))
          (is (some? (:finished-at row))
              ":finished-at populated by reaper")
          (is (#{:done "done"} (:result row))
              ":result roundtrips from the impl's return value")))
      (finally nil))))


(deftest list-executions-empty-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "list-empty")
        c (setup/default-registry-ctx storage)]
    (try
      (is (= [] (fn-exec/list-executions-for-fn c (:id composed)))
          "fn that was never run has no history rows")
      (finally nil))))


(deftest list-executions-returns-recent-runs-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "list-runs")
        c (setup/default-registry-ctx storage)]
    (try
      ;; Three persisted runs — varying :b so we can identify them.
      (doseq [b [10 20 30]]
        (apply-and-await! c {:fn-id (:id composed)
                             :args {:a 1 :b b}
                             :timeout-ms 5000 :persist? true}))
      (let [rows (fn-exec/list-executions-for-fn c (:id composed))]
        (testing "all three runs surface"
          (is (= 3 (count rows))))
        (testing "ordered by started-at desc (latest first)"
          (let [ts (mapv :started-at rows)]
            (is (= ts (reverse (sort ts)))
                "started-at is non-increasing")))
        (testing "rows carry :status and :result"
          (is (every? #(#{:succeeded "succeeded"} (:status %)) rows))
          (is (= #{11 21 31} (set (map :result rows))))))
      (finally nil))))


(deftest list-executions-isolates-by-branch-version-test
  ;; Pre-fix this returned executions for ALL versions of the fn-id
  ;; regardless of branch. Per the versioning UI model, the execute
  ;; popover should only show runs of the version that resolves on
  ;; the CURRENT branch — older versions live behind the `⌛` panel.
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "list-branch-iso")
        parent-ctx (setup/default-registry-ctx storage)
        ;; one run on the parent (test-branch)
        _ (apply-and-await! parent-ctx {:fn-id (:id composed)
                                        :args {:a 1 :b 1}
                                        :timeout-ms 5000 :persist? true})
        child-branch (vs/create-branch! storage "list-iso-child")
        child-storage (vs/switch-branch storage (:id child-branch))]
    (try
      (testing "before child override: child inherits parent's version → sees the run"
        (let [rows (fn-exec/list-executions-for-fn
                     (setup/default-registry-ctx child-storage) (:id composed))]
          (is (= 1 (count rows))
              "no own version yet → resolves to parent's version → shared run")))

      ;; Edit fn on child → new version anchors subsequent runs.
      (sp/update-entity child-storage :fn (:id composed)
                        {:description "edited on child"})
      (let [child-ctx (setup/default-registry-ctx child-storage)]
        (apply-and-await! child-ctx {:fn-id (:id composed)
                                     :args {:a 2 :b 2}
                                     :timeout-ms 5000 :persist? true}))

      (testing "after child override: parent still sees only its own version's run"
        (let [parent-rows (fn-exec/list-executions-for-fn parent-ctx (:id composed))]
          (is (= 1 (count parent-rows)))
          (is (= 2 (:result (first parent-rows))) "1 + 1 from parent run")))

      (testing "child sees only its own version's run, not the parent's"
        (let [child-rows (fn-exec/list-executions-for-fn
                           (setup/default-registry-ctx child-storage) (:id composed))]
          (is (= 1 (count child-rows)))
          (is (= 4 (:result (first child-rows))) "2 + 2 from child run")))

      (testing "list-executions-for-fn-version explicitly targets a version row"
        (let [parent-vid (lookup/resolve-fn-version-id parent-ctx (:id composed))
              child-vid (lookup/resolve-fn-version-id
                          (setup/default-registry-ctx child-storage) (:id composed))]
          (is (not= parent-vid child-vid))
          (is (= 2 (:result (first (fn-exec/list-executions-for-fn-version
                                     parent-ctx parent-vid))))
              "parent version's run")
          (is (= 4 (:result (first (fn-exec/list-executions-for-fn-version
                                     parent-ctx child-vid))))
              "child version's run is reachable from EITHER ctx by explicit version-id")))
      (finally nil))))


(deftest list-executions-caps-at-twenty-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "list-cap")
        c (setup/default-registry-ctx storage)]
    (try
      (dotimes [i 25]
        (apply-and-await! c {:fn-id (:id composed)
                             :args {:a i :b 1}
                             :timeout-ms 5000 :persist? true}))
      (is (= 20 (count (fn-exec/list-executions-for-fn c (:id composed))))
          "default history-limit caps the list at 20 even with 25 runs")
      (testing "explicit :limit narrows the result set"
        (is (= 5 (count (fn-exec/list-executions-for-fn c (:id composed) 5))))
        (is (= 25 (count (fn-exec/list-executions-for-fn c (:id composed) 50)))
            ":limit larger than available rows returns everything"))
      (testing ":limit clamped to max (100) — even a request for 9999"
        (is (<= (count (fn-exec/list-executions-for-fn c (:id composed) 9999)) 100)))
      (testing "non-positive / invalid :limit falls back to default 20"
        (is (= 20 (count (fn-exec/list-executions-for-fn c (:id composed) 0))))
        (is (= 20 (count (fn-exec/list-executions-for-fn c (:id composed) -5))))
        (is (= 20 (count (fn-exec/list-executions-for-fn c (:id composed) nil)))))
      (finally nil))))


;; ============================================================================
;; sweep-executions! — TTL cleanup integrant component
;; ============================================================================

(defn- make-exec-row!
  "Insert a synthetic :fn-execution row with prescribed status +
   timestamps. Returns the row's :id. Skips :fn-version-id (the
   field is :ref but :nullable? is enforced by the storage backend;
   for sweep tests we don't navigate it). The schema enforces
   `:fn-version-id NOT NULL` so we wire one in via a synthetic
   fn-version row that the test creates first."
  [storage fn-version-id status started-at finished-at]
  (let [r (sp/create-entity storage :fn-execution
                            {:fn-version-id fn-version-id
                             :started-at started-at
                             :finished-at finished-at
                             :status status})]
    (:id r)))


(defn- ensure-fn-version!
  "Create a minimal :fn-version row we can refer to from synthetic
   exec rows. Returns its id."
  [storage]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (:base-storage storage)
                       storage)
        branch (sp/create-entity base-storage :branch
                                 {:name (str "sweep-test-"
                                             (random-uuid))
                                  :created-at (java.time.Instant/now)})
        fn-row (sp/create-entity base-storage :fn
                                 {:name (str "sweep-test-fn-"
                                             (random-uuid))})
        v (sp/create-entity base-storage :fn-version
                            {:fn-id (:id fn-row)
                             :branch-id (:id branch)
                             :created-at (java.time.Instant/now)})]
    (:id v)))


(deftest sweep-deletes-succeeded-past-ttl-test
  (let [storage (create-full-storage)
        vid (ensure-fn-version! storage)
        now (java.time.Instant/parse "2026-05-21T12:00:00Z")
        ;; Two rows: one 8 days old (TTL expired), one 1 day old (keep).
        old-id  (make-exec-row! storage vid :succeeded
                                (java.time.Instant/parse "2026-05-13T11:00:00Z")
                                (java.time.Instant/parse "2026-05-13T11:00:01Z"))
        fresh-id (make-exec-row! storage vid :succeeded
                                 (java.time.Instant/parse "2026-05-20T11:00:00Z")
                                 (java.time.Instant/parse "2026-05-20T11:00:01Z"))]
    (try
      (cleanup/sweep-executions! storage now)
      (is (nil? (sp/read-entity storage :fn-execution old-id))
          "succeeded row > 7d gone")
      (is (some? (sp/read-entity storage :fn-execution fresh-id))
          "succeeded row < 7d kept")
      (finally nil))))


(deftest sweep-deletes-failed-past-30d-test
  (let [storage (create-full-storage)
        vid (ensure-fn-version! storage)
        now (java.time.Instant/parse "2026-05-21T12:00:00Z")
        old-id (make-exec-row! storage vid :failed
                               (java.time.Instant/parse "2026-04-10T11:00:00Z")
                               (java.time.Instant/parse "2026-04-10T11:00:01Z"))
        fresh-id (make-exec-row! storage vid :failed
                                 (java.time.Instant/parse "2026-05-10T11:00:00Z")
                                 (java.time.Instant/parse "2026-05-10T11:00:01Z"))]
    (try
      (cleanup/sweep-executions! storage now)
      (is (nil? (sp/read-entity storage :fn-execution old-id))
          "failed row > 30d gone")
      (is (some? (sp/read-entity storage :fn-execution fresh-id))
          "failed row < 30d kept (within failed's longer retention)")
      (finally nil))))


(deftest sweep-zombie-pending-flips-to-cancelled-test
  (let [storage (create-full-storage)
        vid (ensure-fn-version! storage)
        now (java.time.Instant/parse "2026-05-21T12:00:00Z")
        ;; Pending row 2h old (>1h zombie threshold) — flips, NOT deletes.
        zombie-row (sp/create-entity storage :fn-execution
                                     {:fn-version-id vid
                                      :status :pending
                                      :started-at
                                      (java.time.Instant/parse "2026-05-21T10:00:00Z")})
        fresh-row (sp/create-entity storage :fn-execution
                                    {:fn-version-id vid
                                     :status :pending
                                     :started-at
                                     (java.time.Instant/parse "2026-05-21T11:55:00Z")})]
    (try
      (testing "create writes :status pending (sanity-check)"
        (let [row (sp/read-entity storage :fn-execution (:id zombie-row))]
          (is (#{:pending "pending"} (:status row))
              (str "pre-sweep status = " (pr-str (:status row))))))
      (cleanup/sweep-executions! storage now)
      (testing "zombie pending → cancelled (NOT deleted)"
        (let [row (sp/read-entity storage :fn-execution (:id zombie-row))]
          (is (some? row) "row still exists")
          (is (#{:cancelled "cancelled"} (:status row)))
          (is (some? (:finished-at row)) "finished-at set on sweep")))
      (testing "recent pending untouched"
        (let [row (sp/read-entity storage :fn-execution (:id fresh-row))]
          (is (#{:pending "pending"} (:status row)))))
      (finally nil))))


;; ============================================================================
;; Final-B — failed-path: base-fn throws ex-info, row records :failed
;; with truncated :error + :error-data carrying :type.
;; ============================================================================

(defn- make-throwing-fn!
  "Build a base-fn that ALWAYS throws `ex-info` with a known :type
   and an oversized :context string so we exercise the error-data
   truncation path simultaneously. Returns the composed fn-record."
  [storage suffix big-context-bytes]
  (let [base-name (str "throws-" suffix)
        composed-name (str "my-throws-" suffix)
        big-str (str/join (repeat big-context-bytes \X))
        impl-fn (fn [_args _ctx]
                  (throw (ex-info "boom"
                                  {:type :test/boom-err
                                   :context big-str})))]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :keyword)]
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))})))


(deftest apply-failed-path-test
  (let [storage (create-full-storage)
        ;; Small context — within error-data cap; exercises the
        ;; non-truncated branch first.
        {composed :composed} (make-throwing-fn! storage "fail-small" 16)
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed) :args {}
                    :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "inline failure surfaces as :status :failed + ex-data carried"
        (is (= :failed (:status result)))
        (is (= "boom" (:error result)))
        (is (= :test/boom-err (-> result :error-data :type))))
      (testing "row in storage carries failed status + error fields"
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (#{:failed "failed"} (:status row)))
          (is (= "boom" (:error row)))
          (is (some? (:finished-at row)))
          (is (#{:test/boom-err "test/boom-err"}
               (-> row :error-data :type)))))
      (finally nil))))


(deftest apply-failed-path-truncates-error-data-test
  (let [storage (create-full-storage)
        ;; 70 KB context — exceeds 64 KB error-data cap. Should trigger
        ;; the truncation fallback: data shrinks to {:type :truncated true}.
        {composed :composed} (make-throwing-fn! storage "fail-big" (* 70 1024))
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed) :args {}
                    :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "row exists with :failed"
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (some? row))
          (is (#{:failed "failed"} (:status row)))
          (testing "error-data was truncated — keeps :type, drops :context"
            (let [ed (:error-data row)]
              (is (some? ed))
              (is (#{:test/boom-err "test/boom-err"} (:type ed)))
              (is (true? (:truncated ed))
                  "truncation flag set when oversize")
              (is (nil? (:context ed))
                  ":context discarded when over cap")))))
      (finally nil))))


;; The args-size-cap rejection (> 256 KB serialised → `:args-too-large`)
;; is covered in `graphden.integration.execute-http-test` alongside the
;; other Stage-2 guards — see the note above the apply-execute section.


;; ============================================================================
;; Final-D — result truncation: base-fn returns > 5 MB; row carries
;; :result nil + :result-truncated? true (NOT a row-write failure).
;; ============================================================================

(defn- make-big-result-fn!
  "Base-fn that returns a Clojure string sized to `byte-count`. When
   serialised the JSON adds the surrounding quotes (~2 bytes); test
   sizes pass 6 MB so we comfortably exceed the 5 MB cap."
  [storage suffix byte-count]
  (let [base-name (str "big-result-" suffix)
        composed-name (str "my-big-result-" suffix)
        big-str (str/join (repeat byte-count \Y))
        impl-fn (fn [_args _ctx] big-str)]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :text)]
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))})))


(deftest apply-truncates-oversize-result-test
  (let [storage (create-full-storage)
        ;; 6 MB string — over 5 MB cap.
        {composed :composed} (make-big-result-fn! storage "trunc" (* 6 1024 1024))
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed) :args {}
                    :timeout-ms 30000 :persist? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "inline response still reports :succeeded with full :result"
        ;; apply-execute returns the in-memory result regardless of
        ;; storage cap; truncation happens on the persistence side.
        (is (= :succeeded (:status result))))
      (testing "persisted row has :result nil + :result-truncated? true"
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (some? row))
          (is (#{:succeeded "succeeded"} (:status row)))
          (is (nil? (:result row))
              ":result dropped when over cap")
          (is (true? (:result-truncated? row))
              ":result-truncated? flag set on oversize")))
      (finally nil))))


;; ============================================================================
;; Final-Eff — *effect-trace* dyn-var captures runtime-observed effects
;; via `record-effect!` calls inside impls. The reaper writes the
;; captured set onto `:runtime-effects` alongside the terminal status.
;; ============================================================================

(defn- make-effectful-fn!
  "Base-fn that records two effect categories at runtime — `:env` and
   `:io`. We assert both surface on the row, ordering-independent."
  [storage suffix]
  (let [base-name (str "effectful-" suffix)
        composed-name (str "my-effectful-" suffix)
        impl-fn (fn [_args _ctx]
                  (cr/record-effect! :env)
                  (cr/record-effect! :io)
                  :ran)]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :keyword)]
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))})))


(deftest apply-captures-runtime-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-effectful-fn! storage "rt-eff")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed) :args {}
                    :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "inline response surfaces :runtime-effects alongside result"
        (is (= :succeeded (:status result)))
        (is (= #{"env" "io"}
               (set (:runtime-effects result)))))
      (testing "row persists :runtime-effects field"
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (some? row))
          (is (= #{"env" "io"}
                 (set (:runtime-effects row))))))
      (finally nil))))


;; ============================================================================
;; Debug P1 — execution-path capture through the whole submit path:
;; `trace?` on the body → run-future binds `*path-trace*` → the
;; compile-eager seam records the traced `:ref` invocation → persist
;; snapshots the atom onto the row's `:path-trace` jsonb → the status
;; read (get-execution ≡ GET /api/execute/:id) returns it.
;; ============================================================================

(defn- make-ref-chain-fn!
  "Wrapper whose single `:x` slot is REF-BOUND to a pure add composed
   fn — gives the execution a `:ref` invocation for the path-trace
   seam to observe (top-level closures don't pass `call-with-cache`;
   ref targets do). The add's `:a`/`:b` frees propagate up through
   the ref. Returns `{:wrapped … :target …}`."
  [storage suffix]
  (let [{composed :composed} (make-pure-add-fn! storage suffix)
        wrap-base-name (str "trace-wrap-" suffix)
        impl-fn (fn [args _ctx] (rt/resolve-arg args :x))]
    (exec/register-base-fn! (keyword wrap-base-name) impl-fn)
    (let [wrap-base (setup/create-base-fn! storage wrap-base-name :int)
          slot-x (setup/create-slot! storage "x" :int)]
      (setup/attach-slot! storage (:id wrap-base) (:id slot-x) 0)
      (let [wrapped (setup/create-composed-fn!
                      storage (str "my-trace-wrap-" suffix) (:id wrap-base))]
        (setup/bind-ref! storage (:id wrapped) (:id slot-x) (:id composed))
        {:wrapped wrapped :target composed}))))


(deftest apply-captures-path-trace-test
  (let [storage (create-full-storage)
        {:keys [wrapped target]} (make-ref-chain-fn! storage "pt-on")
        c (setup/default-registry-ctx storage)
        result (binding [ce/*traced-fn-ids* (atom #{(:id target)})]
                 (apply-and-await!
                   c {:fn-id (:id wrapped) :args {:a 1 :b 2}
                      :timeout-ms 5000 :persist? true :trace? true}))
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)
        entry-of (fn [payload]
                   (->> (:entries payload)
                        (filter #(= (str (:id target)) (:fn-id %)))
                        first))]
    (try
      (testing "inline response carries the callee sequence"
        (is (= :succeeded (:status result)))
        (is (= 3 (:result result)))
        (let [entry (entry-of (:path-trace result))]
          (is (some? entry) (pr-str (:path-trace result)))
          (is (false? (:cache-hit? entry)))
          (is (nat-int? (:duration-ms entry)))))
      (testing "row persists :path-trace jsonb (keywordized roundtrip)"
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (some? (entry-of (:path-trace row))))
          (is (not (:path-truncated? (:path-trace row))))))
      (testing "status read (GET /api/execute/:id) returns it additively"
        (is (some? (entry-of (:path-trace (fn-exec/get-execution c exec-id))))))
      (finally nil))))


(deftest apply-trace-flag-alone-captures-subtree-test
  ;; Debug P2 — the editor checkbox scenario: `trace?` on the body with
  ;; NO fn in the runtime traced set. run-future's execution-scoped
  ;; `trace-all` binding makes the run's own traversal the selected
  ;; subtree, so the ref frame records anyway.
  (let [storage (create-full-storage)
        {:keys [wrapped target]} (make-ref-chain-fn! storage "pt-all")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id wrapped) :args {:a 5 :b 6}
                    :timeout-ms 5000 :persist? true :trace? true})
        entry (->> (:entries (:path-trace result))
                   (filter #(= (str (:id target)) (:fn-id %)))
                   first)]
    (testing "trace? alone (empty traced set) still captures the path"
      (is (= :succeeded (:status result)))
      (is (= 11 (:result result)))
      (is (some? entry) (pr-str (:path-trace result)))
      (is (false? (:cache-hit? entry)))
      (is (nat-int? (:duration-ms entry))))))


(deftest apply-capture-values-persists-values-test
  ;; Debug P3 — `capture-values?` (the flag behind the editor's explicit
  ;; confirm) implies trace? and additionally lands each fresh frame's
  ;; RETURN VALUE in the persisted `:path-trace` entries.
  (let [storage (create-full-storage)
        {:keys [wrapped target]} (make-ref-chain-fn! storage "pt-vals")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id wrapped) :args {:a 20 :b 22}
                    :timeout-ms 5000 :persist? true :capture-values? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)
        entry-of (fn [payload]
                   (->> (:entries payload)
                        (filter #(= (str (:id target)) (:fn-id %)))
                        first))]
    (testing "capture-values? ALONE implies tracing — no trace? sent"
      (is (= :succeeded (:status result)))
      (is (= 42 (:result result)))
      (let [entry (entry-of (:path-trace result))]
        (is (some? entry) (pr-str (:path-trace result)))
        (is (= 42 (:value entry)) "the ref frame's return value is captured")
        (is (not (:value-truncated? entry)))))
    (testing "row persists the value inside :path-trace jsonb"
      (let [row-entry (entry-of (:path-trace (sp/read-entity storage :fn-execution exec-id)))]
        (is (= 42 (:value row-entry)))
        (testing "internal byte accounting never reaches the wire"
          (is (not-any? #(str/includes? (name %) "value-bytes")
                        (keys row-entry))))))))


(deftest apply-sampled-run-without-trace-flag-test
  ;; Debug P3 constraint 2 — ambient sampling: an fn in the SELECTIVE
  ;; traced set, submitted WITHOUT trace?, records when the session
  ;; sample rate wins (deterministic at rate 1.0 — the decision is a
  ;; single draw at run-future binding time, never per-node). The
  ;; SUBMITTED fn's membership drives the draw; the set then still
  ;; gates which frames record — so the user's selected subtree
  ;; (wrapped + target here) goes in whole.
  (let [storage (create-full-storage)
        {:keys [wrapped target]} (make-ref-chain-fn! storage "pt-smpl")
        c (setup/default-registry-ctx storage)
        result (binding [ce/*traced-fn-ids* (atom #{(:id wrapped) (:id target)})
                         ce/*trace-sample-rate* (atom 0.01)]
                 (ce/set-trace-sampling! 1.0 {:confirm-full true})
                 (apply-and-await!
                   c {:fn-id (:id wrapped) :args {:a 2 :b 3}
                      :timeout-ms 5000 :persist? true}))
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)
        entry (->> (:entries (:path-trace result))
                   (filter #(= (str (:id target)) (:fn-id %)))
                   first)]
    (testing "sampled run produces a :path-trace row without any trace? submit"
      (is (= :succeeded (:status result)))
      (is (= 5 (:result result)))
      (is (some? entry) (pr-str (:path-trace result)))
      (is (false? (:cache-hit? entry)))
      (testing "sampling is path-only — never values"
        (is (not (contains? entry :value))))
      (is (some? (:path-trace (sp/read-entity storage :fn-execution exec-id)))))))


(deftest apply-without-trace-flag-records-nothing-test
  (let [storage (create-full-storage)
        {:keys [wrapped target]} (make-ref-chain-fn! storage "pt-off")
        c (setup/default-registry-ctx storage)
        ;; fn IS in the traced set — but the submission didn't opt in,
        ;; so no atom is bound and nothing records. Sample rate pinned
        ;; to 0 — at the 0.01 default the P3 ambient draw would record
        ;; a path 1% of runs and flake this assertion.
        result (binding [ce/*traced-fn-ids* (atom #{(:id target)})
                         ce/*trace-sample-rate* (atom 0.0)]
                 (apply-and-await!
                   c {:fn-id (:id wrapped) :args {:a 3 :b 4}
                      :timeout-ms 5000 :persist? true}))
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "no trace? → no :path-trace anywhere"
        (is (= :succeeded (:status result)))
        (is (= 7 (:result result)))
        (is (nil? (:path-trace result)))
        (is (nil? (:path-trace (sp/read-entity storage :fn-execution exec-id)))))
      (finally nil))))


;; ============================================================================
;; Auto-persist matrix — declared-effects ≠ #{} forces persistence
;; even when client passes `:persist? false`. Without this rule
;; effectful runs disappear from the audit trail.
;; ============================================================================

(deftest apply-auto-persists-when-declared-effects-test
  (let [storage (create-full-storage)
        ;; A side-effectful base-fn — the impl matters less than the
        ;; rich-type declaration; we stash an `:effects` set under the
        ;; base-fn's name in the rich-types registry so
        ;; `declared-effects-of` returns non-empty.
        {composed :composed composed-name :composed-name}
        (let [add-name "test-effectful-fn"
              composed-name "my-test-effectful-fn"
              impl-fn (fn [_args _ctx] :did-effect)]
          (exec/register-base-fn! (keyword add-name) impl-fn)
          ;; `apply-execute` reads the COMPOSED fn's name (it's the
          ;; `:fn-id` the request points at) and looks IT up in
          ;; rich-types — composed fn-defs normally inherit effects
          ;; from their parent base via the type-checker. Mirror that
          ;; by registering directly under the composed name.
          (registry/record-rich-types-raw!
            (keyword composed-name)
            {:args {} :return :keyword :effects #{:env}})
          (let [base (setup/create-base-fn! storage add-name :keyword)]
            {:composed (setup/create-composed-fn! storage composed-name (:id base))
             :composed-name composed-name}))
        c (setup/default-registry-ctx storage)
        ;; Note: persist?=false — relying on declared-effects to force persistence.
        result (apply-and-await!
                 c {:fn-id (:id composed) :args {}
                    :timeout-ms 5000 :persist? false})]
    (try
      (testing "row persists despite persist?=false (declared-effects ≠ #{})"
        (is (= :succeeded (:status result)))
        (is (some? (:execution-id result))
            ":execution-id surfaces in response when row was created"))
      (testing "declared-effects snapshot stored on the row"
        (let [exec-id (java.util.UUID/fromString (:execution-id result))
              row (sp/read-entity storage :fn-execution exec-id)]
          (is (some? row))
          (is (= ["env"] (:declared-effects row))
              "rich-type's :effects roundtripped onto the row")))
      ;; (control "pure + ¬persist? → no row" lives in
      ;;  apply-inline-for-fast-pure-fn-test — kept separate so the
      ;;  registry's compiled cache doesn't need invalidation between
      ;;  the two fn-creates within one ctx)
      (finally
        ;; Clean up the rich-type entry so it doesn't bleed into
        ;; other tests via the JVM-wide registry atom. Goes through
        ;; record-rich-types-raw! with a pure marker — the registry
        ;; has no public dissoc, and overwriting with effects #{} is
        ;; the closest no-op restoration.
        (registry/record-rich-types-raw!
          (keyword composed-name)
          {:args {} :return :keyword :effects #{}})))))


;; ============================================================================
;; List-typed args — persist-args! `(sequential? v)` branch spawns
;; one :fn-execution-arg + N :fn-execution-arg-item rows ordered by
;; :position. The GET endpoint stitches them into :args[*].items.
;; ============================================================================

(deftest apply-persists-list-args-as-item-rows-test
  (let [storage (create-full-storage)
        ;; Use the pure-add fn shape but pass a LIST value to :a so
        ;; persist-args! takes the sequential? branch. The impl ignores
        ;; the args (we don't care about the runtime result — only the
        ;; row structure persisted).
        {composed :composed} (make-pure-add-fn! storage "list-args")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed)
                    :args {:a [10 20 30]      ; sequential — spawns item rows
                           :b 7}              ; scalar — single arg row
                    :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "two arg rows total — one per supplied free-arg"
        (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                          {:execution-id exec-id})]
          (is (= 2 (count arg-rows)))))
      (testing "list-typed arg has :value nil + spawns three item rows"
        (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                          {:execution-id exec-id})
              list-row (->> arg-rows (filter #(nil? (:value %))) first)]
          (is (some? list-row)
              "the list-arg row has :value nil (per the schema XOR)")
          (let [items (->> (sp/query-entities storage :fn-execution-arg-item
                                              {:execution-arg-id (:id list-row)})
                           (sort-by :position))]
            (is (= 3 (count items)))
            (is (= [0 1 2] (mapv :position items))
                ":position monotonic 0..N-1")
            (is (= [10 20 30] (mapv :value items))
                "item :values preserve input order"))))
      (testing "GET endpoint stitches items into :args[*].items"
        (let [row (fn-exec/get-execution c exec-id)
              args (:args row)
              list-arg (->> args (filter #(nil? (:value %))) first)]
          (is (some? list-arg))
          (is (= 3 (count (:items list-arg))))
          (is (= [10 20 30] (mapv :value (:items list-arg)))
              "items array on the response carries positional order")))
      (finally nil))))


(deftest apply-pure-fn-has-no-runtime-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "pure-eff")
        c (setup/default-registry-ctx storage)
        result (apply-and-await!
                 c {:fn-id (:id composed) :args {:a 1 :b 2}
                    :timeout-ms 5000 :persist? true})
        exec-id (some-> (:execution-id result) java.util.UUID/fromString)]
    (try
      (testing "pure fn → no :runtime-effects in inline response"
        (is (= :succeeded (:status result)))
        (is (nil? (:runtime-effects result))))
      (testing "row has :runtime-effects nil (no record-effect! calls)"
        (let [row (sp/read-entity storage :fn-execution exec-id)]
          (is (nil? (:runtime-effects row)))))
      (finally nil))))


;; `log-effect-drift!` is unit-tested in
;; `graphden.crud.fn-execution.persist-test` via a THREAD-LOCAL
;; `log/*logger-factory*` capture — this file used to hold a
;; `with-redefs clojure.tools.logging/log*` version whose global root
;; rebind was a documented parallel-suite hazard.


(deftest record-effect-noop-outside-trace-test
  (testing "record-effect! is a no-op when *effect-trace* unbound"
    (is (nil? (cr/record-effect! :env))))
  (testing "with trace bound, conj into the atom"
    (let [trace (atom #{})]
      (binding [cr/*effect-trace* trace]
        (cr/record-effect! :env)
        (cr/record-effect! :time)
        (cr/record-effect! :env))  ; dedup via set
      (is (= #{:env :time} @trace)))))


;; ============================================================================
;; Final-E — HTTP-level smoke: validates the wire shape the editor's
;; History panel consumes. Wraps `list-executions-for-fn` in the same
;; `{:ok true :executions [...]}` envelope the route handler returns,
;; then JSON-roundtrips to verify rows survive cheshire serialisation.
;; (Full transport-layer coverage lives in tools/browser-test/edit-execute.test.js.)
;; ============================================================================

(deftest list-executions-http-envelope-shape-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "http-shape")
        c (setup/default-registry-ctx storage)]
    (try
      (doseq [b [10 20]]
        (apply-and-await! c {:fn-id (:id composed)
                             :args {:a 1 :b b}
                             :timeout-ms 5000 :persist? true}))
      (let [rows (fn-exec/list-executions-for-fn c (:id composed))
            envelope {:ok true :executions rows}
            ;; Roundtrip through cheshire so any unserializable values
            ;; (e.g. raw Instants without a custom encoder) blow up here
            ;; instead of in production over the wire.
            wire (-> envelope (json/generate-string) (json/parse-string true))]
        (testing "envelope shape matches what the editor's fetch() expects"
          (is (true? (:ok wire)))
          (is (vector? (:executions wire)))
          (is (= 2 (count (:executions wire)))))
        (testing "each row carries status + result + started-at fields"
          (doseq [row (:executions wire)]
            (is (string? (:status row))
                "status is wire-string (jsonb roundtrip strips keyword)")
            (is (some? (:result row)))
            (is (some? (:started-at row)))
            (is (some? (:fn-version-id row))))))
      (finally nil))))


(deftest list-executions-http-envelope-empty-fn-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "http-empty")
        c (setup/default-registry-ctx storage)
        rows (fn-exec/list-executions-for-fn c (:id composed))
        envelope {:ok true :executions rows}
        wire (-> envelope (json/generate-string) (json/parse-string true))]
    (try
      (testing "fn with no runs → envelope :executions is empty vec, NOT nil"
        (is (true? (:ok wire)))
        (is (vector? (:executions wire)))
        (is (zero? (count (:executions wire)))))
      (finally nil))))


(deftest list-executions-filters-other-fns-test
  (let [storage (create-full-storage)
        {a-composed :composed} (make-pure-add-fn! storage "list-iso-a")
        {b-composed :composed} (make-pure-add-fn! storage "list-iso-b")
        c (setup/default-registry-ctx storage)]
    (try
      ;; Two runs of A, three of B — list for A should return only 2.
      (dotimes [_ 2]
        (apply-and-await! c {:fn-id (:id a-composed)
                             :args {:a 1 :b 2} :timeout-ms 5000
                             :persist? true}))
      (dotimes [_ 3]
        (apply-and-await! c {:fn-id (:id b-composed)
                             :args {:a 1 :b 2} :timeout-ms 5000
                             :persist? true}))
      (is (= 2 (count (fn-exec/list-executions-for-fn c (:id a-composed)))))
      (is (= 3 (count (fn-exec/list-executions-for-fn c (:id b-composed)))))
      (finally nil))))


;; ============================================================================
;; persist-args! — ref-arg branches
;;
;; The end-to-end tests above only cover scalar + scalar-list args. The
;; `(ref-arg? v)` branch (a `{:ref "uuid"}` map at the top level) and the
;; `(ref-arg? item)` branch (a `{:ref ...}` map INSIDE a list arg) had
;; no exercise — these tests call `persist-args!` directly with the same
;; row layout `apply-execute` would assemble, then verify the persisted
;; rows carry `:ref-fn-version-id` (not `:value`).
;; ============================================================================

(deftest persist-args-single-ref-scalar-test
  (let [storage (create-full-storage)
        {composed :composed slot-a :slot-a slot-b :slot-b}
        (make-pure-add-fn! storage "ref-scalar")
        {target :composed} (make-pure-add-fn! storage "ref-scalar-target")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)
        free-slots {:a (:id slot-a) :b (:id slot-b)}]
    (try
      (persist/persist-args! storage (:id exec-row)
                             {:a {:ref (str (:id target))} :b 42}
                             free-slots)
      (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                        {:execution-id (:id exec-row)})
            by-slot (into {} (map (juxt :slot-id identity)) arg-rows)]
        (testing "two rows persisted — one per supplied arg"
          (is (= 2 (count arg-rows))))
        (testing ":a (ref) → :value nil + :ref-fn-version-id resolved"
          (let [a-row (get by-slot (:id slot-a))]
            (is (nil? (:value a-row)))
            (is (some? (:ref-fn-version-id a-row))
                "ref-fn-version-id must resolve through versioned-storage")))
        (testing ":b (scalar) → :value 42 + :ref-fn-version-id nil"
          (let [b-row (get by-slot (:id slot-b))]
            (is (= 42 (:value b-row)))
            (is (nil? (:ref-fn-version-id b-row))))))
      (finally nil))))


(deftest persist-args-ref-with-unresolvable-fn-id-test
  (let [storage (create-full-storage)
        {composed :composed slot-a :slot-a slot-b :slot-b}
        (make-pure-add-fn! storage "ref-unresolved")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)
        free-slots {:a (:id slot-a) :b (:id slot-b)}
        bogus-ref-id (random-uuid)]
    (try
      (persist/persist-args! storage (:id exec-row)
                             {:a {:ref (str bogus-ref-id)} :b 1}
                             free-slots)
      (testing "ref to a non-existent fn-id → :ref-fn-version-id nil (silent)"
        (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                          {:execution-id (:id exec-row)})
              a-row (some #(when (= (:slot-id %) (:id slot-a)) %) arg-rows)]
          (is (nil? (:value a-row)))
          (is (nil? (:ref-fn-version-id a-row))
              "no version row → version-id resolves to nil, row still written")))
      (finally nil))))


(deftest persist-args-list-with-ref-items-test
  (let [storage (create-full-storage)
        {composed :composed slot-a :slot-a slot-b :slot-b}
        (make-pure-add-fn! storage "ref-list")
        {target-a :composed} (make-pure-add-fn! storage "ref-list-target-a")
        {target-b :composed} (make-pure-add-fn! storage "ref-list-target-b")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)
        free-slots {:a (:id slot-a) :b (:id slot-b)}]
    (try
      ;; Mixed list: scalar / ref / scalar — exercises both
      ;; cond-branches in the inner item doseq.
      (persist/persist-args!
        storage (:id exec-row)
        {:a [10
             {:ref (str (:id target-a))}
             20
             {:ref (str (:id target-b))}]
         :b 99}
        free-slots)
      (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                        {:execution-id (:id exec-row)})
            by-slot (into {} (map (juxt :slot-id identity)) arg-rows)
            a-row (get by-slot (:id slot-a))
            items (->> (sp/query-entities storage :fn-execution-arg-item
                                          {:execution-arg-id (:id a-row)})
                       (sort-by :position)
                       vec)]
        (testing "list arg-row itself has :value nil + ref-fn-version-id nil"
          (is (nil? (:value a-row)))
          (is (nil? (:ref-fn-version-id a-row))))
        (testing "four item rows, monotonic positions 0..3"
          (is (= 4 (count items)))
          (is (= [0 1 2 3] (mapv :position items))))
        (testing "scalar items keep :value, ref items keep :ref-fn-version-id"
          (let [[i0 i1 i2 i3] items]
            (is (= 10 (:value i0))) (is (nil? (:ref-fn-version-id i0)))
            (is (nil? (:value i1))) (is (some? (:ref-fn-version-id i1)))
            (is (= 20 (:value i2))) (is (nil? (:ref-fn-version-id i2)))
            (is (nil? (:value i3))) (is (some? (:ref-fn-version-id i3)))
            (is (not= (:ref-fn-version-id i1) (:ref-fn-version-id i3))
                "two different targets resolve to two different version-ids"))))
      (finally nil))))


(deftest persist-args-skips-unknown-slot-names-test
  (let [storage (create-full-storage)
        {composed :composed slot-a :slot-a slot-b :slot-b}
        (make-pure-add-fn! storage "ref-skip")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)
        free-slots {:a (:id slot-a) :b (:id slot-b)}]
    (try
      (persist/persist-args! storage (:id exec-row)
                             {:a 1 :b 2 :unknown 999}
                             free-slots)
      (testing "args whose name isn't in free-slots are skipped silently"
        (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                          {:execution-id (:id exec-row)})]
          (is (= 2 (count arg-rows))
              "only :a and :b rows written; :unknown filtered by :when slot-id")))
      (finally nil))))


;; ============================================================================
;; write-finished! — :cancelled status + runtime-effects merge
;;
;; The `:cancelled` case-branch and the `(:runtime-effects outcome)`
;; cond-> merge in `write-finished!` had no direct test (only the live
;; cancel test that goes through future-cancel covers the :cancelled
;; branch indirectly). These unit-tests pin down the row update shape.
;; ============================================================================

(deftest write-finished-cancelled-without-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "wf-cancel")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)]
    (try
      (persist/write-finished! storage (:id exec-row) {:status :cancelled})
      (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
        (testing ":status flipped to :cancelled"
          (is (= :cancelled (:status row))))
        (testing ":finished-at written"
          (is (some? (:finished-at row))))
        (testing ":result / :error / :runtime-effects all nil"
          (is (nil? (:result row)))
          (is (nil? (:error row)))
          (is (nil? (:runtime-effects row)))))
      (finally nil))))


(deftest write-finished-cancelled-with-runtime-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "wf-cancel-eff")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)]
    (try
      (persist/write-finished! storage (:id exec-row)
                               {:status :cancelled
                                :runtime-effects ["db" "time"]})
      (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
        (testing ":runtime-effects merged into row alongside :cancelled"
          (is (= :cancelled (:status row)))
          (is (= ["db" "time"] (:runtime-effects row)))))
      (finally nil))))


;; ============================================================================
;; resolve-fn-version-id — branch chain walk
;; ============================================================================
;;
;; A fn created on the parent branch (no version row on the child)
;; must still resolve to the parent's :fn-version-id when the child
;; ctx asks for it. Pre-fix the helper did a direct :branch-id
;; filter, so inherited fns returned nil — every fn-execution write
;; on a non-creator branch then had a nil version-id, breaking the
;; current-branch history filter and the per-version executions UI.

(deftest resolve-fn-version-id-walks-branch-chain-test
  (let [storage (create-full-storage)
        ;; create-full-storage wraps the PG storage in a versioned-
        ;; storage pointing at "test-branch"; treat that as the parent
        ;; for this test, then fork off a child.
        {composed :composed} (make-pure-add-fn! storage "chain-walk")
        parent-version-id (lookup/resolve-fn-version-id
                            (setup/default-registry-ctx storage) (:id composed))
        child-branch (vs/create-branch! storage "chain-walk-child")
        child-storage (vs/switch-branch storage (:id child-branch))]
    (try
      (testing "own-branch version resolves directly"
        (is (some? parent-version-id)))

      (testing "inherited-from-parent version resolves through chain walk"
        (let [child-ctx (setup/default-registry-ctx child-storage)
              resolved (lookup/resolve-fn-version-id child-ctx (:id composed))]
          (is (some? resolved)
              "child must NOT return nil for a fn it inherits from parent")
          (is (= parent-version-id resolved)
              "the same fn-version-id surfaces on both branches because the
               child has no override")))

      (testing "child override shadows the inherited version on child only"
        (sp/update-entity child-storage :fn (:id composed)
                          {:description "edited on child"})
        (let [child-resolved (lookup/resolve-fn-version-id
                               (setup/default-registry-ctx child-storage) (:id composed))
              parent-resolved (lookup/resolve-fn-version-id
                                (setup/default-registry-ctx storage) (:id composed))]
          (is (not= parent-version-id child-resolved)
              "child sees its newly-written version")
          (is (= parent-version-id parent-resolved)
              "parent's view is untouched")))
      (finally nil))))


(deftest write-finished-succeeded-with-runtime-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "wf-succ-eff")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)]
    (try
      (persist/write-finished! storage (:id exec-row)
                               {:status :succeeded
                                :result 42
                                :runtime-effects ["env"]})
      (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
        (is (= :succeeded (:status row)))
        (is (= 42 (:result row)))
        (is (false? (:result-truncated? row)))
        (is (= ["env"] (:runtime-effects row))))
      (finally nil))))


;; ============================================================================
;; write-finished! — :failed branch (covers the `case :failed` arm of
;; the body construction + the `cond-> body` runtime-effects merge for
;; the failed path).
;; ============================================================================

(deftest write-finished-failed-without-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "wf-fail")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)]
    (try
      (persist/write-finished! storage (:id exec-row)
                               {:status :failed
                                :error "boom"
                                :error-data {:type :exec/oops :extra "details"}})
      (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
        (is (= :failed (:status row)))
        (is (= "boom" (:error row)))
        (is (= {:type :exec/oops :extra "details"} (:error-data row))
            "small error-data passes through verbatim (jsonb keywordize roundtrip)")
        (is (nil? (:result row))))
      (finally nil))))


(deftest write-finished-failed-with-runtime-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "wf-fail-eff")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)]
    (try
      (persist/write-finished! storage (:id exec-row)
                               {:status :failed
                                :error "io error"
                                :error-data {:type :exec/io-fail}
                                :runtime-effects ["io" "db"]})
      (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
        (is (= :failed (:status row)))
        (is (= ["io" "db"] (:runtime-effects row))
            "runtime-effects merge fires for :failed too, not just :cancelled"))
      (finally nil))))


(deftest write-finished-succeeded-without-runtime-effects-test
  ;; Covers the `:succeeded` arm of `case` when runtime-effects is
  ;; absent — the `cond->` doesn't fire. Pre-fix only the with-effects
  ;; variant was tested for :succeeded.
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "wf-succ-no-eff")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)]
    (try
      (persist/write-finished! storage (:id exec-row)
                               {:status :succeeded :result {:hello "world"}})
      (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
        (is (= :succeeded (:status row)))
        (is (= {:hello "world"} (:result row)))
        (is (false? (:result-truncated? row)))
        (is (nil? (:runtime-effects row))))
      (finally nil))))


;; ============================================================================
;; create-pending-with-args! — atomic helper (pending row + arg rows in
;; one call). Used by lazy-persist + pre-persist paths in fn-execution.
;; ============================================================================

(deftest create-pending-with-args-creates-row-and-args-test
  (let [storage (create-full-storage)
        {composed :composed slot-a :slot-a slot-b :slot-b}
        (make-pure-add-fn! storage "cpwa")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        free-slots {:a (:id slot-a) :b (:id slot-b)}
        row (persist/create-pending-with-args! storage version-id
                                               ["db"] (random-uuid)
                                               {:a 1 :b 2}
                                               free-slots)]
    (testing "parent row carries :status :pending"
      (is (= :pending (:status row)))
      (is (= ["db"] (:declared-effects row))))
    (testing "per-arg rows written"
      (let [args (sp/query-entities storage :fn-execution-arg
                                    {:execution-id (:id row)})]
        (is (= 2 (count args)))
        (is (= #{1 2} (set (map :value args))))))))


;; ============================================================================
;; record-completion! — reaper paths
;;
;; The integration apply-* tests above exercise these indirectly, but
;; the failed-future branch with a plain (non-ex-info) cause and the
;; reaper's outermost `catch Exception` (write-finished itself throws)
;; aren't covered. These tests drive record-completion! directly with
;; pre-resolved/pre-failed futures so the catch arms run synchronously.
;; ============================================================================

(deftest record-completion-failed-future-writes-failed-row-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "rc-fail")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)
        ;; A future that throws on @deref — ExecutionException with the
        ;; original RuntimeException as cause.
        fut (future (throw (RuntimeException. "boom-fail")))
        reaper (persist/record-completion! storage (:id exec-row)
                                           nil fut (atom #{}) nil)]
    ;; reaper itself is a future; wait for it to finish.
    @reaper
    (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
      (is (= :failed (:status row)))
      (is (re-find #"boom-fail" (str (:error row))))
      (is (some? (:finished-at row))))))


(deftest record-completion-cancelled-future-writes-cancelled-row-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "rc-cancel")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)
        ;; Wrap an InterruptedException in an ExecutionException — that
        ;; matches the `(instance? InterruptedException cause)` branch.
        fut (future (throw (InterruptedException. "cancelled")))
        reaper (persist/record-completion! storage (:id exec-row)
                                           nil fut (atom #{}) nil)]
    @reaper
    (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
      (is (= :cancelled (:status row)))
      (is (nil? (:error row)) ":cancelled does not stash an error message"))))


(deftest record-completion-success-with-runtime-effects-test
  (let [storage (create-full-storage)
        {composed :composed} (make-pure-add-fn! storage "rc-ok")
        c (setup/default-registry-ctx storage)
        version-id (lookup/resolve-fn-version-id c (:id composed))
        exec-row (persist/create-pending-row! storage version-id nil nil)
        trace (atom #{:io})
        fut (future :result-value)
        reaper (persist/record-completion! storage (:id exec-row)
                                           nil fut trace ["io"])]
    @reaper
    (let [row (sp/read-entity storage :fn-execution (:id exec-row))]
      (is (= :succeeded (:status row)))
      (is (= :result-value (:result row))
          "future's keyword result roundtrips through jsonb")
      (is (= ["io"] (:runtime-effects row))))))


;; The pure size-cap helpers (jsonize-result / jsonize-error-data /
;; truncate-error / ref-arg?) are unit-tested in
;; `graphden.crud.fn-execution.persist-test` — their dedicated ns. This
;; file keeps only the storage-backed persist coverage.


;; ============================================================================
;; test-runs — the `tests` namespace convention (discovery / runner /
;; status). Lives here to reuse this ns's shared storage fixture — the
;; runner is an execute-pipeline consumer like exec-errors/exec-stats.
;; ============================================================================

(deftest test-ns-path?-segment-matching-test
  (testing "the `tests` segment matches at any depth"
    (is (test-runs/test-ns-path? "tests"))
    (is (test-runs/test-ns-path? "tests.parser"))
    (is (test-runs/test-ns-path? "myproj.tests"))
    (is (test-runs/test-ns-path? "myproj.tests.api")))
  (testing "substrings and lookalikes do NOT match"
    (is (not (test-runs/test-ns-path? "testsuite")))
    (is (not (test-runs/test-ns-path? "myproj.testsuite.api")))
    (is (not (test-runs/test-ns-path? "test")))
    (is (not (test-runs/test-ns-path? "")))
    (is (not (test-runs/test-ns-path? nil)))))


(defn- make-noarg-fn!
  "Base-fn with no slots + a composed instance — a zero-free-arg fn the
   test runner can execute. `impl` is the base's `(fn [args ctx])`.
   Returns the composed fn row."
  [storage suffix impl]
  (let [base-name (str "test-runs-base-" suffix)]
    (exec/register-base-fn! (keyword base-name) impl)
    (let [base (setup/create-base-fn! storage base-name :any)]
      (setup/create-composed-fn! storage (str "t-" suffix) (:id base)))))


(defn- await-terminal-statuses
  "Poll `tests-with-statuses` until every fn in `names` reports a
   TERMINAL status (or 10 s passes) — inline successes may still have
   their row finalised by the async `record-completion!` writer."
  [ctx names]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [by-name (into {} (map (juxt :fn-name identity))
                          (test-runs/tests-with-statuses ctx))
            terminal? (fn [n]
                        (contains? #{"succeeded" "failed" "cancelled"}
                                   (str (:status (get by-name n)))))]
        (if (or (every? terminal? names)
                (>= (System/currentTimeMillis) deadline))
          by-name
          (do (Thread/sleep 50) (recur)))))))


(deftest test-runs-discovery-run-status-test
  (let [storage (create-full-storage)
        ns-tests (sp/create-entity storage :ns {:name "tests"})
        ns-proj (sp/create-entity storage :ns {:name "myproj"})
        ns-proj-tests (sp/create-entity storage :ns {:name "tests"
                                                     :parent-id (:id ns-proj)})
        ns-suite (sp/create-entity storage :ns {:name "testsuite"})
        passing (make-noarg-fn! storage "pass" (fn [_ _] 42))
        failing (make-noarg-fn! storage "fail"
                                (fn [_ _]
                                  (throw (ex-info "assert-eq failed: actual ≠ expected"
                                                  {:type :execution-error/assertion-failed
                                                   :actual 1 :expected 2}))))
        outside (make-noarg-fn! storage "outside" (fn [_ _] 1))
        helper (make-noarg-fn! storage "helper" (fn [_ _] 1))
        _ (sp/update-entity storage :fn (:id helper) {:name "_t-helper"})
        {unrunnable :composed} (make-pure-add-fn! storage "unrun")
        ;; ctx AFTER the register-base-fn! calls — default-registry-ctx
        ;; snapshots the registry. :pg-storage carries the raw pool for
        ;; latest-statuses, same handle apply-execute's stats-ctx uses.
        c (-> (setup/default-registry-ctx storage)
              (assoc :pg-storage @shared-storage))]
    (sp/update-entity storage :fn (:id passing) {:namespace-id (:id ns-tests)})
    (sp/update-entity storage :fn (:id failing) {:namespace-id (:id ns-proj-tests)})
    (sp/update-entity storage :fn (:id outside) {:namespace-id (:id ns-suite)})
    (sp/update-entity storage :fn (:id helper) {:namespace-id (:id ns-tests)})
    (sp/update-entity storage :fn (:id unrunnable) {:namespace-id (:id ns-tests)})

    (testing "discovery — segment-matched namespaces only, named fns only"
      (is (= #{(:id ns-tests) (:id ns-proj-tests)}
             (test-runs/test-namespace-ids storage))
          "testsuite (substring lookalike) and myproj (no tests segment) excluded")
      (is (= #{(:id passing) (:id failing) (:id unrunnable)}
             (into #{} (map :id) (test-runs/test-fn-rows c)))
          "the `_`-private helper inside the tests ns is scaffolding, not a test"))

    (testing "status before any run — every test present, status nil"
      (let [sts (test-runs/tests-with-statuses c)]
        (is (= 3 (count sts)))
        (is (every? (comp nil? :status) sts))))

    (testing "run — pass / fail / not-runnable buckets"
      (let [out (test-runs/run-tests! c {})]
        (is (= 3 (:total out)))
        (is (= 1 (:passed out)))
        (is (= 1 (:failed out)))
        (is (= 1 (:other out)))
        (let [by-name (into {} (map (juxt :fn-name identity)) (:results out))]
          (is (= :succeeded (:status (get by-name "t-pass"))))
          (is (= :failed (:status (get by-name "t-fail"))))
          (is (str/includes? (str (:error (get by-name "t-fail"))) "assert-eq")
              "the assertion message surfaces on the result")
          (is (= :not-runnable (:status (get by-name "my-test-add-unrun"))))
          (is (str/includes? (str (:error (get by-name "my-test-add-unrun"))) "a, b")
              "unbound args are named"))))

    (testing "run — :fn-ids subset (uuid strings, the wire shape)"
      (let [out (test-runs/run-tests! c {:fn-ids [(str (:id passing))]})]
        (is (= 1 (:total out)))
        (is (= 1 (:passed out)))))

    (testing "status after the run — latest execution per current version"
      (let [by-name (await-terminal-statuses c ["t-pass" "t-fail"])]
        (is (= "succeeded" (str (:status (get by-name "t-pass")))))
        (is (= "failed" (str (:status (get by-name "t-fail")))))
        (is (some? (:execution-id (get by-name "t-fail"))))
        (is (str/includes? (str (:error (get by-name "t-fail"))) "assert-eq"))
        (is (nil? (:status (get by-name "my-test-add-unrun")))
            "a never-run test stays status-less")))

    (testing "editing a test rolls its version — status honestly resets to nil"
      (sp/update-entity storage :fn (:id passing) {:description "edited"})
      (let [by-name (into {} (map (juxt :fn-name identity))
                          (test-runs/tests-with-statuses c))]
        (is (nil? (:status (get by-name "t-pass")))
            "stale-by-construction: the new version has no recorded run")
        (is (= "failed" (str (:status (get by-name "t-fail"))))
            "untouched tests keep their status")))))
