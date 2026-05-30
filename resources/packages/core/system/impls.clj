(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives.

   `:invoke`'s type-rule (moved verbatim from `graphden.types.rules`)
   lives here as a `defn` and is wired into the `impls` map as
   `{:impl … :return-type-rule …}`."
  (:require
    [cheshire.core :as json]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.types.core :as types])
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
  (cr/record-effect! :io)
  (let [runtime-bean (ManagementFactory/getRuntimeMXBean)]
    {:name (System/getProperty "java.vm.name")
     :version (System/getProperty "java.version")
     :uptime-ms (RuntimeMXBean/.getUptime runtime-bean)}))


(defbase heap-memory []
  (cr/record-effect! :io)
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
  (cr/record-effect! :time)
  (System/currentTimeMillis))


(defbase env-fn [name]
  (cr/record-effect! :env)
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
  (cr/record-effect! :io)
  (when-let [r (clojure.java.io/resource path)]
    (clojure.core/slurp r)))


(defbase invoke-fn
  "Invoke a one-arg callable with `arg`. Shared body between :invoke
   and :call — they differ only in arg-type schema (`:any` vs `:fn`,
   which controls hof-wrapping at the binding site, not the impl)."
  [func arg]
  (func arg))


(defbase call-noargs-fn
  "Invoke a 0-arg callable. `:func`'s structural type `[:fn {} a]`
   makes the binding-site hof-wrap; the wrap produces a variadic-
   ignore callable closing over the outer free-args. Companion to
   `:call` for fire-and-forget triggers (cron, signal handlers,
   scheduled cleanup)."
  [func]
  (func))


(defbase slurp-fn [input]
  (when (instance? java.io.InputStream input)
    (cr/record-effect! :io)
    (clojure.core/slurp input)))


(defbase parse-int [s]
  (Long/parseLong s))


(defbase sha256-hex-fn [s]
  (when s
    (let [md (java.security.MessageDigest/getInstance "SHA-256")
          bs (.digest md (.getBytes ^String s "UTF-8"))]
      (apply str (map #(format "%02x" (bit-and ^byte % 0xff)) bs)))))


;; === Type-rules ===
;; :invoke — `(:invoke :func F :arg A)` calls F on A and returns
;; whatever F returns. F is `:fn`-typed; if its rich-type is known
;; (callee resolved at sync time) we can lift its return.
;;
;; Without this rule, every `:invoke` chain (router-result, etc.)
;; degrades to :any, severing structural propagation.

(defn invoke-return-rule
  [bindings-info default-ret]
  (let [func-type (get-in bindings-info [:func :type])]
    (cond
      ;; Best case: the bound :func has a structural fn-type and we
      ;; can read its return directly.
      (types/fn-type? func-type)
      (types/fn-ret func-type)
      :else default-ret)))


;; === Registry ===
;; A value is either a bare impl fn or a `{:impl … :*-rule …}` map.

;; System base-fns are mixed: content-passing transforms
;; (`:to-json-string`, `:parse-json`, `:parse-int`, `:sha256-hex`,
;; `:slurp`, `:ex-info`, `:throw`, `:invoke`, `:call`, `:call-noargs`)
;; potentially expose a secret in their result and must propagate;
;; pure environment readers (`:jvm-version`, `:heap-memory`,
;; `:thread-count`, `:os-info`, `:current-time-ms`, `:env`,
;; `:read-resource-or-nil`) take no user input so taint can't enter
;; through them — left bare. `:sha256-hex` deserves special note:
;; even a HASH of a secret leaks the value (rainbow tables, length
;; oracles), so the propagator is mandatory here.
(def impls
  {:to-json-string {:impl to-json-string :return-type-rule (types/wrap-with-taint nil)}
   :parse-json {:impl parse-json :return-type-rule (types/wrap-with-taint nil)}
   :jvm-version jvm-version
   :heap-memory heap-memory
   :thread-count thread-count
   :os-info os-info
   :current-time-ms current-time-ms
   :env env-fn
   :ex-info {:impl ex-info-fn :return-type-rule (types/wrap-with-taint nil)}
   :throw {:impl throw-fn :return-type-rule (types/wrap-with-taint nil)}
   :read-resource-or-nil read-resource-or-nil
   :invoke {:impl invoke-fn :return-type-rule (types/wrap-with-taint invoke-return-rule)}
   :call {:impl invoke-fn :return-type-rule (types/wrap-with-taint nil)}
   :call-noargs {:impl call-noargs-fn :return-type-rule (types/wrap-with-taint nil)}
   :slurp {:impl slurp-fn :return-type-rule (types/wrap-with-taint nil)}
   :parse-int {:impl parse-int :return-type-rule (types/wrap-with-taint nil)}
   :sha256-hex {:impl sha256-hex-fn :return-type-rule (types/wrap-with-taint nil)}})
