(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives."
  (:require
    [cheshire.core :as json]
    [graphden.executor.defbase :refer [defbase]])
  (:import
    (java.lang.management
      ManagementFactory
      MemoryMXBean
      MemoryUsage
      OperatingSystemMXBean
      RuntimeMXBean)))


;; === Ring Response Primitives ===
;; `:ring-response` is a fn-def constructor (see core/system/fns.edn) —
;; built as an :assoc chain that threads `:headers` through
;; `:stringify-map-keys` before assembly. No impl needed.


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
  (Thread/activeCount))


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


(defbase invoke-fn
  "Invoke a one-arg callable with `arg`. Shared body between :invoke
   and :call — they differ only in arg-type schema (`:any` vs `:fn`,
   which controls hof-wrapping at the binding site, not the impl)."
  [func arg]
  (func arg))


(defbase slurp-fn [input]
  (when (instance? java.io.InputStream input)
    (clojure.core/slurp input)))


(defbase sha256-hex-fn [s]
  (when s
    (let [md (java.security.MessageDigest/getInstance "SHA-256")
          bs (.digest md (.getBytes ^String s "UTF-8"))]
      (apply str (map #(format "%02x" (bit-and ^byte % 0xff)) bs)))))


;; === Registry ===

(def impls
  {:to-json-string to-json-string
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
   :call invoke-fn
   :slurp slurp-fn
   :sha256-hex sha256-hex-fn})
