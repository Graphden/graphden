(ns graphden.packages.web.crud-delete.impls
  "Implementations for the `web.crud` entity-delete chain.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [graphden.crud.entities.delete-batch :as delete-batch]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.request :as request]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _query-param-raw
  "Soft URL codec for a raw http-kit query string; enriched-key
   precedence is graph policy."
  [query-string param-name]
  (fn-exec/raw-query-param query-string param-name))


(defbase _entity-uri-params
  "Parse the exact supported entity-route URI shapes."
  [uri]
  (request/parse-uri-segments uri))


(defbase _entity-type-from-string
  "Resolve a type against the live schema, including addon entities."
  [type-str]
  (request/entity-type-from-string
    type-str
    (request/schema-entity-types (:storage ctx))))


(defbase parse-fn-delete-receipts
  "Validate the bounded exact-identity request without reading storage."
  [input]
  (delete-batch/parse-receipts input))


(defbase delete-fn-receipts
  "Indivisible guarded transaction and post-commit publication."
  [receipts]
  (delete-batch/delete-receipts! ctx receipts))


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:_query-param-raw {:impl _query-param-raw :taint-propagate? true}
   :parse-fn-delete-receipts {:impl parse-fn-delete-receipts :taint-propagate? true}
   :delete-fn-receipts {:impl delete-fn-receipts :taint-propagate? true}
   :_entity-uri-params {:impl _entity-uri-params :taint-propagate? true}
   :_entity-type-from-string {:impl _entity-type-from-string :taint-propagate? true}})
