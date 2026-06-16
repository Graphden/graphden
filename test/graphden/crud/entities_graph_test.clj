(ns ^:integration graphden.crud.entities-graph-test
  "Graph-path tests for the entity CRUD HTTP handlers
   (`:process-create-entity` / `:process-update-entity` /
   `:process-delete-entity`). Replaces the test-side reproductions in
   `entities-test.clj` that called `entities/parse-create-request +
   validate-create + apply-create` directly — i.e. tested CLOJURE
   helpers production never runs.

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
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


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
                                     :value 1 :override-kind :fixed})
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
                                     :value 1 :override-kind :fixed})
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
                                  :value 1 :override-kind :fixed})
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
        ;; `sp/create-entity` setup bypasses that path. Force a refresh
        ;; so the cache picks up the just-attached sequence slot.
        _ (ctx/invalidate-graph-cache! (:ctx *graph*))
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

  (testing "an unresolvable field type fails the whole create"
    (let [res (via-create-record (json-req "/api/types/record"
                                           {:name (uniq "BadRecord")
                                            :fields [{:name "x" :type "no-such-type"}]}))]
      (is (false? (:ok res)))
      (is (string? (:error res))))))


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
