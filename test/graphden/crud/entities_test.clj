(ns graphden.crud.entities-test
  "DB-backed tests for `graphden.crud.entities` — the heavy CRUD logic
   behind the web/crud base functions: form parsers, generic
   create/read/update/delete, the compound type-row endpoints, the
   delete-guard reasons, and the HTTP `process-*` dispatchers.

   Uses the shared container plus a real `ExecutionContext` so the
   `invalidate!` path exercises against live storage."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.type-check :as tc]
    [graphden.executor.context :as ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


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
                                      :value 1 :override-kind :fixed})]
          (is (= #{(:id f)}
                 (entities/affected-fn-ids storage :binding-list-item
                                           {:binding-id (:id b)})))))

      (testing ":slot / :ns are cross-cutting → nil (caller does a full clear)"
        (is (nil? (entities/affected-fn-ids storage :slot {:id (random-uuid)})))
        (is (nil? (entities/affected-fn-ids storage :ns {:id (random-uuid)}))))
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
                              :impl-hash nil
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

      (testing "delete-entity :fn skips the pre-read fast-path"
        ;; The `:fn` arm of delete-entity synthesizes `{:id id}` instead
        ;; of pre-reading the row (lines 135-137). The behaviour is
        ;; invisible without an actual :fn delete — this test pins it.
        (let [fn-row (setup/create-base-fn! storage "delete-fast-path")
              ;; No exception before the delete; the snapshot is the
              ;; synthesized map, not a DB read.
              ok? (entities/delete-entity "fn" (:id fn-row) c)]
          (is (true? ok?))
          (is (nil? (sp/read-entity storage :fn (:id fn-row))))))
      (finally (sp/close storage)))))


(deftest apply-create-record-type-rollback-test
  ;; Pins the cleanup path at lines 208-210, 251-256 — a mid-create
  ;; failure (unknown type-ref on field 2) must roll back the :fn row
  ;; created on line 212. Without the cleanup, the orphan :fn would
  ;; persist and the response code would still report :ok false.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "unknown field type triggers rollback of every prior write"
        (let [;; Snapshot the :fn count before the attempt so we can prove
              ;; the rollback put us back exactly.
              before (count (sp/query-entities storage :fn {}))
              resp (entities/apply-create-record-type
                     {:name "rollback-record"
                      :ns-id nil
                      :description "first field is :int (ok), second references an unknown type → rollback"
                      :fields [{:name "ok-field" :type "int"}
                               {:name "bad-field" :type "no-such-type-row"}]}
                     c)
              after (count (sp/query-entities storage :fn {}))]
          (is (false? (:ok resp))
              "compound-create returns {:ok false …} when any field fails")
          (is (string? (:error resp)))
          (is (= before after)
              "rollback restored the :fn count — no orphan rows")
          (is (empty? (sp/query-entities storage :fn {:name "rollback-record"}))
              "the named row is gone after rollback")))
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
      (finally (sp/close storage)))))


;; ============================================================================
;; Delete-guard reasons
;; ============================================================================

(deftest ns-non-empty-reason-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "empty namespace → nil; namespace with a fn inside → reason"
        (let [ns-row (entities/create-entity "ns" {:name "guard-ns"} c)]
          (is (nil? (entities/ns-non-empty-reason storage (:id ns-row))))
          (sp/create-entity storage :fn
                            {:name "child-of-ns" :parent-ids []
                             :impl-hash "h" :namespace-id (:id ns-row)})
          (is (re-find #"contains"
                       (entities/ns-non-empty-reason storage (:id ns-row))))))
      (finally (sp/close storage)))))


(deftest fn-in-use-reason-test
  (let [storage (setup/create-test-storage)]
    (try
      (testing "unreferenced fn → nil; fn used as a parent → reason"
        (let [parent (setup/create-base-fn! storage "fiu-parent")
              child  (setup/create-composed-fn! storage "fiu-child" (:id parent))]
          (is (nil? (entities/fn-in-use-reason storage (:id child))))
          (is (re-find #"parent of"
                       (entities/fn-in-use-reason storage (:id parent))))))
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
      (finally (sp/close storage)))))


;; ============================================================================
;; tighten-fn-type-impl! — fn-typed binding narrowing
;;
;; Each test constructs a slot whose effective type is a callable fn-row
;; (`:constraint [:fn args ret eff]`), a binding pointing at that slot,
;; and exercises one branch of the impl's `cond` chain. The bound-callable
;; effect-escape check needs a referenced fn registered in the rich-types
;; registry, so those tests redef registry/rich-type-of to control it.
;; ============================================================================

