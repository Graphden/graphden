(ns ui-target-lint
  "Reject executable lookup of shipped UI targets outside graph declarations.
   This is a source boundary check, not an allowlist of component identities."
  (:require
    [babashka.fs :as fs]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [edamame.core :as reader]))


(defn- source-paths
  [root]
  (concat (fs/glob root "src/**/*.clj")
          (fs/glob root "resources/packages/**/impls.clj")))


(defn- declaration-boundary?
  [path]
  (or (str/starts-with? path "src/graphden/ui/")
      (boolean (re-matches #"src/graphden/editor/(?:component_templates|config_templates|root_migration[^/]*|root_legacy)\.clj" path))))


(defn- target-namespace?
  [value]
  (and (string? value)
       (str/starts-with? value "app.ui-")
       (not (#{"app.ui-config" "app.ui-components"} value))))


(defn- target-reference?
  [value]
  (and (or (keyword? value) (symbol? value))
       (target-namespace? (namespace value))))


(defn- lookup?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "fn-id" (name (first form)))
       (target-namespace? (second form))))


(defn- offenders
  [form]
  (let [result (volatile! [])]
    (letfn [(visit
              [value]
              (when (or (lookup? value) (target-reference? value))
                (vswap! result conj value))
              (cond
                (map? value) (doseq [[key nested] value] (visit key) (visit nested))
                (coll? value) (doseq [nested value] (visit nested))))]
      (visit form))
    @result))


(defn- read-forms
  [path]
  (reader/parse-string-all (slurp (str path))
                           {:read-cond :allow :fn true :regex true :var true :deref true
                            :syntax-quote true
                            :auto-resolve #(if % (str %) "ui-target-lint")}))


(defn- inspect-source
  [root path]
  (let [relative (str (fs/relativize root path))]
    (when (and (not (declaration-boundary? relative))
               (str/includes? (slurp (str path)) "app.ui-"))
      (try
        (for [form (read-forms path)
              offender (offenders form)]
          (str relative ": " (pr-str offender)))
        (catch Exception error
          (throw (ex-info (str "Cannot parse " relative) {:path relative} error)))))))


(defn- inspect-edn
  [root path]
  (let [relative (str (fs/relativize root path))
        data (edn/read-string (slurp (str path)))
        result (volatile! [])]
    (letfn [(visit
              [value]
              (cond
                (map? value) (do
                               (when (and (string? (:value value))
                                          (re-find #"app\.ui-[A-Za-z0-9_-]+[/.:]" (:value value)))
                                 (vswap! result conj (str relative ": literal UI target in graph value")))
                               (doseq [[_ nested] value] (visit nested)))
                (coll? value) (doseq [nested value] (visit nested))))]
      (visit data))
    @result))


(defn scan
  [root]
  (concat (mapcat #(inspect-source root %) (source-paths root))
          (mapcat #(inspect-edn root %) (fs/glob root "resources/packages/**/fns.edn"))))


(when (= *file* (System/getProperty "babashka.file"))
  (let [root (fs/canonicalize ".")
        failures (vec (scan root))]
    (doseq [failure failures] (println failure))
    (if (seq failures)
      (System/exit 1)
      (println "UI graph target declarations: OK"))))
