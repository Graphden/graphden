(ns graphden.crud.entities-test
  "DB-backed tests for `graphden.crud.entities` — the heavy CRUD logic
   behind the web/crud base functions: form parsers, generic
   create/read/update/delete, the compound type-row endpoints, the
   delete-guard reasons, and the HTTP `process-*` dispatchers.

   Uses the shared container plus a real `ExecutionContext` so the
   `invalidate!` path exercises against live storage."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.executor.context :as ctx]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- test-ctx
  "A real ExecutionContext over a fresh test storage."
  [storage]
  (ctx/create-context {:storage storage :base-fns {}}))


;; ============================================================================
;; Form parsers — pure transforms (parse-fn-from-form needs storage)
;; ============================================================================

(deftest parse-ns-from-form-test
  (testing "name + description + parent-id are coerced; absent keys stay absent"
    (let [u (random-uuid)]
      (is (= {:name "my-ns" :description "d" :parent-id u}
             (entities/parse-ns-from-form {:name "my-ns" :description "d"
                                           :parent-id (str u)})))
      (is (= {} (entities/parse-ns-from-form {})))
      (is (= {:name "x"} (entities/parse-ns-from-form {:name "x"
                                                       :parent-id ""}))))))


(deftest parse-slot-from-form-test
  (testing "type-fn-id coerced (empty → nil); required is a strict true/false"
    (let [u (random-uuid)]
      (is (= {:name "s" :type-fn-id u :required true}
             (entities/parse-slot-from-form {:name "s" :type-fn-id (str u)
                                             :required "true"})))
      (is (= {:type-fn-id nil} (entities/parse-slot-from-form {:type-fn-id ""})))
      (is (= {:required false} (entities/parse-slot-from-form {:required "false"}))))))


(deftest parse-fn-slot-from-form-test
  (testing "both refs coerced, position parsed to int"
    (let [f (random-uuid) s (random-uuid)]
      (is (= {:fn-id f :slot-id s :position 3}
             (entities/parse-fn-slot-from-form {:fn-id (str f) :slot-id (str s)
                                                :position "3"}))))))


(deftest parse-binding-from-form-test
  (testing "value JSON-decoded (empty → nil), boolean flags strict, override-kind keywordised"
    (let [f (random-uuid) s (random-uuid)]
      (is (= {:fn-id f :slot-id s :value 42 :terminal true
              :list-append false :override-kind :fixed}
             (entities/parse-binding-from-form
               {:fn-id (str f) :slot-id (str s) :value "42"
                :terminal "true" :list-append "false" :override-kind "fixed"})))
      (is (= {:value nil} (entities/parse-binding-from-form {:value ""})))
      (is (= {:required nil} (entities/parse-binding-from-form {:required ""})))
      (is (= {} (entities/parse-binding-from-form {})))))

  (testing "ref-fn-id / type-override-fn-id / description / list-closed / required"
    (let [r (random-uuid) tov (random-uuid)]
      (is (= {:ref-fn-id r :type-override-fn-id tov :description "d"
              :list-closed true :required true}
             (entities/parse-binding-from-form
               {:ref-fn-id (str r) :type-override-fn-id (str tov)
                :description "d" :list-closed "true" :required "true"})))
      ;; empty-as-clear on the nullable ref slots
      (is (= {:ref-fn-id nil :type-override-fn-id nil}
             (entities/parse-binding-from-form
               {:ref-fn-id "" :type-override-fn-id ""}))))))


(deftest parse-binding-list-item-from-form-test
  (testing "binding-id + position + value, or a ref-fn-id, with the literal flag"
    (let [b (random-uuid) r (random-uuid)]
      (is (= {:binding-id b :position 0 :value 5}
             (entities/parse-binding-list-item-from-form
               {:binding-id (str b) :position "0" :value "5"})))
      (is (= {:ref-fn-id r :literal true}
             (entities/parse-binding-list-item-from-form
               {:ref-fn-id (str r) :literal "true"}))))))


