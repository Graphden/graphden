(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]])
  (:import
    (java.lang.management
      ManagementFactory
      MemoryMXBean
      MemoryUsage
      OperatingSystemMXBean
      RuntimeMXBean)))


;; === Ring Response Primitives ===

(defn- stringify-keys
  [m]
  (when m (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])) m)))


(defbase ring-response [status headers body]
  ;; Ring spec: header keys must be strings. JSONB round-trip keywordizes
  ;; them, so we coerce back on the way out.
  {:status status
   :headers (stringify-keys headers)
   :body body})


(defbase make-handler [response]
  (let [r response]
    (fn [_request] r)))


;; Ring handler factories. `:fn`-type args arrive as already-wrapped
;; Ring-handler callables (compile.clj/hof-wrap has a `:request`
;; special-case that routes the item to the deep `:request` free arg
;; of the wrapped target). The factory just assembles them.

(defbase make-data-handler [data-fn body-fn status headers]
  ;; Headers from a JSONB-roundtripped literal map come back keyword-
  ;; keyed; http-kit needs string keys. Stringify here so every
  ;; response producer hands downstream code a Ring-shaped response.
  (let [string-headers (stringify-keys headers)]
    (fn [request]
      (let [data (data-fn request)
            body (body-fn data)]
        {:status status
         :headers string-headers
         :body body}))))


(defbase make-action-handler [action-fn base-headers]
  (let [string-base-headers (stringify-keys base-headers)]
    (fn [request]
      (let [result (action-fn request)
            resp-status (or (:status result) 200)
            resp-headers (merge string-base-headers
                                (stringify-keys (or (:headers result) {})))
            resp-body (or (:body result) "")]
        {:status resp-status
         :headers resp-headers
         :body resp-body}))))


(defbase to-json-string [data]
  (json/generate-string data))


(defbase parse-json
  "Parse a JSON string into a data structure."
  [string keywordize]
  (json/parse-string string keywordize))


;; === System Information ===

(defbase jvm-version []
  (let [runtime-bean (ManagementFactory/getRuntimeMXBean)]
    {:name (System/getProperty "java.vm.name")
     :version (System/getProperty "java.version")
     :uptime-ms (RuntimeMXBean/.getUptime runtime-bean)}))


(defbase heap-memory []
  (let [runtime (Runtime/getRuntime)
        heap (MemoryMXBean/.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    {:heap-used (MemoryUsage/.getUsed heap)
     :heap-max (MemoryUsage/.getMax heap)
     :heap-committed (MemoryUsage/.getCommitted heap)
     :free (Runtime/.freeMemory runtime)
     :total (Runtime/.totalMemory runtime)
     :max (Runtime/.maxMemory runtime)}))


(defbase thread-count []
  {:count (Thread/activeCount)})


(defbase os-info []
  (let [os-bean (ManagementFactory/getOperatingSystemMXBean)]
    {:name (OperatingSystemMXBean/.getName os-bean)
     :arch (OperatingSystemMXBean/.getArch os-bean)
     :processors (OperatingSystemMXBean/.getAvailableProcessors os-bean)
     :load-average (OperatingSystemMXBean/.getSystemLoadAverage os-bean)}))


(defbase current-time-ms []
  (System/currentTimeMillis))


(defbase env-fn [name]
  (System/getenv name))


(defbase ex-info-fn
  "Construct a clojure.lang.ExceptionInfo. Lets fn-graphs build
   exceptions compositionally without reaching into Clojure code."
  [message data]
  (ex-info message data))


(defbase throw-fn
  "Throw the given exception. Paired with `ex-info` (or any other
   Throwable-producing fn) to express `raise` at graph level."
  [exception]
  (throw exception))


(defbase read-resource-or-nil
  "Lookup + slurp a classpath resource. Returns the file contents as a
   string, or nil if the resource isn't on the classpath. `read-resource`
   (fn-def) pairs this with :if + :throw to surface a clear error when
   the path is missing."
  [path]
  (when-let [r (clojure.java.io/resource path)]
    (clojure.core/slurp r)))


(defbase invoke-fn [func arg]
  (func arg))


(defbase slurp-fn [input]
  (when (instance? java.io.InputStream input)
    (clojure.core/slurp input)))


;; === Registry ===

(def impls
  {:ring-response ring-response
   :make-handler make-handler
   :make-data-handler make-data-handler
   :make-action-handler make-action-handler
   :to-json-string to-json-string
   :parse-json parse-json
   :jvm-version jvm-version
   :heap-memory heap-memory
   :thread-count thread-count
   :os-info os-info
   :current-time-ms current-time-ms
   :env env-fn
   :ex-info ex-info-fn
   :throw throw-fn
   :read-resource-or-nil read-resource-or-nil
   :invoke invoke-fn
   :slurp slurp-fn})
