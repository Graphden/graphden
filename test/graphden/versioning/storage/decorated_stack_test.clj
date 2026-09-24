(ns ^:integration graphden.versioning.storage.decorated-stack-test
  "VersionedStorage over a DECORATED backend — the cloud stack
   `Versioned(OrgScoped(Postgres))`, with a test decorator of the tenancy
   decorator's shape (`[base scoped? authorize-write]`, no `:pool` of its
   own, every write through a guard). The write paths used to look for the
   pool on the storage they hold, find none there, and run every write
   without a transaction or a lock.

   Also the concurrency contracts of the write paths — the lost update,
   fork-vs-delete, merge-vs-create — pinned by holding the advisory lock a
   path must take from another connection and watching the path WAIT on
   it: a path that never takes the lock does not wait, and the test sees
   it."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as mrg]
    [graphden.versioning.storage.resolution :as res]
    [graphden.versioning.storage.uniqueness :as uniq]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


;; ============================================================================
;; A decorator of the tenancy decorator's shape
;; ============================================================================

(defrecord GuardedStorage
  [base reads authorize-write]

  sp/Storage

  (initialize [_ schema] (sp/initialize base schema))


  (close [_] (sp/close base))


  sp/StorageIntrospection

  (current-entities [_] (sp/current-entities base))


  (current-fields [_ entity-name] (sp/current-fields base entity-name))


  (current-enums [_] (sp/current-enums base))


  (current-enum-values [_ enum-name] (sp/current-enum-values base enum-name))


  (schema-metadata [_] (sp/schema-metadata base))


  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (authorize-write :create entity-name)
    (sp/create-entity base entity-name data))


  (read-entity
    [_ entity-name id]
    (swap! reads update entity-name (fnil inc 0))
    (sp/read-entity base entity-name id))


  (update-entity
    [_ entity-name id data]
    (authorize-write :update entity-name)
    (sp/update-entity base entity-name id data))


  (delete-entity
    [_ entity-name id]
    (authorize-write :delete entity-name)
    (sp/delete-entity base entity-name id))


  (query-entities [_ entity-name where] (sp/query-entities base entity-name where))


  (query-entities [_ entity-name where opts] (sp/query-entities base entity-name where opts))


  (query-latest-per-group
    [_ entity-name where group-cols]
    (sp/query-latest-per-group base entity-name where group-cols))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (authorize-write :create entity-name)
    (sp/create-entities base entity-name data-seq))


  (read-entities [_ entity-name ids] (sp/read-entities base entity-name ids))


  (update-entities
    [_ entity-name data-seq]
    (authorize-write :update entity-name)
    (sp/update-entities base entity-name data-seq))


  (upsert-entities
    [_ entity-name data-seq]
    (authorize-write :create entity-name)
    (sp/upsert-entities base entity-name data-seq))


  (delete-entities
    [_ entity-name ids]
    (authorize-write :delete entity-name)
    (sp/delete-entities base entity-name ids))


  (query-ref-many-owners
    [_ entity-name field-name target-id]
    (sp/query-ref-many-owners base entity-name field-name target-id))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [_ owner-fn-id ref-fn-id]
    (sp/validate-no-dependency-cycle! base owner-fn-id ref-fn-id))


  sp/ConstraintHelpers

  (collect-dependency-chain [_ fn-id] (sp/collect-dependency-chain base fn-id))


  sp/StorageValueCodec

  (encode-value [_ value field-spec] (sp/encode-value base value field-spec))


  (decode-value [_ value field-spec] (sp/decode-value base value field-spec))


  (encode-row [_ row field-specs] (sp/encode-row base row field-specs))


  (decode-row [_ row field-specs] (sp/decode-row base row field-specs))


  sp/StorageErrorClassifier

  (classify-error [_ exception] (sp/classify-error base exception))


  (wrap-error [_ exception operation context] (sp/wrap-error base exception operation context))


  sp/ExecutionGraph

  (resolve-execution-graph [_ fn-id] (sp/resolve-execution-graph base fn-id)))