(defn- make-callable-type-fn!
  "Insert a fn-row whose `:constraint` is the requested fn-type shape.
   Returns the new fn-id. Used as the `:type-fn-id` of a slot so the
   slot's effective type IS that constraint."
  [storage type-name constraint]
  (:id (sp/create-entity storage :fn
                         {:name type-name
                          :parent-ids []
                          :impl-hash nil
                          :constraint constraint})))


(defn- make-binding-on-fn-typed-slot!
  "Build a composed-fn + an fn-typed slot + the binding row pointing
   at the slot. Returns the binding-id so tighten-fn-type-impl! can
   target it."
  [storage suffix constraint]
  (let [cb-fn-id (make-callable-type-fn! storage (str "th-cb-" suffix) constraint)
        base-fn (sp/create-entity storage :fn
                                  {:name (str "th-base-" suffix)
                                   :parent-ids []
                                   :impl-hash "stub"})
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
                               :value nil
                               :override-kind :fixed})]
    [(:id bnd) (:id comp-fn)]))


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
                                       :value 1 :override-kind :fixed})
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
;; tighten — extended coverage: rollback, bound-callable escape, apply-tighten
;;
;; The basic happy/reject branches of `tighten-fn-type-impl!` were
;; covered above; these tests close the remaining branches:
;;   - commit-tighten! rollback path (post-write type-check fails)
;;   - bound-callable effect escape (ref-fn effects exceed new constraint)
;;   - apply-tighten end-to-end (json envelope + invalidate! call)
;; ============================================================================

(deftest commit-tighten-rollback-on-post-write-type-check-fail-test
  ;; Stub `type-check-fn-after-mutation!` to always reject so the
  ;; post-commit roll-back path fires — the binding's
  ;; `:type-override-fn-id` must end up back at its pre-tighten value.
  (let [storage (setup/create-test-storage)]
    (try
      (let [[bid] (make-binding-on-fn-typed-slot!
                    storage "rb" [:fn {} :any #{:io :db}])
            before (sp/read-entity storage :binding bid)
            r (with-redefs [tc/type-check-fn-after-mutation!
                            (fn [_ _] {:reason "synthetic rejection"})]
                (entities/tighten-fn-type-impl! storage bid {:effects ["io"]}))
            after (sp/read-entity storage :binding bid)]
        (is (= 400 (:status r)))
        (is (re-find #"post-write type-check" (:reason r)))
        (is (= (:type-override-fn-id before)
               (:type-override-fn-id after))
            "rollback restored the pre-tighten override pointer"))
      (finally (sp/close storage)))))


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
                                      :parent-ids []
                                      :impl-hash "stub"})
            _ (sp/update-entity storage :binding bid
                                {:ref-fn-id (:id ref-fn)})
            r (with-redefs [registry/rich-type-of
                            (fn [n]
                              (when (= :esc-effectful n)
                                {:effects #{:io}}))]
                ;; Tighten to {:db} — :io must escape → reject.
                (entities/tighten-fn-type-impl! storage bid {:effects ["db"]}))]
        (is (= 400 (:status r)))
        (is (re-find #"produces effects" (:reason r)))
        (is (re-find #":io" (:reason r))
            "the reject message names the escaping effect")
        ;; Use comp-fn-id so the let-binding isn't dead code.
        (is (some? comp-fn-id)))
      (finally (sp/close storage)))))


(deftest apply-tighten-end-to-end-success-test
  ;; Wire apply-tighten (the Ring-shaped wrapper) end-to-end and
  ;; assert the JSON envelope + invalidate! call. invalidate! is
  ;; exercised against a real ExecutionContext so the cache-poke
  ;; path doesn't crash.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (let [[bid comp-fn-id] (make-binding-on-fn-typed-slot!
                               storage "app" [:fn {} :any #{:io :db}])
            resp (entities/apply-tighten
                   {:binding-id bid :delta {:effects ["io"]}}
                   c)]
        (is (= 200 (:status resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"])))
        (let [body (cheshire/parse-string (:body resp) true)]
          (is (= (str comp-fn-id) (:fn-id body)))
          (is (some? (:type-override-fn-id body)))
          (is (= ["fn" {} "any" ["io"]] (:constraint body))
              "constraint round-trips as JSON arrays of stringified keywords")))
      (finally (sp/close storage)))))


(deftest apply-tighten-propagates-reject-as-error-body-test
  ;; When tighten-fn-type-impl! returns a non-200, apply-tighten
  ;; wraps the reason in an HTML error fragment + propagates the
  ;; status. Covers the `else` branch of apply-tighten's `if`.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (let [resp (entities/apply-tighten
                   {:binding-id (random-uuid) :delta {:effects ["io"]}}
                   c)]
        (is (= 404 (:status resp)))
        (is (re-find #"error" (:body resp)))
        (is (re-find #"not found" (:body resp))))
      (finally (sp/close storage)))))
