(ns ci-priority-handoff
  "Share the train's advisory ordering between two processes. Both still
   select their mandatory checks and verify receipts independently."
  (:require
    [babashka.fs :as fs]
    [ci-priority-core :as priority]
    [ci-report :as report]
    [ci-resume :as resume]
    [clojure.edn :as edn])
  (:import
    (java.nio.file
      Files)
    (java.security
      MessageDigest)))


(defn kaocha-hints
  [plan]
  (select-keys plan [:unit :unit-layers :unit-manual :integration :integration-layers]))


(defn- sha256
  [^bytes content]
  (let [digest (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") content)]
    (format "%064x" (java.math.BigInteger. 1 digest))))


(defn- identity-data
  [sha base scope manual]
  {:schema 2 :sha sha :base base :scope scope :manual manual})


(defn write-gate-plan!
  "Write the exact snapshot and return its digest to the invoking gate shell."
  [path sha base expected-path manual]
  (let [{expected-sha :sha :keys [schema scope]} (report/read-report expected-path)
        initial (resume/checkout-candidate)]
    (when-not (and (= 1 schema) (= sha expected-sha (:sha initial))
                   (string? base) (re-matches #"[0-9a-f]{40}" base)
                   (= base (:since scope) (resume/git-output "rev-parse" (str base "^{commit}")))
                   (report/stable-candidate? initial initial))
      (throw (ex-info "Priority plan requires the clean expected candidate and commit base" {})))
    (let [plan (binding [*out* *err*] (priority/plan (priority/changed-paths base) manual))
          payload (str (pr-str (assoc (identity-data sha base scope manual) :plan plan)) "\n")]
      (when-not (report/stable-candidate? initial (resume/checkout-candidate))
        (throw (ex-info "Candidate changed while preparing priority hints" {})))
      (spit path payload :encoding "UTF-8")
      {:digest (sha256 (String/.getBytes payload "UTF-8")) :plan plan})))


(defn- read-plan
  [path digest expected]
  (when (and (string? digest) (re-matches #"[0-9a-f]{64}" digest)
             path (fs/regular-file? path))
    (try
      (let [payload (Files/readAllBytes (fs/path path))]
        (when (= digest (sha256 payload))
          (let [{:keys [plan] :as saved} (edn/read-string (String. payload "UTF-8"))]
            (when (and (map? plan) (= expected (dissoc saved :plan)))
              plan))))
      (catch Exception _ nil))))


(defn ci-hints
  "Allow this invocation's checked plan to affect order. Recompute hints when
   standalone or when the payload, immutable candidate or roster differs."
  [{:keys [candidate scope manual path digest]}]
  (let [base (:since scope)
        plan (when (report/stable-candidate? candidate candidate)
               (read-plan path digest (identity-data (:sha candidate) base scope manual)))]
    (when (and digest (nil? plan))
      (binding [*out* *err*] (println "CI priority handoff unavailable; recalculating ordering")))
    (kaocha-hints (or plan (priority/plan (priority/changed-paths base) manual)))))
