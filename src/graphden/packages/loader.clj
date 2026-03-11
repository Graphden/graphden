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
    [clojure.tools.logging :as log]))


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
  "Loads fns.edn for a module."
  [package-name module-name]
  (let [path (str "packages/" package-name "/" module-name "/fns.edn")]
    (if-let [fns (read-resource-edn path)]
      fns
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
  "Returns true if this is a base function (no :parent key)."
  [fn-def]
  (not (contains? fn-def :parent)))


(defn- deref-args
  "Dereferences all delay values in an args map.
   Used to convert executor's delay-wrapped args to plain values for impls."
  [args lazy-set]
  (reduce-kv
    (fn [m k v]
      (assoc m k (if (and (instance? clojure.lang.IDeref v)
                          (not (contains? lazy-set k)))
                   @v
                   v)))
    {}
    args))


(defn- fn-def->base-fn-def
  "Converts a fns.edn entry + impl function to registry format.

   Input:  {:name :add :args {:nums :jsonb} :return-type :numeric}
   Impl:   (fn [{:keys [nums]}] (apply + nums))
   Output: {:args {:nums {:type :jsonb :required true}}
            :return-type :numeric
            :impl <fn>
            :lazy #{...}  ; optional
            :ctx true}    ; optional

   Note: The wrapper handles:
   - Dereferencing delay-wrapped args (executor passes delays)
   - Passing ctx to impl if :ctx true in fn-def
   - Preserving lazy args as delays (not dereferenced)"
  [fn-def impl-fn]
  (let [lazy-set (or (:lazy fn-def) #{})
        ;; Wrap impl to:
        ;; 1. Match executor's [args ctx] signature
        ;; 2. Deref args before passing to impl (package impls expect plain values)
        wrapped-impl (if (:ctx fn-def)
                       ;; :ctx true - impl expects [args ctx], deref args
                       (fn [args ctx]
                         (impl-fn (deref-args args lazy-set) ctx))
                       ;; :ctx false - impl expects [args], deref args, ignore ctx
                       (fn [args _ctx]
                         (impl-fn (deref-args args lazy-set))))]
    (cond-> {:args (normalize-args (:args fn-def))
             :return-type (:return-type fn-def)
             :impl wrapped-impl}
      (:lazy fn-def) (assoc :lazy (:lazy fn-def))
      (:ctx fn-def) (assoc :ctx true))))


(defn- process-module
  "Processes a single module, returning base-fn-defs and fn-defs."
  [package-name module-name]
  (let [fns (load-module-fns package-name module-name)
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
                               [fn-name (fn-def->base-fn-def fn-def impl-fn)]
                               (do
                                 (log/warn "No impl found for base fn:" fn-name
                                           "in" package-name "/" module-name)
                                 nil))))]

    {:base-fn-defs base-fn-defs
     :fn-defs (vec fn-defs)}))


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

        combined {:base-fn-defs (apply merge (map :base-fn-defs results))
                  :fn-defs (vec (mapcat :fn-defs results))
                  :packages (mapv :meta results)
                  :startup-fn (some #(get-in % [:meta :startup-fn]) (reverse results))}]

    (log/info "Loaded" (count (:base-fn-defs combined)) "base functions,"
              (count (:fn-defs combined)) "fn-defs")
    combined))


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
        (vec (sort (map java.io.File/.getName
                        (filter java.io.File/.isDirectory
                                (java.io.File/.listFiles file)))))))))