(deftest parse-fn-from-form-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "name / description / parent-ids list / namespace empty-as-clear"
        (let [p1 (random-uuid) p2 (random-uuid)]
          (is (= {:name "f" :description "d"}
                 (entities/parse-fn-from-form {:name "f" :description "d"} c)))
          (is (= {:parent-ids [p1 p2]}
                 (entities/parse-fn-from-form
                   {:parent-ids (str p1 "," p2)} c)))
          (is (= {:parent-ids []}
                 (entities/parse-fn-from-form {:parent-ids ""} c)))
          (is (= {:namespace-id nil}
                 (entities/parse-fn-from-form {:namespace-id ""} c)))))

      (testing "expects-effects: comma list / explicit empty / null-unset"
        (is (= {:expects-effects ["db" "io"]}
               (entities/parse-fn-from-form {:expects-effects "db, io"} c)))
        (is (= {:expects-effects []}
               (entities/parse-fn-from-form {:expects-effects "[]"} c)))
        (is (= {:expects-effects nil}
               (entities/parse-fn-from-form {:expects-effects "null"} c))))

      (testing "constraint JSON array is re-keywordised"
        (is (= {:constraint [:union :int :text]}
               (entities/parse-fn-from-form
                 {:constraint "[\"union\",\"int\",\"text\"]"} c))))

      (testing "constraint with comparison ops + numeric members"
        ;; bare `[!<>=]+` ops keywordise; numbers stay as-is (`:else x`)
        (is (= {:constraint [:and [:>= 100] [:<= 599]]}
               (entities/parse-fn-from-form
                 {:constraint "[\"and\",[\">=\",100],[\"<=\",599]]"} c))))

      (testing "a constraint that isn't valid JSON falls back to the raw string"
        ;; json/parse-string throws → caught → raw string → re-kw
        ;; keywordises the bare alpha identifier
        (is (= {:constraint :not-json}
               (entities/parse-fn-from-form {:constraint "not-json"} c))))

      (testing "return-type name resolves to a fn-id"
        (let [parsed (entities/parse-fn-from-form {:return-type "int"} c)]
          (is (= (get setup/primitive-fn-ids :int)
                 (:return-type-fn-id parsed)))))

      (testing "base-fn-id / element-fn-id resolve named types (empty → nil)"
        (let [parsed (entities/parse-fn-from-form
                       {:base-fn-id "int" :element-fn-id "text"} c)]
          (is (= (get setup/primitive-fn-ids :int) (:base-fn-id parsed)))
          (is (= (get setup/primitive-fn-ids :text) (:element-fn-id parsed))))
        (is (= {:base-fn-id nil :element-fn-id nil}
               (entities/parse-fn-from-form
                 {:base-fn-id "" :element-fn-id ""} c))))

      (testing "parent-id (single) + namespace-id resolve to UUIDs"
        (let [p (random-uuid) n (random-uuid)]
          (is (= {:parent-id p :namespace-id n}
                 (entities/parse-fn-from-form
                   {:parent-id (str p) :namespace-id (str n)} c)))))
      (finally (sp/close storage)))))


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
;; Compound type-row creation
;; ============================================================================

(deftest process-create-record-type-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "missing name / empty fields are rejected"
        (is (false? (:ok (entities/process-create-record-type
                           {:body {:fields [{:name "x" :type "int"}]}} c))))
        (is (false? (:ok (entities/process-create-record-type
                           {:body {:name "R" :fields []}} c)))))

      (testing "happy path creates one fn-row + N slots + N fn-slot junctions"
        (let [res (entities/process-create-record-type
                    {:body {:name "MyRecord"
                            :fields [{:name "title" :type "text"}
                                     {:name "count" :type "int"}]}}
                    c)]
          (is (true? (:ok res)))
          (is (= "MyRecord" (:name res)))
          (let [fn-id (java.util.UUID/fromString (:id res))]
            (is (some? (sp/read-entity storage :fn fn-id)))
            (is (= 2 (count (sp/query-entities storage :fn-slot {:fn-id fn-id})))))))

      (testing "an unresolvable field type fails the whole create"
        (let [res (entities/process-create-record-type
                    {:body {:name "BadRecord"
                            :fields [{:name "x" :type "no-such-type"}]}}
                    c)]
          (is (false? (:ok res)))
          (is (string? (:error res)))))
      (finally (sp/close storage)))))


(deftest process-create-list-type-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "missing name / element-type are rejected"
        (is (false? (:ok (entities/process-create-list-type
                           {:body {:element-type "int"}} c))))
        (is (false? (:ok (entities/process-create-list-type
                           {:body {:name "L"}} c)))))

      (testing "happy path creates a fn-row with element-fn-id + items slot"
        (let [res (entities/process-create-list-type
                    {:body {:name "IntList" :element-type "int"}} c)]
          (is (true? (:ok res)))
          (let [fn-id (java.util.UUID/fromString (:id res))
                row   (sp/read-entity storage :fn fn-id)]
            (is (= (get setup/primitive-fn-ids :int) (:element-fn-id row)))
            (is (= 1 (count (sp/query-entities storage :fn-slot {:fn-id fn-id})))))))
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
;; process-create-entity / process-delete-entity (HTTP dispatchers)
;; ============================================================================

