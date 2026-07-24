(ns graphden.packages.loader
  "Package loader for graphden function packages.

   Loads packages from resources/packages/ directory structure:
   - package.edn: Package metadata (name, version, dependencies, modules)
   - module/fns.edn: Function definitions
   - module/impls.clj: Implementation functions (loaded as resources)

   ## Package Structure

   packages/
   ├── core/
   │   ├── package.edn
   │   ├── arithmetic/
   │   │   ├── fns.edn
   │   │   └── impls.clj
   │   └── logic/
   │       ├── fns.edn
   │       └── impls.clj
   └── web/
       ├── package.edn
       └── http/
           ├── fns.edn
           └── impls.clj

   ## Function Types

   1. Base functions: Have :args, :return-type, and implementation in impls.clj
   2. Fn-defs (compositions): Have :parent key, no implementation needed

   ## Usage

   ```clojure
   (require '[graphden.packages.loader :as pkg])

   ;; Load all packages
   (def result (pkg/load-packages [\"core\" \"web\" \"app\"]))

   ;; Get base function definitions for registry
   (:base-fn-defs result)  ; {fn-name -> {:args ... :return-type ... :impl fn}}

   ;; Get fn-defs for composition
   (:fn-defs result)  ; [{:name :foo :parent :bar :args {...}} ...]
   ```"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.tools.reader :as treader]
    [clojure.tools.reader.reader-types :as treader-types]
    [graphden.packages.records.wire :as wire]
    [graphden.packages.semver :as semver]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; EDN Loading
;; =============================================================================

(defn- read-resource-edn
  "Reads and parses EDN from a classpath resource. `#graphden/ref`
   wire refs (records.wire) decode back to their keywords."
  [path]
  (when-let [resource (io/resource path)]
    (with-open [rdr (java.io.PushbackReader. (io/reader resource))]
      (edn/read {:readers wire/wire-readers} rdr))))


(defn- read-resource-edn-with-meta
  "Reads EDN from a classpath resource with line/column metadata
   attached to every collection. Used for `fns.edn` so each fn-def
   knows where it was declared — that surfaces in type-error
   messages so users see `file:line` instead of just `:my-fn`.

   Slightly slower than `clojure.edn/read` because tools.reader
   tracks source positions; called only at startup so it's fine.
   `#graphden/ref` wire refs decode via `*data-readers*` — a
   re-imported whole-graph bundle dropped into a package tree reads
   the same as hand-authored EDN."
  [path]
  (when-let [resource (io/resource path)]
    (let [rdr (treader-types/source-logging-push-back-reader (slurp resource))]
      (binding [treader/*data-readers* wire/wire-readers]
        (treader/read {:eof nil} rdr)))))


(defn- load-package-meta
  "Loads package.edn metadata for a package."
  [package-name]
  (let [path (str "packages/" package-name "/package.edn")]
    (if-let [pkg-meta (read-resource-edn path)]
      pkg-meta
      (throw (ex-info (str "Package not found: " package-name)
                      {:type :package-error/not-found
                       :package package-name
                       :path path})))))


(defn- attach-source-meta
  "Lift line metadata from each fn-def map onto the map itself as
   plain `:source-file` / `:source-line` keys. tools.reader puts the
   info on the metadata map; we copy it into the value so downstream
   consumers (sync, type-check) can read it without juggling
   metadata. fn-defs without metadata pass through unchanged."
  [fn-defs path]
  (mapv (fn [fd]
          (let [m (meta fd)]
            (cond-> fd
              (and (map? fd) (:line m))
              (assoc :source-file path
                     :source-line (:line m)))))
        fn-defs))


(defn- load-module-fns
  "Loads fns.edn for a module. Expected shape:

     {:namespace \"core.arithmetic\"
      :description \"Arithmetic primitives — add/sub/mul/div/mod\"
      :fns [{:name :add :args {...}} ...]}

   Returns {:ns-path string :ns-description string-or-nil :fns [fn-defs]}.

   Every fn-def carries `:source-file` (the resource path) and
   `:source-line` (where the fn-def's opening `{` sat in the EDN).
   Type-check errors and other diagnostics include those so users see
   `file:line` instead of just `:my-fn`."
  [package-name module-name]
  (let [path (str "packages/" package-name "/" module-name "/fns.edn")]
    (if-let [raw (read-resource-edn-with-meta path)]
      {:ns-path (:namespace raw)
       :ns-description (:description raw)
       :fns (attach-source-meta (vec (:fns raw)) path)}
      (throw (ex-info (str "Module fns not found: " package-name "/" module-name)
                      {:type :package-error/module-not-found
                       :package package-name
                       :module module-name
                       :path path})))))


;; =============================================================================
;; Implementation Loading
;; =============================================================================

(defn- load-impls-via-eval
  "Loads implementations by evaluating the impls.clj file as Clojure code.
   Returns the impls map from the namespace."
  [package-name module-name]
  (let [path (str "packages/" package-name "/" module-name "/impls.clj")]
    (when-let [resource (io/resource path)]
      (let [content (slurp resource)
            ;; Parse the ns form to extract the namespace name
            forms (read-string (str "[" content "]"))
            ns-form (first forms)
            ns-sym (when (and (seq? ns-form) (= 'ns (first ns-form)))
                     (second ns-form))]
        (when ns-sym
          ;; Create the namespace if it doesn't exist
          (create-ns ns-sym)

          ;; Evaluate all forms in the namespace context
          (binding [*ns* (the-ns ns-sym)]
            (doseq [form forms]
              (eval form)))

          ;; Return the impls var value
          (when-let [impls-var (ns-resolve ns-sym 'impls)]
            @impls-var))))))


(defn- load-module-impls
  "Loads impls from a module. Returns nil if no impls.clj exists."
  [package-name module-name]
  (load-impls-via-eval package-name module-name))


;; =============================================================================
;; Argument Normalization
;; =============================================================================

(defn- normalize-arg-spec
  "Normalizes an argument spec to the full form.

   Handles:
   - :type -> {:type :type :required true}
   - {:type :type :required false} -> as-is
   - {:type :type} -> {:type :type :required true}"
  [arg-spec]
  (if (keyword? arg-spec)
    {:type arg-spec :required true}
    (merge {:required true} arg-spec)))


(defn- normalize-args
  "Normalizes the :args map to expanded form."
  [args]
  (when args
    (into {}
          (map (fn [[k v]] [k (normalize-arg-spec v)]))
          args)))


;; =============================================================================
;; Function Definition Processing
;; =============================================================================

(defn- type-row?
  "True iff `fn-def` is a type-row declaration (record / refinement /
   list / map / union / variant / fn-type). Type-rows have no impl and
   live in `:fn-defs` alongside composed defs — the records-parser
   routes them by their role marker. `:fn-type` declarations don't
   actually produce a fn-row (they're pure type-aliases) but they
   still flow through this path so they get registered as aliases by
   system/core's `register-type-aliases!`."
  [fn-def]
  (boolean (or (:type fn-def) (:refine fn-def) (:list fn-def)
               (:map fn-def) (:tuple fn-def) (:union fn-def)
               (:variant fn-def) (:fn-type fn-def))))


(defn- base-fn?
  "Returns true if this is a base function — no role markers and no
   parent. Type-rows (`:type` / `:refine` / `:list` / `:union` /
   `:variant`) and composed defs (`:parent` / `:parents`) flow through
   `:fn-defs` instead."
  [fn-def]
  (and (not (contains? fn-def :parent))
       (not (contains? fn-def :parents))
       (not (type-row? fn-def))))


(defn- impl-entry->parts
  "An `impls`-map VALUE is either a bare impl fn (the common short
   form, no type-rule) or a map `{:impl … :return-type-rule …
   :slot-types-rule … :nav-types-rule …}` when the base-fn declares
   per-base-fn type-rules at the base-fn's registration site.
   Normalises both to a map with at least `:impl` (nil-safe — caller
   handles the missing-impl case)."
  [impl-entry]
  (if (map? impl-entry)
    impl-entry
    {:impl impl-entry}))


(defn- fn-def->base-fn-def
  "Converts a fns.edn entry + impl entry to registry format.

   Input:  {:name :add :args {:nums :jsonb} :return-type :numeric}
   Impl:   (defbase add [nums] (apply + nums))
   Output: {:args {:nums {:type :jsonb :required true}}
            :return-type :numeric
            :impl <fn>}

   The `impl-entry` is either a bare impl fn or a map
   `{:impl … :return-type-rule … :slot-types-rule … :nav-types-rule …}`
   — the optional `*-rule` fns are the base-fn-specific type-rules
   declared at the base-fn's own registration site. They flow into the
   base-fn-def map and `record-rich-types!` threads them into the
   rich-types-registry so the type-checker can look them up by base-fn
   identity (no name-dispatch).

   All registered impls are 2-arity `(fn [args ctx] …)` (produced by
   `defbase`). The executor always calls them with both args, so the
   loader simply hands impl-fn through."
  [fn-def impl-entry]
  (let [{:keys [impl return-type-rule slot-types-rule nav-types-rule
                taint-propagate? lazy-seq-args compile-time-value?]}
        (impl-entry->parts impl-entry)]
    (cond-> {:args (normalize-args (:args fn-def))
             :return-type (:return-type fn-def)
             :impl impl}
      (:description fn-def) (assoc :description (:description fn-def))
      ;; Forward `:effects` (set of category tags). Every effectful
      ;; base-fn names its specific category
      ;; (`:db` / `:env` / `:io` / `:network` / `:time` / `:random` /
      ;; `:process` / `:raw-sql`). `:process` = spawns supervised
      ;; background work (service-eligibility marker); `:raw-sql` = raw
      ;; SQL escape hatch, blocked for cloud/tenant graphs.
      (:effects fn-def)     (assoc :effects (set (:effects fn-def)))
      ;; Per-base-fn type-rules — only present when the impls.clj
      ;; declared them on the entry map.
      return-type-rule      (assoc :return-type-rule return-type-rule)
      slot-types-rule       (assoc :slot-types-rule slot-types-rule)
      nav-types-rule        (assoc :nav-types-rule nav-types-rule)
      ;; `:taint-propagate?` — marker-taint (`:secret` &c.) flows from
      ;; any tainted input into the return. Applied CENTRALLY by the
      ;; checker on top of the structural rule/signature result —
      ;; replaces the per-site `types/wrap-with-taint` wrapping.
      taint-propagate?      (assoc :taint-propagate? true)
      ;; `:lazy-seq-args` — seq slots whose ITEMS arrive as delays
      ;; (consumer steps past unforced items, see :cond).
      lazy-seq-args         (assoc :lazy-seq-args lazy-seq-args)
      ;; `:compile-time-value?` — evaluate this base-fn ONCE at compile
      ;; time and bake `(constantly result)` into the closure (see
      ;; compile_eager). Used by `:cell` for a registry-persistent atom.
      compile-time-value?   (assoc :compile-time-value? true)
      ;; `:tags` — declarative capability / shape markers; consumed by
      ;; policy callers (e.g. admin-only-vault gate) via
      ;; `registry/fn-names-with-tag`. See `record-rich-types!`.
      (seq (:tags fn-def))  (assoc :tags (set (:tags fn-def)))
      ;; `:branch-local?` — identity-level monotonic flag. Pass
      ;; through so `records-parser/attach-fn-meta` can stamp it onto
      ;; the fn-row; the merge-resolution filter in
      ;; `versioning.storage.resolution` reads it directly.
      ;; Without this, the seed `:branch-local? true` on base-fns
      ;; (`:http-server`, `:secret-leaf`, `:schedule`, `:env`) is
      ;; silently stripped here and downstream sync writes nil.
      (contains? fn-def :branch-local?)
      (assoc :branch-local? (boolean (:branch-local? fn-def))))))


;; Type-rows are first-class fn-rows declared in `fns.edn` alongside
;; fn-defs:
;;
;;   {:name :ring-response-shape
;;    :type {:status :http-status :headers :jsonb :body :text}}
;;
;;   {:name :positive-int
;;    :refine {:base :int :constraint [:> 0]}}
;;
;;   {:name :int-list
;;    :list :int}
;;
;; Parsing flows through `type-row?` here and the records-parser in
;; `graphden.packages.records`.


(defn- process-module
  "Processes a single module, returning base-fn-defs and fn-defs.
   Each fn-def and base-fn-def receives a :namespace key from the
   module's fns.edn declaration (nil if no namespace declared).

   Type-rows (`:type` / `:refine` / `:list` / `:union` / `:variant`)
   ride along in the `fn-defs` slot — `composition/sync-fns-to-storage!`
   routes them to slot / fn-slot / binding rows by role marker."
  [package-name module-name]
  (let [{ns-path :ns-path ns-description :ns-description fns :fns}
        (load-module-fns package-name module-name)
        impls (load-module-impls package-name module-name)

        ;; Separate base functions from fn-defs
        {base-fns true fn-defs false} (group-by base-fn? fns)

        ;; Convert base functions to registry format. The `impls`-map
        ;; value is either a bare impl fn or a `{:impl … :*-rule …}`
        ;; map; `fn-def->base-fn-def` normalises both. The "no impl"
        ;; check inspects the normalised `:impl` so a rule-only entry
        ;; (missing `:impl`) is still rejected.
        base-fn-defs (into {}
                           (filter some?)
                           (for [fn-def base-fns
                                 :let [fn-name (:name fn-def)
                                       impl-entry (get impls fn-name)
                                       base-def (when impl-entry
                                                  (fn-def->base-fn-def fn-def impl-entry))]]
                             (if (:impl base-def)
                               [fn-name (assoc base-def :namespace ns-path)]
                               (do
                                 (log/warn "No impl found for base fn:" fn-name
                                           "in" package-name "/" module-name)
                                 nil))))

        ;; Attach namespace to fn-defs
        fn-defs-with-ns (mapv #(if ns-path
                                 (assoc % :namespace ns-path)
                                 %)
                              fn-defs)]

    {:base-fn-defs base-fn-defs
     :fn-defs fn-defs-with-ns
     :ns-descriptions (if (and ns-path ns-description)
                        {ns-path ns-description}
                        {})}))


;; =============================================================================
;; Package Loading
;; =============================================================================

(defn- load-single-package
  "Loads a single package and returns its functions.

   The package's top-level namespace (the bare package name, e.g. `core`)
   inherits its description from `package.edn`'s `:description`. Module
   namespaces (`core.arithmetic`, …) get theirs from each module's
   `fns.edn` `:description`."
  [package-name]
  (log/info "Loading package:" package-name)
  (let [pkg-meta (load-package-meta package-name)
        modules (:modules pkg-meta)
        seed-ns-descriptions (cond-> {}
                               (:description pkg-meta)
                               (assoc package-name (:description pkg-meta)))]

    (reduce
      (fn [acc module-name]
        (let [{:keys [base-fn-defs fn-defs ns-descriptions]}
              (process-module package-name module-name)]
          (-> acc
              (update :base-fn-defs merge base-fn-defs)
              (update :fn-defs into fn-defs)
              (update :ns-descriptions merge ns-descriptions))))
      {:base-fn-defs {}
       :fn-defs []
       :ns-descriptions seed-ns-descriptions
       :meta pkg-meta}
      modules)))


(defn- normalize-deps
  "Normalise `package.edn` `:dependencies` into a seq of
   `{:name \"core\" :constraint \">=1.5.0\"|nil}`.

   Accepted shapes (backward-compatible):
   - bare name list      `[\"core\" \"web\"]`         → no constraints
   - map name→constraint `{\"core\" \">=1.5.0\"}`     → constraints (canonical)
   - mixed list entry    `[\"core\" [\"web\" \">=2.0\"]]` → per-entry pair ok

   A flat two-string vector `[\"a\" \"b\"]` is TWO bare names, never a
   name+constraint pair — constraints must use the map form (or a nested
   `[name constraint]` vector) to stay unambiguous."
  [deps]
  (cond
    (nil? deps) []
    (map? deps) (mapv (fn [[n c]] {:name (name n) :constraint c}) deps)
    (sequential? deps)
    (mapv (fn [d]
            (cond
              (string? d)     {:name d :constraint nil}
              (keyword? d)    {:name (name d) :constraint nil}
              (sequential? d) {:name (name (first d)) :constraint (second d)}
              :else (throw (ex-info "Bad :dependencies entry"
                                    {:type :packages/bad-dependencies :entry d}))))
          deps)
    :else (throw (ex-info "Bad :dependencies"
                          {:type :packages/bad-dependencies :dependencies deps}))))


(defn- validate-dep-constraints!
  "Given `{package-name -> package.edn-meta}` for every loaded package,
   throw `:packages/version-conflict` if a declared version constraint is
   not satisfied by the version PRESENT on the classpath. Constraint-free
   deps (legacy bare names) are skipped. Pure over its argument so it is
   unit-testable without classpath fixtures."
  [metas]
  (doseq [[pkg meta] metas
          dep (normalize-deps (:dependencies meta))
          :when (:constraint dep)
          :let [present (get-in metas [(:name dep) :version])]]
    (when-not (semver/satisfies-constraint? present (:constraint dep))
      (throw (ex-info (format "Package %s requires %s %s, but version %s is present"
                              pkg (:name dep) (:constraint dep) (or present "?"))
                      {:type :packages/version-conflict
                       :package pkg
                       :dependency (:name dep)
                       :required (:constraint dep)
                       :present present})))))


(defn- resolve-dependencies
  "Returns packages in dependency order (topological sort).

   Pulls TRANSITIVE deps — if `app` declares `:dependencies [\"core\"
   \"web\" \"storage\"]` and the caller passes just `[\"app\"]`, all
   three are loaded ahead of `app`. Silently dropping a transitive dep
   was a real bug: the missing primitive surfaced as `Unknown parent`
   at sync time, far from the misconfiguration.

   Validates version constraints (`{\"core\" \">=1.5.0\"}` form) against
   the classpath versions once the graph is resolved — a mismatch throws
   `:packages/version-conflict` at boot, not `Unknown parent` at sync.

   Simple DFS topological sort, no cycle detection (a cycle in the
   dep graph would loop here; package.edn dep graphs are tiny and
   reviewed)."
  [package-names]
  (let [metas (atom {})
        load-meta! (fn [pkg]
                     (or (@metas pkg)
                         (let [m (load-package-meta pkg)]
                           (swap! metas assoc pkg m)
                           m)))
        sorted (atom [])
        visited (atom #{})
        visit (fn visit
                [pkg]
                (when-not (@visited pkg)
                  (swap! visited conj pkg)
                  (doseq [dep (normalize-deps (get (load-meta! pkg) :dependencies []))]
                    (visit (:name dep)))
                  (swap! sorted conj pkg)))]
    (doseq [pkg package-names]
      (visit pkg))
    (validate-dep-constraints! @metas)
    @sorted))


(defn load-packages
  "Loads multiple packages in dependency order.

   Arguments:
   - package-names: seq of package names to load (e.g., [\"core\" \"web\" \"app\"])

   Returns:
   {:base-fn-defs {fn-name -> {:args ... :return-type ... :impl fn}}
    :fn-defs [{:name :foo :parent :bar :args {...}} ...]
    :packages [{:name \"core\" :version \"1.0.0\" ...} ...]
    :seeded-services [{:package-name \"app\"
                       :service-name :default
                       :fn-name :web-server
                       :enabled? true
                       :restart-policy :always
                       :cardinality :per-pod
                       :description \"…\"} …]}"
  [package-names]
  (let [ordered (resolve-dependencies package-names)
        _ (log/info "Loading packages in order:" ordered)
        results (mapv load-single-package ordered)
        ;; Single pass over results to build all aggregations
        combined (reduce (fn [acc result]
                           (let [pkg-name (get-in result [:meta :name])
                                 pkg-services (get-in result [:meta :services])]
                             (cond-> (-> acc
                                         (update :base-fn-defs merge (:base-fn-defs result))
                                         (update :fn-defs into (:fn-defs result))
                                         (update :ns-descriptions merge (:ns-descriptions result))
                                         (update :packages conj (:meta result)))
                               (seq pkg-services)
                               (update :seeded-services into
                                       (mapv (fn [svc]
                                               (assoc svc :package-name pkg-name))
                                             pkg-services)))))
                         {:base-fn-defs {} :fn-defs [] :ns-descriptions {}
                          :packages [] :seeded-services []}
                         results)
        ;; Collect all namespace paths declared in modules.
        ;; A path like "core.arithmetic" also implies "core" as a parent ns.
        all-ns-paths (into #{}
                           (comp
                             (mapcat (fn [result]
                                       (keep (fn [[_fn-name fn-def]]
                                               (:namespace fn-def))
                                             (:base-fn-defs result))))
                             (remove nil?))
                           results)
        fn-def-ns    (into #{} (keep :namespace) (:fn-defs combined))
        ;; Expand parent ns paths: "core.arithmetic" → #{"core" "core.arithmetic"}
        expand-ns    (fn [ns-path]
                       (let [segments (str/split ns-path #"\.")
                             paths (map (fn [n]
                                          (str/join "." (take (inc n) segments)))
                                        (range (count segments)))]
                         (set paths)))
        all-namespaces (reduce into #{} (map expand-ns (into all-ns-paths fn-def-ns)))
        combined (assoc combined :namespaces all-namespaces)]
    (log/info "Loaded" (count (:base-fn-defs combined)) "base functions,"
              (count (:fn-defs combined)) "fn-defs")
    combined))


;; =============================================================================
;; Namespace Sync
;; =============================================================================

(defn sync-namespaces!
  "Creates namespace entities in storage for all declared namespace paths.
   Builds the parent-child hierarchy (e.g. 'core.arithmetic' creates both
   'core' and 'core.arithmetic' with parent link). Optional
   `descriptions` map (`{ns-path → string}`) seeds and updates the
   `:description` field on matching namespace entities; undeclared
   intermediate parents (`core` when only `core.arithmetic` is in the
   map) keep `nil`.

   Returns a map {ns-path-string → ns-entity-id} for downstream use."
  ([storage namespace-paths]
   (sync-namespaces! storage namespace-paths {}))
  ([storage namespace-paths descriptions]
   (if (empty? namespace-paths)
     {}
     (let [;; Expand each path to all its prefixes so the parent chain
           ;; is always present — `"a.b.c"` ⇒ `#{"a" "a.b" "a.b.c"}`.
           ;; Honours the docstring's "creates both core and
           ;; core.arithmetic" promise even when the caller passes only
           ;; leaf paths (e.g. package install). Idempotent for callers
           ;; that already pre-expand (the package loader).
           expanded (into #{}
                          (mapcat (fn [p]
                                    (let [segs (str/split p #"\.")]
                                      (map #(str/join "." (take (inc %) segs))
                                           (range (count segs))))))
                          namespace-paths)
           sorted (sort-by #(count (str/split % #"\.")) expanded)
           existing (into {}
                          (map (fn [ns-entity]
                                 [(str (:parent-id ns-entity) ":" (:name ns-entity))
                                  ns-entity]))
                          (sp/query-entities storage :ns {}))
           result (atom {})]
       (doseq [ns-path sorted]
         (let [segments (str/split ns-path #"\.")
               parent-path (when (> (count segments) 1)
                             (str/join "." (butlast segments)))
               parent-id (when parent-path (get @result parent-path))
               seg-name (last segments)
               lookup-key (str parent-id ":" seg-name)
               existing-entity (get existing lookup-key)
               description (get descriptions ns-path)]
           (if existing-entity
             (do
               (when (and description
                          (not= description (:description existing-entity)))
                 (sp/update-entity storage :ns (:id existing-entity)
                                   {:description description}))
               (swap! result assoc ns-path (:id existing-entity)))
             (let [new-entity (sp/create-entity storage :ns
                                                (cond-> {:name seg-name
                                                         :parent-id parent-id}
                                                  description (assoc :description description)))]
               (swap! result assoc ns-path (:id new-entity))))))
       (log/info "Synced" (count @result) "namespaces:" (keys @result))
       @result))))


;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn load-default-packages
  "Loads the default package set: core, web, app."
  []
  (load-packages ["core" "web" "app"]))


(defn get-seeded-services
  "Returns the vector of `:services` entries aggregated across all
   loaded packages — each entry is the original package map plus
   `:package-name` so the seeder can compute deterministic ids."
  [loaded-packages]
  (vec (:seeded-services loaded-packages)))


(defn list-available-packages
  "Lists all available packages in resources/packages/."
  []
  (when-let [packages-url (io/resource "packages/")]
    (let [file (io/file packages-url)]
      (when (java.io.File/.isDirectory file)
        (when-let [children (java.io.File/.listFiles file)]
          (->> children
               (filter #(java.io.File/.isDirectory ^java.io.File %))
               (map #(java.io.File/.getName ^java.io.File %))
               sort
               vec))))))
