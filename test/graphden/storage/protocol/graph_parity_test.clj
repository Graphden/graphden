(ns graphden.storage.protocol.graph-parity-test
  "Parity guard for the binding fn-reference EDGE SET — the recurring
   'parallel-path drift' class made structural.

   A `:resolved-value` binding references its resolver fn ONLY through
   `:resolver-fn-id` (its `:ref-fn-id` is nil), a value-narrowing binding through
   `:type-override-fn-id`, a plain ref through `:ref-fn-id`. Every fn-ref WALKER
   — the three execution-graph resolvers, the invalidation/cycle checks, the
   ghost-repair ref map, the hard-delete child-ref map, and package export — must
   chase ALL THREE, or it drops part of the dependency closure (fn-not-found on
   compile / install / export). `:resolver-fn-id` was silently missed by the
   execution-graph resolvers (fixed 2026-08-25), then again by package export.

   This test pins the canonical set and asserts every KNOWN walker covers it, so
   a walker that drifts — or a newly-added binding fn-ref field left out of one —
   reddens CI and names the laggard, instead of surfacing as a lost-dependency
   bug rounds later. Adding a binding fn-ref field ⇒ update `canonical` here AND
   every walker; this test tells you which ones you missed."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.export :as export]
    [graphden.storage.postgres.graph :as pg-graph]
    [graphden.storage.protocol.graph :as proto-graph]
    [graphden.versioning.identity-repair :as idrepair]
    [graphden.versioning.storage.core :as vcore]
    [graphden.versioning.storage.resolution :as res]))


(def ^:private canonical
  "Every fn-reference field a `:binding` (or its `:binding-version` mirror) can
   carry. THE source of truth for the walkers below."
  #{:ref-fn-id :type-override-fn-id :resolver-fn-id})


(def ^:private ref-target #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private tov-target #uuid "00000000-0000-0000-0000-0000000000b2")
(def ^:private rsv-target #uuid "00000000-0000-0000-0000-0000000000c3")
(def ^:private owner-id   #uuid "00000000-0000-0000-0000-0000000000d4")
(def ^:private binding-id #uuid "00000000-0000-0000-0000-0000000000e5")


(def ^:private full-binding
  "One binding carrying all three fn-ref fields at once."
  {:id binding-id :fn-id owner-id
   :ref-fn-id ref-target :type-override-fn-id tov-target :resolver-fn-id rsv-target})


(def ^:private all-targets #{ref-target tov-target rsv-target})


(deftest identity-repair-ref-fields-covers-canonical
  (let [rf @#'idrepair/ref-fields]
    (is (= canonical (set (:binding rf)))
        "identity-repair/ref-fields :binding drifted from the canonical set")
    (is (= canonical (set (:binding-version rf)))
        "identity-repair/ref-fields :binding-version drifted")))


(deftest identity-child-refs-covers-canonical
  (let [fn-refs (set (:fn @#'vcore/identity-child-refs))]
    (doseq [f canonical]
      (is (contains? fn-refs [:binding f])
          (str "identity-child-refs :fn missing [:binding " f "]"))
      (is (contains? fn-refs [:binding-version f])
          (str "identity-child-refs :fn missing [:binding-version " f "]")))))


(deftest closure-resolver-extractors-cover-canonical
  (testing "the generic-BFS extractor chases every binding fn-ref field"
    (is (= all-targets (@#'proto-graph/extract-fn-refs-from-bindings [full-binding]))))
  (testing "the versioned-batch extractor chases every binding fn-ref field"
    (is (= all-targets (@#'res/extract-fn-refs-from-bindings [full-binding])))))


(deftest postgres-cte-sql-mentions-every-edge
  (let [sql @#'pg-graph/reachable-fns-sql]
    (doseq [col ["b.ref_fn_id" "b.type_override_fn_id" "b.resolver_fn_id"]]
      (is (str/includes? sql col)
          (str "reachable-fns-sql (execution-graph CTE) missing column " col)))))


(deftest export-dependency-walk-covers-canonical
  ;; The export dependency-closure walk (fn-ref-fn-ids) — a binding with all
  ;; three fn-ref fields must contribute all three to the exported deps.
  (let [fn-ref-fn-ids @#'export/fn-ref-fn-ids
        ctx {:fns {owner-id {:id owner-id :parent-ids []}}
             :fn-slots {} :slots {}
             :bindings {owner-id [full-binding]}
             :items {}}
        deps (set (filter some? (fn-ref-fn-ids owner-id ctx)))]
    (is (= all-targets deps)
        "export/fn-ref-fn-ids dropped a binding fn-ref target (incomplete deps)")))
