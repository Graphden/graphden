(ns graphden.packages.web.crud-delete.impls
  "Implementations for the `web.crud` entity-delete chain.

   Each `defbase` is a thin shim: its body delegates to a plain
   function under `src/graphden/crud/*`, passing the implicit `ctx`
   symbol through as an explicit argument. The heavy logic — request
   parsing, write-time validation, type checks, the `process-*`
   dispatchers, sequence ops and the type-API bodies — lives in those
   `src/` namespaces so each base-fn impl stays a minimal primitive."
  (:require
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.request :as request]
    [graphden.executor.defbase :refer [defbase]]))


(defbase query-param
  "Pull a named query-string parameter from a Ring request. Tolerates
   both reitit's enriched `:query-params` shape (string-or-keyword
   keyed) AND raw http-kit requests that carry only `:query-string`.
   Returns nil when the parameter is absent.

   Single-library boundary; the multi-source fallback is infra noise
   not user logic. Admins compose URL-handling primitives over the
   resulting string."
  [request param-name]
  (fn-exec/query-param request param-name))


(defbase extract-entity-params
  "Pull `{:type-str :id-str :entity-type}` out of a Ring request.
   Prefers reitit's `:path-params` (set by enrich-request); falls
   back to URI segment parsing for the http-kit passthrough path
   (`:branch-routing-wrap` and friends invoke handlers with the raw
   request map). `:entity-type` is the canonical keyword for the
   URL segment (`fn` → `:fn`, …) or nil when the segment doesn't
   match a known entity type.

   Single-library boundary over `request/extract-entity-params`.
   The dual-source merge (path-params + URI fallback) is infra
   compensation for the per-handler-routing variance, not user
   logic — admins can compose new entity-type→keyword mappings at
   the graph layer over the resulting `:type-str` field.

   The known-types set comes from the LIVE schema, so an addon's
   entities (`grant`, `org`, `app`, …) resolve through the same routes
   as the core ones — hardcoding the core seven left every addon entity
   with a 400 on the generic delete."
  [request]
  (request/extract-entity-params
    request
    (request/schema-entity-types (:storage ctx))))


;; The package loader pairs each base-fn declared in this module's
;; `fns.edn` with its impl by looking up this map (keyword name -> impl).
(def impls
  {:query-param query-param
   :extract-entity-params extract-entity-params})
