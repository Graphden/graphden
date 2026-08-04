(ns graphden.web.route-shape
  "The bare-route handler calling convention — shared vocabulary for
   the write-time guard (`crud/validation`), the sync-time validator
   (`packages/sync`) and the corpus guard test
   (`route-handler-shape-guard-test`).

   Routes WITHOUT middleware (`:get-route` / `:post-route` in
   `app.routes.method`) hand the compiled handler the RAW ring
   request, positionally (reitit → shape-callable). Only two DECLARED
   `:lambda-params` shapes thread that correctly: `[]` (static
   response) and `[:request]`. Any other declared shape mis-binds the
   request silently at the wire — 2+ params make the map-callable
   treat the request as the lambda-value map (every form field parses
   blank), one wrong-named param lands the whole request in that slot
   (rendered as markup). nil `:lambda-params` = legacy derived params,
   validated separately by the compile pipeline."
  (:require
    [graphden.packages.records.ids :as ids]))


(def bare-route-parents
  "The middleware-less route templates. The middlewared family
   (`:post` / `:get-auth-required` / `*-with-middleware`) threads the
   request through the middleware chain and tolerates wider handler
   shapes — deliberately NOT in this set."
  #{:get-route :post-route})


(def bare-route-template-ns
  "The package module that owns the bare route templates."
  "app.routes.method")


(def allowed-handler-params
  "The only `:lambda-params` shapes the raw positional call threads."
  #{[] [:request]})


(def bare-route-template-ids
  "Deterministic package ids of the bare templates — ancestor-closure
   membership is tested by ID, never by name (a tenant may legally
   name their own fn `get-route` in another namespace; see
   docs/adr/AUDIT-name-vs-id-resolution.md)."
  (delay (into #{}
               (map #(ids/fn-id bare-route-template-ns %))
               bare-route-parents)))


(defn valid-handler-lambda-params?
  "True when `lp` may legally be the DECLARED `:lambda-params` of a
   bare-route handler. nil (derived) passes — only a declared bad
   shape breaks the wire. Accepts the stored string form or keywords."
  [lp]
  (or (nil? lp)
      (contains? allowed-handler-params (mapv keyword lp))))
