(require '[babashka.fs :as fs])
(load-file (str (fs/parent *file*) "/ci_priority_core.clj"))


(require '[ci-priority-core :as priority]
         '[clojure.string :as str])


;; This CLI prints ordering hints only. The authoritative suite owns execution.
(let [[mode base prefix sha] *command-line-args*
      manual (priority/manual-hints (System/getenv "GRAPHDEN_CI_FIRST_TESTS"))
      changed (priority/changed-paths (or base (System/getenv "GRAPHDEN_CI_SINCE")))]
  (case (or mode "run")
    "run" (prn (priority/plan changed manual))
    "gate-plan" (do
                  (when-not (and (seq prefix) (string? sha) (re-matches #"[0-9a-f]{40}" sha))
                    (throw (ex-info "Gate plan requires an output prefix and candidate SHA" {})))
                  (let [plan (priority/plan changed manual)]
                    ;; Fresh train-local artifacts are hints, never validation credit.
                    (spit (str prefix ".edn") (pr-str {:schema 1 :sha sha :base base :plan plan}))
                    (spit (str prefix ".integration") (str/join "\n" (:integration plan)))
                    (spit (str prefix ".browser") (str/join "\n" (:browser plan)))
                    (spit (str prefix ".kaocha.edn")
                          (pr-str (select-keys plan [:unit :unit-layers :unit-manual
                                                     :integration :integration-layers])))))
    "kaocha-hints" (prn (select-keys (priority/plan changed manual)
                                     [:unit :unit-layers :unit-manual :integration :integration-layers]))
    "runtime-order" (println (str/join "\n" (priority/runtime-order changed (:runtime manual))))
    "integration" (println (str/join "\n" (:integration (priority/plan changed manual))))
    "browser" (println (str/join "\n" (:browser (priority/plan changed manual))))
    "browser-order" (println (str/join "\n" (priority/browser-order changed (:browser manual))))
    "validate-manual" (do (priority/runtime-order nil (:runtime manual))
                          (priority/browser-order nil (:browser manual))
                          (priority/plan nil manual)
                          (println "manual test priorities valid"))
    (throw (ex-info "Unknown priority mode" {:mode mode}))))
