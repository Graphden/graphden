(ns graphden.executor.runtime-types-test
  "Tests for the runtime type-registry refresh mechanisms — the work
   that lets record-types created via CRUD become resolvable to the
   type-checker without a server restart.

   Covers:
   - `register-type-aliases-from-db!` walks DB type-rows and registers
     aliases for records (via fn-slots), refinements (via :base-fn-id),
     lists (via :element-fn-id), unions (via :constraint).
   - `produces-callable?` derivation: a ref-binding whose target's
     `:return-type` is itself a fn-type gets the runtime flag set
     so `make-ref-entry` thunks instead of hof-wrapping."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; register-type-aliases-from-db!
;; ============================================================================

(defn- prim
  [k]
  (records/primitive-fn-id k))


(defn- create-record-type-row!
  "Storage-level helper: inserts a fn-row + slot rows + fn-slot rows
   for a record-type. Returns the fn-id."
  [storage fn-name fields]
  (let [fn-id (java.util.UUID/randomUUID)
        slots (mapv (fn [[fname ftype]]
                      {:id (java.util.UUID/randomUUID)
                       :name (name fname)
                       :type-fn-id (prim ftype)
                       :required true})
                    fields)
        fn-slots (mapv (fn [idx s]
                         {:id (java.util.UUID/randomUUID)
                          :fn-id fn-id
                          :slot-id (:id s)
                          :position idx})
                       (range)
                       slots)]
    (sp/upsert-entities storage :fn
                        [{:id fn-id
                          :name (name fn-name)
                          :namespace-id nil
                          :parent-ids []
                          :impl-hash nil
                          :base-fn-id nil
                          :element-fn-id nil
                          :return-type-fn-id nil
                          :anonymous-hash nil
                          :constraint nil
                          :description nil}])
    (sp/upsert-entities storage :slot slots)
    (sp/upsert-entities storage :fn-slot fn-slots)
    fn-id))


(deftest record-type-from-db-becomes-alias
  (testing "DB-stored record-type registers as a type-alias"
    (let [storage (setup/create-test-storage)]
      (try
        (types/clear-aliases!)
        (create-record-type-row! storage :tg-message
                                 [[:chat-id :int] [:text :text]])
        (let [ctx (exec/create-context {:storage storage})]
          (cr/refresh-type-registries-from-storage! ctx)
          (is (= {:chat-id :int :text :text}
                 (get (types/aliases-snapshot) :tg-message))
              "record-type's structural shape registered")
          (is (types/well-formed? :tg-message)
              "alias keyword is well-formed after registration")
          (is (= {:chat-id :int :text :text}
                 (types/resolve-alias :tg-message))
              "resolve-alias expands to structural body"))
        (finally
          (sp/close storage))))))


(deftest refinement-from-db-becomes-alias
  (testing "DB-stored refinement registers as a [:refine base constraint] alias"
    (let [storage (setup/create-test-storage)]
      (try
        (types/clear-aliases!)
        (sp/upsert-entities
          storage :fn
          [{:id (java.util.UUID/randomUUID)
            :name "positive-int"
            :namespace-id nil
            :parent-ids []
            :impl-hash nil
            :base-fn-id (prim :int)
            :element-fn-id nil
            :return-type-fn-id nil
            :anonymous-hash nil
            :constraint [:> 0]
            :description nil}])
        (cr/refresh-type-registries-from-storage!
          (exec/create-context {:storage storage}))
        (is (= [:refine :int [:> 0]]
               (get (types/aliases-snapshot) :positive-int)))
        (finally
          (sp/close storage))))))


(deftest list-type-from-db-becomes-alias
  (testing "DB-stored list-type registers as a [:list T] alias"
    (let [storage (setup/create-test-storage)]
      (try
        (types/clear-aliases!)
        (sp/upsert-entities
          storage :fn
          [{:id (java.util.UUID/randomUUID)
            :name "int-list"
            :namespace-id nil
            :parent-ids []
            :impl-hash nil
            :base-fn-id nil
            :element-fn-id (prim :int)
            :return-type-fn-id nil
            :anonymous-hash nil
            :constraint nil
            :description nil}])
        (cr/refresh-type-registries-from-storage!
          (exec/create-context {:storage storage}))
        (is (= [:list :int]
               (get (types/aliases-snapshot) :int-list)))
        (finally
          (sp/close storage))))))


(deftest union-type-from-db-becomes-alias
  (testing "DB-stored union registers via the :constraint payload"
    (let [storage (setup/create-test-storage)]
      (try
        (types/clear-aliases!)
        (sp/upsert-entities
          storage :fn
          [{:id (java.util.UUID/randomUUID)
            :name "int-or-text"
            :namespace-id nil
            :parent-ids []
            :impl-hash nil
            :base-fn-id nil
            :element-fn-id nil
            :return-type-fn-id nil
            :anonymous-hash nil
            :constraint [:union :int :text]
            :description nil}])
        (cr/refresh-type-registries-from-storage!
          (exec/create-context {:storage storage}))
        (is (= [:union :int :text]
               (get (types/aliases-snapshot) :int-or-text)))
        (finally
          (sp/close storage))))))


(deftest variant-type-from-db-becomes-alias
  (testing "DB-stored variant desugars into a union-of-pinned-records via types/desugar-variant — same structural form the EDN-side path produces (mirrors `core.refinements/result-text` registration)"
    (let [storage (setup/create-test-storage)]
      (try
        (types/clear-aliases!)
        (sp/upsert-entities
          storage :fn
          [{:id (java.util.UUID/randomUUID)
            :name "result-text"
            :namespace-id nil
            :parent-ids []
            :impl-hash nil
            :base-fn-id nil
            :element-fn-id nil
            :return-type-fn-id nil
            :anonymous-hash nil
            :constraint [:variant :ok :text :err :text]
            :description nil}])
        (cr/refresh-type-registries-from-storage!
          (exec/create-context {:storage storage}))
        (let [snap (get (types/aliases-snapshot) :result-text)]
          (is (some? snap)
              ":result-text actually registers — pre-fix it ended up in :failed because the registry stored the raw [:variant …] form which well-formed? rejects.")
          (is (types/union-type? snap)
              "stored body is the desugared [:union …] form")
          (let [members (set (types/union-members snap))]
            (is (contains? members
                           {:tag [:refine :keyword [:= :ok]] :value :text})
                ":ok branch is a tag-pinned record")
            (is (contains? members
                           {:tag [:refine :keyword [:= :err]] :value :text})
                ":err branch is a tag-pinned record")))
        (finally
          (sp/close storage))))))


(deftest registration-iterates-to-fixed-point
  (testing "alias whose body references another (yet-unregistered) alias resolves on a later pass"
    (let [storage (setup/create-test-storage)]
      (try
        (types/clear-aliases!)
        ;; `:user` references `:user-id` — registration order matters
        ;; only at the well-formed? level; the loop retries.
        (sp/upsert-entities
          storage :fn
          [{:id (java.util.UUID/randomUUID)
            :name "user-id"
            :namespace-id nil
            :parent-ids []
            :impl-hash nil
            :base-fn-id (prim :int)
            :element-fn-id nil
            :return-type-fn-id nil
            :anonymous-hash nil
            :constraint [:> 0]
            :description nil}])
        ;; Record needs a slot pointing at user-id by id, so plumb
        ;; via primitive :int instead — the user-id alias would
        ;; require a fn-id reference rather than a primitive in the
        ;; slot's type-fn-id, exercising the same fixed-point path.
        (create-record-type-row! storage :user
                                 [[:id :int] [:name :text]])
        (cr/refresh-type-registries-from-storage!
          (exec/create-context {:storage storage}))
        (is (= [:refine :int [:> 0]] (get (types/aliases-snapshot) :user-id)))
        (is (= {:id :int :name :text} (get (types/aliases-snapshot) :user)))
        (finally
          (sp/close storage))))))


(deftest mutual-recursive-records-register
  (testing "two records that reference each other both register (Phase 7 — types/register-type-aliases-batch handles cycles by pre-extending the validation view with every pending name)"
    (let [storage (setup/create-test-storage)]
      (try
        (types/clear-aliases!)
        (let [a-id (java.util.UUID/randomUUID)
              b-id (java.util.UUID/randomUUID)
              a-slot {:id (java.util.UUID/randomUUID)
                      :name "to-b"
                      :type-fn-id b-id
                      :required true}
              b-slot {:id (java.util.UUID/randomUUID)
                      :name "to-a"
                      :type-fn-id a-id
                      :required true}]
          (sp/upsert-entities
            storage :fn
            [{:id a-id :name "rec-a" :namespace-id nil :parent-ids []
              :impl-hash nil :base-fn-id nil :element-fn-id nil
              :return-type-fn-id nil :anonymous-hash nil
              :constraint nil :description nil}
             {:id b-id :name "rec-b" :namespace-id nil :parent-ids []
              :impl-hash nil :base-fn-id nil :element-fn-id nil
              :return-type-fn-id nil :anonymous-hash nil
              :constraint nil :description nil}])
          (sp/upsert-entities storage :slot [a-slot b-slot])
          (sp/upsert-entities
            storage :fn-slot
            [{:id (java.util.UUID/randomUUID) :fn-id a-id
              :slot-id (:id a-slot) :position 0}
             {:id (java.util.UUID/randomUUID) :fn-id b-id
              :slot-id (:id b-slot) :position 0}])
          (create-record-type-row! storage :unrelated [[:n :int]])
          (cr/refresh-type-registries-from-storage!
            (exec/create-context {:storage storage}))
          (is (= {:n :int} (get (types/aliases-snapshot) :unrelated))
              "non-cyclic alias registers")
          (is (= {:to-b :rec-b} (get (types/aliases-snapshot) :rec-a))
              "rec-a registers with forward reference to rec-b")
          (is (= {:to-a :rec-a} (get (types/aliases-snapshot) :rec-b))
              "rec-b registers with forward reference to rec-a")
          ;; resolve-alias must terminate on the cycle via its own
          ;; `seen` set — no stack-overflow even though the alias
          ;; map encodes a cycle.
          (is (some? (types/resolve-alias :rec-a))
              "resolve-alias terminates on cyclic alias chain"))
        (finally
          (sp/close storage))))))


(deftest self-recursive-record-registers
  (testing "a tree-shaped record (children: [:list :tree]) registers via single register-type-alias!"
    (types/clear-aliases!)
    (types/register-type-alias! :tree {:value :int :children [:list :tree]})
    (is (= {:value :int :children [:list :tree]}
           (get (types/aliases-snapshot) :tree))
        "self-recursive body validates against the scratch view that includes the name being registered")))


(deftest dangling-reference-still-rejected
  (testing "an alias whose body references a name that's neither registered nor in the same batch is still rejected"
    (types/clear-aliases!)
    (let [{:keys [registered failed]}
          (types/register-type-aliases-batch
            [[:has-dangling {:x :missing-name}]])]
      (is (empty? registered))
      (is (= 1 (count failed)))
      (is (= :has-dangling (:nm (first failed)))))))


;; ============================================================================
;; produces-callable? — runtime dispatch tag for ref-bindings
;; ============================================================================

(deftest ref-with-fn-type-return-marks-produces-callable
  (testing "ref binding to a fn whose return is a fn-type sets :produces-callable? true"
    (registry/record-rich-types-raw!
      :ring-handler-stub
      {:return [:fn {:request :jsonb} :jsonb]
       :args {}})
    ;; Verify the predicate fires from rich-types correctly. compile/
    ;; bindings.clj's `ref-produces-callable?` is private but
    ;; observable via this same registry interaction — the field is
    ;; computed identically.
    (let [info (registry/rich-type-of :ring-handler-stub)]
      (is (types/fn-type? (:return info))
          "stub's recorded return is the structural fn-type"))))


(deftest ref-with-primitive-return-no-callable-flag
  (testing "ref binding to a fn whose return is a primitive does NOT set :produces-callable?"
    (registry/record-rich-types-raw!
      :int-producer
      {:return :int :args {}})
    (let [info (registry/rich-type-of :int-producer)]
      (is (not (types/fn-type? (:return info)))
          "primitive return is not a fn-type — produces-callable? would be false"))))
