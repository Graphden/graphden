(ns graphden.packages.loaded
  "The loaded-package roster — what `:app/packages` loaded at boot,
   kept as a process-global read model so the graph (the marketplace's
   Executor tab, `:loaded-packages`) can list the executor's packages
   without re-reading the classpath.

   Same shape of seam as `graphden.crud.api-routes-js`: the init-key
   installs once at boot, readers get a plain vector. Only the
   `package.edn` metadata plus a per-package `:base-fn-count` are kept —
   no fn-defs, no impls — because the roster answers one question
   (\"which impl / fns packages does THIS executor run, and where did
   they come from?\"), never \"what is in them\"."
  (:require
    [graphden.packages.manifest :as manifest]))


(defonce ^:private roster (atom []))


(defn- external-names
  "Package names the operator manifest (`executor-packages.edn`) adds —
   a Type-2 package pulled in by git / Maven coord, as opposed to a
   package shipped in the platform's own resource tree."
  []
  (set (manifest/package-names (manifest/read-manifest))))


(defn roster-entry
  "One roster row from a loaded package's `:meta` (its `package.edn`) and
   the base-fn defs the loader attributed to it: name, version,
   description, module count, whether it ships impls (any base-fn),
   and its origin (`:manifest` for an operator-listed external package,
   `:bundled` otherwise)."
  [pkg-meta base-fn-count external?]
  {:name (:name pkg-meta)
   :version (:version pkg-meta)
   :description (:description pkg-meta)
   :modules (vec (:modules pkg-meta))
   :dependencies (vec (map #(if (map? %) (:name %) %) (:dependencies pkg-meta)))
   :base-fn-count base-fn-count
   :kind (if (pos? (long base-fn-count)) "impl+fns" "fns-only")
   :origin (if external? "manifest" "bundled")})


(defn install!
  "Record the roster from the loader's `load-packages` result:
   `:packages` (each package's meta) and `:base-fn-counts` (impls per
   package name)."
  [{:keys [packages base-fn-counts]}]
  (let [ext (external-names)
        counts (or base-fn-counts {})]
    (reset! roster
            (vec (for [m packages]
                   (roster-entry m (get counts (:name m) 0) (contains? ext (:name m))))))))


(defn read-roster
  "The loaded-package roster, in load order — `[]` before boot."
  []
  @roster)


(defn clear!
  []
  (reset! roster []))
