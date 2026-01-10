(ns graphden.memory-storage.codec
  "Value codec for memory storage.

   Memory storage stores values natively in Clojure data structures,
   so no encoding/decoding is needed. This codec provides a passthrough
   implementation for API consistency with other backends."
  (:require
    [graphden.storage-protocol.interface :as sp]))


;; === MemoryValueCodec implementation ===
;;
;; Memory storage keeps values in their native Clojure form.
;; This codec is a passthrough that satisfies the StorageValueCodec protocol.

(defrecord MemoryValueCodec
  []

  sp/StorageValueCodec

  (encode-value
    [_this value _field-spec]
    ;; Memory storage keeps values as-is
    value)


  (decode-value
    [_this value _field-spec]
    ;; No decoding needed for in-memory values
    value)


  (encode-row
    [_this row _field-specs]
    ;; Rows are stored as-is in memory
    row)


  (decode-row
    [_this row _field-specs]
    ;; No decoding needed
    row))


(defn create-codec
  "Creates a memory value codec instance."
  []
  (->MemoryValueCodec))


(def ^:private default-codec (delay (create-codec)))


(defn encode-row
  "Encodes a row using the default codec (passthrough for memory)."
  [row field-specs]
  (sp/encode-row @default-codec row field-specs))


(defn decode-row
  "Decodes a row using the default codec (passthrough for memory)."
  [row field-specs]
  (sp/decode-row @default-codec row field-specs))