(defn- pg-storage
  []
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vds/extend-builder)
                   (vts/extend-builder)
                   (ds/build))]
    ;; Room for a held lock, two blocked writers and the probe that
    ;; watches them wait.
    (-> (pg/create-storage (assoc (th/get-container-config *container*) :pool-size 6))
        (sp/initialize-with-cleanup! schema))))


(defn- guarded
  "`pg` under the test decorator. A write of an [op entity] pair in the
   decorator's `:refused` atom throws — the stand-in for the tenancy write
   guard / quota."
  [pg]
  (let [refused (atom #{})]
    (assoc (->GuardedStorage pg (atom {})
                             (fn [op entity-name]
                               (when (contains? @refused [op entity-name])
                                 (throw (ex-info "refused by the decorator"
                                                 {:type :test/refused
                                                  :op op :entity entity-name})))))
           :refused refused)))


(defn- with-stack
  "Call `(f pg deco v)` — Postgres, the decorator over it, versioned over
   the decorator; closes the backend."
  [f]
  (let [pg (pg-storage)
        deco (guarded pg)]
    (try (f pg deco (vs/wrap-with-versioning deco))
         (finally (sp/close pg)))))


;; ============================================================================
;; Advisory-lock probes
;; ============================================================================

(defn- hold-lock!
  "Take the session-level advisory lock `k` (the key space
   `uniq/xact-lock!` uses) on a dedicated connection; returns it for
   `release!`."
  ^Connection [pool k]
  (let [conn (jdbc/get-connection pool)]
    (jdbc/execute! conn ["SELECT pg_advisory_lock(hashtext(?)::bigint)" (uniq/advisory-key k)])
    conn))


(defn- release!
  "Unlock (a pooled connection goes back to the pool still holding its
   session locks) and hand the connection back."
  [^Connection conn]
  (jdbc/execute! conn ["SELECT pg_advisory_unlock_all()"])
  (Connection/.close conn))


(defn- lock-waiters
  [pool]
  (-> (jdbc/execute-one! pool ["SELECT count(*) AS n FROM pg_locks l
                                JOIN pg_database d ON d.oid = l.database
                               WHERE l.locktype = 'advisory' AND NOT l.granted
                                 AND d.datname = current_database()"])
      vals first long))


(defn- await-waiters
  "Poll until at least `n` backends wait on an advisory lock; the count seen."
  [pool n]
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (let [seen (lock-waiters pool)]
        (if (or (>= seen n) (> (System/currentTimeMillis) deadline))
          seen
          (do (Thread/sleep 20) (recur)))))))


