(ns ci-resume-cli
  (:require
    [ci-report :as report]
    [ci-resume :as resume]
    [clojure.string :as str]))


(let [[command kind & args] *command-line-args*]
  (case command
    "carry" (resume/carry-hints! kind (first args))
    "start" (let [[path source sha & roster] args
                  run (resume/begin! path kind sha roster (resume/read-hint source kind)
                                     (= "1" (System/getenv "GRAPHDEN_RESUME_CREDIT")))]
              (println (str/join "\n" (:roster run))))
    "auto" (println (str/join "\n" (:roster (resume/invocation! kind args))))
    "mark" (let [[path id status] args
                 receipt (resume/read-hint path kind)]
             (when-not receipt (throw (ex-info "Missing progress receipt" {:path path})))
             (resume/mark! {:path path :state (atom receipt)} id (keyword status)))
    "eligible" (let [[gate-path expected-path sha base] args
                     gate (try (report/read-report gate-path) (catch Exception _ nil))
                     expected (try (report/read-report expected-path) (catch Exception _ nil))]
                 (when-not (resume/interrupted-gate? gate expected sha base)
                   (System/exit 1)))
    "credited" (let [[path sha & roster] args
                     prior (resume/read-hint path kind)]
                 (println (str/join "\n" (resume/credited prior sha roster))))
    "match" (let [[path sha & roster] args]
              (when-not (resume/exact-credit? (resume/read-hint path kind) sha roster)
                (System/exit 1)))
    "status" (let [[path id] args
                   receipt (resume/read-hint path kind)]
               (println (name (get-in receipt [:status id] :missing))))
    (throw (ex-info "Unknown resume command" {:command command}))))
