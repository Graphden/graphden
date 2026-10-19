(require '[babashka.fs :as fs])
(load-file (str (fs/parent *file*) "/ci_priority_core.clj"))


(require '[babashka.process :as p]
         '[ci-priority-core :as priority]
         '[clojure.string :as str])


(defn- run-command!
  [label command]
  (println "priority:" label)
  (flush)
  (let [result (apply p/shell {:continue true} command)]
    (when-not (zero? (:exit result))
      (println "priority failed:" label)
      (System/exit 1))))


(defn- run-kaocha!
  [suite ns-name]
  (println "priority:" suite ns-name)
  (flush)
  (let [result @(p/process {:cmd ["clojure" "-M:dev:test" "-m" "kaocha.runner"
                                  suite "--focus" ns-name]
                            :out :string :err :string})
        output (str (:out result) "\n" (:err result))]
    (print (:out result))
    (binding [*out* *err*] (print (:err result)))
    (flush)
    (when (or (not (zero? (:exit result)))
              (priority/empty-focused-run? output))
      (println "priority failed or selected no tests:" suite ns-name)
      (System/exit 1))))


(defn- run-focused!
  [base]
  (let [{:keys [runtime unit integration]} (priority/plan (priority/changed-paths base))]
    (if (and (empty? runtime) (empty? unit) (empty? integration))
      (println "priority: no exact source-to-test match; full suite follows")
      (do
        (doseq [path runtime]
          (run-command! path ["node" path]))
        (doseq [ns-name unit]
          (run-kaocha! ":unit" ns-name))
        (doseq [ns-name integration]
          (run-kaocha! ":integration" ns-name))))))


(let [[mode base] *command-line-args*]
  (case (or mode "run")
    "run" (run-focused! (or base (System/getenv "GRAPHDEN_CI_SINCE")))
    "browser" (println (str/join "\n" (priority/browser-first (priority/changed-paths base))))
    "browser-order" (println (str/join "\n" (priority/browser-order (priority/changed-paths base))))
    (throw (ex-info "Unknown priority mode" {:mode mode}))))
