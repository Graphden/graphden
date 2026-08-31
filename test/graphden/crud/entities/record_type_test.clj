(ns graphden.crud.entities.record-type-test
  "Unit tests for the compound type-row create / update pipeline —
   parse stage, the journalled apply bodies and both rollback
   replayers. Storage is an in-memory protocol stub that records
   every write, so each test asserts the exact row shapes, write
   order and journal contents without a database. Type refs are
   primitive names / UUID strings, which `resolve-type-fn-id`
   resolves deterministically without touching storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities.record-type :as rt]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]))


(def ^:private int-type-id (ids/primitive-fn-id :int))
(def ^:private text-type-id (ids/primitive-fn-id :text))
(def ^:private sequence-type-id (ids/primitive-fn-id :sequence))


(defn- row-matches?
  "In-memory `where` matcher: a vector value means membership (the IN
   clause shape `load-update-record-type-state` uses for slot ids),
   anything else means equality."
  [where row]
  (every? (fn [[k v]]
            (if (vector? v)
              (some #(= % (get row k)) v)
              (= v (get row k))))
          where))


(defn- stub-storage
  "StorageCRUD stub over `rows` (an atom of `{entity-type {id row}}`),
   appending every mutation to the `writes` atom as
   `[:create/:update/:delete entity-type payload]`. `opts` may carry
   `:fail-delete-ids` — a set of ids whose delete throws (exercises
   the best-effort rollback contract)."
  ([rows writes] (stub-storage rows writes {}))
  ([rows writes {:keys [fail-delete-ids] :or {fail-delete-ids #{}}}]
   (reify sp/StorageCRUD
     (read-entity
       [_ entity-type id]
       (get-in @rows [entity-type id]))

     (create-entity
       [_ entity-type data]
       (swap! writes conj [:create entity-type data])
       (swap! rows assoc-in [entity-type (:id data)] data)
       data)

     (update-entity
       [_ entity-type id patch]
       (swap! writes conj [:update entity-type id patch])
       (swap! rows update-in [entity-type id] merge patch)
       patch)

     (delete-entity
       [_ entity-type id]
       (when (fail-delete-ids id)
         (throw (ex-info "stubbed delete failure" {:id id})))
       (swap! writes conj [:delete entity-type id])
       (swap! rows update entity-type dissoc id)
       true)

     (query-entities
       [_ entity-type where]
       (filterv #(row-matches? where %) (vals (get @rows entity-type))))

     (query-entities
       [_ entity-type where _opts]
       (filterv #(row-matches? where %) (vals (get @rows entity-type))))

     (query-latest-per-group
       [_ _ _ _]
       (throw (AssertionError. "query-latest-per-group must not run"))))))


(defn- fresh-ctx
  "Empty stub world: `{:ctx … :rows … :writes …}`."
  ([] (fresh-ctx {}))
  ([opts]
   (let [rows (atom {})
         writes (atom [])]
     {:ctx {:storage (stub-storage rows writes opts)}
      :rows rows
      :writes writes})))


;; === parse-create-record-type ===============================================

(deftest parse-create-record-type-full-body-test
  (let [ns-id (java.util.UUID/randomUUID)
        parsed (rt/parse-create-record-type
                 {:body {:name "point"
                         :namespace-id (str ns-id)
                         :description "a point"
                         :fields [{:name "x" :type "int"}]}})]
    (is (= {:name "point"
            :ns-id ns-id
            :description "a point"
            :fields [{:name "x" :type "int"}]}
           parsed))))


(deftest parse-create-record-type-coercion-test
  (testing "non-string name is coerced, absent name stays nil"
    (is (= "42" (:name (rt/parse-create-record-type {:body {:name 42}}))))
    (is (nil? (:name (rt/parse-create-record-type {:body {}})))))
  (testing "blank / absent / malformed namespace-id all collapse to nil"
    (is (nil? (:ns-id (rt/parse-create-record-type
                        {:body {:namespace-id "   "}}))))
    (is (nil? (:ns-id (rt/parse-create-record-type {:body {}}))))
    (is (nil? (:ns-id (rt/parse-create-record-type
                        {:body {:namespace-id "not-a-uuid"}})))))
  (testing "fields default to an empty vector"
    (is (= [] (:fields (rt/parse-create-record-type {:body {}}))))))


;; === apply-create-record-type-body ==========================================

(deftest create-record-type-writes-fn-slots-and-junctions-test
  (let [{:keys [ctx writes]} (fresh-ctx)
        journal (atom [])
        result (rt/apply-create-record-type-body
                 {:name "point" :ns-id nil :description "a point"
                  :fields [{:name "x" :type "int" :description "abscissa"}
                           {:name "label" :type "text" :required false}]}
                 journal ctx)
        [[_ _ fn-row] [_ _ slot-x] [_ _ fs-x] [_ _ slot-label] [_ _ fs-label]]
        @writes]
    (testing "write order is fn, then slot + fn-slot per field"
      (is (= [[:create :fn] [:create :slot] [:create :fn-slot]
              [:create :slot] [:create :fn-slot]]
             (mapv #(subvec % 0 2) @writes))))
    (testing "fn row is a bare type-row: no parents, no base-fn markers"
      (is (= {:name "point" :namespace-id nil :parent-ids []
              :base-fn-id nil :element-fn-id nil :return-type-fn-id nil
              :anonymous-hash nil :constraint nil :description "a point"}
             (dissoc fn-row :id))))
    (testing "slot rows carry resolved type ids, required default and
              the optional description"
      (is (= {:name "x" :type-fn-id int-type-id :required true
              :description "abscissa"}
             (dissoc slot-x :id)))
      (is (= {:name "label" :type-fn-id text-type-id :required false}
             (dissoc slot-label :id))))
    (testing "fn-slot junctions link the new fn to each slot in order"
      (is (= {:fn-id (:id fn-row) :slot-id (:id slot-x) :position 0}
             (dissoc fs-x :id)))
      (is (= {:fn-id (:id fn-row) :slot-id (:id slot-label) :position 1}
             (dissoc fs-label :id))))
    (testing "journal mirrors every write in creation order"
      (is (= [[:fn (:id fn-row)]
              [:slot (:id slot-x)] [:fn-slot (:id fs-x)]
              [:slot (:id slot-label)] [:fn-slot (:id fs-label)]]
             @journal)))
    (testing "success response stringifies the new id"
      (is (= {:ok true :id (str (:id fn-row)) :name "point"} result)))))


(deftest create-record-type-omits-empty-description-test
  (let [{:keys [ctx writes]} (fresh-ctx)
        _ (rt/apply-create-record-type-body
            {:name "p" :ns-id nil :description "" :fields []}
            (atom []) ctx)
        [[_ _ fn-row]] @writes]
    (is (not (contains? fn-row :description)))))


(deftest create-record-type-blank-field-name-throws-test
  (let [{:keys [ctx]} (fresh-ctx)
        journal (atom [])
        ex (try (rt/apply-create-record-type-body
                  {:name "p" :ns-id nil :description nil
                   :fields [{:name "  " :type "int"}]}
                  journal ctx)
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :type-row/field-missing-name (:type (ex-data ex))))
    (testing "the already-written fn row stays journalled for rollback"
      (is (= [:fn] (mapv first @journal))))))


(deftest create-record-type-unknown-type-throws-test
  (let [{:keys [ctx]} (fresh-ctx)
        journal (atom [])
        ex (try (rt/apply-create-record-type-body
                  {:name "p" :ns-id nil :description nil
                   :fields [{:name "x" :type "no-such-type"}]}
                  journal ctx)
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :crud/unknown-type-ref (:type (ex-data ex))))
    (is (= [:fn] (mapv first @journal)))))


;; === apply-create-list-type-body ============================================

(deftest create-list-type-writes-element-fn-and-items-slot-test
  (let [{:keys [ctx writes]} (fresh-ctx)
        journal (atom [])
        result (rt/apply-create-list-type-body
                 {:name "ints" :ns-id nil :description nil
                  :element-ref "int"}
                 journal ctx)
        [[_ _ fn-row] [_ _ slot-row] [_ _ fs-row]] @writes]
    (testing "fn row carries the resolved element type"
      (is (= {:name "ints" :namespace-id nil :parent-ids []
              :base-fn-id nil :element-fn-id int-type-id
              :return-type-fn-id nil :anonymous-hash nil :constraint nil}
             (dissoc fn-row :id))))
    (testing "the synthesised items slot is a required sequence"
      (is (= {:name "items" :type-fn-id sequence-type-id :required true}
             (dissoc slot-row :id)))
      (is (= {:fn-id (:id fn-row) :slot-id (:id slot-row) :position 0}
             (dissoc fs-row :id))))
    (testing "journal + response mirror the create path"
      (is (= [:fn :slot :fn-slot] (mapv first @journal)))
      (is (= {:ok true :id (str (:id fn-row)) :name "ints"} result)))))


;; === apply-create-rollback ==================================================

(deftest create-rollback-deletes-in-reverse-test
  (let [{:keys [ctx rows writes]} (fresh-ctx)
        fn-id (java.util.UUID/randomUUID)
        slot-id (java.util.UUID/randomUUID)
        fs-id (java.util.UUID/randomUUID)
        _ (reset! rows {:fn {fn-id {:id fn-id}}
                        :slot {slot-id {:id slot-id}}
                        :fn-slot {fs-id {:id fs-id}}})
        journal (atom [[:fn fn-id] [:slot slot-id] [:fn-slot fs-id]])
        result (rt/apply-create-rollback
                 journal
                 (ex-info "boom" {:type :storage-error/whatever})
                 ctx)]
    (testing "entries replay newest-first"
      (is (= [[:delete :fn-slot fs-id]
              [:delete :slot slot-id]
              [:delete :fn fn-id]]
             @writes)))
    (testing "ExceptionInfo surfaces message + ex-data"
      (is (= {:ok false :error "boom"
              :data {:type :storage-error/whatever}}
             result)))))


(deftest create-rollback-is-best-effort-and-nil-safe-test
  (let [slot-id (java.util.UUID/randomUUID)
        fn-id (java.util.UUID/randomUUID)
        {:keys [ctx writes]} (fresh-ctx {:fail-delete-ids #{slot-id}})
        journal (atom [[:fn fn-id] [:slot slot-id]])
        result (rt/apply-create-rollback
                 journal (RuntimeException.) ctx)]
    (testing "a failing delete does not block earlier journal entries"
      (is (= [[:delete :fn fn-id]] @writes)))
    (testing "a nil exception message coerces to a string, and plain
              exceptions carry no :data key"
      (is (= {:ok false :error ""} result)))))


;; === apply-update-record-type-body ==========================================

(defn- seed-record-type!
  "Populate `rows` with an existing record type: fn row + one
   `[slot fn-slot]` pair per `[name type-id]` field, positioned in
   order. Returns `{:fn-id … :slots {name {:slot-id … :fs-id …}}}`."
  [rows fn-name fields]
  (let [fn-id (java.util.UUID/randomUUID)
        slots (into {}
                    (map-indexed
                      (fn [idx [nm type-id]]
                        (let [slot-id (java.util.UUID/randomUUID)
                              fs-id (java.util.UUID/randomUUID)]
                          (swap! rows assoc-in [:slot slot-id]
                                 {:id slot-id :name nm :type-fn-id type-id
                                  :required true})
                          (swap! rows assoc-in [:fn-slot fs-id]
                                 {:id fs-id :fn-id fn-id :slot-id slot-id
                                  :position idx})
                          [nm {:slot-id slot-id :fs-id fs-id}])))
                    fields)]
    (swap! rows assoc-in [:fn fn-id]
           {:id fn-id :name fn-name :parent-ids []})
    {:fn-id fn-id :slots slots}))


(deftest update-record-type-reorder-add-remove-test
  (let [{:keys [ctx rows writes]} (fresh-ctx)
        {:keys [fn-id slots]} (seed-record-type!
                                rows "point" [["x" int-type-id]
                                              ["y" int-type-id]])
        journal (atom [])
        ;; Drop `x`, keep `y` moved to the front, add fresh `label`.
        result (rt/apply-update-record-type-body
                 {:fn-id fn-id :name "point2" :description "renamed"
                  :has-description? true
                  :fields [{:name "y" :type "int"}
                           {:name "label" :type "text"}]}
                 journal ctx)
        x-fs-id (get-in slots ["x" :fs-id])
        y-slot-id (get-in slots ["y" :slot-id])
        y-fs-id (get-in slots ["y" :fs-id])]
    (testing "reused slot `y` keeps its slot row but is re-positioned via
              delete + re-create (positions are UNIQUE per fn)"
      (is (some #(= [:delete :fn-slot y-fs-id] %) @writes))
      (is (some #(= % [:create :fn-slot {:id y-fs-id :fn-id fn-id
                                         :slot-id y-slot-id :position 0}])
                @writes)))
    (testing "removed field `x` loses its junction; its slot row survives"
      (is (some #(= [:delete :fn-slot x-fs-id] %) @writes))
      (is (contains? (get @rows :slot) (get-in slots ["x" :slot-id]))))
    (testing "new field `label` mints a slot + junction at position 1"
      (let [label-slot (first (filter #(= "label" (:name %))
                                      (vals (get @rows :slot))))
            label-fs (first (filter #(= (:id label-slot) (:slot-id %))
                                    (vals (get @rows :fn-slot))))]
        (is (= {:name "label" :type-fn-id text-type-id :required true}
               (dissoc label-slot :id)))
        (is (= {:fn-id fn-id :slot-id (:id label-slot) :position 1}
               (dissoc label-fs :id)))))
    (testing "rename + description land as one fn-row patch"
      (is (some #(= % [:update :fn fn-id {:name "point2"
                                          :description "renamed"}])
                @writes)))
    (testing "every write is journalled with its op for the rollback path"
      (is (= #{:create :delete} (set (map :op @journal))))
      (is (every? #(contains? % :entity-type) @journal)))
    (is (= {:ok true :id (str fn-id) :name "point2"} result))))


(deftest update-record-type-noop-when-nothing-changes-test
  (let [{:keys [ctx rows writes]} (fresh-ctx)
        {:keys [fn-id]} (seed-record-type!
                          rows "point" [["x" int-type-id]])
        journal (atom [])
        result (rt/apply-update-record-type-body
                 {:fn-id fn-id :name nil :description nil
                  :has-description? false
                  :fields [{:name "x" :type "int"}]}
                 journal ctx)]
    (testing "identical fields at identical positions write nothing"
      (is (= [] @writes))
      (is (= [] @journal)))
    (testing "response falls back to the existing fn name"
      (is (= {:ok true :id (str fn-id) :name "point"} result)))))


(deftest update-record-type-retype-mints-new-slot-test
  (let [{:keys [ctx rows]} (fresh-ctx)
        {:keys [fn-id slots]} (seed-record-type!
                                rows "point" [["x" int-type-id]])
        old-slot-id (get-in slots ["x" :slot-id])
        _ (rt/apply-update-record-type-body
            {:fn-id fn-id :name nil :description nil :has-description? false
             :fields [{:name "x" :type "text"}]}
            (atom []) ctx)
        x-slots (filterv #(= "x" (:name %)) (vals (get @rows :slot)))
        junctions (vals (get @rows :fn-slot))]
    (testing "same name + different type is a mint, not a reuse — slot
              rows are immutable"
      (is (= #{int-type-id text-type-id} (set (map :type-fn-id x-slots))))
      (is (= 1 (count junctions)))
      (is (not= old-slot-id (:slot-id (first junctions))))
      (is (= text-type-id
             (:type-fn-id (get-in @rows [:slot (:slot-id (first junctions))])))))))


(deftest update-record-type-blank-field-name-throws-before-writes-test
  (let [{:keys [ctx rows writes]} (fresh-ctx)
        {:keys [fn-id]} (seed-record-type! rows "point" [["x" int-type-id]])
        ex (try (rt/apply-update-record-type-body
                  {:fn-id fn-id :name nil :description nil
                   :has-description? false
                   :fields [{:name "x" :type "int"} {:name "" :type "int"}]}
                  (atom []) ctx)
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :type-row/field-missing-name (:type (ex-data ex))))
    (testing "types resolve up-front, so no storage write happened"
      (is (= [] @writes)))))


;; === apply-update-record-type-rollback ======================================

(deftest update-rollback-replays-journal-in-reverse-test
  (let [{:keys [ctx rows writes]} (fresh-ctx)
        slot-id (java.util.UUID/randomUUID)
        deleted-row {:id (java.util.UUID/randomUUID)
                     :fn-id (java.util.UUID/randomUUID)
                     :slot-id slot-id :position 0}
        _ (swap! rows assoc-in [:slot slot-id] {:id slot-id})
        journal (atom [{:op :delete :entity-type :fn-slot :row deleted-row}
                       {:op :create :entity-type :slot :id slot-id}
                       {:op :noop-unknown}])
        result (rt/apply-update-record-type-rollback
                 journal
                 (ex-info "late failure" {:type :storage-error/conflict})
                 ctx)]
    (testing "newest entry first: undo the create, then resurrect the
              deleted row; unknown ops are skipped"
      (is (= [[:delete :slot slot-id]
              [:create :fn-slot deleted-row]]
             @writes)))
    (is (= {:ok false :error "late failure"
            :data {:type :storage-error/conflict}}
           result))))


(deftest update-rollback-swallows-step-failures-test
  (let [failing-id (java.util.UUID/randomUUID)
        other-id (java.util.UUID/randomUUID)
        {:keys [ctx writes]} (fresh-ctx {:fail-delete-ids #{failing-id}})
        journal (atom [{:op :create :entity-type :slot :id other-id}
                       {:op :create :entity-type :slot :id failing-id}])
        result (rt/apply-update-record-type-rollback
                 journal (RuntimeException.) ctx)]
    (testing "a stuck reversal doesn't block the remaining entries"
      (is (= [[:delete :slot other-id]] @writes)))
    (is (= {:ok false :error ""} result))))
