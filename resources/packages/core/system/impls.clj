(ns graphden.packages.core.system.impls
  "Implementations for core/system base functions.

   Provides system information and Ring response primitives.

   `:invoke`'s type-rule (moved verbatim from `graphden.types.rules`)
   lives here as a `defn` and is wired into the `impls` map as
   `{:impl … :return-type-rule …}`."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.trace :as trace]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.records.ids :as ids]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.types.core :as types]
    [graphden.util.counters :as counters])
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


(defbase to-json-pretty [data]
  ;; Sibling of :to-json-string rather than an optional :pretty slot on
  ;; it: retrofitting a slot onto a 47-consumer base-fn changes every
  ;; callable consumer's free-arg surface (hof-lambda-params ambiguity)
  ;; — locality of changes wins over multi-arity here.
  (json/generate-string data {:pretty true}))


(defbase parse-json
  "Parse a JSON string into a data structure. Malformed input surfaces
   as a typed `:validation-error/malformed-json` (→ 400) rather than a
   bare `JsonParseException` — the caller's input is untrusted and an
   untyped throw would map to 500 and page on `:http/server-error`."
  [string keywordize]
  (try
    (json/parse-string string keywordize)
    (catch com.fasterxml.jackson.core.JsonProcessingException _
      (throw (ex-info "Malformed JSON."
                      {:type :validation-error/malformed-json})))))


(defbase parse-edn
  "Read one EDN value from `string`; nil when it doesn't parse. Generic
   counterpart to `:parse-json` — EDN is the one encoding where
   `:other-fn` (a reference) and \"other-fn\" (a string) stay
   distinguishable, so fn-def bundles travel as EDN (the MCP
   `upsert-fn-defs` tool, `POST /api/import/graph`). Moved here from the
   optional mcp package when the registry's import route needed it too —
   base-fn names are globally unique, so shared primitives live in core."
  [string]
  (try (edn/read-string string) (catch Exception _ nil)))


;; === System Information ===

(defbase system-property-fn
  "`(System/getProperty name)` — JVM system property, nil if unset.
   Counterpart to `:env` (environment variables): both read a single
   process-scoped key/value, both are pure-ish reads under the `:env`
   effect tag. Use for `java.vm.name`, `java.version`, `user.dir`, etc."
  [name]
  (cr/record-effect! :env)
  (System/getProperty name))


(defbase jvm-uptime-ms-fn
  "`(.getUptime (ManagementFactory/getRuntimeMXBean))` — ms since JVM
   startup. Single library call so admins can build their own JVM-info
   shape via `:zipmap` without an opaque all-in-one `:jvm-version` impl."
  []
  (cr/record-effect! :io)
  (RuntimeMXBean/.getUptime (ManagementFactory/getRuntimeMXBean)))


;; Heap / process-memory readings — one bean call each, so admins can
;; build their own memory shape via `:zipmap` (same standard as
;; `:jvm-uptime-ms`). `:heap-memory` (fns.edn) recomposes the original
;; 6-field map in the graph.

(defn- heap-usage
  ^MemoryUsage []
  (MemoryMXBean/.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean)))


(defbase heap-used-fn []
  (cr/record-effect! :io)
  (MemoryUsage/.getUsed (heap-usage)))


(defbase heap-max-fn []
  (cr/record-effect! :io)
  (MemoryUsage/.getMax (heap-usage)))


(defbase heap-committed-fn []
  (cr/record-effect! :io)
  (MemoryUsage/.getCommitted (heap-usage)))


(defbase free-memory-fn []
  (cr/record-effect! :io)
  (Runtime/.freeMemory (Runtime/getRuntime)))


(defbase total-memory-fn []
  (cr/record-effect! :io)
  (Runtime/.totalMemory (Runtime/getRuntime)))


(defbase max-memory-fn []
  (cr/record-effect! :io)
  (Runtime/.maxMemory (Runtime/getRuntime)))


(defbase thread-count []
  (cr/record-effect! :io)
  (Thread/activeCount))


;; OS readings — one bean call each; `:os-info` (fns.edn) recomposes
;; the original 4-field map in the graph.

(defbase os-name-fn []
  (cr/record-effect! :io)
  (OperatingSystemMXBean/.getName (ManagementFactory/getOperatingSystemMXBean)))


(defbase os-arch-fn []
  (cr/record-effect! :io)
  (OperatingSystemMXBean/.getArch (ManagementFactory/getOperatingSystemMXBean)))


(defbase os-processors-fn []
  (cr/record-effect! :io)
  (OperatingSystemMXBean/.getAvailableProcessors (ManagementFactory/getOperatingSystemMXBean)))


(defbase os-load-average-fn []
  (cr/record-effect! :io)
  (OperatingSystemMXBean/.getSystemLoadAverage (ManagementFactory/getOperatingSystemMXBean)))


(defbase counters-snapshot []
  (cr/record-effect! :io)
  (counters/snapshot))


