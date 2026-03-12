(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [graphden.executor.interface :as exec])
  (:import
    (java.lang.management
      ManagementFactory
      MemoryMXBean
      MemoryUsage
      OperatingSystemMXBean
      RuntimeMXBean)))


;; === Ring Response Primitives ===

(defn ring-response
  [{:keys [status headers body]}]
  {:status status
   :headers headers
   :body body})


(defn make-handler
  [{:keys [response]}]
  (let [r response]
    (fn [_request] r)))


(defn make-request-handler
  "Creates a handler that calls response-fn for each request.
   response-fn must be a fn-id (UUID) of a function with one required argument.
   The function will be called with the request object for each incoming request."
  [{:keys [response-fn]} ctx]
  ;; response-fn is a fn-id (UUID), convert to callable
  (let [callable (exec/make-single-arg-callable ctx response-fn)]
    (fn [request]
      (callable request))))


(defn to-json-string
  [{:keys [data]}]
  (json/generate-string data))


;; === System Information ===

(defn jvm-info
  [_args]
  (let [runtime (Runtime/getRuntime)
        memory-bean (ManagementFactory/getMemoryMXBean)
        heap-usage (MemoryMXBean/.getHeapMemoryUsage memory-bean)
        os-bean (ManagementFactory/getOperatingSystemMXBean)
        runtime-bean (ManagementFactory/getRuntimeMXBean)]
    {:jvm {:name (System/getProperty "java.vm.name")
           :version (System/getProperty "java.version")
           :uptime-ms (RuntimeMXBean/.getUptime runtime-bean)}
     :memory {:heap-used (MemoryUsage/.getUsed heap-usage)
              :heap-max (MemoryUsage/.getMax heap-usage)
              :heap-committed (MemoryUsage/.getCommitted heap-usage)
              :free (Runtime/.freeMemory runtime)
              :total (Runtime/.totalMemory runtime)
              :max (Runtime/.maxMemory runtime)}
     :threads {:count (Thread/activeCount)}
     :os {:name (OperatingSystemMXBean/.getName os-bean)
          :arch (OperatingSystemMXBean/.getArch os-bean)
          :processors (OperatingSystemMXBean/.getAvailableProcessors os-bean)
          :load-average (OperatingSystemMXBean/.getSystemLoadAverage os-bean)}}))


(defn current-time-ms
  [_args]
  (System/currentTimeMillis))


;; === Response Handler Factory ===

(defn make-response-handler
  "Creates a Ring handler that processes requests through data-fn and formats response.

   Args:
   - data-fn: fn-id (delay) to call with request, returns data
   - body-fn: optional fn-id (delay) to transform data to body string
   - headers: response headers map
   - success-status: HTTP status for success (default 200)
   - error-status: HTTP status for errors (default 500)

   If body-fn is nil, data is converted to string directly.
   If data-fn returns a map with :status/:headers/:body, those override defaults.

   data-fn can have 0 or 1 required args:
   - 0 args: request is ignored (e.g., list-all-entities)
   - 1 arg: request is passed to it (e.g., get-entity-details)"
  [{:keys [data-fn body-fn headers success-status error-status]} ctx]
  (log/info "make-response-handler called" {:data-fn data-fn :body-fn body-fn})
  (let [ok-status (or success-status 200)
        err-status (or error-status 500)
        base-headers (or headers {})
        ;; Deref fn-ids once at handler creation time
        data-fn-id (when data-fn @data-fn)
        body-fn-id (when body-fn @body-fn)]
    (log/info "make-response-handler fn-ids" {:data-fn-id data-fn-id :body-fn-id body-fn-id})
    (fn [request]
      (try
        (log/debug "make-response-handler handling request")
        (let [;; Call data-fn with request (handles 0 or 1 args)
              data-callable (exec/make-optional-arg-callable ctx data-fn-id)
              _ (log/debug "data-callable created")
              result (data-callable request)
              _ (log/debug "data-callable returned" {:result-type (type result)})]
          ;; Check if result is already a full response
          (if (and (map? result) (:body result))
            ;; Result is a response - merge with defaults
            {:status (or (:status result) ok-status)
             :headers (merge base-headers (or (:headers result) {}))
             :body (:body result)}
            ;; Result is data - transform to body
            (let [body (if body-fn-id
                         (let [_ (log/debug "creating body-callable")
                               body-callable (exec/make-optional-arg-callable ctx body-fn-id)
                               _ (log/debug "body-callable created, calling with result")]
                           (body-callable result))
                         (str result))]
              {:status ok-status
               :headers base-headers
               :body body})))
        (catch Exception e
          (log/error e "make-response-handler error")
          {:status err-status
           :headers base-headers
           :body (str "Error: " (ex-message e))})))))


;; === Registry ===

(def impls
  {:ring-response ring-response
   :make-handler make-handler
   :make-request-handler (with-meta make-request-handler {:ctx true})
   :make-response-handler (with-meta make-response-handler {:ctx true})
   :to-json-string to-json-string
   :jvm-info jvm-info
   :current-time-ms current-time-ms})
