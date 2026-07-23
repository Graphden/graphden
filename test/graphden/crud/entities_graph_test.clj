(ns ^:integration graphden.crud.entities-graph-test
  "Graph-path tests for the entity CRUD HTTP handlers
   (`:process-create-entity` / `:process-update-entity` /
   `:process-delete-entity`) — exercising the graph path production
   actually reaches, not the Clojure helpers.

   These tests bootstrap the full `[core web app]` package set once
   per JVM (via `setup/bootstrap-crud-graph!`) and invoke
   `:process-create-entity` &c. through `cr/execute`, the same code
   path the `:create-entity-handler` Ring handler reaches in
   production. Storage is shared across deftests in this ns — each
   test minimises collision by using `random-uuid` suffixes for the
   entities it creates."
  (:require
    [cheshire.core :as cheshire]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


;; ============================================================================
;; Bootstrap fixture — heavy, once per JVM
;; ============================================================================

(def ^:dynamic *graph* nil)


(defn- graph-fixture
  "Wraps the heavy bootstrap in `with-clean-registry` so the ~190
   base-fn impls registered here land in a thread-local override
   atom (`*registry-override*`) instead of the process-global
   registry. Sibling test ns'es running in parallel kaocha threads
   keep their own override atoms — no global pollution, no cross-test
   leak via the shared registry."
  [t]
  (exec/with-clean-registry
    #(let [graph (setup/bootstrap-crud-graph-from-golden!)
           storage (:storage graph)]
       (try
         (binding [*graph* graph]
           (t))
         (finally (sp/close storage))))))


(use-fixtures :once
  (setup/create-container-fixture)
  graph-fixture)


(defn- entity-ctx
  "ExecutionContext over the bootstrap storage — used by direct
   `entities/get-entity` / `entities/create-entity` calls for setup
   and verification. Distinct from `*graph* :ctx` (same storage,
   minimal :base-fns map) to mirror the existing `test-ctx` helper
   the per-test storage tests use."
  []
  (ctx/create-context {:storage (:storage *graph*) :base-fns {}}))


(defn- uniq
  "Random-uuid-suffixed name. Storage is shared across deftests in this
   ns; unique names prevent cross-test collisions on `UNIQUE(name)`."
  [stem]
  (str stem "-" (random-uuid)))


(defn- form-req
  "Ring-shaped request for a form-encoded POST/PUT body."
  ([uri body] (form-req uri body :post))
  ([uri body method]
   {:uri uri
    :request-method method
    :body body
    :headers {"content-type" "application/x-www-form-urlencoded"}}))


(defn- json-req
  "Ring-shaped request for a JSON body."
  ([uri body] (json-req uri body :post))
  ([uri body method]
   {:uri uri
    :request-method method
    :body (cheshire/generate-string body)
    :headers {"content-type" "application/json"}}))


(defn- via-create
  [request]
  (setup/via-graph *graph* :process-create-entity request))


(defn- via-update
  [request]
  (setup/via-graph *graph* :process-update-entity request))


(defn- via-delete
  [request]
  (setup/via-graph *graph* :process-delete-entity request))


(defn- via-seq-append
  [request]
  (setup/via-graph *graph* :process-sequence-append request))


(defn- via-seq-remove
  [request]
  (setup/via-graph *graph* :process-sequence-remove request))


(defn- via-seq-update
  [request]
  (setup/via-graph *graph* :process-sequence-update request))


(defn- via-create-record
  [request]
  (setup/via-graph *graph* :process-create-record-type request))


(defn- via-create-list
  [request]
  (setup/via-graph *graph* :process-create-list-type request))


(defn- via-update-record
  [request]
  (setup/via-graph *graph* :process-update-record-type request))


;; ============================================================================
;; process-create-entity
;; ============================================================================

(deftest process-create-entity-test
  (testing "form-encoded ns create → 200 + row persisted"
    (let [ns-name (uniq "proc-ns")
          resp (via-create (form-req "/api/entities/ns" (str "name=" ns-name)))]
      (is (= 200 (:status resp)))
      (is (some #(= ns-name (:name %))
                (sp/query-entities (:storage *graph*) :ns {})))))

  (testing "a request with no usable body → 400"
    (let [resp (via-create {:uri "/api/entities/ns" :request-method :post})]
      (is (= 400 (:status resp))))))


