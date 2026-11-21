(ns structured-input-checked-migration
  "Offline EDN migration with the trusted checkout's implementation signatures."
  (:require
    [graphden.executor.registry.core :as registry]
    [graphden.input.source-certification :as certification]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.sync :as sync]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [structured-input-dry-run :as dry]
    [structured-input-inventory :as inventory]))


(defn- trusted-signatures
  [package-names]
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})
            shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
    (let [{:keys [fn-defs base-fn-defs]} (loader/load-packages package-names)]
      (sync/register-type-aliases! fn-defs)
      (doseq [[name {module-ns :namespace :as base}] base-fn-defs]
        (registry/record-rich-types! (ids/fn-id module-ns name) name base))
      (:by-id @registry/*rich-types-override*))))


(defn- arguments
  [args]
  (let [[pairs source-options] (split-with #(not= "--repo" %) args)]
    (when-not (and (seq pairs) (even? (count pairs))
                   (every? (fn [[flag name]]
                             (and (= "--trusted-package" flag) (re-matches #"[a-z0-9][a-z0-9.-]*" name)))
                           (partition 2 pairs)))
      (throw (ex-info "Provide --trusted-package NAME before --repo arguments"
                      {:code :trusted-package-required})))
    (assoc (dry/cli-options source-options) :packages (mapv second (partition 2 pairs)))))


(defn- source-report
  [{:keys [packages repositories manifest artifact]}]
  (let [pinned (when manifest (inventory/read-edn manifest (slurp manifest)))
        roots (trusted-signatures packages)
        options {:verify-scopes #(certification/verify-plan %1 %2 %3 roots)}]
    (if artifact
      (let [result (dry/migration-artifact repositories pinned options)]
        (dry/write-artifact! artifact result)
        (:report result))
      (dry/dry-run repositories pinned options))))


(defn -main
  [& args]
  (try
    (let [options (arguments args)
          stdout System/out
          report (try
                   ;; Native package logs go to stderr. Stdout is EDN for the caller.
                   (System/setOut System/err)
                   (binding [*out* *err*] (source-report options))
                   (finally (System/setOut stdout)))]
      (print (inventory/report-text report)))
    (catch Exception failure
      (binding [*out* *err*]
        (prn {:code (or (:code (ex-data failure)) (:type (ex-data failure)) :checked-migration-failed)}))
      (System/exit 2))))
