(ns graphden.logging.interface
  "Structured logging with correlation ID support.

   Provides:
   - Correlation ID generation and propagation
   - MDC (Mapped Diagnostic Context) helpers
   - Structured context logging

   Usage:
     ;; In middleware - set correlation ID for request
     (with-correlation-id (generate-correlation-id)
       (process-request))

     ;; In any code - correlation ID is automatically included in logs
     (log/info \"Processing request\")  ; [correlation-id=abc123] Processing request

     ;; Add custom context
     (with-context {:user-id \"u123\" :fn-name \"my-fn\"}
       (log/info \"Executing function\"))"
  (:require
    [clojure.string :as str])
  (:import
    (org.slf4j
      MDC)))


;; =============================================================================
;; Correlation ID
;; =============================================================================

(def ^:const correlation-id-key
  "MDC key for correlation ID."
  "correlation-id")


(defn generate-correlation-id
  "Generates a unique correlation ID for request tracing.
   Format: 8 character hex string (first 8 chars of UUID)."
  []
  (subs (str (random-uuid)) 0 8))


(defn get-correlation-id
  "Returns the current correlation ID from MDC, or nil if not set."
  []
  (MDC/get correlation-id-key))


(defn set-correlation-id!
  "Sets the correlation ID in MDC. Returns the ID."
  [correlation-id]
  (MDC/put correlation-id-key correlation-id)
  correlation-id)


(defn clear-correlation-id!
  "Clears the correlation ID from MDC."
  []
  (MDC/remove correlation-id-key))


(defmacro with-correlation-id
  "Executes body with the given correlation ID set in MDC.
   Clears the correlation ID after body execution."
  [correlation-id & body]
  `(let [cid# ~correlation-id]
     (set-correlation-id! cid#)
     (try
       (do ~@body)
       (finally
         (clear-correlation-id!)))))


;; =============================================================================
;; Context Helpers
;; =============================================================================

(defn set-context!
  "Sets multiple MDC context values from a map."
  [context-map]
  (doseq [[k v] context-map]
    (MDC/put (name k) (str v))))


(defn clear-context!
  "Clears multiple MDC context values."
  [context-keys]
  (doseq [k context-keys]
    (MDC/remove (name k))))


(defmacro with-context
  "Executes body with additional MDC context values.
   Context is cleared after body execution."
  [context-map & body]
  `(let [ctx# ~context-map
         ks# (clojure.core/keys ctx#)]
     (set-context! ctx#)
     (try
       (do ~@body)
       (finally
         (clear-context! ks#)))))


;; =============================================================================
;; Middleware Helpers
;; =============================================================================

(def ^:const request-id-header
  "HTTP header for incoming request ID."
  "X-Request-ID")


(def ^:const correlation-id-header
  "HTTP header for correlation ID (outgoing)."
  "X-Correlation-ID")


(defn wrap-correlation-id
  "Ring middleware that sets correlation ID from request header or generates new one.
   Adds correlation-id to request map and sets MDC."
  [handler]
  (fn [request]
    (let [correlation-id (or (get-in request [:headers (str/lower-case request-id-header)])
                             (generate-correlation-id))]
      (with-correlation-id correlation-id
        (let [response (handler (assoc request :correlation-id correlation-id))]
          (assoc-in response [:headers correlation-id-header] correlation-id))))))