;; :render-prometheus is a GRAPH fn-def now (core/system/fns.edn) — a
;; `:fix` worklist over the nested metrics map (flatten / sanitise via
;; `:re-replace` / numeric-filter / format / `:str-join`). The former
;; Clojure formatter claimed to be "a library-adapter primitive, like
;; :render-hiccup", but wrapped no library — the whole OpenMetrics
;; exposition was hand-rolled composition, which belongs in the graph.


(defbase log-warn-fn
  "One library call — `clojure.tools.logging/warn`. The graph's
   observability primitive for best-effort failure paths (a graph
   `:try` whose `:on-throw` must stay visible to operators)."
  [message data]
  (cr/record-effect! :io)
  (log/warn message data))


(defbase current-time-ms []
  (cr/record-effect! :time)
  (System/currentTimeMillis))


(defbase env-fn [name]
  (cr/record-effect! :env)
  (System/getenv name))


(defbase deploy-config-fn
  "Pure read of the boot-time public-settings snapshot — no effect
   recorded (see `graphden.system.deploy-config`)."
  [key]
  (deploy-config/read-setting key))


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


(def ^:private build-hashes-resource "graphden-build-hashes.json")


(def ^:private build-hashes-text
  "The build artifact, slurped once — it cannot change while the process
   runs (it ships inside the jar)."
  (delay
    (if-let [r (io/resource build-hashes-resource)]
      (slurp r)
      (throw (ex-info (str "Resource not found: " build-hashes-resource)
                      {:type :execution-error/resource-not-found
                       :path build-hashes-resource})))))


(defbase build-hashes-raw
  "The baked build-hashes JSON as text, cached per process. No effect
   recorded — a fixed, non-secret build artifact (see fns.edn)."
  []
  @build-hashes-text)


