(ns graphden.crud.org-visible-graph-test
  "Org visibility of the shared `:graph-cache` read path.

   The cache is primed org-AGNOSTICALLY (`prime-graph-cache!` reads the
   privileged compile storage), so BEFORE `org-visible-slice` a tenant's
   sidebar `:tree`/`:search`, the type datalist and layout enumerated
   every org's fn names, namespaces and binding values straight off the
   cache — OrgScopedStorage never saw those reads. These tests pin the
   read-side filter: a bound tenant org sees own + public/un-owned rows
   only; the platform tier (and single-tenant, where `*current-org*` is
   unbound) sees the dump unchanged. Pure in-memory ctx — the leak was
   precisely that no storage round-trip is involved on a cache hit."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities :as entities]
    [graphden.crud.types-api :as ta]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]
    [graphden.types.diagnostics :as diag])
  (:import
    (java.util
      UUID)))


(def ^:private empty-ns-storage
  ;; Just enough storage for the `:tree` scope's namespaces delay; the
  ;; whole point of these tests is that the fn rows come off the CACHE.
  ;; Keyword access for `current-branch-id` (`(:branch-id this)`) → nil.
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify sp/StorageCRUD
    (query-entities [_ _ _] [])

    (query-entities [_ _ _ _] [])))


(def ^:private a-fn-id (UUID/randomUUID))
(def ^:private b-fn-id (UUID/randomUUID))
(def ^:private pub-fn-id (UUID/randomUUID))


(def ^:private full-graph
  ;; What an org-agnostic prime leaves in the cache: platform rows
  ;; (nil org-id) + two tenants' private rows, across all five tables.
  {:fns [{:id pub-fn-id :name "public-fn" :namespace-id nil}
         {:id a-fn-id :name "acme-secret-pipeline" :namespace-id nil :org-id "acme"}
         {:id b-fn-id :name "bcorp-own-fn" :namespace-id nil :org-id "bcorp"}]
   :slots [{:id (UUID/randomUUID) :name "s-pub"}
           {:id (UUID/randomUUID) :name "s-acme" :org-id "acme"}]
   :fn-slots []
   :bindings [{:id (UUID/randomUUID) :fn-id a-fn-id :org-id "acme"
               :value "acme business data"}]
   :list-items []})