(deftest process-create-entity-fn-test
  (testing "form-encoded composed-fn create → 200 + entityCreated"
    (let [storage (:storage *graph*)
          base-name (uniq "pce-base")
          comp-name (uniq "pce-composed")
          base (setup/create-base-fn! storage base-name)
          resp (via-create (form-req "/api/entities/fn"
                                     (str "name=" comp-name
                                          "&parent-ids=" (:id base))))]
      (is (= 200 (:status resp)))
      (is (some #(= comp-name (:name %))
                (sp/query-entities storage :fn {}))))))


(deftest process-create-entity-binding-test
  (let [storage (:storage *graph*)
        base (setup/create-base-fn! storage (uniq "pceb-base"))
        slot (setup/create-slot! storage "n" :int)
        _    (setup/attach-slot! storage (:id base) (:id slot) 0)
        comp-fn (setup/create-composed-fn! storage (uniq "pceb-comp-fn") (:id base))]
    (testing "form-encoded binding create → 200, binding persisted"
      (let [resp (via-create (form-req "/api/entities/binding"
                                       (str "fn-id=" (:id comp-fn)
                                            "&slot-id=" (:id slot)
                                            "&value=42&override-kind=fixed")))]
        (is (= 200 (:status resp)))
        (is (= 1 (count (sp/query-entities storage :binding
                                           {:fn-id (:id comp-fn)}))))))

    (testing "a binding carrying rename-to also mints the renamed-view slot"
      (let [base2 (setup/create-base-fn! storage (uniq "pceb-base2"))
            slot2 (setup/create-slot! storage "orig" :int)
            _     (setup/attach-slot! storage (:id base2) (:id slot2) 0)
            comp2 (setup/create-composed-fn! storage (uniq "pceb-comp2") (:id base2))
            renamed (uniq "renamed-n")
            resp  (via-create (form-req "/api/entities/binding"
                                        (str "fn-id=" (:id comp2)
                                             "&slot-id=" (:id slot2)
                                             "&value=1&override-kind=fixed"
                                             "&rename-to=" renamed)))]
        (is (= 200 (:status resp)))
        (is (some #(= renamed (:name %))
                  (sp/query-entities storage :slot {})))))))


;; ============================================================================
;; process-update-entity
;; ============================================================================

(deftest process-update-entity-test
  (testing "form-encoded ns update → 200, field applied"
    (let [c (entity-ctx)
          ns-row (entities/create-entity "ns" {:name (uniq "upd-ns")} c)
          resp (via-update (form-req (str "/api/entities/ns/" (:id ns-row))
                                     "description=changed" :put))]
      (is (= 200 (:status resp)))
      (is (= "changed"
             (:description (entities/get-entity "ns" (:id ns-row) c))))))

  (testing "a request with no id segment → 400"
    (let [resp (via-update (form-req "/api/entities/ns" "name=x" :put))]
      (is (= 400 (:status resp))))))


(deftest process-update-entity-fn-test
  (testing "form-encoded fn description update → 200, field applied"
    (let [storage (:storage *graph*)
          f    (setup/create-base-fn! storage (uniq "pue-fn"))
          resp (via-update (form-req (str "/api/entities/fn/" (:id f))
                                     "description=updated-desc" :put))]
      (is (= 200 (:status resp)))
      (is (= "updated-desc"
             (:description (sp/read-entity storage :fn (:id f)))))))

  (testing "form-encoded binding value update → 200, value applied"
    ;; exercises the override_kind enum codec round-trip
    (let [storage (:storage *graph*)
          base    (setup/create-base-fn! storage (uniq "pue-base"))
          slot    (setup/create-slot! storage "n" :int)
          _       (setup/attach-slot! storage (:id base) (:id slot) 0)
          comp-fn (setup/create-composed-fn! storage (uniq "pue-comp") (:id base))
          bind    (sp/create-entity storage :binding
                                    {:fn-id (:id comp-fn) :slot-id (:id slot)
                                     :value 1})
          resp    (via-update (form-req (str "/api/entities/binding/" (:id bind))
                                        "value=99" :put))]
      (is (= 200 (:status resp)))
      (is (= 99 (:value (sp/read-entity storage :binding (:id bind))))))))