(defn- attempt
  "Run `f` on a future; the result is `:ok` or the ex-info's `:type`."
  [f]
  (future (try (f) :ok
               (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))


;; ============================================================================
;; The tx seam
;; ============================================================================

(deftest the-pool-is-found-under-a-decorator
  (with-stack (fn [pg deco _v]
                (testing "the decorator has no pool of its own; the one below it is found"
                  (is (not (contains? deco :pool)))
                  (is (identical? (:pool pg) (tx/datasource deco))))
                (testing "binding to a connection keeps the decorator and rebinds the backend"
                  (let [conn (Object.)
                        bound (tx/with-connection deco conn)]
                    (is (instance? GuardedStorage bound))
                    (is (identical? conn (tx/datasource bound)))
                    (is (identical? (:authorize-write deco) (:authorize-write bound)))))
                (testing "a storage and its transaction copies share one cache key"
                  (is (= (tx/without-connection deco)
                         (tx/without-connection (tx/with-connection deco (Object.)))))))))


(deftest a-create-refused-half-way-leaves-nothing-behind
  ;; The identity row and its version row are one write. Refusing the
  ;; version row (a decorator guard, a quota) must take the identity row
  ;; with it — off a transaction it stayed: a versionless ghost that
  ;; resolves nowhere and counts against the tenant's quota.
  (with-stack (fn [pg deco v]
                (reset! (:refused deco) #{[:create :fn-version]})
                (let [ex (try (sp/create-entity v :fn {:name "half" :parent-ids [] :description "d"})
                              (catch clojure.lang.ExceptionInfo e e))]
                  (is (= :test/refused (:type (ex-data ex))))
                  (is (empty? (sp/query-entities pg :fn {:name "half"}))
                      "the identity row was rolled back with the refused version row")))))


(deftest same-name-creates-through-a-decorator-serialize
  (with-stack (fn [_pg _deco v]
                (let [results (mapv deref (doall (repeatedly 8 #(attempt
                                                                  (fn []
                                                                    (sp/create-entity
                                                                      v :fn {:name "race"
                                                                             :parent-ids []
                                                                             :description "r"}))))))]
                  (is (= 1 (count (filter #{:ok} results))) "exactly one create landed")
                  (is (every? #{:constraint-violation/fn-name-collision} (remove #{:ok} results)))
                  (is (= 1 (count (sp/query-entities v :fn {:name "race"}))))))))


(deftest a-branch-delete-refused-half-way-leaves-the-branch-whole
  (with-stack (fn [pg deco v]
                (let [feat (vs/create-branch! v "doomed")
                      vf (vs/switch-branch v (:id feat))
                      f (sp/create-entity vf :fn {:name "on-feat" :parent-ids [] :description "d"})]
                  (reset! (:refused deco) #{[:delete :branch]})
                  (is (= :test/refused
                         (try (vs/delete-branch! v (:id feat)) nil
                              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
                  (is (seq (sp/query-entities pg :fn-version {:fn-id (:id f)}))
                      "the branch's version rows are still there")
                  (is (= "on-feat" (:name (sp/read-entity vf :fn (:id f))))
                      "and the branch still resolves its fn")))))


;; ============================================================================
;; Concurrency contracts (items: lost update, fork/delete, merge/create)
;; ============================================================================

(deftest concurrent-updates-of-different-fields-both-land
  ;; Two edits of the same fn — one sets the description, one the name.
  ;; Each used to read the row BEFORE its transaction; the later commit
  ;; wrote its field onto the stale row and silently undid the other edit.
  (with-stack (fn [pg _deco v]
                (let [f (sp/create-entity v :fn {:name "edited" :parent-ids [] :description "old"})
                      k (first (uniq/row-lock-keys (vs/current-branch-id v) :fn [(:id f)]))
                      gate (hold-lock! (:pool pg) k)
                      a (attempt #(sp/update-entity v :fn (:id f) {:description "new"}))
                      b (attempt #(sp/update-entity v :fn (:id f) {:name "renamed"}))]
                  (is (= 2 (await-waiters (:pool pg) 2)) "both updates wait on the row lock")
                  (release! gate)
                  (is (= [:ok :ok] [@a @b]))
                  (is (= {:name "renamed" :description "new"}
                         (select-keys (sp/read-entity v :fn (:id f)) [:name :description]))
                      "neither edit was lost")))))


(deftest a-fork-and-a-delete-of-its-parent-serialize
  ;; A fork created while its parent is being deleted used to land under a
  ;; parent that was gone a moment later — the delete checked for children
  ;; before taking its lock, and the fork took none.
  (with-stack (fn [pg _deco v]
                (let [parent (vs/create-branch! v "parent")
                      gate (hold-lock! (:pool pg) (mrg/branch-lock-key (:id parent)))
                      del (attempt #(vs/delete-branch! v (:id parent)))
                      _ (await-waiters (:pool pg) 1)
                      fork (attempt #(vs/create-branch! v "child" {:base-branch-id (:id parent)}))]
                  (is (= 2 (await-waiters (:pool pg) 2)) "the fork waits on the parent's lock too")
                  (release! gate)
                  (let [outcome #{@del @fork}
                        branches (vs/list-branches v)
                        ids (set (map :id branches))]
                    (is (contains? outcome :ok) "one of them went through")
                    (is (not= #{:ok} outcome) "not both")
                    (is (every? #(or (nil? (:base-branch-id %)) (contains? ids (:base-branch-id %)))
                                branches)
                        "no branch forks off a missing parent"))))))


(deftest a-merge-waits-on-the-names-it-surfaces
  ;; A merge surfacing fn `shared` onto main and a create of `shared` on main
  ;; each passed its own uniqueness check before the other committed — both
  ;; landed. The merge now takes the collision lock a create takes.
  (with-stack (fn [pg _deco v]
                (let [feat (vs/create-branch! v "feat")
                      vf (vs/switch-branch v (:id feat))
                      _ (sp/create-entity vf :fn {:name "shared" :parent-ids [] :description "f"})
                      gate (hold-lock! (:pool pg) (uniq/collision-lock-key nil :fn {:name "shared"}))
                      merged (attempt #(vs/merge-branch! v (:id feat)))]
                  (is (= 1 (await-waiters (:pool pg) 1)) "the merge waits on the name's lock")
                  (release! gate)
                  (is (= :ok @merged))))))


;; ============================================================================
;; N+1 — merged list items' owners are read once
;; ============================================================================

(deftest merged-list-items-read-their-bindings-once
  (with-stack (fn [_pg deco v]
                (let [owner (sp/create-entity v :fn {:name "owner" :parent-ids [] :description "o"})
                      slot (sp/create-entity v :slot {:name "xs" :type-fn-id (:id owner)})
                      b (sp/create-entity v :binding {:fn-id (:id owner) :slot-id (:id slot)
                                                      :list-append true})
                      feat (vs/create-branch! v "items")
                      vf (vs/switch-branch v (:id feat))]
                  (doseq [i (range 6)]
                    (sp/create-entity vf :binding-list-item {:binding-id (:id b) :position i :value i}))
                  (vs/merge-branch! v (:id feat))
                  (reset! (:reads deco) {})
                  (is (= 6 (count (sp/query-entities v :binding-list-item {:binding-id (:id b)}))))
                  (is (zero? (get @(:reads deco) :binding 0))
                      "no per-item read of the owning binding")))))


;; ============================================================================
;; The whole-branch load memo under a decorator
;; ============================================================================

(deftest graph-load-memo-works-under-a-decorator-and-keys-on-the-org
  ;; The memo keyed on `graph-epoch/current` of the handle it held — and a
  ;; decorator has no pool, so on the cloud stack it never switched on: a
  ;; tenant's [Run all] paid two whole-branch loads per test. Through the
  ;; epoch handle it memoises there too; and because the decorator filters
  ;; by the org in scope, another org — or the raw base — reads its own.
  (with-stack
    (fn [pg _deco v]
      (let [known? (fn [storage fid]
                     (try (contains? (:fns (sp/resolve-execution-graph storage fid)) fid)
                          (catch clojure.lang.ExceptionInfo e
                            (if (= :not-found (:type (ex-data e))) false (throw e)))))
            a (sp/create-entity v :fn {:name "memo-a" :parent-ids [] :description "h"})]
        (res/call-with-graph-load-memo
          (fn []
            (is (known? v (:id a)))
            ;; Written under the versioning layer: no epoch bump, so only a
            ;; memoised load misses it.
            (let [raw (sp/create-entity pg :fn {:name "memo-raw" :parent-ids []
                                                :description "h"})]
              (testing "a second resolve through the decorator reuses the load"
                (is (false? (known? v (:id raw)))))
              (testing "another org in scope does not share the entry"
                (is (true? (binding [tc/*current-org* (random-uuid)] (known? v (:id raw))))))
              (testing "a raw (undecorated) read does not share the scoped entry"
                (let [raw2 (sp/create-entity pg :fn {:name "memo-raw2" :parent-ids []
                                                     :description "h"})]
                  (is (true? (known? (vs/wrap-with-versioning pg) (:id raw2))))
                  (is (false? (known? v (:id raw2)))))))))))))
