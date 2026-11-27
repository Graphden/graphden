(ns graphden.input.runtime
  "Invocation-local lazy record values for the offline structured-input path.
   Not wired into the production compiler. Only explicitly created field
   cells are deferred; literal maps, functions and IDeref values stay opaque."
  (:require
    [graphden.executor.runtime :as runtime])
  (:import
    (clojure.lang
      AMapEntry
      APersistentMap
      IDeref
      IKVReduce
      IObj)))


(deftype FieldCell
  [resolver]

  IDeref

  (deref [_] (resolver))


  Object

  (toString [_] "#<input-field-cell>"))


(defn field-cell
  "Create one value occurrence. Allocate inside an invocation, never in a
   compiled global constant. The existing runtime thunk supplies thread-safe
   once semantics, including cached failures. Cells compare by identity."
  [resolver]
  (when-not (ifn? resolver)
    (throw (ex-info "Field resolver must be callable" {:type :input/invalid-cell})))
  (FieldCell. (runtime/thunk resolver)))


(defn value-cell
  "An opaque literal occurrence. A caller-provided callable or IDeref is a
   value, not an instruction to invoke or dereference it."
  [value]
  (field-cell (constantly value)))


(definterface RecordState
  (^Object fieldCells []))


(defn record-view?
  [value]
  (instance? RecordState value))


(defn- lazy-entry
  "A normal persistent map entry whose value is read only on value access.
   Key enumeration must not force a value just to construct its map entry."
  [key cell]
  (proxy [AMapEntry] []
    (key [] key)

    (val [] @cell)

    (getKey [] key)

    (getValue [] @cell)))


(defn- entry-seq
  [cells]
  (seq (map (fn [[key cell]] (lazy-entry key cell)) cells)))


(defn- read-field
  [cells key not-found]
  (if-let [entry (find cells key)]
    @(val entry)
    not-found))


(defn- field-key!
  [key]
  (when-not (keyword? key)
    (throw (ex-info "Record field names must be keywords" {:type :input/invalid-field-key}))))


(declare ^:private make-record)


(defn- make-record
  [cells metadata keyword-keys?]
  ;; APersistentMap supplies symmetric value equality, Clojure/Java hashes,
  ;; map invocation, Java Map views and persistent conj. Those are ordinary
  ;; value consumers, never the compiler's non-forcing memo identity.
  (proxy [APersistentMap IObj IKVReduce RecordState] []
    (fieldCells [] cells)

    (meta [] metadata)

    (withMeta [m] (make-record cells m keyword-keys?))

    (count [] (count cells))

    (containsKey [key] (contains? cells key))

    (entryAt
      [key]
      (when-let [entry (find cells key)] (lazy-entry key (val entry))))

    (valAt
      ([key] (read-field cells key nil))
      ([key not-found] (read-field cells key not-found)))

    (assoc
      [key value]
      (when keyword-keys? (field-key! key))
      (make-record (assoc cells key (value-cell value)) metadata keyword-keys?))

    (assocEx
      [key value]
      (when keyword-keys? (field-key! key))
      (when (contains? cells key)
        (throw (RuntimeException. "Key already present")))
      (make-record (assoc cells key (value-cell value)) metadata keyword-keys?))

    (without [key] (make-record (dissoc cells key) metadata keyword-keys?))

    (empty [] (make-record {} metadata keyword-keys?))

    (seq [] (entry-seq cells))

    (iterator [] (Iterable/.iterator (or (entry-seq cells) [])))

    (kvreduce
      [f init]
      (reduce-kv (fn [acc key cell] (f acc key @cell)) init cells))))


(defn- checked-cells
  [cells]
  (when-not (and (map? cells) (not (record-view? cells))
                 (every? #(instance? FieldCell %) (vals cells)))
    (throw (ex-info "Record fields must be explicit cells" {:type :input/invalid-record-cells})))
  (into {} cells))


(defn record-view
  "Construct a persistent map view from keyword field-name → FieldCell. Nested
   records must be supplied explicitly as cell results; dictionaries are
   never inspected or recursively wrapped. Share existing cells to preserve
   projections of one producer; independent invocations allocate fresh cells."
  [cells]
  (let [cells (checked-cells cells)]
    (doseq [key (keys cells)] (field-key! key))
    (make-record cells nil true)))


(defn dictionary-view
  "Project a fully supplied record as an open native dictionary. Every entry
   retains its original cell, including extra keys outside the declared fields."
  [cells]
  (make-record (checked-cells cells) nil false))


(defn cache-token
  "Return a non-forcing identity token for an explicit record view. Tokens
   compare field names and cell identities, ignoring metadata and cell values.
   Assoc/dissoc preserve unchanged cells; equal independent computations do
   not deduplicate by shape. This is NOT a replacement for the production
   cache's existing value equality on ordinary arguments."
  [record]
  (when-not (record-view? record)
    (throw (ex-info "Cache token requires a record view" {:type :input/record-view-required})))
  (graphden.input.runtime.RecordState/.fieldCells record))


(defn diagnostic-summary
  "Safe diagnostic shape. Printing/serializing the map itself is a VALUE
   consumption operation and may execute fields; diagnostics must opt into
   this summary instead. No deferred values or literal payloads are read."
  [record]
  {:kind :input-record :field-count (count (cache-token record))})


(defn materialize-record
  "Explicitly read a record's fields into ordinary maps, recursively only
   for explicit record views. Literal dictionaries, sequences, functions and
   IDeref values remain untouched. Streaming serializers can consume the map
   directly instead, retaining their own size/depth limits."
  [value]
  (if (record-view? value)
    (reduce-kv (fn [result key child]
                 (assoc result key (materialize-record child)))
               {} value)
    value))
