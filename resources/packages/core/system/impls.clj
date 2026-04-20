(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.interface :as exec])
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


;; Plain `defn` (not `defbase`) because these factories bridge two executors:
;;
;; - Compile path (production): `:fn`-type args arrive as already-wrapped
;;   Ring-handler callables. compile.clj/hof-wrap has a `:request`
;;   special-case that routes the item to the deep `:request` free arg —
;;   exactly what these factories want. So we just return the callable.
;;
;; - Legacy queue path (tests): `:fn`-type args arrive as SmartDelay(UUID)
;;   because the `:ctx true` flag skips loader-level pre-wrapping. We
;;   deref to the raw fn-id and build a name-aware callable via the legacy
;;   exec helpers so the item routes to the right deep arg.

(defn- deref-if-needed
  [v]
  (if (instance? clojure.lang.IDeref v) @v v))


(defn make-request-handler
  [{:keys [response-fn]} ctx]
  (if (fn? response-fn)
    response-fn
    (let [fn-id (deref-if-needed response-fn)]
      (exec/make-named-arg-callable ctx fn-id "request"))))


(defn make-data-handler
  [{:keys [data-fn body-fn status headers]} ctx]
  (let [data-callable (if (fn? data-fn)
                        data-fn
                        (exec/make-optional-arg-callable ctx (deref-if-needed data-fn)))
        body-callable (if (fn? body-fn)
                        body-fn
                        (exec/make-single-arg-callable ctx (deref-if-needed body-fn)))
        st (deref-if-needed status)
        hs (deref-if-needed headers)]
    (fn [request]
      (let [data (data-callable request)
            body (body-callable data)]
        {:status st
         :headers hs
         :body body}))))


(defn make-action-handler
  [{:keys [action-fn base-headers]} ctx]
  (let [action-callable (if (fn? action-fn)
                          action-fn
                          (exec/make-optional-arg-callable ctx (deref-if-needed action-fn)))
        base-hs (deref-if-needed base-headers)]
    (fn [request]
      (let [result (action-callable request)
            resp-status (or (:status result) 200)
            resp-headers (merge base-hs (or (:headers result) {}))
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


(defn- read-resource-impl
  [path]
  (if-let [resource (clojure.java.io/resource path)]
    (slurp resource)
    (throw (ex-info (str "Resource not found: " path)
                    {:type :execution-error/resource-not-found
                     :path path}))))


(defbase read-resource
  "Read a resource file from classpath and return its contents as string."
  [path]
  (read-resource-impl path))


(defbase concat-resources [paths separator]
  (str/join separator (map read-resource-impl paths)))


(defbase invoke-fn [func arg]
  (func arg))


(defbase slurp-fn [input]
  (when (instance? java.io.InputStream input)
    (clojure.core/slurp input)))


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
   :env env-fn
   :read-resource read-resource
   :concat-resources concat-resources
   :invoke invoke-fn
   :slurp slurp-fn})