(def ^:private shipped-asset-kinds #{"js" "css" "svg" "html" "md"})


(defn- shipped-asset-path?
  "The allow-list: under `packages/`, no parent-dir segment, one of the
   asset extensions."
  [path]
  (and (string? path)
       (str/starts-with? path "packages/")
       (not (str/includes? path ".."))
       (contains? shipped-asset-kinds (last (str/split path #"\.")))))


(def ^:private shipped-assets
  "Per-process cache `{path text}` — a shipped file cannot change while
   the process runs."
  (atom {}))


(defbase shipped-asset
  "A shipped frontend asset, read once and cached. No effect recorded —
   public bytes, allow-listed path (see fns.edn)."
  [path]
  (when-not (shipped-asset-path? path)
    (throw (ex-info (str "Not a shipped asset path: " (pr-str path)
                         " — packages/… ending in one of " (sort shipped-asset-kinds))
                    {:type :validation-error/shipped-asset-path :path path})))
  (or (get @shipped-assets path)
      (if-let [r (io/resource path)]
        (let [text (slurp r)]
          (swap! shipped-assets assoc path text)
          text)
        (throw (ex-info (str "Resource not found: " path)
                        {:type :execution-error/resource-not-found :path path})))))


(defbase read-resource-bytes
  "Classpath resource → byte array, nil when absent. The binary sibling
   of `read-resource-or-nil`."
  [path]
  (cr/record-effect! :io)
  (when-let [r (io/resource path)]
    (with-open [in (io/input-stream r)]
      (java.io.InputStream/.readAllBytes in))))


(defbase read-resource-or-nil
  "Lookup + slurp a classpath resource. Returns the file contents as a
   string, or nil if the resource isn't on the classpath. `read-resource`
   (fn-def) pairs this with :if + :throw to surface a clear error when
   the path is missing."
  [path]
  (cr/record-effect! :io)
  (when-let [r (io/resource path)]
    (slurp r)))


(defbase invoke-fn
  "Invoke a one-arg callable with `arg`. Shared body between :invoke
   and :call — they differ only in arg-type schema (`:any` vs `:fn`,
   which controls hof-wrapping at the binding site, not the impl)."
  [func arg]
  (func arg))


(defbase call-with-fn
  "Invoke a callable with a MAP of named arguments — the shape
   `make-shape-callable` builds for a slot whose fn-type names 2+ args."
  [func args]
  (func args))


(defbase call-traced-fn
  "`(func arg)` as a persisted hop of a cross-service trace when
   `trace-id` is set — the callee's identity and its per-call arg name
   ride on the callable's metadata (`hof-wrap`), so the row is an
   execution of THAT fn with the arg under its own name. No trace id →
   a plain call, nothing persisted."
  [func arg trace-id parent-execution-id]
  (if (and trace-id (:graphden.executor/fn-id (meta func)))
    (let [m (meta func)
          arg-name (or (first (:graphden.executor/lambda-params m)) :arg)]
      (cr/record-effect! :db)
      (trace/run-traced-with! ctx (:graphden.executor/fn-id m)
                              {:trace-id trace-id :parent-execution-id parent-execution-id}
                              {arg-name arg}
                              #(func arg)))
    (func arg)))


(defbase call-noargs-fn
  "Invoke a 0-arg callable. `:func`'s structural type `[:fn {} a]`
   makes the binding-site hof-wrap; the wrap produces a variadic-
   ignore callable closing over the outer free-args. Companion to
   `:call` for fire-and-forget triggers (cron, signal handlers,
   scheduled cleanup)."
  [func]
  (func))


(defbase try-fn
  "Run `body` (a 0-arg HOF callable) and return its result. If body
   throws, invoke `on-throw` with the caught Exception and return its
   result instead. Pairs with `:atom` + `:swap-conj` to express
   journalled-write transactions at the graph level (see
   `:_update-record-type-apply` for the canonical use).

   `Throwable` deliberately NOT caught — OOM / `InterruptedException` /
   `LinkageError` etc. must propagate; graph-level try-catch is for
   recoverable exceptions only, same convention as Clojure `try`'s
   typical `catch Exception` shape."
  [body on-throw]
  (try
    (body)
    (catch Exception e
      (on-throw e))))


(defbase slurp-fn [input]
  (when (instance? java.io.InputStream input)
    (cr/record-effect! :io)
    (clojure.core/slurp input)))


(defbase parse-int [s]
  (Long/parseLong s))


;; One digest primitive, algorithm as DATA (the graph pins it — the
;; `:sha256-hex` fn-def preset in fns.edn; same ladder shape as
;; `:http-request` / `:route`). Delegates to the SAME `ids/digest-hex`
;; the package sync's shape-hashing uses — one hex-digest impl repo-wide.
;; An algorithm the JVM's providers don't know throws
;; NoSuchAlgorithmException, surfacing as an execution-error.
(defbase digest-hex-fn [algorithm s]
  (when s
    (ids/digest-hex algorithm s)))


(defbase throwable-message-fn [ex]
  (when ex (Throwable/.getMessage ex)))


(defbase throwable-class-name-fn [ex]
  (when ex (Class/.getName (Object/.getClass ex))))


(defbase ex-data-fn
  "`(ex-data ex)` — structured payload of an ex-info exception
   (`{:type … …}`), or nil for plain exceptions / nil input."
  [ex]
  (when ex (ex-data ex)))


(defbase parse-uuid-fn
  "Parse `:string` as a UUID. Returns nil for non-string / blank /
   malformed input — every failure mode collapses to nil so graph
   callers don't need a try/catch wrapper. Defensive boundary
   mirroring `crud.request/parse-uuid-or-clear`."
  [string]
  (when (and (string? string) (not (str/blank? string)))
    (try (java.util.UUID/fromString string)
         (catch IllegalArgumentException _ nil))))


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
;; (`:to-json-string`, `:parse-json`, `:parse-int`, `:digest-hex`,
;; `:slurp`, `:ex-info`, `:throw`, `:invoke`, `:call`, `:call-noargs`)
;; potentially expose a secret in their result and must propagate;
;; pure environment readers (`:system-property`, `:jvm-uptime-ms`,
;; the heap/os bean readers, `:thread-count`, `:current-time-ms`,
;; `:env`, `:read-resource-or-nil`) take no user input so taint can't
;; enter through them — left bare. `:digest-hex` deserves special note:
;; even a HASH of a secret leaks the value (rainbow tables, length
;; oracles), so the propagator is mandatory here.
(def impls
  {:to-json-string {:impl to-json-string :taint-propagate? true}
   :to-json-pretty {:impl to-json-pretty :taint-propagate? true}
   :parse-json {:impl parse-json :taint-propagate? true}
   :parse-edn {:impl parse-edn :taint-propagate? true}
   :system-property system-property-fn
   :jvm-uptime-ms jvm-uptime-ms-fn
   :heap-used heap-used-fn
   :heap-max heap-max-fn
   :heap-committed heap-committed-fn
   :free-memory free-memory-fn
   :total-memory total-memory-fn
   :max-memory max-memory-fn
   :thread-count thread-count
   :os-name os-name-fn
   :os-arch os-arch-fn
   :os-processors os-processors-fn
   :os-load-average os-load-average-fn
   :counters-snapshot counters-snapshot
   :log-warn log-warn-fn
   :current-time-ms current-time-ms
   :env env-fn
   :deploy-config deploy-config-fn
   :ex-info {:impl ex-info-fn :taint-propagate? true}
   :throw {:impl throw-fn :taint-propagate? true}
   :read-resource-or-nil read-resource-or-nil
   :read-resource-bytes read-resource-bytes
   :shipped-asset shipped-asset
   :build-hashes-raw build-hashes-raw
   :invoke {:impl invoke-fn :return-type-rule invoke-return-rule :taint-propagate? true}
   :call {:impl invoke-fn :taint-propagate? true}
   :call-with {:impl call-with-fn :taint-propagate? true}
   :call-traced {:impl call-traced-fn :taint-propagate? true}
   :call-noargs {:impl call-noargs-fn :taint-propagate? true}
   :try {:impl try-fn :taint-propagate? true}
   :slurp {:impl slurp-fn :taint-propagate? true}
   :parse-int {:impl parse-int :taint-propagate? true}
   :digest-hex {:impl digest-hex-fn :taint-propagate? true}
   :throwable-message {:impl throwable-message-fn :taint-propagate? true}
   :throwable-class-name {:impl throwable-class-name-fn :taint-propagate? true}
   :ex-data {:impl ex-data-fn :taint-propagate? true}
   :parse-uuid {:impl parse-uuid-fn :taint-propagate? true}})
