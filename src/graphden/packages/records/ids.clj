(ns graphden.packages.records.ids
  "Deterministic UUID-v5 id derivation + anonymous-shape hashing +
   primitive boot-data. Leaf namespace — depends on nothing else under
   `graphden.packages.records`."
  (:require
    [clojure.string :as str])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)
    (java.util
      UUID)))


;; =============================================================================
;; UUID v5 (deterministic, name-based)
;; =============================================================================

(def ^:private records-namespace-uuid
  "Stable namespace UUID for records produced by this parser. Changing
   it invalidates EVERY row's id — only do that during full rebuild."
  #uuid "f0a3b8c2-7e9d-4a1c-9f8b-3d4e5f6a7b8c")


(defn uuid-v5
  "UUID v5 (SHA-1 of namespace || name). Deterministic — same inputs →
   same output."
  ^UUID [namespace-uuid name-str]
  (let [ns-bytes (let [arr (byte-array 16)
                       buf (java.nio.ByteBuffer/wrap arr)]
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getMostSignificantBits namespace-uuid))
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getLeastSignificantBits namespace-uuid))
                   arr)
        name-bytes (String/.getBytes name-str StandardCharsets/UTF_8)
        digest (doto (MessageDigest/getInstance "SHA-1")
                 (MessageDigest/.update ns-bytes)
                 (MessageDigest/.update name-bytes))
        hash-bytes (MessageDigest/.digest digest)]
    (aset hash-bytes 6 (unchecked-byte (bit-or (bit-and (aget hash-bytes 6) 0x0f) 0x50)))
    (aset hash-bytes 8 (unchecked-byte (bit-or (bit-and (aget hash-bytes 8) 0x3f) 0x80)))
    (let [buf (java.nio.ByteBuffer/wrap hash-bytes 0 16)
          msb (java.nio.ByteBuffer/.getLong buf)
          lsb (java.nio.ByteBuffer/.getLong buf)]
      (UUID. msb lsb))))


(defn fn-id
  "Deterministic UUID for a globally-named fn. `ns-path` is the
   module's `:namespace` string (e.g. \"core.system\") or nil for
   namespace-less. `fn-name` is a keyword."
  ^UUID [ns-path fn-name]
  (uuid-v5 records-namespace-uuid (str "fn:" (or ns-path "") "/" (name fn-name))))


(defn anonymous-fn-id
  "Deterministic UUID for an anonymous (composite) fn keyed by the
   shape-hash. Anonymous types with the same shape collapse to one
   row via `fn.anonymous_hash` UNIQUE."
  ^UUID [shape-hash]
  (uuid-v5 records-namespace-uuid (str "anon-fn:" shape-hash)))


(defn seeded-service-id
  "Deterministic UUID for a `:service` row seeded from a
   `package.edn :services` entry. Keyed by `(package-name,
   service-name)` so re-running the seeder lands on the same row
   (idempotent), letting admins toggle `:enabled?` without the
   next boot recreating their preference."
  ^UUID [package-name service-name]
  (uuid-v5 records-namespace-uuid
           (str "seeded-service:" package-name ":" (name service-name))))


(defn slot-id
  "Deterministic UUID for a slot owned by a specific fn. Slots are
   immutable once created; sharing across fns happens via composition
   rows referencing the same slot-id (future optimization — for now
   each fn owns its own slot rows)."
  ^UUID [owner-fn-id slot-name]
  (uuid-v5 records-namespace-uuid
           (str "slot:" owner-fn-id ":" (name slot-name))))


(defn fn-slot-id
  "Deterministic UUID for a fn-slot junction row."
  ^UUID [own-fn-id own-slot-id]
  (uuid-v5 records-namespace-uuid
           (str "fn-slot:" own-fn-id ":" own-slot-id)))


(defn binding-id
  "Deterministic UUID for a binding (fn × slot)."
  ^UUID [own-fn-id own-slot-id]
  (uuid-v5 records-namespace-uuid
           (str "binding:" own-fn-id ":" own-slot-id)))


(defn binding-list-item-id
  "Deterministic UUID for a binding's ordered list item."
  ^UUID [owner-binding-id position]
  (uuid-v5 records-namespace-uuid
           (str "list-item:" owner-binding-id ":" position)))


