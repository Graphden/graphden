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
                                     (= "1" (System/getenv "GRAPHDEN_RESUME_CREDIT")))
                  manual-stage (System/getenv "WTQ_MANUAL_FIRST_STAGE")
                  ordered (if (and (= kind "stages") (some #{manual-stage} (:roster run)))
                            (resume/stage-dependencies
                              (into [manual-stage] (remove #{manual-stage} (:roster run))))
                            (:roster run))]
              (when (not= ordered (:roster run))
                (resume/record-order! run ordered))
              (println (str/join "\n" ordered)))
    "auto" (println (str/join "\n" (:roster (resume/invocation! kind args))))
    "mark" (let [[path id status] args
                 receipt (resume/read-hint path kind)]
             (when-not receipt (throw (ex-info "Missing progress receipt" {:path path})))
             (resume/mark! {:path path :state (atom receipt)} id (keyword status)))
    "proof" (let [[path id proof-path] args
                  receipt (resume/read-hint path kind)
                  proof (report/read-report proof-path)]
              (when-not (and receipt (resume/valid-external-proof? proof (:sha receipt)))
                (throw (ex-info "Progress proof target mismatch" {})))
              (resume/mark-proof! {:path path :state (atom receipt)} id proof))
    "verify-proof" (let [[path sha] args]
                     (when-not (resume/valid-external-proof? (report/read-report path) sha)
                       (throw (ex-info "Invalid hosted integration receipt" {}))))
    "proof-source" (let [[path id] args]
                     (println (name (get-in (resume/read-hint path kind)
                                            [:proof id :source] :local))))
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
