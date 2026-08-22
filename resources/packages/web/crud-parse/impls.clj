(ns graphden.packages.web.crud-parse.impls
  "Implementations for `web.crud` request-body parsing.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [ring.util.codec :as codec]))


(defbase resolve-type-fn-id
  "Resolve a type-row reference to its fn-id. `v` is either a raw UUID
   string (returned as-is after parse) or a fn name like
   `\"ring-response-shape\"` (resolved via `query-fn-by-name`). nil on
   blank input. Throws `:crud/unknown-type-ref` ExceptionInfo when a
   non-blank name doesn't match a fn-row.

   Used by the `parse-fn-from-form` form parser to coerce the
   `:return-type` / `:base-fn-id` / `:element-fn-id` form fields into
   the FK shape storage expects. Single-library boundary over
   `tc/resolve-type-fn-id` — the dual-shape (UUID OR name) is
   intrinsic to the editor's wire format, not user logic to vary."
  [v]
  (cr/record-effect! :db)
  (tc/resolve-type-fn-id (request/require-storage ctx) v))


(defbase parse-constraint
  "JSON-parse a constraint-shape string and recursively re-keywordise
   constraint-head identifiers (`:union`, `:variant`, `:and`, `:or`,
   `:>=`, etc.) plus type-name members (`:null`, `:int`, …) so the
   downstream type-checker sees Clojure keywords.

   Returns nil on blank input. On non-blank input that fails to parse
   as JSON, returns the raw string unchanged (parse failure is
   swallowed).

   Kept as ONE base-fn deliberately — NOT for lack of recursion
   (`:fix` shipped; the walk WOULD express as a graph): this is a
   self-contained wire-format parser (JSON string → typed constraint
   vector), the same carve-out class as `:pick-encoding`'s RFC
   negotiation. The keyword-detection regex is the editor-side wire
   contract and must not vary per user — splitting it across graph
   nodes would hand out exactly the tuning surface the contract
   forbids."
  [raw]
  (when-not (str/blank? raw)
    (let [parsed (try (json/parse-string raw)
                      (catch Exception _ raw))]
      (letfn [(re-kw
                [x]
                (cond
                  (and (string? x)
                       (or (str/starts-with? x ":")
                           (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*" x)
                           (re-matches #"[!<>=]+" x)))
                  (keyword (str/replace-first x #"^:" ""))
                  (or (vector? x) (sequential? x)) (mapv re-kw x)
                  :else x))]
        (re-kw parsed)))))


(defbase str-to-uuid
  [string]
  (try
    (java.util.UUID/fromString string)
    (catch Exception _ nil)))


(defbase form-decode
  "Decode an `application/x-www-form-urlencoded` string into a
   `{string string}` map (`ring.util.codec/form-decode`). Single
   library boundary — no regex, so large bodies (a 200KB asset
   override) don't hit `*max-regex-input-length*`. A body without any
   `=` decodes to a bare string — coerced to `{}`.

   ring returns a VECTOR for a repeated key; a form field is one value,
   so collapse to the LAST occurrence — matching the `:parse-query-string`
   contract this replaced (`Repeated keys collapse to last`) and keeping
   the declared `:text-map` return honest. Otherwise every `:parse-form-body`
   consumer could receive a vector where it expects a string."
  [string]
  (let [decoded (codec/form-decode (or string ""))]
    (if (map? decoded)
      (reduce-kv (fn [m k v] (assoc m k (if (vector? v) (peek v) v))) {} decoded)
      {})))


;; === Registry ===


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:resolve-type-fn-id resolve-type-fn-id
   :parse-constraint parse-constraint
   :str-to-uuid str-to-uuid
   :form-decode {:impl form-decode :taint-propagate? true}})
