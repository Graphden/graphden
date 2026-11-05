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
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
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


(defn- constraint-base-type
  "Resolve the optional refinement base so JSON string operands keep their domain."
  [ctx base-ref]
  (when-not (str/blank? base-ref)
    (if-let [id (request/parse-uuid-or-clear base-ref)]
      (do
        (cr/record-effect! :db)
        (let [storage (request/require-storage ctx)]
          (some->> (sp/read-entity storage :fn id)
                   (tc/type-fn->rich-type storage)
                   types/resolve-alias)))
      (types/resolve-alias (keyword (str/replace-first base-ref #"^:" ""))))))


(defbase parse-constraint
  "Strict JSON wire decoder. Operator heads and structural type names become
   keywords; refinement values keep the domain of their base type. A failed
   JSON parse stays raw for the write validator to reject without an HTTP 500."
  [raw base-ref]
  (when-not (str/blank? raw)
    (let [parsed (try (json/parse-string-strict raw)
                      (catch Exception _ raw))
          keyword-values? (types-api/keyword-domain?
                            (constraint-base-type ctx base-ref))]
      (letfn [(structural-token
                [x]
                (cond
                  (string? x) (keyword (str/replace-first x #"^:" ""))
                  (sequential? x) (mapv structural-token x)
                  :else x))
              (decode
                [x]
                (if-not (and (sequential? x) (seq x))
                  x
                  (let [op (first (types-api/json->constraint false [(first x)]))]
                    (cond
                      (#{:union :variant :fn :list :map :tuple} op)
                      (into [op] (map structural-token) (rest x))

                      (#{:and :or :not} op)
                      (into [op] (map decode) (rest x))

                      :else (types-api/json->constraint keyword-values? x)))))]
        (decode parsed)))))


(defbase _form-decode-raw
  "Ring's form codec only. It returns a string for a body without `=`,
   and vector values for repeated keys; graph policy normalizes both."
  [string]
  (codec/form-decode (or string "")))


;; === Registry ===


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:resolve-type-fn-id resolve-type-fn-id
   :parse-constraint parse-constraint
   :_form-decode-raw {:impl _form-decode-raw :taint-propagate? true}})
