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
      (is (= {} (entities/parse-binding-from-form {}))))))


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

      (testing "return-type name resolves to a fn-id"
        (let [parsed (entities/parse-fn-from-form {:return-type "int"} c)]
          (is (= (get setup/primitive-fn-ids :int)
                 (:return-type-fn-id parsed)))))
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
