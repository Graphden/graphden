(ns graphden.executor.test-setup
  "Shared test setup for executor tests in the slot/fn-slot/binding model.

   Helpers create fn rows, slot rows, fn-slot junctions, and binding
   rows directly via the storage protocol. Higher-level helpers like
   `setup-add-function!` synthesise a small example graph end-to-end."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as rt]
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


;; ============================================================================
;; Impl helper — inline `defbase`-style for test registration
;; ============================================================================

(defmacro fn-impl
  "Build an anonymous base-fn impl whose body references args by name,
   mirroring `defbase` but inline. The symbols `args` and `ctx` are
   bound by the generated fn so HOF impls and lazy impls can reach
   them."
  [arg-syms & body]
  (let [let-bindings (mapcat (fn [s] [s `(rt/resolve-arg ~'args ~(keyword s))]) arg-syms)]
    `(fn [~'args ~'ctx]
       (let [~'ctx ~'ctx
             ~'args ~'args
             ~@let-bindings]
         ~@body))))


;; ============================================================================
;; Container management
;; ============================================================================

(def ^:dynamic *container*
  nil)


(defn create-container-fixture
  []
  (pth/create-container-fixture #'*container*))


(defn create-clean-db-fixture
  []
  (pth/create-clean-db-fixture #'*container*))


(defn create-test-storage
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))
        schema (gds/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    ;; Pre-seed the 14 primitive fn-rows so slot.type-fn-id refs resolve.
    ;; `boot-primitive-records` returns tagged records (`:kind :fn`); strip
    ;; the tag before storage upsert.
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    storage))


;; ============================================================================
;; Test helpers — slot/fn-slot/binding model
;; ============================================================================

(def primitive-fn-ids (records/primitive-fn-ids))


(defn create-base-fn!
  "Creates a base-fn row (impl-hash set, no parent-ids). Returns the
   created fn record."
  ([storage fn-name]
   (create-base-fn! storage fn-name nil))
  ([storage fn-name return-type-keyword]
   (sp/create-entity storage :fn
                     {:name fn-name
                      :parent-ids nil
                      :impl-hash "test-stub-hash"
                      :return-type-fn-id (when return-type-keyword
                                           (get primitive-fn-ids return-type-keyword))})))


(defn create-composed-fn!
  "Creates a composed fn-row inheriting from `parent-id`."
  [storage fn-name parent-id]
  (sp/create-entity storage :fn
                    {:name fn-name
                     :parent-ids [parent-id]}))


(defn create-slot!
  "Creates a slot whose type points at a primitive (by keyword) or an
   explicit fn-id (UUID)."
  [storage slot-name type-ref]
  (let [type-fn-id (cond
                     (uuid? type-ref) type-ref
                     (keyword? type-ref) (or (get primitive-fn-ids type-ref) type-ref)
                     :else type-ref)]
    (sp/create-entity storage :slot
                      {:name slot-name
                       :type-fn-id type-fn-id})))


(defn attach-slot!
  "Inserts a fn-slot junction at the given position."
  [storage fn-id slot-id position]
  (sp/create-entity storage :fn-slot
                    {:fn-id fn-id
                     :slot-id slot-id
                     :position position}))


(defn bind-value!
  "Creates a value-binding for `slot-id` on `fn-id`."
  [storage fn-id slot-id value]
  (sp/create-entity storage :binding
                    {:fn-id fn-id
                     :slot-id slot-id
                     :value value
                     :override-kind :fixed}))


(defn bind-ref!
  "Creates a ref-binding for `slot-id` on `fn-id` pointing at
   `target-fn-id`."
  [storage fn-id slot-id target-fn-id]
  (sp/create-entity storage :binding
                    {:fn-id fn-id
                     :slot-id slot-id
                     :ref-fn-id target-fn-id
                     :override-kind :fixed}))


(defn create-arg!
  "Compatibility helper that bridges legacy `arg`-table call sites.
   Two flavours:

   1. Primary-arg form (no `:source-id`): create a slot owned by
      `fn-id` and attach it via fn-slot. Returns the slot record.

   2. Inherited-arg form (`:source-id` set): treat `fn-id` as a
      composed fn that wants to bind the slot named by `:source-id`'s
      legacy id. Since slot-ids are deterministic from `(parent-fn-id,
      slot-name)`, we recover the slot-id from the source-arg's
      `:fn-id` + `:name` and emit a binding row. `:value` / `:ref-id`
      determine which kind of binding."
  ([storage fn-id opts]
   (create-arg! storage fn-id opts 0))
  ([storage fn-id
    {arg-name :name arg-type :type
     :keys [source-id value ref-id]} position]
   (cond
     ;; Inherited-with-binding form. The caller passes a slot record
     ;; (or its id) as `:source-id`. We recover the slot-id from the
     ;; record and add the corresponding binding row.
     source-id
     (let [;; Look up the source slot by id. The `:source-id` parameter
           ;; in legacy tests was an arg-row id; in the new model
           ;; `setup-add-function!` returns slot records as `:slot-a`/
           ;; `:slot-b`, so source-id IS the slot-id directly.
           slot-id source-id]
       (cond
         (some? value) (bind-value! storage fn-id slot-id value)
         (some? ref-id) (bind-ref! storage fn-id slot-id ref-id)
         :else nil))

     :else
     (let [slot (create-slot! storage arg-name arg-type)]
       (attach-slot! storage fn-id (:id slot) position)
       slot))))


(defn setup-add-function!
  "Builds a small `:add` example: base-fn `add` with two `:int` slots,
   plus a composed instance with neither bound. Returns a map with
   `:base-fn`, `:slot-a` / `:slot-b` (and `:arg-a` / `:arg-b` aliases
   for legacy callers), `:composed-fn`."
  [storage]
  (exec/register-base-fn!
    :add
    (fn [args _ctx]
      (+ (rt/resolve-arg args :a) (rt/resolve-arg args :b))))
  (let [unique-suffix (str (random-uuid))
        base-fn (create-base-fn! storage "add" :int)
        slot-a (create-slot! storage "a" :int)
        slot-b (create-slot! storage "b" :int)
        _ (attach-slot! storage (:id base-fn) (:id slot-a) 0)
        _ (attach-slot! storage (:id base-fn) (:id slot-b) 1)
        composed-fn (create-composed-fn! storage
                                         (str "my-add-" unique-suffix)
                                         (:id base-fn))]
    {:base-fn base-fn
     :slot-a slot-a :slot-b slot-b
     ;; Legacy aliases — `setup/create-arg!` interprets `:source-id`
     ;; as the slot-id directly, so passing `(:id arg-a)` works.
     :arg-a slot-a :arg-b slot-b
     :composed-fn composed-fn}))
