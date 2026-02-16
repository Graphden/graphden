(ns graphden.executor.base-fns.system
  "System information base functions for health checks and metrics.

   Provides:
   - jvm-info: Returns JVM/system information map
   - current-time-ms: Returns current time in milliseconds
   - health-status: Returns basic health status
   - json-response: Wraps data in JSON Ring response
   - json-handler: Creates Ring handler from data-producing fn"
  (:require
    [cheshire.core :as json]
    [graphden.executor.registry.macros :refer [defbase]])
  (:import
    (java.lang.management
      ManagementFactory
      MemoryMXBean
      MemoryUsage
      OperatingSystemMXBean
      RuntimeMXBean)))


;; =============================================================================
;; Ring Response Helpers
;; =============================================================================

(defbase json-response
  "Wraps data in a JSON Ring response map.

   Arguments:
   - data: Any JSON-serializable value

   Returns:
   {:status 200
    :headers {\"Content-Type\" \"application/json\"}
    :body <json-encoded-data>}"
  {:args {:data :any}
   :return-type :jsonb}
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string data)})


(defbase json-handler
  "Creates a Ring handler that returns JSON response from data.

   Arguments:
   - data: Any JSON-serializable value to return

   Returns:
   A Ring handler function that ignores the request and returns
   the data as a JSON response.

   Usage in fn-defs:
   {:name :health-handler-fn
    :parent :json-handler
    :args {:data :health-status>}}  ; execute health-status and wrap result"
  {:args {:data :any}
   :return-type :fn}
  (let [response {:status 200
                  :headers {"Content-Type" "application/json"}
                  :body (json/generate-string data)}]
    (fn [_request] response)))


;; =============================================================================
;; System Information Functions
;; =============================================================================

(defbase jvm-info
  "Returns JVM and system information map.

   Returns:
   {:jvm {:name \"...\", :version \"...\", :uptime-ms N}
    :memory {:heap-used N, :heap-max N, :heap-committed N, :free N, :total N, :max N}
    :threads {:count N}
    :os {:name \"...\", :arch \"...\", :processors N, :load-average N}}"
  {:args {}
   :return-type :jsonb}
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


(defbase current-time-ms
  "Returns current time in milliseconds since epoch."
  {:args {}
   :return-type :int}
  (System/currentTimeMillis))


(defbase health-status
  "Returns basic health status map.

   Returns:
   {:status \"healthy\", :timestamp <current-time-ms>}"
  {:args {}
   :return-type :jsonb}
  {:status "healthy"
   :timestamp (System/currentTimeMillis)})


;; =============================================================================
;; Exports
;; =============================================================================

(def system-defs
  "System base function definitions."
  {:json-response json-response
   :json-handler json-handler
   :jvm-info jvm-info
   :current-time-ms current-time-ms
   :health-status health-status})
