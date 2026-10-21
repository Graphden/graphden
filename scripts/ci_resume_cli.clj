(ns ci-resume-cli
  (:require
    [ci-resume :as resume]
    [clojure.string :as str]))


(let [[command kind & args] *command-line-args*]
  (case command
    "carry" (resume/carry-hints! kind (first args))
    "start" (let [[path source sha & roster] args
                  run (resume/begin! path kind sha roster (resume/read-hint source kind))]
              (println (str/join "\n" (:roster run))))
    "auto" (println (str/join "\n" (:roster (resume/invocation! kind args))))
    "mark" (let [[path id status] args
                 receipt (resume/read-hint path kind)]
             (when-not receipt (throw (ex-info "Missing progress receipt" {:path path})))
             (resume/mark! {:path path :state (atom receipt)} id (keyword status)))
    (throw (ex-info "Unknown resume command" {:command command}))))
