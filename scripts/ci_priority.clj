(require '[babashka.fs :as fs])
(load-file (str (fs/parent *file*) "/ci_priority_core.clj"))


(require '[ci-priority-core :as priority]
         '[clojure.string :as str])


;; This CLI prints ordering hints only. The authoritative suite owns execution.
(let [[mode base] *command-line-args*
      manual (priority/manual-hints (System/getenv "GRAPHDEN_CI_FIRST_TESTS"))
      changed (priority/changed-paths (or base (System/getenv "GRAPHDEN_CI_SINCE")))]
  (case (or mode "run")
    "run" (prn (priority/plan changed manual))
    "kaocha-hints" (prn (select-keys (priority/plan changed manual)
                                     [:unit :unit-layers :integration :integration-layers]))
    "runtime-order" (println (str/join "\n" (priority/runtime-order changed (:runtime manual))))
    "browser" (println (str/join "\n" (:browser (priority/plan changed manual))))
    "browser-order" (println (str/join "\n" (priority/browser-order changed (:browser manual))))
    "validate-manual" (do (priority/runtime-order nil (:runtime manual))
                          (priority/browser-order nil (:browser manual))
                          (priority/plan nil manual)
                          (println "manual test priorities valid"))
    (throw (ex-info "Unknown priority mode" {:mode mode}))))