(deftest org-visible-slice-filters-foreign-rows
  (testing "a bound tenant org sees own + un-owned rows only"
    (tctx/with-org "bcorp"
                   (let [sliced (ta/org-visible-slice full-graph)]
                     (is (= #{"public-fn" "bcorp-own-fn"}
                            (into #{} (map :name) (:fns sliced)))
                         "acme's private fn is gone; public + own remain")
                     (is (= ["s-pub"] (mapv :name (:slots sliced)))
                         "foreign slots are gone too")
                     (is (empty? (:bindings sliced))
                         "a foreign binding (with its literal :value) never crosses orgs"))))
  (testing "the fn's own org sees its rows"
    (tctx/with-org "acme"
                   (is (= #{"public-fn" "acme-secret-pipeline"}
                          (into #{} (map :name) (:fns (ta/org-visible-slice full-graph)))))))
  (testing "platform tier / single-tenant (unbound org) is a pass-through"
    (is (identical? full-graph (ta/org-visible-slice full-graph))
        "unbound *current-org* normalises to the public org → identity")
    (tctx/with-org tctx/public-org
                   (is (identical? full-graph (ta/org-visible-slice full-graph))))))


(deftest cached-or-load-graph-slices-the-shared-cache
  (testing "a cache HIT is sliced per current org — no storage involved"
    (let [ctx {:graph-cache (atom full-graph)}]
      (tctx/with-org "bcorp"
                     (is (= #{"public-fn" "bcorp-own-fn"}
                            (into #{} (map :name) (:fns (ta/cached-or-load-graph ctx))))))
      (is (= 3 (count (:fns @(:graph-cache ctx))))
          "the cache itself keeps the FULL graph — slicing is per read"))))


(deftest cached-or-load-graph-slice-is-stable-per-snapshot-and-org
  ;; Every tenant read used to re-filter the whole graph into a NEW
  ;; object, so identity-keyed memos downstream (layout lookups, the lint
  ;; memo) never hit for tenants — POST /api/graph/layout re-derived the
  ;; whole graph on every expansion.
  (let [cache (atom full-graph)
        ctx {:graph-cache cache}
        read-as (fn [org] (tctx/with-org org (ta/cached-or-load-graph ctx)))
        first-read (read-as "bcorp")]
    (testing "the same snapshot + org yields the SAME slice object"
      (is (identical? first-read (read-as "bcorp"))))
    (testing "another org gets its own slice"
      (is (= #{"public-fn" "acme-secret-pipeline"}
             (into #{} (map :name) (:fns (read-as "acme")))))
      (is (identical? first-read (read-as "bcorp"))
          "a second org does not evict the first"))
    (testing "a new snapshot (a write replaced it) is re-sliced"
      (reset! cache (update full-graph :fns conj
                            {:id (UUID/randomUUID) :name "bcorp-new-fn" :org-id "bcorp"}))
      (let [after (read-as "bcorp")]
        (is (not (identical? first-read after)))
        (is (contains? (into #{} (map :name) (:fns after)) "bcorp-new-fn"))))))


(deftest sidebar-scopes-respect-org-visibility
  ;; the `graph-*` reads take `base` off the cache; these pin the
  ;; two enumeration scopes a tenant hits on every editor init.
  (let [ctx {:graph-cache (atom full-graph)
             :storage empty-ns-storage}]
    (binding [diag/*diagnostics-override* (atom {})]
      (testing ":search cannot find a foreign org's fn"
        (tctx/with-org "bcorp"
                       (let [{:keys [fns]} (entities/graph-search
                                             ctx "acme" false)]
                         (is (empty? fns) "substring search over foreign names finds nothing"))
                       (let [{:keys [fns]} (entities/graph-search
                                             ctx "bcorp-own" false)]
                         (is (= [(str b-fn-id)] (mapv (comp str :id) fns))
                             "own fns still resolve"))))
      (testing ":tree diag counts ignore foreign fn-ids in the shared bucket"
        (diag/record! nil a-fn-id [{:message "acme's broken fn"}])
        (tctx/with-org "bcorp"
                       (let [{:keys [counts]} (entities/graph-tree ctx)]
                         (is (every? (comp nil? :type-error-count) counts)
                             "no phantom per-namespace error chip from a foreign org's fn")))
        (tctx/with-org "acme"
                       (let [{:keys [counts]} (entities/graph-tree ctx)]
                         (is (= 1 (reduce + 0 (keep :type-error-count counts)))
                             "the owning org still sees its own count")))))))


(deftest org-visible-rich-snapshot-filters-foreign-fns
  ;; F1 regression: the NAME-keyed rich-type registry has no org filter,
  ;; so a tenant enumerating /api/types would read every other org's
  ;; composed-fn names + signatures. org-visible-rich-snapshot restricts
  ;; it to names visible in the org-sliced graph.
  (binding [registry/*rich-types-override* (atom {})]
    (registry/record-rich-types-raw! pub-fn-id :public-fn
                                     {:return :int :args {} :namespace nil})
    (registry/record-rich-types-raw! a-fn-id :acme-secret-pipeline
                                     {:return :text :args {:token :text} :namespace nil})
    (registry/record-rich-types-raw! b-fn-id :bcorp-own-fn
                                     {:return :bool :args {} :namespace nil})
    (let [ctx {:graph-cache (atom full-graph)}]
      (testing "a tenant sees only its own + public fn signatures"
        (tctx/with-org "bcorp"
                       (let [snap (ta/org-visible-rich-snapshot ctx)]
                         (is (contains? snap :public-fn))
                         (is (contains? snap :bcorp-own-fn))
                         (is (not (contains? snap :acme-secret-pipeline))
                             "acme's composed-fn signature must not leak to bcorp"))))
      (testing "the owning org sees its own"
        (tctx/with-org "acme"
                       (let [snap (ta/org-visible-rich-snapshot ctx)]
                         (is (contains? snap :acme-secret-pipeline))
                         (is (not (contains? snap :bcorp-own-fn))))))
      (testing "platform tier is an identity pass-through (single-tenant cache stays)"
        (is (identical? (registry/rich-types-snapshot)
                        (ta/org-visible-rich-snapshot ctx)))))))