;; =============================================================================
;; Anonymous-shape hashing
;; =============================================================================

(defn digest-hex
  "Lower-case hex digest of `s` under `algo` (e.g. \"SHA-1\", \"SHA-256\").
   Shared by the shape-dedup hashing here and the executor registry."
  [algo s]
  (let [digest (MessageDigest/getInstance algo)
        utf-bytes (String/.getBytes ^String s StandardCharsets/UTF_8)
        hash-bytes (MessageDigest/.digest digest utf-bytes)]
    (str/join (map #(format "%02x" (bit-and ^byte % 0xff)) hash-bytes))))


(defn shape-hash
  "Stable hash of a shape — sorted (slot-name, type-keyword) pairs. Used
   to dedupe anonymous composites that have identical structure across
   different declarations."
  [shape-map]
  (digest-hex "SHA-1"
              (pr-str (->> shape-map
                           (into (sorted-map))
                           (mapv (fn [[k v]] [(name k) (pr-str v)]))))))


;; =============================================================================
;; Primitive boot-data
;; =============================================================================

(def primitive-names
  "15 primitive types pre-seeded as fn-rows on startup. Each becomes a
   leaf in the type tree — slots reference these via `slot.type-fn-id`.

   `:fn-ref` is the IDENTITY primitive: a slot typed `:fn-ref` receives
   the id of the fn bound to it — never its value, never a callable.
   The binding is an ordinary `ref-fn-id` row (an explicit graph edge,
   visible in Used-by, protected from GC), but it is not an EVALUATION
   edge: the compiler bakes the id, the free-arg walkers stop at it,
   and the cycle checks ignore it — so two services may each hold the
   other's identity. `:service-endpoint` (web/service) is the first
   consumer."
  [:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes
   :any :fn :sequence :keyword :float :fn-ref])


(defn primitive-fn-id
  "Deterministic fn-id for one of the 15 primitive types. Public so
   callers (loader, system/core for `:fn-type` aliases) can resolve
   a primitive keyword to the fn-id without going through the full
   `name->id` map."
  ^UUID [primitive-name]
  (uuid-v5 records-namespace-uuid
           (str "primitive:" (name primitive-name))))


(defn primitive-fn-ids
  "Map `{primitive-keyword → fn-id}` for the 15 base primitives."
  []
  (into {}
        (map (fn [p] [p (primitive-fn-id p)]))
        primitive-names))


(defn boot-primitive-records
  "Records to upsert at startup for the 15 primitive types. Each is a
   bare fn-row — name only, no slots/parents/impl/constraint."
  []
  (mapv (fn [p]
          {:kind :fn
           :id (primitive-fn-id p)
           :name (name p)
           :namespace-id nil
           :parent-ids []
           :base-fn-id nil
           :element-fn-id nil
           :return-type-fn-id nil
           :anonymous-hash nil
           :constraint nil
           :description (str "Primitive type :" (name p) ".")})
        primitive-names))


;; =============================================================================
;; Identity edges (`:fn-ref`)
;; =============================================================================

(def fn-ref-type-id
  "The `:fn-ref` primitive's fn-id — the type a slot carries when it
   takes a fn's IDENTITY rather than its value. Public so the layers
   that classify graph edges (compile deps, the cycle constraint) can
   recognise an identity edge by type id, never by slot or fn name."
  (primitive-fn-id :fn-ref))


(defn identity-edge?
  "Is `binding` (a `:binding` row) on `slot` (its `:slot` row) an
   IDENTITY edge — a ref bound into a `:fn-ref`-typed slot? Such an
   edge names a fn without evaluating it, so it is excluded from
   evaluation-dependency walks (compile order, invalidation closure,
   cycle detection) while staying a real inbound reference for
   Used-by and GC. The binding's own `:type-override-fn-id` wins over
   the slot's declared type, mirroring the executor's effective-type
   rule."
  [binding slot]
  (boolean (and (:ref-fn-id binding)
                (= fn-ref-type-id
                   (or (:type-override-fn-id binding) (:type-fn-id slot))))))
