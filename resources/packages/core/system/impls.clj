(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives."
  (:require
    [cheshire.core :as json])
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
   :to-json-string to-json-string
   :jvm-info jvm-info
   :current-time-ms current-time-ms})
