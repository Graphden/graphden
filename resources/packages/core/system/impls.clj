(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
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
  [{:keys [response-fn]} ctx]
  (exec/make-single-arg-callable ctx response-fn))


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

(defn jvm-version
  [_args]
  (let [runtime-bean (ManagementFactory/getRuntimeMXBean)]
    {:name (System/getProperty "java.vm.name")
     :version (System/getProperty "java.version")
     :uptime-ms (RuntimeMXBean/.getUptime runtime-bean)}))

(defn heap-memory
  [_args]
  (let [runtime (Runtime/getRuntime)
        heap (MemoryMXBean/.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    {:heap-used (MemoryUsage/.getUsed heap)
     :heap-max (MemoryUsage/.getMax heap)
     :heap-committed (MemoryUsage/.getCommitted heap)
     :free (Runtime/.freeMemory runtime)
     :total (Runtime/.totalMemory runtime)
     :max (Runtime/.maxMemory runtime)}))

(defn thread-count
  [_args]
  {:count (Thread/activeCount)})

(defn os-info
  [_args]
  (let [os-bean (ManagementFactory/getOperatingSystemMXBean)]
    {:name (OperatingSystemMXBean/.getName os-bean)
     :arch (OperatingSystemMXBean/.getArch os-bean)
     :processors (OperatingSystemMXBean/.getAvailableProcessors os-bean)
     :load-average (OperatingSystemMXBean/.getSystemLoadAverage os-bean)}))


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


(defn concat-resources
  [{:keys [paths separator]}]
  (str/join separator (map #(read-resource {:path %}) paths)))


;; === Registry ===

(def impls
  {:ring-response ring-response
   :make-handler make-handler
   :make-request-handler (with-meta make-request-handler {:ctx true})
   :make-data-handler (with-meta make-data-handler {:ctx true})
   :make-action-handler (with-meta make-action-handler {:ctx true})
   :to-json-string to-json-string
   :parse-json parse-json
   :jvm-version jvm-version
   :heap-memory heap-memory
   :thread-count thread-count
   :os-info os-info
   :current-time-ms current-time-ms
   :read-resource read-resource
   :concat-resources concat-resources})
