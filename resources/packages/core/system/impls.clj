(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives."
  (:require
    [cheshire.core :as json]
    [graphden.executor.interface :as exec]
    [hiccup2.core :as h])
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


(defn make-response-handler
  "Generic response handler factory.
   - inner-fn: called with request (0 or 1 required args), returns data
   - body-fn: transforms data to body string (optional, uses str if nil)
   - headers: response headers map (default {})
   - success-status: status on success (default 200)
   - error-status: status on error (default 500)
   - error-body-fn: transforms {:message :type} to body string (optional)
   - dynamic-response: if true, inner-fn returns full {:status :headers :body} map"
  [{:keys [inner-fn body-fn headers success-status error-status error-body-fn dynamic-response]} ctx]
  (let [inner-callable (exec/make-optional-arg-callable ctx inner-fn)
        body-callable (when body-fn (exec/make-single-arg-callable ctx body-fn))
        error-body-callable (when error-body-fn (exec/make-single-arg-callable ctx error-body-fn))
        base-headers (or headers {})
        ok-status (or success-status 200)
        err-status (or error-status 500)]
    (fn [request]
      (try
        (let [result (inner-callable request)]
          (if dynamic-response
            ;; inner-fn returns full response map with :status :headers :body
            (let [resp-status (or (:status result) ok-status)
                  resp-headers (merge base-headers (or (:headers result) {}))
                  resp-body (or (:body result) "")]
              {:status resp-status
               :headers resp-headers
               :body resp-body})
            ;; standard flow: transform result to body
            (let [body (if body-callable
                         (body-callable result)
                         (str result))]
              {:status ok-status
               :headers base-headers
               :body body})))
        (catch Exception e
          (let [error-data {:message (ex-message e)
                            :type (str (:type (ex-data e)))}
                error-body (if error-body-callable
                             (error-body-callable error-data)
                             (str "Error: " (ex-message e)))]
            {:status err-status
             :headers base-headers
             :body error-body}))))))


(defn json-error-body
  "Formats error as JSON string."
  [{:keys [error]}]
  (json/generate-string {:error (:message error)
                         :type (:type error)}))


(defn html-error-body
  "Formats error as HTML string."
  [{:keys [error]}]
  (str "<p class=\"error\">Error: " (:message error) "</p>"))


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


;; === Registry ===

(def impls
  {:ring-response ring-response
   :make-handler make-handler
   :make-request-handler (with-meta make-request-handler {:ctx true})
   :make-response-handler (with-meta make-response-handler {:ctx true})
   :json-error-body json-error-body
   :html-error-body html-error-body
   :to-json-string to-json-string
   :jvm-info jvm-info
   :current-time-ms current-time-ms})