(deftest process-create-entity-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "form-encoded ns create → 200 + entityCreated trigger"
        (let [resp (entities/process-create-entity
                     {:uri "/api/entities/ns" :body "name=proc-ns"} c)]
          (is (= 200 (:status resp)))
          (is (= "entityCreated" (get-in resp [:headers "HX-Trigger"])))
          (is (some #(= "proc-ns" (:name %))
                    (sp/query-entities storage :ns {})))))

      (testing "a request with no usable body → 400"
        (let [resp (entities/process-create-entity {:uri "/api/entities/ns"} c)]
          (is (= 400 (:status resp)))))
      (finally (sp/close storage)))))


(deftest process-delete-entity-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "deleting an empty namespace → 200"
        (let [ns-row (entities/create-entity "ns" {:name "del-ns"} c)
              resp   (entities/process-delete-entity
                       {:uri (str "/api/entities/ns/" (:id ns-row))} c)]
          (is (= 200 (:status resp)))
          (is (nil? (entities/get-entity "ns" (:id ns-row) c)))))

      (testing "deleting a fn that is still a parent → 409"
        (let [parent (setup/create-base-fn! storage "pde-parent")
              _      (setup/create-composed-fn! storage "pde-child" (:id parent))
              resp   (entities/process-delete-entity
                       {:uri (str "/api/entities/fn/" (:id parent))} c)]
          (is (= 409 (:status resp)))))

      (testing "a request with no id → 400"
        (let [resp (entities/process-delete-entity {:uri "/api/entities/fn"} c)]
          (is (= 400 (:status resp)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-update-record-type
;; ============================================================================

(deftest process-update-record-type-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "missing id / empty fields are rejected"
        (is (false? (:ok (entities/process-update-record-type
                           {:body {:fields [{:name "x" :type "int"}]}} c))))
        (is (false? (:ok (entities/process-update-record-type
                           {:body {:id (str (random-uuid)) :fields []}} c)))))

      (testing "an unknown fn id → not found"
        (let [res (entities/process-update-record-type
                    {:body {:id (str (random-uuid))
                            :fields [{:name "x" :type "int"}]}} c)]
          (is (false? (:ok res)))
          (is (re-find #"not found" (:error res)))))

      (testing "happy update — adding a field keeps the old slot, mints the new one"
        (let [created (entities/process-create-record-type
                        {:body {:name "UpdRec"
                                :fields [{:name "title" :type "text"}]}} c)
              rec-id  (:id created)
              res     (entities/process-update-record-type
                        {:body {:id rec-id
                                :fields [{:name "title" :type "text"}
                                         {:name "count" :type "int"}]}} c)]
          (is (true? (:ok res)))
          (is (= 2 (count (sp/query-entities
                            storage :fn-slot
                            {:fn-id (java.util.UUID/fromString rec-id)}))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-update-entity
;; ============================================================================

(deftest process-update-entity-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "form-encoded ns update → 200, field applied"
        (let [ns-row (entities/create-entity "ns" {:name "upd-ns"} c)
              resp   (entities/process-update-entity
                       {:uri (str "/api/entities/ns/" (:id ns-row))
                        :body "description=changed"} c)]
          (is (= 200 (:status resp)))
          (is (= "changed"
                 (:description (entities/get-entity "ns" (:id ns-row) c))))))

      (testing "a request with no id segment → 400"
        (let [resp (entities/process-update-entity
                     {:uri "/api/entities/ns" :body "name=x"} c)]
          (is (= 400 (:status resp)))))
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


;; ============================================================================
;; Sequence operations — append / update / remove
;; ============================================================================

(deftest sequence-operations-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (let [host (setup/create-base-fn! storage "seq-host")
            slot (setup/create-slot! storage "items" :sequence)
            _    (setup/attach-slot! storage (:id host) (:id slot) 0)
            append-uri (str "/api/sequence/append/" (:id host))]
        (testing "append to a fn with a sequence slot → 200, item persisted"
          (let [resp (entities/process-sequence-append
                       {:uri append-uri :body "{\"value\": 42}"} c)]
            (is (= 200 (:status resp)))
            (is (= 1 (count (sp/query-entities storage :binding-list-item {}))))))

        (testing "append: invalid fn-id → 400, missing body → 400"
          (is (= 400 (:status (entities/process-sequence-append
                                {:uri "/api/sequence/append/not-a-uuid"
                                 :body "{}"} c))))
          (is (= 400 (:status (entities/process-sequence-append
                                {:uri append-uri} c)))))

        (testing "update then remove the appended item"
          (let [item-id (:id (first (sp/query-entities
                                      storage :binding-list-item {})))
                upd  (entities/process-sequence-update
                       {:uri (str "/api/sequence/item/" item-id)
                        :body "{\"value\": 99}"} c)
                _    (is (= 200 (:status upd)))
                rm   (entities/process-sequence-remove
                       {:uri (str "/api/sequence/item/" item-id)} c)]
            (is (= 200 (:status rm)))
            (is (nil? (sp/read-entity storage :binding-list-item item-id)))))

        (testing "remove / update of an unknown item → 404"
          (is (= 404 (:status (entities/process-sequence-remove
                                {:uri (str "/api/sequence/item/" (random-uuid))}
                                c))))
          (is (= 404 (:status (entities/process-sequence-update
                                {:uri (str "/api/sequence/item/" (random-uuid))
                                 :body "{\"value\": 1}"} c))))))

      (testing "append to a fn with NO sequence slot → 404"
        (let [plain (setup/create-base-fn! storage "no-seq-host")
              s     (setup/create-slot! storage "n" :int)
              _     (setup/attach-slot! storage (:id plain) (:id s) 0)
              resp  (entities/process-sequence-append
                      {:uri (str "/api/sequence/append/" (:id plain))
                       :body "{\"value\": 1}"} c)]
          (is (= 404 (:status resp)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-tighten-binding-effects — request validation branches
;; ============================================================================

(deftest process-tighten-binding-effects-validation-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "invalid binding-id → 400"
        (is (= 400 (:status (entities/process-tighten-binding-effects
                              {:uri "/api/bindings/not-a-uuid/tighten-fn-effects"
                               :body {}} c)))))

      (let [uri (str "/api/bindings/" (random-uuid) "/tighten-fn-effects")]
        (testing "'effects' not a JSON array → 400"
          (is (= 400 (:status (entities/process-tighten-binding-effects
                                {:uri uri :body {:effects "nope"}} c)))))

        (testing "'args' not a JSON object → 400"
          (is (= 400 (:status (entities/process-tighten-binding-effects
                                {:uri uri :body {:args "nope"}} c)))))

        (testing "a body with no args / ret / effects → 400"
          (is (= 400 (:status (entities/process-tighten-binding-effects
                                {:uri uri :body {}} c))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-create-entity — fn / binding success paths + error humanising
;; ============================================================================

(deftest process-create-entity-fn-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "form-encoded composed-fn create → 200 + entityCreated"
        (let [base (setup/create-base-fn! storage "pce-base")
              resp (entities/process-create-entity
                     {:uri "/api/entities/fn"
                      :body (str "name=pce-composed&parent-ids=" (:id base))}
                     c)]
          (is (= 200 (:status resp)))
          (is (= "entityCreated" (get-in resp [:headers "HX-Trigger"])))
          (is (some #(= "pce-composed" (:name %))
                    (sp/query-entities storage :fn {})))))
      (finally (sp/close storage)))))


(deftest process-create-entity-binding-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (let [base (setup/create-base-fn! storage "pceb-base")
            slot (setup/create-slot! storage "n" :int)
            _    (setup/attach-slot! storage (:id base) (:id slot) 0)
            comp-fn (setup/create-composed-fn! storage "pceb-comp-fn" (:id base))]
        (testing "form-encoded binding create → 200, binding persisted"
          (let [resp (entities/process-create-entity
                       {:uri "/api/entities/binding"
                        :body (str "fn-id=" (:id comp-fn) "&slot-id=" (:id slot)
                                   "&value=42&override-kind=fixed")}
                       c)]
            (is (= 200 (:status resp)))
            (is (= 1 (count (sp/query-entities storage :binding
                                               {:fn-id (:id comp-fn)}))))))

        (testing "a binding carrying rename-to also mints the renamed-view slot"
          (let [base2 (setup/create-base-fn! storage "pceb-base2")
                slot2 (setup/create-slot! storage "orig" :int)
                _     (setup/attach-slot! storage (:id base2) (:id slot2) 0)
                comp2 (setup/create-composed-fn! storage "pceb-comp2" (:id base2))
                resp  (entities/process-create-entity
                        {:uri "/api/entities/binding"
                         :body (str "fn-id=" (:id comp2) "&slot-id=" (:id slot2)
                                    "&value=1&override-kind=fixed&rename-to=renamed-n")}
                        c)]
            (is (= 200 (:status resp)))
            (is (some #(= "renamed-n" (:name %))
                      (sp/query-entities storage :slot {}))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-update-entity — fn / binding success paths
;; ============================================================================

(deftest process-update-entity-fn-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "form-encoded fn description update → 200, field applied"
        (let [f    (setup/create-base-fn! storage "pue-fn")
              resp (entities/process-update-entity
                     {:uri (str "/api/entities/fn/" (:id f))
                      :body "description=updated-desc"} c)]
          (is (= 200 (:status resp)))
          (is (= "updated-desc"
                 (:description (sp/read-entity storage :fn (:id f)))))))

      (testing "form-encoded binding value update → 200, value applied"
        ;; the binding row carries the override_kind enum column —
        ;; exercises the codec round-trip fixed in postgres/crud.clj
        (let [base    (setup/create-base-fn! storage "pue-base")
              slot    (setup/create-slot! storage "n" :int)
              _       (setup/attach-slot! storage (:id base) (:id slot) 0)
              comp-fn (setup/create-composed-fn! storage "pue-comp" (:id base))
              bind    (sp/create-entity storage :binding
                                        {:fn-id (:id comp-fn) :slot-id (:id slot)
                                         :value 1 :override-kind :fixed})
              resp    (entities/process-update-entity
                        {:uri (str "/api/entities/binding/" (:id bind))
                         :body "value=99"} c)]
          (is (= 200 (:status resp)))
          (is (= 99 (:value (sp/read-entity storage :binding (:id bind)))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-delete-entity — fn / binding success paths
;; ============================================================================

(deftest process-delete-entity-fn-binding-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "deleting an unreferenced fn → 200 + entityDeleted"
        (let [f    (setup/create-base-fn! storage "pde-free-fn")
              resp (entities/process-delete-entity
                     {:uri (str "/api/entities/fn/" (:id f))} c)]
          (is (= 200 (:status resp)))
          (is (= "entityDeleted" (get-in resp [:headers "HX-Trigger"])))
          (is (nil? (sp/read-entity storage :fn (:id f))))))

      (testing "deleting a binding → 200, row gone"
        (let [base (setup/create-base-fn! storage "pde-bind-base")
              slot (setup/create-slot! storage "n" :int)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              comp-fn (setup/create-composed-fn! storage "pde-bind-comp-fn" (:id base))
              bind (sp/create-entity storage :binding
                                     {:fn-id (:id comp-fn) :slot-id (:id slot)
                                      :value 1 :override-kind :fixed})
              resp (entities/process-delete-entity
                     {:uri (str "/api/entities/binding/" (:id bind))} c)]
          (is (= 200 (:status resp)))
          (is (nil? (sp/read-entity storage :binding (:id bind))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-update-record-type — field removal / reposition / fn rename
;; ============================================================================

(deftest process-update-record-type-diff-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "dropping a field, reordering the rest, renaming the record"
        (let [created (entities/process-create-record-type
                        {:body {:name "DiffRec"
                                :fields [{:name "a" :type "int"}
                                         {:name "b" :type "text"}
                                         {:name "c" :type "int"}]}} c)
              rec-id  (:id created)
              fn-uuid (java.util.UUID/fromString rec-id)
              ;; keep c + a (drops b), reorder, rename + describe the fn-row
              res     (entities/process-update-record-type
                        {:body {:id rec-id
                                :name "DiffRecRenamed"
                                :description "now described"
                                :fields [{:name "c" :type "int"}
                                         {:name "a" :type "int"}]}} c)]
          (is (true? (:ok res)))
          (is (= 2 (count (sp/query-entities storage :fn-slot
                                             {:fn-id fn-uuid}))))
          (let [row (sp/read-entity storage :fn fn-uuid)]
            (is (= "DiffRecRenamed" (:name row)))
            (is (= "now described" (:description row))))))

      (testing "a field with a blank name fails the whole update"
        (let [created (entities/process-create-record-type
                        {:body {:name "BlankFieldRec"
                                :fields [{:name "x" :type "int"}]}} c)
              res     (entities/process-update-record-type
                        {:body {:id (:id created)
                                :fields [{:name "" :type "int"}]}} c)]
          (is (false? (:ok res)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-create-record-type — a field missing its name rolls back
;; ============================================================================

(deftest process-create-record-type-blank-field-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "a blank field name throws → cleanup → :ok false, nothing left"
        (let [before (count (sp/query-entities storage :fn {}))
              res    (entities/process-create-record-type
                       {:body {:name "RollbackRec"
                               :fields [{:name "" :type "int"}]}} c)]
          (is (false? (:ok res)))
          (is (= before (count (sp/query-entities storage :fn {})))
              "the half-created fn-row was rolled back")))
      (finally (sp/close storage)))))


;; ============================================================================
;; process-* — request error / rejection branches
;; ============================================================================

(deftest process-create-entity-rejection-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "a malformed form (bad UUID) → 400, not an uncaught 500"
        ;; parent-id isn't a UUID → parse-fn-from-form throws
        ;; IllegalArgumentException → caught → :parse-rej → 400
        (let [resp (entities/process-create-entity
                     {:uri "/api/entities/fn"
                      :body "name=bad&parent-id=not-a-uuid"} c)]
          (is (= 400 (:status resp)))))

      (testing "a composed fn can't own a fresh (non-rename) slot via fn-slot"
        ;; POST /api/entities/fn-slot for a composed fn + a plain slot
        ;; whose :source-slot-id is nil → fn-slot-rej → 400
        (let [base (setup/create-base-fn! storage "pcer-base")
              comp-fn (setup/create-composed-fn! storage "pcer-comp" (:id base))
              slot (setup/create-slot! storage "fresh" :int)
              resp (entities/process-create-entity
                     {:uri "/api/entities/fn-slot"
                      :body (str "fn-id=" (:id comp-fn) "&slot-id=" (:id slot)
                                 "&position=0")} c)]
          (is (= 400 (:status resp)))
          (is (re-find #"can only own slots that rename" (:body resp)))))
      (finally (sp/close storage)))))


(deftest process-update-entity-slot-and-rename-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "form-encoded slot update dispatches through parse-slot-from-form"
        (let [slot (setup/create-slot! storage "n" :int)
              resp (entities/process-update-entity
                     {:uri (str "/api/entities/slot/" (:id slot))
                      :body "description=slot-desc"} c)]
          (is (= 200 (:status resp)))
          (is (= "slot-desc"
                 (:description (sp/read-entity storage :slot (:id slot)))))))

      (testing "a malformed form (bad UUID) on update → 400, not 500"
        (let [f (setup/create-base-fn! storage "puesr-badform")
              resp (entities/process-update-entity
                     {:uri (str "/api/entities/fn/" (:id f))
                      :body "parent-id=not-a-uuid"} c)]
          (is (= 400 (:status resp)))))

      (testing "a binding update carrying rename-to mints the renamed-view slot"
        (let [base    (setup/create-base-fn! storage "puesr-base")
              slot    (setup/create-slot! storage "orig" :int)
              _       (setup/attach-slot! storage (:id base) (:id slot) 0)
              comp-fn (setup/create-composed-fn! storage "puesr-comp" (:id base))
              bind    (sp/create-entity storage :binding
                                        {:fn-id (:id comp-fn) :slot-id (:id slot)
                                         :value 1 :override-kind :fixed})
              resp    (entities/process-update-entity
                        {:uri (str "/api/entities/binding/" (:id bind))
                         :body "value=2&rename-to=renamed-orig"} c)]
          (is (= 200 (:status resp)))
          (is (some #(= "renamed-orig" (:name %))
                    (sp/query-entities storage :slot {})))))
      (finally (sp/close storage)))))


(deftest process-create-list-type-rollback-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "an unresolvable element-type rolls the half-created row back"
        (let [before (count (sp/query-entities storage :fn {}))
              res    (entities/process-create-list-type
                       {:body {:name "BadList" :element-type "no-such-type"}} c)]
          (is (false? (:ok res)))
          (is (string? (:error res)))
          (is (= before (count (sp/query-entities storage :fn {})))
              "cleanup removed the partially-created list fn-row")))
      (finally (sp/close storage)))))
