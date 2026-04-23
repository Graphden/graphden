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
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; EDN Loading
;; =============================================================================

(defn- read-resource-edn
  "Reads and parses EDN from a classpath resource."
  [path]
  (when-let [resource (io/resource path)]
    (with-open [rdr (java.io.PushbackReader. (io/reader resource))]
      (edn/read rdr))))


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


(defn- load-module-fns
  "Loads fns.edn for a module. Expected shape:

     {:namespace \"core.arithmetic\"
      :fns [{:name :add :args {...}} ...]}

   Returns {:ns-path string :fns [fn-defs]}."
  [package-name module-name]
  (let [path (str "packages/" package-name "/" module-name "/fns.edn")]
    (if-let [raw (read-resource-edn path)]
      {:ns-path (:namespace raw)
       :fns (vec (:fns raw))}
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

(defn- base-fn?
  "Returns true if this is a base function (no :parent / :parents key)."
  [fn-def]
  (and (not (contains? fn-def :parent))
       (not (contains? fn-def :parents))))


(defn- fn-def->base-fn-def
  "Converts a fns.edn entry + impl function to registry format.

   Input:  {:name :add :args {:nums :jsonb} :return-type :numeric}
   Impl:   (defbase add [nums] (apply + nums))
   Output: {:args {:nums {:type :jsonb :required true}}
            :return-type :numeric
            :impl <fn>}

   All registered impls are 2-arity `(fn [args ctx] …)` (produced by
   `defbase`). The executor always calls them with both args, so the
   loader simply hands impl-fn through."
  [fn-def impl-fn]
  {:args (normalize-args (:args fn-def))
   :return-type (:return-type fn-def)
   :impl impl-fn})


(defn- process-module
  "Processes a single module, returning base-fn-defs and fn-defs.
   Each fn-def and base-fn-def receives a :namespace key from the module's
   fns.edn declaration (nil if no namespace declared)."
  [package-name module-name]
  (let [{ns-path :ns-path fns :fns} (load-module-fns package-name module-name)
        impls (load-module-impls package-name module-name)

        ;; Separate base functions from fn-defs
        {base-fns true fn-defs false} (group-by base-fn? fns)

        ;; Convert base functions to registry format
        base-fn-defs (into {}
                           (filter some?)
                           (for [fn-def base-fns
                                 :let [fn-name (:name fn-def)
                                       impl-fn (get impls fn-name)]]
                             (if impl-fn
                               [fn-name (assoc (fn-def->base-fn-def fn-def impl-fn)
                                               :namespace ns-path)]
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
     :fn-defs fn-defs-with-ns}))


;; =============================================================================
;; Package Loading
;; =============================================================================

(defn- load-single-package
  "Loads a single package and returns its functions."
  [package-name]
  (log/info "Loading package:" package-name)
  (let [pkg-meta (load-package-meta package-name)
        modules (:modules pkg-meta)]

    (reduce
      (fn [acc module-name]
        (let [{:keys [base-fn-defs fn-defs]} (process-module package-name module-name)]
          (-> acc
              (update :base-fn-defs merge base-fn-defs)
              (update :fn-defs into fn-defs))))
      {:base-fn-defs {}
       :fn-defs []
       :meta pkg-meta}
      modules)))


(defn- resolve-dependencies
  "Returns packages in dependency order (topological sort).
   Simple implementation assuming no cycles."
  [package-names]
  (let [metas (into {} (for [pkg package-names]
                         [pkg (load-package-meta pkg)]))

        ;; Build dependency graph
        get-deps (fn [pkg]
                   (filter (set package-names)
                           (get-in metas [pkg :dependencies] [])))

        ;; Simple DFS-based topological sort
        sorted (atom [])
        visited (atom #{})

        visit (fn visit
                [pkg]
                (when-not (@visited pkg)
                  (swap! visited conj pkg)
                  (doseq [dep (get-deps pkg)]
                    (visit dep))
                  (swap! sorted conj pkg)))]

    (doseq [pkg package-names]
      (visit pkg))

    @sorted))


(defn load-packages
  "Loads multiple packages in dependency order.

   Arguments:
   - package-names: seq of package names to load (e.g., [\"core\" \"web\" \"app\"])

   Returns:
   {:base-fn-defs {fn-name -> {:args ... :return-type ... :impl fn}}
    :fn-defs [{:name :foo :parent :bar :args {...}} ...]
    :packages [{:name \"core\" :version \"1.0.0\" ...} ...]
    :startup-fn :web-server}  ; from last package with :startup-fn"
  [package-names]
  (let [ordered (resolve-dependencies package-names)
        _ (log/info "Loading packages in order:" ordered)
        results (mapv load-single-package ordered)
        ;; Single pass over results to build all aggregations
        combined (reduce (fn [acc result]
                           (-> acc
                               (update :base-fn-defs merge (:base-fn-defs result))
                               (update :fn-defs into (:fn-defs result))
                               (update :packages conj (:meta result))
                               (cond-> (get-in result [:meta :startup-fn])
                                 (assoc :startup-fn (get-in result [:meta :startup-fn])))))
                         {:base-fn-defs {} :fn-defs [] :packages [] :startup-fn nil}
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
   'core' and 'core.arithmetic' with parent link).

   Returns a map {ns-path-string → ns-entity-id} for downstream use."
  [storage namespace-paths]
  (if (empty? namespace-paths)
    {}
    (let [;; Sort paths by depth so parents are created before children
          sorted (sort-by #(count (str/split % #"\.")) namespace-paths)
          ;; Existing ns entities by [parent-id name]
          existing (into {}
                         (map (fn [ns-entity]
                                [(str (:parent-id ns-entity) ":" (:name ns-entity))
                                 ns-entity]))
                         (sp/query-entities storage :ns {}))
          result (atom {})  ; ns-path → id
          ]
      (doseq [ns-path sorted]
        (let [segments (str/split ns-path #"\.")
              ;; Resolve parent: for "core.arithmetic", parent = result["core"]
              parent-path (when (> (count segments) 1)
                            (str/join "." (butlast segments)))
              parent-id (when parent-path (get @result parent-path))
              seg-name (last segments)
              lookup-key (str parent-id ":" seg-name)
              existing-entity (get existing lookup-key)]
          (if existing-entity
            (swap! result assoc ns-path (:id existing-entity))
            (let [new-entity (sp/create-entity storage :ns
                                               {:name seg-name
                                                :parent-id parent-id})]
              (swap! result assoc ns-path (:id new-entity))))))
      (log/info "Synced" (count @result) "namespaces:" (keys @result))
      @result)))


;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn load-default-packages
  "Loads the default package set: core, web, app."
  []
  (load-packages ["core" "web" "app"]))


(defn get-startup-fn-name
  "Returns the startup function name from loaded packages."
  [loaded-packages]
  (:startup-fn loaded-packages))


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
