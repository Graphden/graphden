(require '[babashka.fs :as fs])
(load-file (str (fs/parent *file*) "/ci_priority_core.clj"))


(require '[ci-priority-core :as priority]
         '[ci-priority-handoff :as handoff]
         '[clojure.string :as str])


;; This CLI prints ordering hints only. The authoritative suite owns execution.
(let [[mode base prefix sha expected] *command-line-args*
      manual (priority/manual-hints (System/getenv "GRAPHDEN_CI_FIRST_TESTS"))
      changed (delay (priority/changed-paths (or base (System/getenv "GRAPHDEN_CI_SINCE"))))]
  (case (or mode "run")
    "run" (prn (priority/plan @changed manual))
    "gate-plan" (do
                  (when-not (and (seq prefix) (seq expected))
                    (throw (ex-info "Gate plan requires an output prefix and expected CI roster" {})))
                  (let [{:keys [plan digest]} (handoff/write-gate-plan! (str prefix ".edn") sha base expected manual)]
                    (spit (str prefix ".integration") (str/join "\n" (:integration plan)))
                    (spit (str prefix ".browser") (str/join "\n" (:browser plan)))
                    (spit (str prefix ".kaocha.edn") (pr-str (handoff/kaocha-hints plan)))
                    (println digest)))
    "kaocha-hints" (prn (handoff/kaocha-hints (priority/plan @changed manual)))
    "runtime-order" (println (str/join "\n" (priority/runtime-order @changed (:runtime manual))))
    "integration" (println (str/join "\n" (:integration (priority/plan @changed manual))))
    "browser" (println (str/join "\n" (:browser (priority/plan @changed manual))))
    "browser-order" (println (str/join "\n" (priority/browser-order @changed (:browser manual))))
    "validate-manual" (do (priority/runtime-order nil (:runtime manual))
                          (priority/browser-order nil (:browser manual))
                          (priority/plan nil manual)
                          (println "manual test priorities valid"))
    (throw (ex-info "Unknown priority mode" {:mode mode}))))