(deftest process-update-entity-slot-and-rename-test
  (testing "form-encoded slot update dispatches through parse-slot-from-form"
    (let [storage (:storage *graph*)
          slot (setup/create-slot! storage "n" :int)
          resp (via-update (form-req (str "/api/entities/slot/" (:id slot))
                                     "description=slot-desc" :put))]
      (is (= 200 (:status resp)))
      (is (= "slot-desc"
             (:description (sp/read-entity storage :slot (:id slot)))))))

  (testing "a malformed form (bad UUID) on update → 400, not 500"
    (let [storage (:storage *graph*)
          f (setup/create-base-fn! storage (uniq "puesr-badform"))
          resp (via-update (form-req (str "/api/entities/fn/" (:id f))
                                     "parent-id=not-a-uuid" :put))]
      (is (= 400 (:status resp)))))

  (testing "a binding update carrying rename-to mints the renamed-view slot"
    (let [storage (:storage *graph*)
          base    (setup/create-base-fn! storage (uniq "puesr-base"))
          slot    (setup/create-slot! storage "orig" :int)
          _       (setup/attach-slot! storage (:id base) (:id slot) 0)
          comp-fn (setup/create-composed-fn! storage (uniq "puesr-comp") (:id base))
          bind    (sp/create-entity storage :binding
                                    {:fn-id (:id comp-fn) :slot-id (:id slot)
                                     :value 1})
          renamed (uniq "renamed-orig")
          resp    (via-update (form-req (str "/api/entities/binding/" (:id bind))
                                        (str "value=2&rename-to=" renamed) :put))]
      (is (= 200 (:status resp)))
      (is (some #(= renamed (:name %))
                (sp/query-entities storage :slot {}))))))


;; ============================================================================
;; process-delete-entity
;; ============================================================================

(deftest process-delete-entity-test
  (testing "deleting an empty namespace → 200"
    (let [c (entity-ctx)
          ns-row (entities/create-entity "ns" {:name (uniq "del-ns")} c)
          resp   (via-delete {:uri (str "/api/entities/ns/" (:id ns-row))
                              :request-method :delete})]
      (is (= 200 (:status resp)))
      (is (nil? (entities/get-entity "ns" (:id ns-row) c)))))

  (testing "deleting a fn that is still a parent → 409"
    (let [storage (:storage *graph*)
          parent (setup/create-base-fn! storage (uniq "pde-parent"))
          _      (setup/create-composed-fn! storage (uniq "pde-child") (:id parent))
          resp   (via-delete {:uri (str "/api/entities/fn/" (:id parent))
                              :request-method :delete})]
      (is (= 409 (:status resp)))))

  (testing "a request with no id → 400"
    (let [resp (via-delete {:uri "/api/entities/fn" :request-method :delete})]
      (is (= 400 (:status resp)))))

  (testing "delete on a well-formed but absent fn-id → 404 (regression: was 200 OK, fooling the UI into thinking the row was removed)"
    (let [resp (via-delete {:uri (str "/api/entities/fn/"
                                      "00000000-0000-0000-0000-000000000000")
                            :request-method :delete})]
      (is (= 404 (:status resp)))
      (is (str/includes? (or (:body resp) "")
                         "00000000-0000-0000-0000-000000000000")))))


(deftest process-delete-entity-fn-binding-test
  (testing "deleting an unreferenced fn → 200, row gone"
    (let [storage (:storage *graph*)
          f    (setup/create-base-fn! storage (uniq "pde-free-fn"))
          resp (via-delete {:uri (str "/api/entities/fn/" (:id f))
                            :request-method :delete})]
      (is (= 200 (:status resp)))
      (is (nil? (sp/read-entity storage :fn (:id f))))))

  (testing "deleting a binding → 200, row gone"
    (let [storage (:storage *graph*)
          base (setup/create-base-fn! storage (uniq "pde-bind-base"))
          slot (setup/create-slot! storage "n" :int)
          _    (setup/attach-slot! storage (:id base) (:id slot) 0)
          comp-fn (setup/create-composed-fn! storage (uniq "pde-bind-comp-fn") (:id base))
          bind (sp/create-entity storage :binding
                                 {:fn-id (:id comp-fn) :slot-id (:id slot)
                                  :value 1})
          resp (via-delete {:uri (str "/api/entities/binding/" (:id bind))
                            :request-method :delete})]
      (is (= 200 (:status resp)))
      (is (nil? (sp/read-entity storage :binding (:id bind)))))))


