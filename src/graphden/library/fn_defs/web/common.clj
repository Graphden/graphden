(ns graphden.library.fn-defs.web.common
  "Common fn-def building blocks shared between web components.

   These are reusable abstractions that multiple web fn-def files can use:
   - Route building blocks (assoc-empty, method-map, route, get-route, etc.)
   - Response status hierarchy (ok-response, not-found-response, error-response)
   - Content-type responses (json-ok, html-ok, text-ok, etc.)
   - Health status composition

   ## Usage

   In your fn-defs file:
   ```clojure
   (ns my.fn-defs
     (:require [graphden.library.fn-defs.web.common :as common]))

   (def fn-defs
     (into common/fn-defs
           [;; your specific fn-defs here
            ]))
   ```")


;; =============================================================================
;; ROUTE BUILDING BLOCKS
;; Pass-Through Args Pattern: each level sets one arg, free args propagate up
;; =============================================================================

(def route-building-blocks
  "Route composition hierarchy.

   Inheritance chain:
   - assoc-empty: assoc-any with m={} (free: k, v)
   - assoc-handler: assoc-empty with k=\"handler\" (free: v)
   - method-map: assoc-empty with v=:assoc-handler (free: k, v via pass-through)
   - route: pair with b=:method-map (free: a, k, v)
   - get-route/post-route/etc: route with k preset (free: a, v)"
  [;; Level 1: assoc with empty map preset (free: k, v)
   {:name :assoc-empty
    :parent :assoc-any
    :args {:m {}}}

   ;; Level 2: assoc-handler (free: v) - sets k="handler"
   {:name :assoc-handler
    :parent :assoc-empty
    :args {:k "handler"}}

   ;; Level 2: method-map (free: k, v via pass-through)
   {:name :method-map
    :parent :assoc-empty
    :args {:v :assoc-handler}}

   ;; Level 3: route = pair with b=method-map (free: a, k, v)
   {:name :route
    :parent :pair
    :args {:b :method-map}}

   ;; Level 4: HTTP method routes (free: a, v)
   {:name :get-route
    :parent :route
    :args {:k "get"}}

   {:name :post-route
    :parent :route
    :args {:k "post"}}

   {:name :put-route
    :parent :route
    :args {:k "put"}}

   {:name :delete-route
    :parent :route
    :args {:k "delete"}}

   {:name :patch-route
    :parent :route
    :args {:k "patch"}}])


;; =============================================================================
;; RESPONSE STATUS HIERARCHY
;; Each level sets one thing, enabling clean inheritance
;; =============================================================================

(def response-status-building-blocks
  "Response status codes hierarchy.

   - ok-response: ring-response with status=200 (free: headers, body)
   - not-found-response: ring-response with status=404 (free: headers, body)
   - error-response: ring-response with status=500 (free: headers, body)"
  [{:name :ok-response
    :parent :ring-response
    :args {:status 200}}

   {:name :not-found-response
    :parent :ring-response
    :args {:status 404}}

   {:name :error-response
    :parent :ring-response
    :args {:status 500}}])


;; =============================================================================
;; CONTENT-TYPE RESPONSE HIERARCHY
;; Inherit from status, set content-type (free: body)
;; =============================================================================

(def content-type-building-blocks
  "Content-type response hierarchy.

   Each inherits from a status response and sets Content-Type header.
   Free arg: body"
  [;; JSON responses
   {:name :json-ok-response
    :parent :ok-response
    :args {:headers {"Content-Type" "application/json"}}}

   ;; HTML responses
   {:name :html-ok-response
    :parent :ok-response
    :args {:headers {"Content-Type" "text/html; charset=utf-8"}}}

   ;; Plain text responses
   {:name :text-ok-response
    :parent :ok-response
    :args {:headers {"Content-Type" "text/plain"}}}

   {:name :text-not-found-response
    :parent :not-found-response
    :args {:headers {"Content-Type" "text/plain"}}}

   {:name :text-error-response
    :parent :error-response
    :args {:headers {"Content-Type" "text/plain"}}}

   ;; SVG responses
   {:name :svg-ok-response
    :parent :ok-response
    :args {:headers {"Content-Type" "image/svg+xml"}}}

   ;; Cached SVG (for static assets like favicon)
   {:name :cached-svg-ok-response
    :parent :svg-ok-response
    :args {:headers {"Content-Type" "image/svg+xml"
                     "Cache-Control" "public, max-age=86400"}}}])


;; =============================================================================
;; HEALTH STATUS (fn-def composition)
;; Creates {"status": "healthy", "timestamp": <current-time-ms>}
;; =============================================================================

(def health-status-building-blocks
  "Health status composition.

   Builds health status map using assoc primitives:
   - assoc-status: assoc-empty with k=\"status\" (free: v)
   - assoc-timestamp: assoc with k=\"timestamp\" (free: m, v)
   - health-status-base: {\"status\": \"healthy\"}
   - health-status: {\"status\": \"healthy\", \"timestamp\": <current-time-ms>}"
  [{:name :assoc-status
    :parent :assoc-empty
    :args {:k "status"}}

   {:name :assoc-timestamp
    :parent :assoc
    :args {:k "timestamp"}}

   {:name :health-status-base
    :parent :assoc-status
    :args {:v "healthy"}}

   {:name :health-status
    :parent :assoc-timestamp
    :args {:m :health-status-base
           :v :current-time-ms}}])


;; =============================================================================
;; COMBINED EXPORTS
;; =============================================================================

(def fn-defs
  "All common fn-def building blocks.

   Includes:
   - Route building blocks (9 fn-defs)
   - Response status hierarchy (3 fn-defs)
   - Content-type responses (7 fn-defs)
   - Health status composition (4 fn-defs)

   Total: 23 fn-defs"
  (vec (concat route-building-blocks
               response-status-building-blocks
               content-type-building-blocks
               health-status-building-blocks)))
