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
;; Ring Response Primitives
;; =============================================================================

(defbase ring-response
  "Creates a Ring response map from components.

   Arguments:
   - status: HTTP status code (integer)
   - headers: Map of header name to value
   - body: Response body (string)

   Returns:
   {:status <status> :headers <headers> :body <body>}"
  {:args {:status :int
          :headers :jsonb
          :body :text}
   :return-type :jsonb}
  {:status status
   :headers headers
   :body body})


(defbase make-handler
  "Creates a Ring handler function from a response map.

   Arguments:
   - response: A Ring response map {:status :headers :body}

   Returns:
   A function (fn [request] response) that ignores request and returns response.

   Note: The response is captured at handler creation time (via let binding),
   not at request time. This ensures the response delay is dereferenced
   during startup, avoiding stale execution context issues."
  {:args {:response :jsonb}
   :return-type :fn}
  ;; Capture response value NOW (at make-handler execution time)
  ;; Using let binding forces deref before the fn closure is created
  (let [r response]
    (fn [_request] r)))


(defbase to-json-string
  "Serializes data to JSON string.

   Arguments:
   - data: Any JSON-serializable value

   Returns:
   JSON string representation."
  {:args {:data :any}
   :return-type :text}
  (json/generate-string data))


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
  {:ring-response ring-response
   :make-handler make-handler
   :to-json-string to-json-string
   :jvm-info jvm-info
   :current-time-ms current-time-ms
   :health-status health-status})