(deftest process-delete-entity-secret-gate-test
  ;; Seed a `secret-leaf` base-fn + a secret-shaped child of it (a
  ;; composed fn whose parent-ids is exactly [secret-leaf-id]).
  ;; NOTE: :secret-leaf is registered by `core` packages bootstrap,
  ;; so we look it up by name rather than re-creating.
  (let [storage (:storage *graph*)
        sl (or (first (sp/query-entities storage :fn {:name "secret-leaf"}))
               (setup/create-base-fn! storage "secret-leaf" :text))
        sec (setup/create-composed-fn! storage (uniq "_secret-pwd") (:id sl))]

    (testing "deleting a secret-shaped fn through generic /api/entities/fn → 409"
      (let [resp (via-delete {:uri (str "/api/entities/fn/" (:id sec))
                              :request-method :delete})]
        (is (= 409 (:status resp)))
        (is (re-find #"DELETE /api/secrets" (:body resp)))
        (testing "row is still there (the guard did NOT delete it)"
          (is (some? (sp/read-entity storage :fn (:id sec)))))))

    (testing "a regular fn-def with no secret-leaf parent still deletes through the generic endpoint"
      (let [free (setup/create-base-fn! storage (uniq "non-secret-fn"))
            resp (via-delete {:uri (str "/api/entities/fn/" (:id free))
                              :request-method :delete})]
        (is (= 200 (:status resp)))
        (is (nil? (sp/read-entity storage :fn (:id free))))))))


(deftest process-delete-entity-ns-cross-branch-test
  ;; A namespace delete is GLOBAL (the `ns` row is non-versioned) and
  ;; there is no DB-level FK on `fn.namespace-id`, so the emptiness guard
  ;; must reject a namespace that still holds a fn live on ANY branch,
  ;; not only the current one — otherwise the delete orphans that fn's
  ;; `:namespace-id` on the other branch. `*graph*` is bound to `main`,
  ;; so `via-delete` runs with `main` as the current branch.
  (let [main-storage (:storage *graph*)
        any-ret (get setup/primitive-fn-ids :any)
        mk-fn! (fn [storage ns-id]
                 (sp/create-entity storage :fn
                                   {:name (uniq "xbr-fn")
                                    :parent-ids nil
                                    :namespace-id ns-id
                                    :return-type-fn-id any-ret}))]

    (testing "ns holding a fn live only on a CHILD branch → 409 (would orphan it)"
      (let [ns-row (entities/create-entity "ns" {:name (uniq "xbr-ns")} (entity-ctx))
            branch (vs/create-branch! main-storage (uniq "xbr-branch"))
            b-storage (vs/switch-branch main-storage (:id branch))
            ;; Create the fn ONLY on branch B, in ns X. On `main` it does
            ;; not resolve, so the old current-branch-only guard let the
            ;; delete through; the cross-branch guard blocks it.
            _fn-on-b (mk-fn! b-storage (:id ns-row))
            resp (via-delete {:uri (str "/api/entities/ns/" (:id ns-row))
                              :request-method :delete})]
        (is (= 409 (:status resp)))
        (is (str/includes? (or (:body resp) "") "remove the contents first"))
        (testing "the namespace row survives the rejected delete"
          (is (some? (entities/get-entity "ns" (:id ns-row) (entity-ctx)))))))

    (testing "regression: a fn created then tombstoned on ALL branches leaves the ns deletable"
      (let [ns-row (entities/create-entity "ns" {:name (uniq "xbr-empty-ns")} (entity-ctx))
            f (mk-fn! main-storage (:id ns-row))
            ;; Tombstone the fn on `main` (the only branch it lives on).
            del-fn (via-delete {:uri (str "/api/entities/fn/" (:id f))
                                :request-method :delete})
            del-ns (via-delete {:uri (str "/api/entities/ns/" (:id ns-row))
                                :request-method :delete})]
        (is (= 200 (:status del-fn)))
        (is (= 200 (:status del-ns))
            "an ns whose only fn was deleted everywhere is empty and deletable")
        (is (nil? (entities/get-entity "ns" (:id ns-row) (entity-ctx))))))

    (testing "a fn live on the CURRENT branch still blocks the delete (unchanged)"
      (let [ns-row (entities/create-entity "ns" {:name (uniq "xbr-cur-ns")} (entity-ctx))
            _f (mk-fn! main-storage (:id ns-row))
            resp (via-delete {:uri (str "/api/entities/ns/" (:id ns-row))
                              :request-method :delete})]
        (is (= 409 (:status resp)))
        (is (str/includes? (or (:body resp) "") "remove the contents first"))))))


;; ============================================================================
;; process-* — request error / rejection branches
;; ============================================================================

(deftest process-create-entity-rejection-test
  (testing "a malformed form (bad UUID) → 400, not an uncaught 500"
    ;; parent-id isn't a UUID → parse throws → :parse-rej → 400
    (let [resp (via-create (form-req "/api/entities/fn"
                                     "name=bad&parent-id=not-a-uuid"))]
      (is (= 400 (:status resp)))))

  (testing "a composed fn can't own a fresh (non-rename) slot via fn-slot"
    ;; POST /api/entities/fn-slot for a composed fn + a plain slot
    ;; whose :source-slot-id is nil → fn-slot-rej → 400
    (let [storage (:storage *graph*)
          base (setup/create-base-fn! storage (uniq "pcer-base"))
          comp-fn (setup/create-composed-fn! storage (uniq "pcer-comp") (:id base))
          slot (setup/create-slot! storage "fresh" :int)
          resp (via-create (form-req "/api/entities/fn-slot"
                                     (str "fn-id=" (:id comp-fn)
                                          "&slot-id=" (:id slot)
                                          "&position=0")))]
      (is (= 400 (:status resp)))
      (is (re-find #"can only own slots that rename" (:body resp))))))


;; ============================================================================
;; Sequence operations — process-sequence-append/remove/update
;; ============================================================================

(deftest sequence-operations-test
  (let [storage (:storage *graph*)
        host (setup/create-base-fn! storage (uniq "seq-host"))
        slot (setup/create-slot! storage "items" :sequence)
        _    (setup/attach-slot! storage (:id host) (:id slot) 0)
        ;; `find-sequence-binding` reads the in-memory graph-cache —
        ;; production CRUD writes invalidate it, but our direct
        ;; `sp/create-entity` setup bypasses that path. Refresh the cache so it
        ;; picks up the just-attached sequence slot.
        ;;
        ;; SEED with the host fn-id — do NOT use the 1-arity full clear. The full
        ;; clear nils the compiled registry, and because the CRUD handlers are
        ;; themselves graph fns executed THROUGH that registry, the next
        ;; `via-seq-append` rebuilt all ~2600 golden fns before it could run: 52 s,
        ;; measured, for a single append (kaocha attributed the whole cost to this
        ;; one test). The seed splices just the host's rows into the graph-cache
        ;; and recompiles just the host, leaving the registry warm.
        _ (ctx/invalidate-graph-cache! (:ctx *graph*) #{(:id host)})
        append-uri (str "/api/sequence/append/" (:id host))]
    (testing "append to a fn with a sequence slot → 200, item persisted"
      (let [resp (via-seq-append (json-req append-uri {:value 42}))]
        (is (= 200 (:status resp)))
        (is (some #(= 42 (:value %))
                  (sp/query-entities storage :binding-list-item {})))))

    (testing "append: invalid fn-id → 400, missing body → 400"
      (is (= 400 (:status (via-seq-append
                            (json-req "/api/sequence/append/not-a-uuid" {})))))
      (is (= 400 (:status (via-seq-append
                            {:uri append-uri :request-method :post})))))

    (testing "update then remove the appended item"
      (let [item-id (->> (sp/query-entities storage :binding-list-item {})
                         (some #(when (= 42 (:value %)) (:id %))))
            upd (via-seq-update (json-req (str "/api/sequence/item/" item-id)
                                          {:value 99} :put))
            _   (is (= 200 (:status upd)))
            rm  (via-seq-remove {:uri (str "/api/sequence/item/" item-id)
                                 :request-method :delete})]
        (is (= 200 (:status rm)))
        (is (nil? (sp/read-entity storage :binding-list-item item-id)))))

    (testing "remove / update of an unknown item → 404"
      (is (= 404 (:status (via-seq-remove
                            {:uri (str "/api/sequence/item/" (random-uuid))
                             :request-method :delete}))))
      (is (= 404 (:status (via-seq-update
                            (json-req (str "/api/sequence/item/" (random-uuid))
                                      {:value 1} :put))))))

    (testing "append to a fn with NO sequence slot → 404"
      (let [plain (setup/create-base-fn! storage (uniq "no-seq-host"))
            s     (setup/create-slot! storage "n" :int)
            _     (setup/attach-slot! storage (:id plain) (:id s) 0)
            resp  (via-seq-append (json-req (str "/api/sequence/append/" (:id plain))
                                            {:value 1}))]
        (is (= 404 (:status resp)))))))


;; ============================================================================
;; Record / list type compound handlers
;; ============================================================================

(deftest process-create-record-type-test
  (testing "missing name / empty fields are rejected"
    (is (false? (:ok (via-create-record (json-req "/api/types/record"
                                                  {:fields [{:name "x" :type "int"}]})))))
    (is (false? (:ok (via-create-record (json-req "/api/types/record"
                                                  {:name "R" :fields []}))))))

  (testing "happy path creates one fn-row + N slots + N fn-slot junctions"
    (let [storage (:storage *graph*)
          rec-name (uniq "MyRecord")
          res (via-create-record (json-req "/api/types/record"
                                           {:name rec-name
                                            :fields [{:name "title" :type "text"}
                                                     {:name "count" :type "int"}]}))]
      (is (true? (:ok res)))
      (is (= rec-name (:name res)))
      (let [fn-id (java.util.UUID/fromString (:id res))]
        (is (some? (sp/read-entity storage :fn fn-id)))
        (is (= 2 (count (sp/query-entities storage :fn-slot {:fn-id fn-id})))))))

  (testing "an unresolvable field type fails the whole create AND rolls back every prior write"
    (let [storage (:storage *graph*)
          bad-name (uniq "BadRecord")
          ;; Snapshot before so we can prove rollback put us back exactly.
          before (count (sp/query-entities storage :fn {}))
          ;; First field is valid (:int) → gets written; second references
          ;; an unknown type → the whole create must roll back the first.
          res (via-create-record (json-req "/api/types/record"
                                           {:name bad-name
                                            :fields [{:name "ok-field" :type "int"}
                                                     {:name "x" :type "no-such-type"}]}))]
      (is (false? (:ok res)))
      (is (string? (:error res)))
      (is (= before (count (sp/query-entities storage :fn {})))
          "rollback restored the :fn count — no orphan rows")
      (is (empty? (sp/query-entities storage :fn {:name bad-name}))
          "the named row is gone after rollback"))))


(deftest process-create-list-type-test
  (testing "missing name / element-type are rejected"
    (is (false? (:ok (via-create-list (json-req "/api/types/list"
                                                {:element-type "int"})))))
    (is (false? (:ok (via-create-list (json-req "/api/types/list" {:name "L"}))))))

  (testing "happy path creates a fn-row with element-fn-id + items slot"
    (let [storage (:storage *graph*)
          res (via-create-list (json-req "/api/types/list"
                                         {:name (uniq "IntList")
                                          :element-type "int"}))]
      (is (true? (:ok res)))
      (let [fn-id (java.util.UUID/fromString (:id res))
            row   (sp/read-entity storage :fn fn-id)]
        (is (= (get setup/primitive-fn-ids :int) (:element-fn-id row)))
        (is (= 1 (count (sp/query-entities storage :fn-slot {:fn-id fn-id}))))))))


(deftest process-update-record-type-test
  (testing "missing id / empty fields are rejected"
    (is (false? (:ok (via-update-record (json-req "/api/types/record"
                                                  {:fields [{:name "x" :type "int"}]}
                                                  :put)))))
    (is (false? (:ok (via-update-record (json-req "/api/types/record"
                                                  {:id (str (random-uuid)) :fields []}
                                                  :put))))))

  (testing "an unknown fn id → not found"
    (let [res (via-update-record (json-req "/api/types/record"
                                           {:id (str (random-uuid))
                                            :fields [{:name "x" :type "int"}]}
                                           :put))]
      (is (false? (:ok res)))
      (is (re-find #"not found" (:error res)))))

  (testing "happy update — adding a field keeps the old slot, mints the new one"
    (let [storage (:storage *graph*)
          created (via-create-record (json-req "/api/types/record"
                                               {:name (uniq "UpdRec")
                                                :fields [{:name "title" :type "text"}]}))
          rec-id  (:id created)
          res     (via-update-record (json-req "/api/types/record"
                                               {:id rec-id
                                                :fields [{:name "title" :type "text"}
                                                         {:name "count" :type "int"}]}
                                               :put))]
      (is (true? (:ok res)))
      (is (= 2 (count (sp/query-entities
                        storage :fn-slot
                        {:fn-id (java.util.UUID/fromString rec-id)})))))))


(deftest process-update-record-type-diff-test
  (testing "dropping a field, reordering the rest, renaming the record"
    (let [storage (:storage *graph*)
          orig-name (uniq "DiffRec")
          new-name  (uniq "DiffRecRenamed")
          created (via-create-record (json-req "/api/types/record"
                                               {:name orig-name
                                                :fields [{:name "a" :type "int"}
                                                         {:name "b" :type "text"}
                                                         {:name "c" :type "int"}]}))
          rec-id  (:id created)
          fn-uuid (java.util.UUID/fromString rec-id)
          ;; keep c + a (drops b), reorder, rename + describe the fn-row
          res     (via-update-record (json-req "/api/types/record"
                                               {:id rec-id
                                                :name new-name
                                                :description "now described"
                                                :fields [{:name "c" :type "int"}
                                                         {:name "a" :type "int"}]}
                                               :put))]
      (is (true? (:ok res)))
      (is (= 2 (count (sp/query-entities storage :fn-slot
                                         {:fn-id fn-uuid}))))
      (let [row (sp/read-entity storage :fn fn-uuid)]
        (is (= new-name (:name row)))
        (is (= "now described" (:description row))))))

  (testing "a field with a blank name fails the whole update"
    (let [created (via-create-record (json-req "/api/types/record"
                                               {:name (uniq "BlankFieldRec")
                                                :fields [{:name "x" :type "int"}]}))
          res     (via-update-record (json-req "/api/types/record"
                                               {:id (:id created)
                                                :fields [{:name "" :type "int"}]}
                                               :put))]
      (is (false? (:ok res))))))


(deftest process-create-record-type-blank-field-test
  (testing "a blank field name throws → cleanup → :ok false, nothing left"
    (let [storage (:storage *graph*)
          rec-name (uniq "RollbackRec")
          before (count (sp/query-entities storage :fn {:name rec-name}))
          res    (via-create-record (json-req "/api/types/record"
                                              {:name rec-name
                                               :fields [{:name "" :type "int"}]}))]
      (is (false? (:ok res)))
      (is (= before (count (sp/query-entities storage :fn {:name rec-name})))
          "the half-created fn-row was rolled back"))))


(deftest process-create-list-type-rollback-test
  (testing "an unresolvable element-type rolls the half-created row back"
    (let [storage (:storage *graph*)
          list-name (uniq "BadList")
          before (count (sp/query-entities storage :fn {:name list-name}))
          res    (via-create-list (json-req "/api/types/list"
                                            {:name list-name
                                             :element-type "no-such-type"}))]
      (is (false? (:ok res)))
      (is (string? (:error res)))
      (is (= before (count (sp/query-entities storage :fn {:name list-name})))
          "cleanup removed the partially-created list fn-row"))))


(deftest graph-cache-splice-is-not-stale-test
  ;; `invalidate-graph-cache!` used to nil the whole `:graph-cache` on every
  ;; write, so the next reader reloaded the entire graph from Postgres. It now
  ;; SPLICES the changed fns' rows in place — which trades a performance problem
  ;; for a correctness risk: a stale read is worse than a slow one.
  ;;
  ;; So pin read-after-write through the same cached path the type API and
  ;; /api/graph/entities use. Create, rename, delete — each must be visible to
  ;; the very next read.
  (let [ns-name (uniq "cache-ns")]
    (testing "a fn created by a write is visible to the next cached read"
      (via-create (form-req "/api/entities/ns" (str "name=" ns-name)))
      (let [fresh (types-api/cached-or-load-graph (:ctx *graph*))]
        (is (some? fresh) "the cache is populated, not nil")))

    (testing "a create, then a delete, both land in the cache"
      (let [fn-name (uniq "cache-fn")
            _ (via-create (form-req "/api/entities/fn" (str "name=" fn-name)))
            after-create (types-api/cached-or-load-graph (:ctx *graph*))
            created (first (filter #(= fn-name (:name %)) (:fns after-create)))]
        (is (some? created)
            "the new fn is in the cached graph immediately after the write —
             a stale splice would omit it")

        (via-delete {:uri (str "/api/entities/fn/" (:id created))
                     :request-method :delete})
        (let [after-delete (types-api/cached-or-load-graph (:ctx *graph*))]
          (is (not-any? #(= fn-name (:name %)) (:fns after-delete))
              "the deleted fn is gone from the cached graph — a splice that only
               ADDED would leave a tombstoned fn visible forever"))))))


(deftest seedless-writes-do-not-drop-the-compiled-registry-test
  ;; A write answers the question "which compiled closures can this have changed?"
  ;; — and for a `:ns` or a `:slot` the honest answer is "none". Both used to
  ;; answer `nil` instead, which the invalidator reads as "I don't know" and
  ;; handles by dropping the WHOLE compiled registry. The next request then
  ;; rebuilt every fn in the graph.
  ;;
  ;; Measured against the e2e graph (4137 fns) before the fix:
  ;;
  ;;   POST /api/entities/ns    390 ms  ->  next request  49.6 s
  ;;   POST /api/entities/slot  361 ms  ->  next request  49.8 s
  ;;   (a seeded :fn or :binding write   ->  next request    12 ms)
  ;;
  ;; Reads either side of it cost 14 ms. Every type-editing test in the e2e suite
  ;; creates slots, which is why three of those files cost 165 s, 115 s and 114 s
  ;; against a median of 8 s for the rest. A registry left cold here is not a slow
  ;; test — it is a fifty-second stall for whoever asks next, in the editor as
  ;; much as in CI.
  (let [ctx (:ctx *graph*)
        registry (:compiled-registry ctx)
        warm-count (fn [] (count @registry))]

    (testing ":ns — a namespace reaches no compiled closure"
      (via-create (form-req "/api/entities/ns" (str "name=" (uniq "seedless-ns"))))
      (let [n (warm-count)]
        (is (some? @registry)
            "creating a namespace must not drop the compiled registry")
        (via-create (form-req "/api/entities/ns" (str "name=" (uniq "seedless-ns"))))
        (is (some? @registry) "still warm after a second namespace")
        (is (= n (warm-count))
            "and no fn was recompiled — a namespace changes no closure")))

    (testing ":slot — nothing exposes a slot until an fn-slot says so"
      (let [storage (:storage *graph*)
            int-id (:id (first (sp/query-entities storage :fn {:name "int"})))
            slot-name (uniq "seedless-slot")
            n (warm-count)]
        (via-create (form-req "/api/entities/slot"
                              (str "name=" slot-name "&type-fn-id=" int-id)))
        (is (some? @registry)
            "creating a slot must not drop the compiled registry")
        (is (= n (warm-count))
            "and no fn was recompiled — nothing exposes the slot yet")

        (testing "and the cache still learns about the slot"
          ;; The registry is untouched, but the graph cache DOES hold slot rows —
          ;; a reader of the whole graph must not miss one just because nothing
          ;; has attached it to an fn yet.
          (let [g (types-api/cached-or-load-graph ctx)]
            (is (some #(= slot-name (:name %)) (:slots g))
                "the new slot is visible to the next cached read")))))

    (testing ":fn — a seeded write still keeps the registry warm"
      ;; The delta path recompiles the blast radius rather than dropping
      ;; everything. What must NOT happen is the wholesale clear: the fns already
      ;; compiled stay compiled, and the next request pays for the edit, not for
      ;; the graph.
      (let [n (warm-count)]
        (via-create (form-req "/api/entities/fn" (str "name=" (uniq "seeded-fn"))))
        (is (some? @registry) "the registry stays warm on the delta path too")
        (is (>= (warm-count) n)
            "and keeps every closure it had already compiled")))))


;; ============================================================================
;; /api/graph/entities scopes — HTTP handler integration
;; ============================================================================

(defn- via-entities
  "Invoke the /api/graph/entities handler with the given query params and
   parse its JSON body (keywordized)."
  [params]
  (let [resp (setup/via-graph *graph* :all-entities-handler
                              {:request-method :get
                               :uri "/api/graph/entities"
                               :query-params params})]
    (cheshire/parse-string (:body resp) true)))


(deftest all-entities-handler-scopes-test
  (testing "scope=tree — namespaces + counts, NO fn rows"
    (let [body (via-entities {"scope" "tree"})]
      (is (contains? body :namespaces))
      (is (contains? body :counts))
      (is (not (contains? body :fns)) "the tree payload carries no fn rows")
      (is (seq (:counts body)))
      (is (every? #(contains? % :namespace-id) (:counts body)))
      (is (every? #(and (contains? % :count) (pos-int? (:count %))) (:counts body)))))

  (testing "scope=namespace — light fn rows for one namespace"
    (let [tree (via-entities {"scope" "tree"})
          nid  (->> (:counts tree)
                    (filter #(and (:namespace-id %) (pos? (:count %))))
                    first :namespace-id)
          body (via-entities {"scope" "namespace" "namespace-id" nid})]
      (is (some? nid) "the bootstrap graph has a populated, named namespace")
      (is (seq (:fns body)))
      (is (every? #(= nid (:namespace-id %)) (:fns body))
          "every returned fn belongs to the requested namespace")
      (is (every? :name (:fns body)) "anonymous fns are excluded")
      (is (not (contains? body :slots)) "light payload — no heavy relational tables")
      (is (every? #(contains? % :role) (:fns body)) "light rows carry :role")))

  (testing "scope=search — capped, case-insensitive name matches"
    (let [body (via-entities {"scope" "search" "q" "add"})]
      (is (seq (:fns body)) "at least the base-fn `add` matches")
      (is (every? #(str/includes? (str/lower-case (:name %)) "add") (:fns body))
          "every match contains the needle")
      (is (contains? body :truncated?))))

  (testing "scope=search with a blank q — no matches"
    (is (empty? (:fns (via-entities {"scope" "search" "q" "   "})))))

  (testing "scope=index still returns the full-fns shape (backward compat)"
    (let [body (via-entities {"scope" "index"})]
      (is (contains? body :fns))
      (is (contains? body :namespaces))
      (is (seq (:fns body))))))
