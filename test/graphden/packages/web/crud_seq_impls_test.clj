(ns ^:serial graphden.packages.web.crud-seq-impls-test
  "Unit tests for the `web/crud-seq` base-fn impls.

   `:pkg-delete-guard-reason` is the server half of the 2026-08-20
   incident fix: one stray API write against a PACKAGE-SYNCED fn
   (`:add`) changed the behaviour of every descendant in the
   installation until the next boot reverted it. The guard has to
   cover the fn row itself AND the binding-family rows it owns —
   including a `binding-list-item`, which only reaches its owner
   through its parent binding. A nil answer means \"the delete is
   allowed\", so any arm that stops resolving the owner silently
   reopens the hole.

   The `_seq-*-load-*` impls are the loaders the sequence `:cond`
   chains call BEFORE the id guards have necessarily matched — each
   must answer nil for an absent id rather than asking storage.

   `^:serial`: the package-owned registry is a process-global defonce;
   this ns writes it and restores it in a `finally`."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.packages.owned :as owned]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.storage-double :as double]))


(use-fixtures :once (impls/impls-fixture "web" "crud-seq"))


(defn- call
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(defn- with-owned
  "Run `body` with `ids` marked package-owned, restoring the global
   registry afterwards."
  [ids body]
  (let [snapshot @@#'owned/owned-ids]
    (try
      (owned/record-owned-ids! ids)
      (body)
      (finally (reset! @#'owned/owned-ids snapshot)))))


(deftest pkg-delete-guard-refuses-deletes-that-damage-a-package-fn
  (let [pkg-fn (random-uuid)
        user-fn (random-uuid)
        pkg-binding (random-uuid)
        user-binding (random-uuid)
        ctx {:storage (double/rows-storage
                        {:fn {pkg-fn {:id pkg-fn :name "add"}
                              user-fn {:id user-fn :name "my-fn"}}
                         :binding {pkg-binding {:id pkg-binding :fn-id pkg-fn}
                                   user-binding {:id user-binding :fn-id user-fn}}})}
        reason (fn [entity-type row]
                 (call :pkg-delete-guard-reason
                       {:entity-type entity-type :row row} ctx))]

    (with-owned
      [pkg-fn]
      (fn []
        (testing "deleting the package fn ROW itself is refused"
          (let [r (reason "fn" {:id pkg-fn :name "add"})]
            (is (string? r))
            (is (str/includes? r "add") "the message names the fn being protected")
            (is (str/includes? r "fns.edn")
                "and points at the legitimate path instead of just saying no")))

        (testing "deleting a BINDING owned by the package fn is refused"
          (is (string? (reason "binding" {:id pkg-binding :fn-id pkg-fn}))))

        (testing "deleting a FN-SLOT owned by the package fn is refused"
          (is (string? (reason "fn-slot" {:id (random-uuid) :fn-id pkg-fn}))))

        (testing "a BINDING-LIST-ITEM resolves its owner THROUGH its binding"
          ;; The item row carries no :fn-id; without the indirection the
          ;; guard would let a package fn's sequence be edited away.
          (is (string? (reason "binding-list-item" {:id (random-uuid)
                                                    :binding-id pkg-binding}))))

        (testing "the SAME shapes on a user-owned fn are allowed"
          (is (nil? (reason "fn" {:id user-fn :name "my-fn"})))
          (is (nil? (reason "binding" {:id user-binding :fn-id user-fn})))
          (is (nil? (reason "binding-list-item" {:binding-id user-binding}))))

        (testing "entity types outside the binding family are never guarded"
          ;; :slot rows are shared global identities — the guard has no
          ;; owner to resolve, and must not invent one.
          (is (nil? (reason "slot" {:id (random-uuid)})))
          (is (nil? (reason "service" {:id (random-uuid) :fn-id pkg-fn}))))

        (testing "a row with no owner reference at all → nil, no throw"
          (is (nil? (reason "binding" {})))
          (is (nil? (reason "binding-list-item" {:binding-id (random-uuid)})))
          (is (nil? (reason "fn" {}))))))

    (testing "with NOTHING package-owned the same deletes are all allowed"
      ;; A fixture-built graph (no package bootstrap) stays fully writable.
      (is (nil? (reason "fn" {:id pkg-fn :name "add"})))
      (is (nil? (reason "binding" {:id pkg-binding :fn-id pkg-fn}))))))


(deftest seq-loaders-answer-nil-for-an-absent-id
  ;; nil ctx: each loader must short-circuit BEFORE `require-storage`.
  (testing "append's binding lookup skips when the request carried no fn-id"
    (is (nil? (call :_seq-append-load-binding {:parsed {}} nil)))
    (is (nil? (call :_seq-append-load-binding {:parsed {:fn-id nil}} nil))))

  (testing "the item loaders skip when the request carried no item-id"
    (doseq [kw [:_seq-remove-load-item :_seq-update-load-item :_seq-move-load-item]]
      (is (nil? (call kw {:parsed {}} nil)) (str kw " with no :item-id"))
      (is (nil? (call kw {:parsed {:item-id nil}} nil)) (str kw " with a nil :item-id")))))


(deftest seq-item-loaders-read-the-binding-list-item-table
  (let [item-id (random-uuid)
        row {:id item-id :position 3 :value 10}
        ctx {:storage (double/rows-storage {:binding-list-item {item-id row}})}]
    (testing "update and move share one loader and one row shape"
      ;; The move flow reuses the update loader verbatim; if either read
      ;; a different table the position arithmetic would run on nil.
      (is (= row (call :_seq-update-load-item {:parsed {:item-id item-id}} ctx)))
      (is (= row (call :_seq-move-load-item {:parsed {:item-id item-id}} ctx)))
      (is (= row (call :_seq-remove-load-item {:parsed {:item-id item-id}} ctx))))

    (testing "an id that matches no row → nil (the not-found guard's input)"
      (is (nil? (call :_seq-update-load-item {:parsed {:item-id (random-uuid)}} ctx))))))
