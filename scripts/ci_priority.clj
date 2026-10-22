(require '[babashka.fs :as fs])
(load-file (str (fs/parent *file*) "/ci_priority_core.clj"))


(require '[ci-priority-core :as priority]
         '[clojure.string :as str])


;; This CLI prints ordering hints only. The authoritative suite owns execution.
(let [[mode base] *command-line-args*
      changed (priority/changed-paths (or base (System/getenv "GRAPHDEN_CI_SINCE")))]
  (case (or mode "run")
    "run" (prn (priority/plan changed))
    "kaocha-hints" (prn (select-keys (priority/plan changed) [:unit :integration]))
    "runtime-order" (println (str/join "\n" (priority/runtime-order changed)))
    "browser" (println (str/join "\n" (priority/browser-first changed)))
    "browser-order" (println (str/join "\n" (priority/browser-order changed)))
    (throw (ex-info "Unknown priority mode" {:mode mode}))))
