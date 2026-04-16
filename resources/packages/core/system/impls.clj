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
  (let [callable (exec/make-single-arg-callable ctx response-fn)]
    (fn [request]
      (callable request))))


(defn make-data-handler
  "Data handler factory: data-fn(request) -> data, body-fn(data) -> body string.
   Returns Ring handler function."
  [{:keys [data-fn body-fn status headers]} ctx]
  (let [data-callable (exec/make-optional-arg-callable ctx data-fn)
        body-callable (exec/make-single-arg-callable ctx body-fn)]
    (fn [request]
      (let [data (data-callable request)
            body (body-callable data)]
        {:status status
         :headers headers
         :body body}))))


(defn make-action-handler
  "Action handler factory: action-fn(request) -> {:status :headers :body}.
   Merges base-headers with headers from action-fn result."
  [{:keys [action-fn base-headers]} ctx]
  (let [action-callable (exec/make-optional-arg-callable ctx action-fn)]
    (fn [request]
      (let [result (action-callable request)
            resp-status (or (:status result) 200)
            resp-headers (merge base-headers (or (:headers result) {}))
            resp-body (or (:body result) "")]
        {:status resp-status
         :headers resp-headers
         :body resp-body}))))


(defn to-json-string
  [{:keys [data]}]
  (json/generate-string data))


(defn parse-json
  "Parse a JSON string into a data structure."
  [{:keys [string keywordize]}]
  (json/parse-string string keywordize))


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


(defn read-resource
  "Read a resource file from classpath and return its contents as string."
  [{:keys [path]}]
  (if-let [resource (clojure.java.io/resource path)]
    (slurp resource)
    (throw (ex-info (str "Resource not found: " path)
                    {:type :execution-error/resource-not-found
                     :path path}))))


;; === Registry ===

(def impls
  {:ring-response ring-response
   :make-handler make-handler
   :make-request-handler (with-meta make-request-handler {:ctx true})
   :make-data-handler (with-meta make-data-handler {:ctx true})
   :make-action-handler (with-meta make-action-handler {:ctx true})
   :to-json-string to-json-string
   :parse-json parse-json
   :jvm-info jvm-info
   :current-time-ms current-time-ms
   :read-resource read-resource})
