(ns ^:serial graphden.crud.entities-test
  "DB-backed tests for `graphden.crud.entities` — the heavy CRUD logic
   behind the web/crud base functions: form parsers, generic
   create/read/update/delete, the compound type-row endpoints, the
   delete-guard reasons, and the HTTP `process-*` dispatchers.

   Uses the shared container plus a real `ExecutionContext` so the
   `invalidate!` path exercises against live storage.

   Parallel-safe: no `with-redefs` (serial-reduction cluster B). The
   two tighten tests that used to root-redef the hot-path
   `registry/rich-type-of-id` / `tc/type-check-fn-after-mutation!`
   now register REAL rich-types entries and construct data that makes
   the genuine post-write check fail — writes go to the parallel
   plugin's per-NS-thread `*rich-types-override*` atom (the `:once`
   `with-isolated-rich-types` fixture covers solo runs). The search
   cap is a thread-local `binding` of the now-dynamic
   `entity-list/*default-search-limit*`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.validation :as validation]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types)


(defn- test-ctx
  "A real ExecutionContext over a fresh test storage."
  [storage]
  (ctx/create-context {:storage storage :base-fns {}}))


;; ============================================================================
;; affected-fn-ids
;; ============================================================================

(deftest affected-fn-ids-test
  (let [storage (setup/create-test-storage)]
    (try
      (testing ":fn — id when present, nil otherwise"
        (let [id (random-uuid)]
          (is (= #{id} (entities/affected-fn-ids storage :fn {:id id})))
          (is (nil? (entities/affected-fn-ids storage :fn {})))))

      (testing ":fn-slot / :binding seed on :fn-id"
        (let [fid (random-uuid)]
          (is (= #{fid} (entities/affected-fn-ids storage :fn-slot {:fn-id fid})))
          (is (= #{fid} (entities/affected-fn-ids storage :binding {:fn-id fid})))))

      (testing ":binding-list-item resolves the owner fn through its binding"
        (let [f    (setup/create-base-fn! storage "afi-fn")
              slot (setup/create-slot! storage "s" :int)
              b    (sp/create-entity storage :binding
                                     {:fn-id (:id f) :slot-id (:id slot)
                                      :value 1})]
          (is (= #{(:id f)}
                 (entities/affected-fn-ids storage :binding-list-item
                                           {:binding-id (:id b)})))))

      ;; These two used to answer `nil` — "I don't know" — and `nil` makes the
      ;; caller drop the whole compiled registry, so the next request rebuilt
      ;; every fn in the graph. Measured at 4137 fns: one namespace create cost
      ;; the next request 49.6 s, one slot create 49.8 s.
      ;;
      ;; They are not unknown. They are EMPTY: neither write can reach a compiled
      ;; closure. `nil` still means unknown, and still full-clears, for the
      ;; callers that genuinely are.
      (testing ":ns reaches no compiled closure → #{} (nothing to invalidate)"
        (is (= #{} (entities/affected-fn-ids storage :ns {:id (random-uuid)}))))

      (testing ":service is desired-state metadata → #{} (no closure moves)"
        ;; A `:service` write used to answer nil (the fallthrough) → full clear →
        ;; the whole registry dropped, and the next request recompiled the entire
        ;; graph (~48s, executor-blocking). A service row changes no fn definition;
        ;; the reconciler reacts through its own NOTIFY listener, not this path.
        (is (= #{} (entities/affected-fn-ids storage :service {:id (random-uuid)
                                                               :fn-id (random-uuid)}))))

      (testing ":slot seeds the fns that already EXPOSE it — none, on a create"
        (let [slot (setup/create-slot! storage "afi-orphan" :int)]
          (is (= #{} (entities/affected-fn-ids storage :slot {:id (:id slot)}))
              "nothing points at a slot until an fn-slot does"))

        (testing "and the owners, once an fn-slot does point at it"
          (let [f (setup/create-base-fn! storage "afi-slot-owner")
                slot (setup/create-slot! storage "afi-owned" :int)]
            (sp/create-entity storage :fn-slot
                              {:fn-id (:id f) :slot-id (:id slot) :position 0})
            (is (= #{(:id f)}
                   (entities/affected-fn-ids storage :slot {:id (:id slot)}))
                "an in-place slot edit is seeded, not silently skipped"))))

      (testing "non-graph entities (executions, package pins, tenancy addon rows) → #{}"
        ;; The compiler reads only the fn-graph entity types, so no other
        ;; write can move a compiled closure. These used to hit the nil
        ;; fallthrough → full registry clear per grant / org / token write.
        (doseq [et [:execution :package-install :package-version
                    :org :token :domain :grant :user]]
          (is (= #{} (entities/affected-fn-ids storage et {:id (random-uuid)}))
              (str et " write must not full-clear the compiled registry"))))
      (finally (sp/close storage)))))


;; ============================================================================
;; notify-after-write! — cross-pod NOTIFY delta-seed emission
;;
;; Regression guard: a fn-graph DELETE must emit a DELTA seed event
;; (`:id <owner-fn-id>`), NOT the empty-id full-clear. A bare `{:id id}`
;; snapshot fell through to `affected-fn-ids` → nil → `:id ""`, and since
;; a pod receives its OWN notify, every delete then forced a full
;; compiled-registry rebuild (tens of seconds) on the emitting pod.
;; ============================================================================

(deftest notify-after-write-delta-seed-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [events (atom [])
            emit-ctx {:notify-emitter (fn [ev] (swap! events conj ev))}
            clear! (fn [] (reset! events []))]

        (testing ":binding delete with a snapshot carrying :fn-id emits ONE delta seed"
          (clear!)
          (let [fid (random-uuid)]
            (entities/notify-after-write! emit-ctx storage :binding :delete
                                          {:fn-id fid :id (random-uuid)})
            (is (= [{:kind :fn :op :invalidate :id (str fid)}] @events)
                "delta seed = owner fn-id, not empty full-clear")))

        (testing ":binding-list-item delete resolves the owner fn through its binding"
          (clear!)
          (let [f    (setup/create-base-fn! storage "naw-fn")
                slot (setup/create-slot! storage "s" :int)
                b    (sp/create-entity storage :binding
                                       {:fn-id (:id f) :slot-id (:id slot)
                                        :value 1})]
            (entities/notify-after-write! emit-ctx storage :binding-list-item :delete
                                          {:binding-id (:id b) :id (random-uuid)})
            (is (= [{:kind :fn :op :invalidate :id (str (:id f))}] @events)
                "owner fn-id derived from the pre-read binding")))

        ;; A pod receives its OWN notify, and an empty-id event means "full clear"
        ;; to the listener — so emitting one here undid the local delta and
        ;; rebuilt the whole graph on the next request. This was what kept the
        ;; 50-second slot write alive after the local path had already been fixed.
        (testing "a :slot write nothing exposes emits NOTHING — it changed no closure"
          (clear!)
          (let [slot (setup/create-slot! storage "naw-orphan" :int)]
            (entities/notify-after-write! emit-ctx storage :slot :write {:id (:id slot)})
            (is (= [] @events)
                "an empty-id event would tell every pod to drop its registry")))

        (testing "a :slot write DOES seed the fns that expose it"
          (clear!)
          (let [f (setup/create-base-fn! storage "naw-slot-owner")
                slot (setup/create-slot! storage "naw-owned" :int)]
            (sp/create-entity storage :fn-slot
                              {:fn-id (:id f) :slot-id (:id slot) :position 0})
            (clear!)
            (entities/notify-after-write! emit-ctx storage :slot :write {:id (:id slot)})
            (is (= [{:kind :fn :op :invalidate :id (str (:id f))}] @events))))

        (testing ":service writes emit a distinct :service event, not a fn-invalidate"
          (clear!)
          (let [sid (random-uuid)]
            (entities/notify-after-write! emit-ctx storage :service :start {:id sid})
            (is (= [{:kind :service :op :start :id (str sid)}] @events))))

        (testing "an un-versioned storage has no branch → the key is OMITTED, not nil"
          (clear!)
          (entities/notify-after-write! emit-ctx storage :binding :delete
                                        {:fn-id (random-uuid) :id (random-uuid)})
          (is (not (contains? (first @events) :branch-id)))
          (is (not (contains? (first @events) :org-id))
              "no :org-id on the row → the key is omitted (single-tenant)"))

        (testing "a scoped row's :org-id rides along for SSE fan-out"
          (clear!)
          (let [fid (random-uuid)]
            (entities/notify-after-write! emit-ctx storage :binding :delete
                                          {:fn-id fid :id (random-uuid) :org-id "acme"})
            (is (= "acme" (:org-id (first @events)))
                "the writing org is read straight off the (stamped) row")))

        (testing "a versioned storage stamps the branch the write landed on"
          ;; The receiving pod needs it: an edit on `dev` must not recompile
          ;; `main`, and an edit on `main` must recompile every cached branch
          ;; that inherits from it.
          (clear!)
          (let [branch (sp/create-entity storage :branch
                                         {:name "naw-branch"
                                          :created-at (java.time.Instant/now)})
                versioned (vs/->VersionedStorage storage (:id branch))
                fid (random-uuid)]
            (entities/notify-after-write! emit-ctx versioned :binding :delete
                                          {:fn-id fid :id (random-uuid)})
            (is (= [{:kind :fn :op :invalidate
                     :id (str fid) :branch-id (str (:id branch))}]
                   @events))))

        (testing "no :notify-emitter on the ctx → no-op (tests / single-pod)"
          (clear!)
          (entities/notify-after-write! {} storage :binding :delete
                                        {:fn-id (random-uuid) :id (random-uuid)})
          (is (empty? @events))))
      (finally (sp/close storage)))))


;; ============================================================================
;; Generic CRUD round-trip
;; ============================================================================

(deftest generic-crud-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "create → get → list → update → delete round-trip"
        (let [created (entities/create-entity "ns" {:name "crud-ns"} c)
              id (:id created)]
          (is (some? id))
          (is (= "crud-ns" (:name (entities/get-entity "ns" id c))))
          (is (some #(= id (:id %)) (entities/list-entities "ns" {} c)))
          (entities/update-entity "ns" id {:description "updated"} c)
          (is (= "updated" (:description (entities/get-entity "ns" id c))))
          (is (true? (entities/delete-entity "ns" id c)))
          (is (nil? (entities/get-entity "ns" id c)))))

      (testing "create-entity surfaces a write-rej as a typed ex-info"
        (let [ex (try (entities/create-entity
                        "fn" {:name "crud-bad" :parent-ids []
                              :constraint [:union :int]} c)
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo ex))
          (is (= :constraint-violation/constraint-shape (:type (ex-data ex))))))

      (testing "update-entity surfaces a write-rej as a typed ex-info"
        ;; Create a valid fn-row, then try to update it with a malformed
        ;; constraint — write-rej fires and we want the ex-info shape
        ;; (lines 119-121 in crud/entities.clj).
        (let [created (entities/create-entity
                        "fn" {:name "crud-update-target"
                              :parent-ids []
                              :base-fn-id nil
                              :element-fn-id nil
                              :return-type-fn-id nil
                              :anonymous-hash nil
                              :constraint nil} c)
              ex (try (entities/update-entity
                        "fn" (:id created)
                        {:constraint [:union :int]} c)
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo ex))
          (is (= :constraint-violation/constraint-shape (:type (ex-data ex))))
          (is (= (:id created) (:id (ex-data ex))))))

      (testing "delete-entity :fn pre-reads to capture name for rich-type unregister"
        ;; The `:fn` arm of delete-entity now pre-reads the row so we
        ;; can drop the rich-types-registry entry by name; the read
        ;; pays for itself since the registry is keyed on fn-name,
        ;; not fn-id. Before this change the arm synthesised
        ;; `{:id id}` and the registry leaked.
        (let [fn-row (setup/create-base-fn! storage "delete-with-name-capture")
              ok? (entities/delete-entity "fn" (:id fn-row) c)]
          (is (true? ok?))
          (is (nil? (sp/read-entity storage :fn (:id fn-row))))
          ;; The rich-type entry for this fn-name was registered when
          ;; `create-base-fn!` ran above. After delete it should be
          ;; gone — verifying the unregister-rich-type! wiring fires.
          (is (nil? (registry/rich-type-of :delete-with-name-capture))
              "rich-type entry dropped on :fn delete")))
      (finally (sp/close storage)))))


;; ============================================================================
;; list-all-graph-entities
;; ============================================================================

(deftest list-all-graph-entities-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "returns the five graph tables + namespaces, fns carry a :role"
        (let [_    (setup/create-base-fn! storage "lage-fn")
              dump (entities/list-all-graph-entities c)]
          (is (contains? dump :fns))
          (is (contains? dump :slots))
          (is (contains? dump :namespaces))
          (is (every? #(contains? % :role) (:fns dump)))))
      (testing "scope :index — drops bindings / slots / fn-slots / list-items,"
        (testing "keeps fns + namespaces; fns still carry :role"
          (let [_    (setup/create-base-fn! storage "lage-fn-idx")
                dump (entities/list-all-graph-entities c :index)]
            (is (= #{:fns :namespaces} (set (keys dump)))
                ":index payload is exactly {:fns :namespaces}")
            (is (seq (:fns dump)) "fns are still populated")
            (is (every? #(contains? % :role) (:fns dump))
                ":role still computed on fns")
            (is (every? (fn [f] (not-any? nil? (vals f))) (:fns dump))
                ":index fns carry NO nil-valued fields — they're stripped to
                 cut ~25-40% of the sidebar payload churn (an absent key
                 reads as null client-side, full detail comes from :subtree)"))))
      (testing "scope :full (explicit) matches the no-scope default"
        (is (= (entities/list-all-graph-entities c)
               (entities/list-all-graph-entities c :full))
            ":full echoes the unchanged default behaviour"))
      (testing "scope :subtree + root-id — only the BFS closure"
        (let [parent-id (java.util.UUID/randomUUID)
              child-id  (java.util.UUID/randomUUID)
              unrelated-id (java.util.UUID/randomUUID)
              _ (sp/create-entity storage :fn
                                  {:id parent-id :name "subtree-parent"
                                   :parent-ids []})
              _ (sp/create-entity storage :fn
                                  {:id child-id :name "subtree-child"
                                   :parent-ids [parent-id]})
              _ (sp/create-entity storage :fn
                                  {:id unrelated-id :name "subtree-unrelated"
                                   :parent-ids []})
              ;; sp/create-entity bypasses the graph-cache invalidation
              ;; the real write path runs (`:try-apply-create` →
              ;; `invalidate!`), so the prior sub-tests' cached graph
              ;; doesn't include these new fns. Drop the cache so the
              ;; next loader picks up the writes.
              _ (ctx/invalidate-graph-cache! c)
              dump (entities/list-all-graph-entities c :subtree child-id)
              fn-ids (into #{} (map :id) (:fns dump))]
          (is (contains? fn-ids child-id) "root in subtree")
          (is (contains? fn-ids parent-id) "parent reachable via parent-ids")
          (is (not (contains? fn-ids unrelated-id))
              "unrelated fn excluded from subtree")
          (is (every? #(contains? % :role) (:fns dump))
              ":role still computed on subtree fns")))
      (testing "scope :subtree without root-id falls back to full"
        (is (= (entities/list-all-graph-entities c)
               (entities/list-all-graph-entities c :subtree nil))
            "nil root-id → full payload (silent fallback)"))
      (finally (sp/close storage)))))


(def ^:private light-fn-keys
  "The exact whitelist `:tree` / `:namespace` / `:search` project each fn
   down to (nils dropped). Mirrors `entities/light-fn-fields`."
  #{:id :name :namespace-id :role :description :constraint
    :parent-ids :return-type-fn-id
    :used-as-parent-count :used-as-ref-count})


(deftest list-all-graph-entities-scoped-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        ns-a (java.util.UUID/randomUUID)
        ns-b (java.util.UUID/randomUUID)
        a1 (java.util.UUID/randomUUID)
        a2 (java.util.UUID/randomUUID)
        b1 (java.util.UUID/randomUUID)
        anon (java.util.UUID/randomUUID)
        r1 (java.util.UUID/randomUUID)
        child (java.util.UUID/randomUUID)
        bcomp (java.util.UUID/randomUUID)]
    (sp/create-entity storage :ns {:id ns-a :name "alpha"})
    (sp/create-entity storage :ns {:id ns-b :name "beta"})
    ;; two named fns in ns-a, one in ns-b, one ANONYMOUS in ns-a (must be
    ;; excluded everywhere), one named fn in the root bucket (nil ns).
    (sp/create-entity storage :fn {:id a1 :name "alpha-widget" :namespace-id ns-a :parent-ids []})
    (sp/create-entity storage :fn {:id a2 :name "alpha-gadget" :namespace-id ns-a :parent-ids []})
    (sp/create-entity storage :fn {:id b1 :name "beta-widget" :namespace-id ns-b :parent-ids []})
    (sp/create-entity storage :fn {:id anon :name nil :namespace-id ns-a :parent-ids []})
    (sp/create-entity storage :fn {:id r1 :name "root-thing" :parent-ids []})
    ;; A root-bucket child that DEPENDS on ns-a's fns — parents a1, and a
    ;; ref binding pointing at a2 — so the reverse-ref counts on a1/a2 are
    ;; non-zero. Kept in the root bucket so ns-a / ns-b counts stay clean.
    (sp/create-entity storage :fn {:id child :name "child-of-a1" :parent-ids [a1]})
    ;; A named COMPOSED fn in ns-b — the one row here the types lens must
    ;; NOT count (`:type-count` below): non-empty parent-ids → :composed.
    (sp/create-entity storage :fn {:id bcomp :name "beta-composed" :namespace-id ns-b :parent-ids [b1]})
    (let [slot (setup/create-slot! storage "sfi-ref-slot" :int)]
      (sp/create-entity storage :binding
                        {:fn-id child :slot-id (:id slot)
                         :ref-fn-id a2}))
    ;; sp/create-entity bypasses the graph-cache invalidation the real
    ;; write path runs — drop the cache so the loader sees these writes.
    (ctx/invalidate-graph-cache! c)
    (try
      (testing "scope :tree — {:namespaces :counts} only, NO fn rows"
        (let [dump (entities/list-all-graph-entities c :tree)]
          (is (= #{:namespaces :counts} (set (keys dump)))
              ":tree payload is exactly {:namespaces :counts}")
          (let [count-by (into {} (map (juxt :namespace-id :count)) (:counts dump))
                types-by (into {} (map (juxt :namespace-id :type-count)) (:counts dump))]
            (is (= 2 (get count-by ns-a))
                "ns-a counts its 2 NAMED fns; the anonymous one is excluded")
            (is (= 2 (get count-by ns-b)))
            ;; The nil bucket also holds the 14 seeded primitive fn-rows, so
            ;; assert presence + that r1 lifted the count, not an exact value.
            (is (contains? count-by nil)
                "the namespace-less bucket is present in :counts")
            (is (pos? (get count-by nil)))
            ;; :type-count — the types-lens payload. Parent-less slot-less
            ;; named rows classify :primitive (empty type-rows), composed
            ;; fns are excluded.
            (is (= 2 (get types-by ns-a))
                "a1/a2 classify :primitive — counted as type-rows")
            (is (= 1 (get types-by ns-b))
                "b1 counts; the composed beta-composed does NOT")
            (is (pos? (get types-by nil))
                "the primitives bucket reports its seeded type-rows")
            ;; :fn-count — the fn-lens payload: named non-type rows.
            (let [fns-by (into {} (map (juxt :namespace-id :fn-count)) (:counts dump))]
              (is (nil? (get fns-by ns-a))
                  "every ns-a row is type-shaped — the zero :fn-count key is omitted")
              (is (= 1 (get fns-by ns-b))
                  "the composed beta-composed is the one plain fn in ns-b")
              (is (pos? (get fns-by nil))
                  "child-of-a1 (composed) counts in the root bucket")))))
      (testing "scope :namespace — one namespace's light named fns"
        (let [dump (entities/list-all-graph-entities c :namespace nil ns-a nil)
              ids  (into #{} (map :id) (:fns dump))
              by-id (into {} (map (juxt :id identity)) (:fns dump))]
          (is (= #{a1 a2} ids)
              "only ns-a's named fns; anonymous excluded, other ns excluded")
          (is (= #{:fns} (set (keys dump))) "no heavy tables (:slots etc.)")
          (is (every? #(contains? % :role) (:fns dump)) "light rows carry :role")
          (is (every? #(every? light-fn-keys (keys %)) (:fns dump))
              "light rows are projected to the whitelist — nothing extra")
          (testing "carry whole-graph reverse-ref counts (delete/edit gate)"
            (is (= 1 (:used-as-parent-count (by-id a1)))
                "a1 is parented by the root-bucket child")
            (is (= 1 (:used-as-ref-count (by-id a2)))
                "a2 is ref'd by the child's binding")
            (is (not (contains? (by-id a1) :used-as-ref-count))
                "zero counts are omitted (→ 0 client-side)")
            (is (not (contains? (by-id a2) :used-as-parent-count))))))
      (testing "scope :namespace with nil namespace-id — the (root) bucket"
        (let [ids (into #{} (map :id) (:fns (entities/list-all-graph-entities c :namespace nil nil nil)))]
          (is (contains? ids r1)
              "the namespace-less named fn is addressable as the root bucket")
          (is (not (contains? ids a1)) "namespaced fns are not in the root bucket")
          (is (not (contains? ids anon)) "the anonymous fn is excluded")))
      (testing "scope :search — capped, case-insensitive name-substring"
        (let [dump (entities/list-all-graph-entities c :search nil nil "widget")]
          (is (= #{a1 b1} (into #{} (map :id) (:fns dump)))
              "both *-widget fns match across namespaces")
          (is (false? (:truncated? dump)) "well under the cap")
          (is (every? #(every? light-fn-keys (keys %)) (:fns dump))
              "search rows share the light projection")))
      (testing "scope :search is case-insensitive and matches raw name"
        (is (= #{a1 a2}
               (into #{} (map :id)
                     (:fns (entities/list-all-graph-entities c :search nil nil "ALPHA"))))))
      (testing "scope :search-text adds description matches, ranked after name hits"
        (let [desc (java.util.UUID/randomUUID)]
          (sp/create-entity storage :fn {:id desc :name "opaque-name"
                                         :description "Frobnicates a widget quietly"})
          (ctx/invalidate-graph-cache! c)
          (is (not (contains? (into #{} (map :id)
                                    (:fns (entities/list-all-graph-entities c :search nil nil "widget")))
                              desc))
              "the name-only :search scope (sidebar filter) does not match descriptions")
          (let [dump (entities/list-all-graph-entities c :search-text nil nil "widget")]
            (is (contains? (into #{} (map :id) (:fns dump)) desc)
                "a description-only match is found")
            (is (= desc (:id (last (:fns dump))))
                "…but ranked after every name match"))
          (sp/delete-entity storage :fn desc)
          (ctx/invalidate-graph-cache! c)))
      (testing "scope :search with blank / nil q — no matches, not truncated"
        (let [dump (entities/list-all-graph-entities c :search nil nil "   ")]
          (is (empty? (:fns dump)))
          (is (false? (:truncated? dump)))))
      (testing "scope :search caps at the limit and flags :truncated?"
        ;; Bind the (dynamic) private cap low rather than seed 200+ rows.
        (binding [entity-list/*default-search-limit* 1]
          (let [dump (entities/list-all-graph-entities c :search nil nil "widget")]
            (is (= 1 (count (:fns dump))) "result capped at the limit")
            (is (true? (:truncated? dump)) "more matched than were returned"))))
      (testing "scope :view — smart-view rule tokens over the graph"
        ;; A grandchild extends child-of-a1, so `uses:` must walk
        ;; TRANSITIVELY, not one hop.
        (let [grand (java.util.UUID/randomUUID)]
          (sp/create-entity storage :fn {:id grand :name "grand-of-a1"
                                         :parent-ids [child]})
          (ctx/invalidate-graph-cache! c)
          (testing "uses:<bare name> — the reverse transitive closure"
            (is (= #{child grand}
                   (into #{} (map :id)
                         (:fns (entities/list-all-graph-entities
                                 c :view nil nil "uses:alpha-widget"))))
                "the child extending a1 AND the grandchild through it"))
          (testing "uses:<qualified name> resolves through the ns path"
            (is (= #{child grand}
                   (into #{} (map :id)
                         (:fns (entities/list-all-graph-entities
                                 c :view nil nil "uses:alpha.alpha-widget"))))))
          (testing "uses: through a ref binding, not only parent-ids"
            (is (contains? (into #{} (map :id)
                                 (:fns (entities/list-all-graph-entities
                                         c :view nil nil "uses:alpha-gadget")))
                           child)
                "the fn holding the ref binding counts as a user"))
          (testing "rules AND-combine"
            (is (= #{child}
                   (into #{} (map :id)
                         (:fns (entities/list-all-graph-entities
                                 c :view nil nil "uses:alpha-widget name:child"))))))
          (testing "bare token = name substring; unknown target = empty view"
            (is (= #{a1 b1}
                   (into #{} (map :id)
                         (:fns (entities/list-all-graph-entities
                                 c :view nil nil "widget")))))
            (is (empty? (:fns (entities/list-all-graph-entities
                                c :view nil nil "uses:no-such-fn")))))
          (testing "blank rule string is an empty view, not everything"
            (is (empty? (:fns (entities/list-all-graph-entities
                                c :view nil nil "  ")))))
          (testing "ns:<path> — the fn's namespace, or anything under it"
            (is (= #{a1 a2}
                   (into #{} (map :id)
                         (:fns (entities/list-all-graph-entities
                                 c :view nil nil "ns:alpha"))))
                "named fns of :alpha (anon excluded); :beta's stay out")
            (is (empty? (:fns (entities/list-all-graph-entities
                                c :view nil nil "ns:al")))
                "a namespace PREFIX is not a match — segments only"))
          (testing "unused:true — the dead-code view"
            (is (= #{bcomp}
                   (into #{} (map :id)
                         (:fns (entities/list-all-graph-entities
                                 c :view nil nil "unused:true ns:beta"))))
                "beta-composed has no users; beta-widget is extended by it")
            (is (empty? (:fns (entities/list-all-graph-entities
                                c :view nil nil "unused:true ns:alpha")))
                "both alpha fns are used (parented / ref'd) — not dead")
            (is (empty? (:fns (entities/list-all-graph-entities
                                c :view nil nil "unused:banana")))
                "a non-true value matches nothing, not everything"))))
      (finally (sp/close storage)))))


;; ============================================================================
;; resolve-sequence-payload
;; ============================================================================

(deftest resolve-sequence-payload-test
  (let [storage (setup/create-test-storage)]
    (try
      (testing ":ref → ref-fn-id"
        (let [u (random-uuid)]
          (is (= {:ref-fn-id u}
                 (entities/resolve-sequence-payload storage {:ref (str u)})))))

      (testing "a malformed :ref throws a MAPPED 400, not a bare UUID exception"
        ;; Untrusted client JSON — `UUID/fromString` on a non-UUID used to
        ;; leak an IllegalArgumentException up as a 500. Now it's a typed
        ;; :validation-error (family-mapped to 400 in web.errors).
        (let [ex (try (entities/resolve-sequence-payload storage {:ref "not-a-uuid"})
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo ex))
          (is (= :validation-error/invalid-uuid (:type (ex-data ex)))))
        (is (thrown? clojure.lang.ExceptionInfo
              (entities/resolve-sequence-payload storage {:ref 12345}))
            "a non-string :ref (JSON number) is rejected too, not NPE/500"))

      (testing ":value — plain literal, and the keyword-literal wire form"
        (is (= {:value 7} (entities/resolve-sequence-payload storage {:value 7})))
        (is (= {:value :kw :literal true}
               (entities/resolve-sequence-payload storage {:value ":kw"}))))

      (testing ":ref-name resolves through storage; unknown name throws"
        (let [f (setup/create-base-fn! storage "rsp-target")]
          (is (= {:ref-fn-id (:id f)}
                 (entities/resolve-sequence-payload storage {:ref-name "rsp-target"})))
          (is (thrown? clojure.lang.ExceptionInfo
                (entities/resolve-sequence-payload
                  storage {:ref-name "rsp-missing"})))))

      (testing "a body with none of :ref / :ref-name / :value throws"
        (is (thrown? clojure.lang.ExceptionInfo
              (entities/resolve-sequence-payload storage {}))))
      (finally (sp/close storage)))))


;; ============================================================================
;; ensure-rename-slot!
;; ============================================================================

(deftest ensure-rename-slot-test
  (let [storage (setup/create-test-storage)]
    (try
      (testing "a composed fn gets a renamed-view slot linked to the source slot"
        (let [parent   (setup/create-base-fn! storage "ers-parent")
              src-slot (setup/create-slot! storage "orig" :int)
              _        (setup/attach-slot! storage (:id parent) (:id src-slot) 0)
              child    (setup/create-composed-fn! storage "ers-child" (:id parent))]
          (entities/ensure-rename-slot! storage (:id child) (:id src-slot) "renamed")
          (let [renamed (->> (sp/query-entities storage :slot {})
                             (filter #(= "renamed" (:name %)))
                             first)]
            (is (some? renamed))
            (is (= (:id src-slot) (:source-slot-id renamed))))
          ;; Idempotent — a second identical call must not throw.
          (is (nil? (entities/ensure-rename-slot!
                      storage (:id child) (:id src-slot) "renamed")))))

      (testing "blank rename-to / base-fn owner → no-op"
        (let [base (setup/create-base-fn! storage "ers-base")
              s    (setup/create-slot! storage "x" :int)]
          (is (nil? (entities/ensure-rename-slot! storage (:id base) (:id s) "")))
          (is (nil? (entities/ensure-rename-slot! storage (:id base) (:id s) "nope")))
          (is (empty? (->> (sp/query-entities storage :slot {})
                           (filter #(= "nope" (:name %))))))))
      (finally (sp/close storage)))))


(deftest binding-create-handler-forwards-rename-to-a-view-slot
  ;; Regression: the edge-label arg rename works end-to-end. The rename
  ;; side-effect reads `:rename-to` from the RAW form-data — NOT the binding
  ;; parser (which correctly omits the retired `:rename-to` column) — so
  ;; `apply-create-core` must forward it to `ensure-rename-slot!`, minting a
  ;; rename-view `:slot` with `:source-slot-id`. (A prior audit flagged this as
  ;; broken by tracing only the parser path; this pins the real handler chain.)
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (let [parent   (setup/create-base-fn! storage "brh-parent")
            src-slot (setup/create-slot! storage "orig" :int)
            _        (setup/attach-slot! storage (:id parent) (:id src-slot) 0)
            child    (setup/create-composed-fn! storage "brh-child" (:id parent))
            res      (entities/apply-create-core
                       {:entity-type :binding :type-str "binding"
                        :form-data {:rename-to "renamed"}
                        :entity-data {:fn-id (:id child) :slot-id (:id src-slot)}}
                       c)]
        (testing "the binding is created and the rename-view slot is forwarded"
          (is (some? (:created res)))
          (let [renamed (->> (sp/query-entities storage :slot {})
                             (filter #(= "renamed" (:name %))) first)]
            (is (some? renamed) "a rename-view slot was created through the handler")
            (is (= (:id src-slot) (:source-slot-id renamed))))))
      (finally (sp/close storage)))))


(deftest create-entity-vault-put-capability-gate-test
  ;; Followup-A6: any direct attempt to create a fn-def with
  ;; `parent-ids` touching one of the admin-only WRITE vault
  ;; base-fns (`:vault-put`, `:vault-delete`,
  ;; `:vault-metadata-put`) is refused by the same gate that
  ;; covers `:vault-get` / `:secret-leaf`. Read-only
  ;; `:vault-metadata-get` is NOT gated — metadata isn't a
  ;; secret value.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        ;; Seed every admin-only base-fn name so the gate's
        ;; registry-lookup finds at least one matching row.
        vp (setup/create-base-fn! storage "vault-put" :null)
        vd (setup/create-base-fn! storage "vault-delete" :null)
        vmp (setup/create-base-fn! storage "vault-metadata-put" :null)
        vmg (setup/create-base-fn! storage "vault-metadata-get" :jsonb)
        ;; Production gets the `:admin-only-vault` tag from
        ;; `web/vault/fns.edn`'s `:tags` field; stub each tagged
        ;; base-fn's rich-type so `find-admin-only-vault-base-fn-ids`
        ;; resolves the seeded rows. `:vault-metadata-get` is
        ;; deliberately untagged — it's the read-only carve-out.
        _ (doseq [n [:vault-put :vault-delete :vault-metadata-put]]
            (registry/record-rich-types!
              n {:return :null :args {} :tags #{:admin-only-vault}}))]
    (try
      (testing "parent :vault-put → rejected"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"admin-only vault base-fn"
              (entities/create-entity
                :fn {:name "_via-put" :parent-ids [(:id vp)]} c))))

      (testing "parent :vault-delete → rejected"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"admin-only vault base-fn"
              (entities/create-entity
                :fn {:name "_via-delete" :parent-ids [(:id vd)]} c))))

      (testing "parent :vault-metadata-put → rejected"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"admin-only vault base-fn"
              (entities/create-entity
                :fn {:name "_via-meta-put" :parent-ids [(:id vmp)]} c))))

      (testing "parent :vault-metadata-get → ALLOWED (read-only)"
        (is (some? (entities/create-entity
                     :fn {:name "_via-meta-get" :parent-ids [(:id vmg)]} c))))

      (testing "admin marker bypasses all gated parents (parity with secret-create path)"
        (is (some? (entities/create-entity
                     :fn {:name "_admin-put-call"
                          :parent-ids [(:id vp)]
                          :_admin-secret-create true}
                     c))))
      (finally (sp/close storage)))))


(deftest create-entity-secret-fn-capability-gate-test
  ;; Any direct attempt to create a fn-def with
  ;; `parent-ids=[<secret-leaf>]` through the generic
  ;; `entities/create-entity` is refused unless the caller sets the
  ;; in-memory `:_admin-secret-create` marker. The admin path
  ;; (`crud.secrets/create-secret`) sets the marker; user-facing
  ;; endpoints (`/api/entities/fn` form-post, ad-hoc API clients)
  ;; never do. The marker is also stripped before the row reaches
  ;; storage — verified via the read-back row.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)
        sl (setup/create-base-fn! storage "secret-leaf" :text)
        ;; Same as the vault-put gate test: register the tag so the
        ;; gate's registry-lookup finds the seeded row. Production
        ;; gets these from `web/vault/fns.edn`.
        _ (registry/record-rich-types!
            :secret-leaf {:return :text :args {}
                          :tags #{:secret-shape :admin-only-vault}})]
    (try
      (testing "no marker → :capability/secret-leaf-restricted"
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"created via POST /api/secrets"
              (entities/create-entity
                :fn
                {:name "_blocked" :parent-ids [(:id sl)]}
                c))))

      (testing "admin marker → succeeds AND :_admin-secret-create is stripped"
        (let [row (entities/create-entity
                    :fn
                    {:name "_allowed"
                     :parent-ids [(:id sl)]
                     :_admin-secret-create true}
                    c)
              persisted (sp/read-entity storage :fn (:id row))]
          (is (some? persisted))
          (is (= "_allowed" (:name persisted)))
          (is (not (contains? persisted :_admin-secret-create))
              ":_admin-secret-create must never persist to storage")))

      (testing "non-secret-leaf fn-defs are unaffected"
        (let [other (setup/create-base-fn! storage "other-base" :text)]
          (is (some? (entities/create-entity
                       :fn
                       {:name "_normal" :parent-ids [(:id other)]}
                       c)))))

      (testing "F2: the UPDATE path is gated too — a plain fn PUT to
                re-parent onto a gated base-fn is refused"
        (let [other (setup/create-base-fn! storage "other-base-2" :text)
              plain (entities/create-entity
                      :fn {:name "_plain-to-reparent" :parent-ids [(:id other)]} c)]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo #"created via POST /api/secrets"
                (entities/update-entity :fn (:id plain) {:parent-ids [(:id sl)]} c)))
          (testing "an update that doesn't touch :parent-ids is unaffected"
            (is (some? (entities/update-entity :fn (:id plain) {:description "note"} c))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; tighten-fn-type-impl! — fn-typed binding narrowing
;;
;; Each test constructs a slot whose effective type is a callable fn-row
;; (`:constraint [:fn args ret eff]`), a binding pointing at that slot,
;; and exercises one branch of the impl's `cond` chain. Tests that need
;; a referenced fn in the rich-types registry register it for REAL via
;; `record-rich-types-raw!` (thread-isolated — see the ns docstring).
;; ============================================================================

(defn- make-callable-type-fn!
  "Insert a fn-row whose `:constraint` is the requested fn-type shape.
   Returns the new fn-id. Used as the `:type-fn-id` of a slot so the
   slot's effective type IS that constraint."
  [storage type-name constraint]
  (:id (sp/create-entity storage :fn
                         {:name type-name
                          :parent-ids []
                          :constraint constraint})))


(defn- make-binding-on-fn-typed-slot!
  "Build a composed-fn + an fn-typed slot + the binding row pointing
   at the slot. Returns `[binding-id comp-fn-id base-fn-id]` so
   tighten-fn-type-impl! can target the binding and tests can extend
   the parent (`th-base-<suffix>`) with more slots."
  [storage suffix constraint]
  (let [cb-fn-id (make-callable-type-fn! storage (str "th-cb-" suffix) constraint)
        base-fn (sp/create-entity storage :fn
                                  {:name (str "th-base-" suffix)
                                   :parent-ids []})
        slot   (sp/create-entity storage :slot
                                 {:name "cb" :type-fn-id cb-fn-id})
        _      (sp/create-entity storage :fn-slot
                                 {:fn-id (:id base-fn)
                                  :slot-id (:id slot) :position 0})
        comp-fn (sp/create-entity storage :fn
                                  {:name (str "th-f-" suffix)
                                   :parent-ids [(:id base-fn)]})
        bnd (sp/create-entity storage :binding
                              {:fn-id (:id comp-fn)
                               :slot-id (:id slot)
                               :value nil})]
    [(:id bnd) (:id comp-fn) (:id base-fn)]))


(deftest tighten-fn-type-impl-missing-binding-404
  (let [storage (setup/create-test-storage)]
    (try
      (let [r (entities/tighten-fn-type-impl! storage (random-uuid)
                                              {:effects ["io"]})]
        (is (= 404 (:status r)))
        (is (re-find #"not found" (:reason r))))
      (finally (sp/close storage)))))


(deftest tighten-fn-type-impl-non-fn-type-400
  ;; Slot's effective type is a plain `:int` (not [:fn ...]) — can't
  ;; tighten because there's nothing fn-shaped to narrow.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base    (setup/create-base-fn! storage "tnf-base")
            slot    (setup/create-slot! storage "x" :int)
            _       (setup/attach-slot! storage (:id base) (:id slot) 0)
            comp-fn (setup/create-composed-fn! storage "tnf-f" (:id base))
            bnd     (sp/create-entity storage :binding
                                      {:fn-id (:id comp-fn) :slot-id (:id slot)
                                       :value 1})
            r (entities/tighten-fn-type-impl! storage (:id bnd)
                                              {:effects ["io"]})]
        (is (= 400 (:status r)))
        (is (re-find #"not an fn-type" (:reason r))))
      (finally (sp/close storage)))))


(deftest tighten-fn-type-impl-rejects-widening
  ;; Current effective type is [:fn {} :any #{:io}]. Asking for #{:io :db}
  ;; would widen → reject.
  (let [storage (setup/create-test-storage)]
    (try
      (let [[bid] (make-binding-on-fn-typed-slot!
                    storage "widen" [:fn {} :any #{:io}])
            r (entities/tighten-fn-type-impl! storage bid {:effects ["io" "db"]})]
        (is (= 400 (:status r)))
        (is (re-find #"not a narrowing" (:reason r))))
      (finally (sp/close storage)))))


(deftest tighten-fn-type-impl-happy-effects-narrowing
  ;; Current type [:fn {} :any #{:io :db}]; tighten to #{:io}.
  ;; No ref-fn-id on the binding → bound-callable check trivially
  ;; passes. The post-write type-check on the owning fn also passes
  ;; because there's nothing for it to complain about.
  (let [storage (setup/create-test-storage)]
    (try
      (let [[bid] (make-binding-on-fn-typed-slot!
                    storage "narrow" [:fn {} :any #{:io :db}])
            r (entities/tighten-fn-type-impl! storage bid {:effects ["io"]})]
        (is (= 200 (:status r)))
        (is (some? (-> r :result :type-override-fn-id))
            "binding's :type-override-fn-id now points at the new constraint fn"))
      (finally (sp/close storage)))))


(deftest tighten-effects-impl-thin-wrapper-test
  ;; tighten-effects-impl! just forwards to tighten-fn-type-impl! with
  ;; only :effects filled in — covers the wrapper line.
  (let [storage (setup/create-test-storage)]
    (try
      (let [[bid] (make-binding-on-fn-typed-slot!
                    storage "wrap" [:fn {} :any #{:io :env}])
            r (entities/tighten-effects-impl! storage bid ["io"])]
        (is (= 200 (:status r))))
      (finally (sp/close storage)))))


;; ============================================================================
;; tighten — extended coverage: post-write warn path, bound-callable escape
;;
;; The basic happy/reject branches of `tighten-fn-type-impl!` were
;; covered above; these tests close the remaining branches:
;;   - commit-tighten! keep-and-warn path (post-write type-check fails —
;;     error-tolerance Phase 2 keeps the override + records a diagnostic)
;;   - bound-callable effect escape (ref-fn effects exceed new constraint)
;; ============================================================================

(deftest commit-tighten-keeps-write-and-warns-on-post-write-type-check-fail-test
  ;; Make the REAL post-write `type-check-fn-after-mutation!` fail: the
  ;; owning fn carries a second binding whose literal violates its
  ;; `:int` slot, and the parent's signature is registered for real in
  ;; the (thread-isolated) rich-types registry so `check-fn-def!` has a
  ;; contract to check against. The tighten pre-checks never look at
  ;; that slot, so the write commits; the aggregate check then FAILS —
  ;; and since error-tolerance Phase 2 the override is KEPT, the
  ;; failure is recorded as a per-branch diagnostic, and the 200 result
  ;; carries it as `:type-warnings`.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)]
      (try
        (let [[bid comp-fn-id base-id] (make-binding-on-fn-typed-slot!
                                         storage "rb" [:fn {} :any #{:io :db}])
              nslot (setup/create-slot! storage "n" :int)
              _ (setup/attach-slot! storage base-id (:id nslot) 1)
              _ (setup/bind-value! storage comp-fn-id (:id nslot) "not-an-int")
              _ (registry/record-rich-types-raw!
                  base-id :th-base-rb
                  {:return :any
                   :args {:cb [:fn {} :any #{:io :db}] :n :int}
                   :effects #{}})
              r (entities/tighten-fn-type-impl! storage bid {:effects ["io"]})
              after (sp/read-entity storage :binding bid)]
          (is (= 200 (:status r)))
          (is (some? (get-in r [:result :type-override-fn-id])))
          (is (= (get-in r [:result :type-override-fn-id])
                 (:type-override-fn-id after))
              "the tighten override was KEPT despite the failing aggregate check")
          (is (vector? (get-in r [:result :type-warnings]))
              "the 200 result surfaces the failure as :type-warnings")
          (is (= (get-in r [:result :type-warnings])
                 (diag/errors-for-fn nil comp-fn-id))
              "the same diagnostics landed in the per-branch store"))
        (finally (sp/close storage))))))


(deftest tighten-rejects-when-bound-callable-effects-exceed-new-constraint-test
  ;; Binding has a :ref-fn-id pointing at a fn whose registry
  ;; rich-type declares :io effect. The proposed constraint forbids
  ;; :io → reject with "produces effects … forbids" message.
  (let [storage (setup/create-test-storage)]
    (try
      (let [[bid comp-fn-id] (make-binding-on-fn-typed-slot!
                               storage "esc" [:fn {} :any #{:io :db}])
            ;; A REAL ref-fn-id row registered in rich-types with :io.
            ref-fn (sp/create-entity storage :fn
                                     {:name "esc-effectful"
                                      :parent-ids []})
            _ (sp/update-entity storage :binding bid
                                {:ref-fn-id (:id ref-fn)})
            ;; The tighten path reads the registry by the ref-row's ID
            ;; (`rich-type-of-id`) — register under that identity for
            ;; real (3-arity threads the row id; thread-isolated).
            _ (registry/record-rich-types-raw!
                (:id ref-fn) :esc-effectful
                {:return :any :args {} :effects #{:io}})
            ;; Tighten to {:db} — :io must escape → reject.
            r (entities/tighten-fn-type-impl! storage bid {:effects ["db"]})]
        (is (= 400 (:status r)))
        (is (re-find #"produces effects" (:reason r)))
        (is (re-find #":io" (:reason r))
            "the reject message names the escaping effect")
        ;; Use comp-fn-id so the let-binding isn't dead code.
        (is (some? comp-fn-id)))
      (finally (sp/close storage)))))


;; ============================================================================
;; error-tolerance Phase 2 — a type-breaking USER write is KEPT + warns
;;
;; The core behaviour flip: `apply-create-core` / `apply-update-core`
;; no longer roll back (or reject) a binding write that fails the
;; owning fn's aggregate type-check. The row lands, the failure is
;; recorded in the per-branch diagnostics store, and the success
;; envelope carries `:type-warnings` additively. Structural gates
;; (cycle / MI / terminal / list-closed) are untouched and still
;; hard-reject.
;; ============================================================================

(deftest create-type-breaking-binding-kept-with-warnings-test
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "etp2-base")
              slot  (setup/create-slot! storage "a" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :etp2-base {:return :int :args {:a :int} :effects #{}})
              child (setup/create-composed-fn! storage "etp2-child" (:id base))
              r     (entities/apply-create-core
                      {:entity-type :binding
                       :type-str "binding"
                       :form-data {}
                       ;; `:value-present true` mirrors the form
                       ;; parser's value-presence normalisation.
                       :entity-data {:fn-id (:id child) :slot-id (:id slot)
                                     :value "not-an-int"
                                     :value-present true}}
                      c)]
          (testing "the write SUCCEEDS — row present, no rollback, no :error"
            (is (some? (:created r)))
            (is (nil? (:error r)))
            (is (some? (sp/read-entity storage :binding (:created r)))
                "the type-breaking binding row survived"))
          (testing "the success envelope surfaces the failure additively"
            (is (vector? (:type-warnings r)))
            (is (= :int (get-in r [:type-warnings 0 :expected])))
            (is (= :text (get-in r [:type-warnings 0 :actual]))))
          (testing "the per-branch diagnostics store recorded the same entry"
            (is (= (:type-warnings r)
                   (diag/errors-for-fn nil (:id child)))))
          (testing "fixing the binding via the update core clears everything"
            (let [r2 (entities/apply-update-core
                       {:entity-type :binding
                        :type-str "binding"
                        :id-uuid (:created r)
                        :form-data {}
                        :entity-data {:value 5}}
                       c)]
              (is (= (:created r) (:updated r2)))
              (is (nil? (:type-warnings r2)))
              (is (nil? (diag/errors-for-fn nil (:id child)))))))
        (finally (sp/close storage))))))


(deftest update-type-breaking-binding-kept-with-warnings-test
  ;; The update path had NO post-mutation check before Phase 2 (only
  ;; the now-removed pre-write guard) — this covers the added
  ;; record-after-write: a valid binding updated to a type-breaking
  ;; value still updates, warns, and records.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "etp2u-base")
              slot  (setup/create-slot! storage "a" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :etp2u-base {:return :int :args {:a :int} :effects #{}})
              child (setup/create-composed-fn! storage "etp2u-child" (:id base))
              bind  (setup/bind-value! storage (:id child) (:id slot) 5)
              ;; entity-data deliberately omits :fn-id — the check must
              ;; recover the owner from the row (partial form payload).
              r     (entities/apply-update-core
                      {:entity-type :binding
                       :type-str "binding"
                       :id-uuid (:id bind)
                       :form-data {}
                       :entity-data {:value "nope"}}
                      c)]
          (is (= (:id bind) (:updated r)))
          (is (vector? (:type-warnings r)))
          (is (= (:type-warnings r)
                 (diag/errors-for-fn nil (:id child)))
              "recorded under the owning fn recovered from the binding row"))
        (finally (sp/close storage))))))


(deftest seq-append-type-breaking-item-warns-and-update-fix-clears-test
  ;; Phase 3, Gap A — the sequence-op cores run the same post-write
  ;; aggregate check the binding cores got in Phase 2: a type-breaking
  ;; appended item is KEPT, warned about, and recorded; fixing it via
  ;; the sequence-update core clears the stored diagnostic.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "etp3s-base")
              slot  (setup/create-slot! storage "nums" :any)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :etp3s-base {:return :int :args {:nums [:list :int]} :effects #{}})
              child (setup/create-composed-fn! storage "etp3s-child" (:id base))
              r     (entities/apply-seq-append-core
                      {:fn-id (:id child) :body {:value "not-an-int"}}
                      {:fn-id (:id child) :slot-id (:id slot) :synthetic true}
                      c)]
          (testing "the append SUCCEEDS — item present, no rollback, no :error"
            (is (some? (:created r)))
            (is (nil? (:error r)))
            (is (some? (sp/read-entity storage :binding-list-item (:created r)))
                "the type-breaking item row survived"))
          (testing "the result carries :binding-id — the append success path uses it to
                    route through invalidate! (:invalidate-after-write), which restarts
                    cron/loop services holding the pre-append closure (like update/move)"
            (is (some? (:binding-id r)))
            (is (= (:binding-id r)
                   (:binding-id (sp/read-entity storage :binding-list-item (:created r))))
                "it is the owning binding of the appended item"))
          (testing "the success shape surfaces the failure additively"
            (is (vector? (:type-warnings r))))
          (testing "the per-branch diagnostics store recorded the same entry"
            (is (= (:type-warnings r)
                   (diag/errors-for-fn nil (:id child)))))
          (testing "fixing the item via the sequence-update core clears everything"
            (let [r2 (entities/apply-seq-update-core
                       {:item-id (:created r) :body {:value 5}}
                       (sp/read-entity storage :binding-list-item (:created r))
                       c)]
              (is (= (:created r) (:updated r2)))
              (is (nil? (:type-warnings r2)))
              (is (nil? (diag/errors-for-fn nil (:id child))))))
          (testing "re-break, then DELETE the item — Gap B clears via the list-item path"
            (let [r3 (entities/apply-seq-update-core
                       {:item-id (:created r) :body {:value "broken-again"}}
                       (sp/read-entity storage :binding-list-item (:created r))
                       c)]
              (is (vector? (:type-warnings r3)))
              (entities/delete-entity "binding-list-item" (:created r) c)
              (is (nil? (diag/errors-for-fn nil (:id child)))
                  "item delete re-ran the owner's check through its binding"))))
        (finally (sp/close storage))))))


(deftest seq-insert-at-position-and-move-test
  ;; The optional `:position` body field turns append into INSERT
  ;; (later items shift +1); the move core swaps an item with its
  ;; up/down neighbour through a free temp position. Order is read
  ;; back positionally after every step.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "seqmv-base")
              slot  (setup/create-slot! storage "items" :any)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              child (setup/create-composed-fn! storage "seqmv-child" (:id base))
              synth {:fn-id (:id child) :slot-id (:id slot) :synthetic true}
              ;; The slot is `:any`-typed (not the `:sequence` type-fn),
              ;; so `find-sequence-binding` doesn't apply — resolve the
              ;; materialised binding row directly.
              seq-binding (fn []
                            (or (first (sp/query-entities storage :binding
                                                          {:fn-id (:id child)}))
                                synth))
              append! (fn [body]
                        (entities/apply-seq-append-core
                          {:fn-id (:id child) :body body}
                          (seq-binding)
                          c))
              order (fn []
                      (->> (sp/query-entities storage :binding-list-item
                                              {:binding-id (:id (seq-binding))})
                           (sort-by :position)
                           (mapv :value)))
              _  (append! {:value "a"})
              rb (append! {:value "b"})
              _  (append! {:value "c"})]
          (testing "baseline appends land in order"
            (is (= ["a" "b" "c"] (order))))

          (testing "insert at b's position shifts b and c right"
            (let [r (append! {:value "x" :position 1})]
              (is (nil? (:error r)))
              (is (= 1 (:position r)))
              (is (= ["a" "x" "b" "c"] (order)))))

          (testing "a position past the end clamps to a plain append"
            (let [r (append! {:value "z" :position 99})]
              (is (= 4 (:position r)))
              (is (= ["a" "x" "b" "c" "z"] (order)))))

          (testing "a malformed position is rejected before any write"
            (is (some? (:error (append! {:value "w" :position -1}))))
            (is (some? (:error (append! {:value "w" :position 1.5}))))
            (is (= ["a" "x" "b" "c" "z"] (order))))

          (let [b-id (:created rb)
                b-item #(sp/read-entity storage :binding-list-item b-id)
                move! (fn [dir]
                        (entities/apply-seq-move-core
                          {:item-id b-id :body {:direction dir}}
                          (b-item) c))]
            (testing "move up swaps with the left neighbour"
              (let [r (move! "up")]
                (is (nil? (:error r)))
                (is (= ["a" "b" "x" "c" "z"] (order)))))
            (testing "move down swaps back"
              (move! "down")
              (is (= ["a" "x" "b" "c" "z"] (order))))
            (testing "a move at the edge is a no-op success"
              (move! "up")
              (move! "up")
              (let [r (move! "up")]
                (is (nil? (:error r)))
                (is (= ["b" "a" "x" "c" "z"] (order))
                    "two ups reached the head; the third changed nothing")))
            (testing "a malformed direction is rejected"
              (is (some? (:error (move! "sideways")))))))
        (finally (sp/close storage))))))


(deftest delete-binding-clears-stored-diagnostic-test
  ;; Phase 3, Gap B — deleting the offending binding row re-runs the
  ;; owner's aggregate check, which clears the stored diagnostic (the
  ;; fn is clean again once the bad binding is gone). Also: deleting
  ;; the fn itself drops its entry outright.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "etp3d-base")
              slot  (setup/create-slot! storage "a" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :etp3d-base {:return :int :args {:a :int} :effects #{}})
              child (setup/create-composed-fn! storage "etp3d-child" (:id base))
              r     (entities/apply-create-core
                      {:entity-type :binding
                       :type-str "binding"
                       :form-data {}
                       :entity-data {:fn-id (:id child) :slot-id (:id slot)
                                     :value "not-an-int"
                                     :value-present true}}
                      c)]
          (is (vector? (:type-warnings r)) "precondition: diagnostic recorded")
          (is (some? (diag/errors-for-fn nil (:id child))))
          (testing "deleting the offending binding clears the stored entry"
            (entities/delete-entity "binding" (:created r) c)
            (is (nil? (diag/errors-for-fn nil (:id child)))))
          (testing "a re-broken fn's entry dies with the fn row itself"
            (let [r2 (entities/apply-create-core
                       {:entity-type :binding
                        :type-str "binding"
                        :form-data {}
                        :entity-data {:fn-id (:id child) :slot-id (:id slot)
                                      :value "still-not-an-int"
                                      :value-present true}}
                       c)]
              (is (vector? (:type-warnings r2)))
              (entities/delete-entity "fn" (:id child) c)
              (is (nil? (diag/errors-for-fn nil (:id child)))))))
        (finally (sp/close storage))))))


(deftest structural-gates-still-reject-test
  ;; Phase 2 flipped ONLY the type check. The structural write-rej
  ;; family (dependency cycles here; MI / terminal / list-closed
  ;; covered in `crud/validation_test.clj`) must keep hard-rejecting.
  (let [storage (setup/create-test-storage)]
    (try
      (let [a    (setup/create-base-fn! storage "sg-a")
            b    (setup/create-base-fn! storage "sg-b")
            slot (setup/create-slot! storage "s" :any)
            _    (sp/create-entity storage :binding
                                   {:fn-id (:id a) :slot-id (:id slot)
                                    :ref-fn-id (:id b)})
            rej  (validation/write-rej storage :binding
                                       {:fn-id (:id b) :slot-id (:id slot)
                                        :ref-fn-id (:id a)})]
        (is (some? rej) "closing a ref cycle is still a hard rejection")
        (is (re-find #"[Dd]ependency cycle" (:reason rej))))
      (finally (sp/close storage)))))


;; ============================================================================
;; error-tolerance SECURITY CARVE-OUT — secret-flow violations stay HARD
;;
;; Phase 2 made type failures warn-and-persist; the compensating gate
;; (execute refusal) reads the DERIVED diagnostics store — too thin
;; for a security class. A diagnostic whose types carry the `:secret`
;; marker (laundering a `[:secret …]` flow into a plain slot) must
;; keep the pre-Phase-2 behaviour: write rolled back, `{:error …}`
;; (400 on the wire), NO store record. See docs/SECRETS.md § Flow
;; protection vs Error Tolerance.
;; ============================================================================

(deftest create-secret-laundering-binding-hard-rejected-test
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "etsec-base")
              slot  (setup/create-slot! storage "s" :text)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :etsec-base {:return :int :args {:s :text} :effects #{}})
              ;; A secret-returning ref — same deterministic-id contract
              ;; as the other stubs, so the name-keyed registry entry
              ;; and the row agree on identity.
              leaf  (setup/create-base-fn! storage "etsec-leaf" :text)
              _     (registry/record-rich-types-raw!
                      :etsec-leaf {:return [:secret :text] :args {} :effects #{}})
              child (setup/create-composed-fn! storage "etsec-child" (:id base))
              r     (entities/apply-create-core
                      {:entity-type :binding
                       :type-str "binding"
                       :form-data {}
                       :entity-data {:fn-id (:id child) :slot-id (:id slot)
                                     :ref-fn-id (:id leaf)}}
                      c)]
          (testing "the laundering write is HARD-rejected — {:error}, no :created"
            (is (nil? (:created r)))
            (is (nil? (:type-warnings r)))
            (is (string? (:error r)))
            (is (re-find #"(?i)type-check failed" (:error r))
                "the error message IS the diagnostic message"))
          (testing "the row does not exist (rolled back)"
            (is (empty? (sp/query-entities storage :binding {:fn-id (:id child)}))))
          (testing "NO store diagnostic recorded for the rejected write"
            (is (nil? (diag/errors-for-fn nil (:id child)))))
          (testing "regression pair: an ORDINARY type violation still warns+persists"
            (let [child2 (setup/create-composed-fn! storage "etsec-child2" (:id base))
                  r2 (entities/apply-create-core
                       {:entity-type :binding
                        :type-str "binding"
                        :form-data {}
                        :entity-data {:fn-id (:id child2) :slot-id (:id slot)
                                      :value 42
                                      :value-present true}}
                       c)]
              (is (some? (:created r2)))
              (is (nil? (:error r2)))
              (is (vector? (:type-warnings r2)))
              (is (some? (sp/read-entity storage :binding (:created r2)))
                  "the ordinary type-breaking row survived")
              (is (= (:type-warnings r2)
                     (diag/errors-for-fn nil (:id child2)))
                  "and its diagnostic IS recorded"))))
        (finally (sp/close storage))))))


(deftest update-secret-laundering-binding-hard-rejected-restores-test
  ;; The update core has no row to delete — the carve-out restores the
  ;; touched fields from the pre-update image instead.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "etsecu-base")
              slot  (setup/create-slot! storage "s" :text)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :etsecu-base {:return :int :args {:s :text} :effects #{}})
              leaf  (setup/create-base-fn! storage "etsecu-leaf" :text)
              _     (registry/record-rich-types-raw!
                      :etsecu-leaf {:return [:secret :text] :args {} :effects #{}})
              child (setup/create-composed-fn! storage "etsecu-child" (:id base))
              bind  (setup/bind-value! storage (:id child) (:id slot) "hello")
              r     (entities/apply-update-core
                      {:entity-type :binding
                       :type-str "binding"
                       :id-uuid (:id bind)
                       :form-data {}
                       :entity-data {:ref-fn-id (:id leaf)}}
                      c)]
          (testing "the laundering update is HARD-rejected"
            (is (nil? (:updated r)))
            (is (string? (:error r)))
            (is (re-find #"(?i)type-check failed" (:error r))))
          (testing "the touched field was restored from the pre-image"
            (let [row (sp/read-entity storage :binding (:id bind))]
              (is (nil? (:ref-fn-id row)) "ref-fn-id back to nil")
              (is (= "hello" (:value row)) "original value untouched")))
          (testing "NO store diagnostic recorded"
            (is (nil? (diag/errors-for-fn nil (:id child))))))
        (finally (sp/close storage))))))


(deftest seq-append-secret-laundering-item-hard-rejected-test
  ;; The sequence-append core rolls back BOTH the item and the
  ;; synthetic host binding it materialised for it.
  (binding [diag/*diagnostics-override* (atom {})]
    (let [storage (setup/create-test-storage)
          c (test-ctx storage)]
      (try
        (let [base  (setup/create-base-fn! storage "etsecs-base")
              slot  (setup/create-slot! storage "nums" :any)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :etsecs-base {:return :int :args {:nums [:list :text]}
                                    :effects #{}})
              leaf  (setup/create-base-fn! storage "etsecs-leaf" :text)
              _     (registry/record-rich-types-raw!
                      :etsecs-leaf {:return [:secret :text] :args {} :effects #{}})
              child (setup/create-composed-fn! storage "etsecs-child" (:id base))
              r     (entities/apply-seq-append-core
                      {:fn-id (:id child) :body {:ref (str (:id leaf))}}
                      {:fn-id (:id child) :slot-id (:id slot) :synthetic true}
                      c)]
          (testing "the laundering append is HARD-rejected"
            (is (nil? (:created r)))
            (is (string? (:error r)))
            (is (re-find #"(?i)type-check failed" (:error r))))
          (testing "item AND synthetic host binding rolled back"
            (is (empty? (sp/query-entities storage :binding {:fn-id (:id child)}))))
          (testing "NO store diagnostic recorded"
            (is (nil? (diag/errors-for-fn nil (:id child))))))
        (finally (sp/close storage))))))


;; Security-critical: strip-impl-of hides a fn's composition (parent-ids +
;; bindings) from a viewer without :view-impl, while leaving its signature
;; visible and every non-hidden fn fully intact. A leak here = a tenant
;; seeing a public/shared fn's internals. Pure — no DB.
(deftest strip-impl-of-hides-only-flagged-fns
  (let [graph {:fns        [{:id "A" :parent-ids ["P"] :name "a"}
                            {:id "B" :parent-ids ["Q"] :name "b"}]
               :bindings   [{:id "bA" :fn-id "A" :value 1}
                            {:id "bB" :fn-id "B" :value 2}]
               :list-items [{:binding-id "bA" :position 0}
                            {:binding-id "bB" :position 0}]
               :fn-slots   [{:fn-id "A" :slot-id "s"}]
               :slots      [{:id "s" :name "in"}]
               :namespaces [{:id "n"}]}
        fn-by (fn [r id] (first (filter #(= id (:id %)) (:fns r))))]
    (testing "a hidden fn loses parent-ids + bindings + list-items"
      (let [r (entities/strip-impl-of graph #{"A"})]
        (is (= [] (:parent-ids (fn-by r "A"))) "hidden fn parent-ids blanked")
        (is (= ["Q"] (:parent-ids (fn-by r "B"))) "visible fn parent-ids intact")
        (is (= #{"bB"} (into #{} (map :id) (:bindings r)))
            "only the hidden fn's bindings dropped")
        (is (= #{"bB"} (into #{} (map :binding-id) (:list-items r)))
            "the hidden fn's list-items dropped with its binding")))
    (testing "the SIGNATURE (fn-slots / slots) and discoverability survive"
      (let [r (entities/strip-impl-of graph #{"A"})]
        (is (= (:fn-slots graph) (:fn-slots r)) "fn-slots untouched")
        (is (= (:slots graph) (:slots r)) "slots untouched")
        (is (= 2 (count (:fns r))) "both fns still present — discoverable")))
    (testing "empty hidden-set is identity"
      (is (= graph (entities/strip-impl-of graph #{}))))
    (testing "a dump with no :fns (tree / namespace scope) is a graceful no-op"
      (is (= {:namespaces [] :counts {}}
             (entities/strip-impl-of {:namespaces [] :counts {}} #{"A"}))))))
