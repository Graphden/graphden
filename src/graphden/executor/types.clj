(ns graphden.executor.types
  "Human-readable type hints for error messages.

   Runtime type validation used to live here too; it was tied to the
   legacy queue's arg-dispatch path and got removed along with the
   queue. All that remains is a small registry of per-type hint
   strings that error messages can look up by arg-type keyword.")


;; === Type Hints ===

(def ^:private default-type-hints
  "Human-readable hints for expected Clojure types (built-in types)."
  {:fn "UUID (function reference)"
   :ref "UUID (entity reference)"
   :int "integer (e.g., 42, -1)"
   :bool "boolean (true or false)"
   :text "string (e.g., \"hello\")"
   :numeric "number (int, float, bigdec, ratio)"
   :jsonb "map or vector"
   :bytes "byte array (byte-array)"
   :timestamptz "java.time.Instant, java.time.LocalDateTime, or java.util.Date"
   :enum "keyword (e.g., :active, :pending)"
   :uuid "UUID"})


(def custom-type-hints
  "Atom containing custom type hints that extend the built-in hints.
   Use `register-type-hint!` to add hints for custom types.
   Hints registered here take precedence over default hints."
  (atom {}))


(defn register-type-hint!
  "Registers a human-readable hint for a custom type.

   Example:
   (register-type-hint! :email \"string in email format (e.g., user@example.com)\")"
  [type-keyword hint-string]
  (when-not (keyword? type-keyword)
    (throw (ex-info "type-keyword must be a keyword"
                    {:type :invalid-argument
                     :type-keyword type-keyword})))
  (when-not (string? hint-string)
    (throw (ex-info "hint-string must be a string"
                    {:type :invalid-argument
                     :hint-string hint-string})))
  (swap! custom-type-hints assoc type-keyword hint-string))


(defn get-type-hint
  "Gets human-readable hint for a type, checking custom hints first."
  [arg-type]
  (or (get @custom-type-hints arg-type)
      (get default-type-hints arg-type)
      (name arg-type)))
