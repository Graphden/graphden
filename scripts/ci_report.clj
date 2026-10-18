(ns ci-report
  "Small, secret-free machine receipt for one CI invocation. A missing or
   incomplete receipt is never proof that a required check passed."
  (:require
    [clojure.edn :as edn])
  (:import
    (java.nio.file
      Files
      Paths
      StandardCopyOption)))


(defn receipt
  [{:keys [sha command since checks scoped manual status results elapsed-ms exit wait-ms path]}]
  {:schema 1
   :complete true
   :sha sha
   :command command
   :scope {:since since
           :selected (mapv :name checks)
           :out-of-scope (mapv :name scoped)
           :manual-skip (mapv :name manual)}
   :exit exit
   :artifact {:kind :ci-report :path path}
   :duration-ms elapsed-ms
   :wait (when (pos? wait-ms) {:reason :ci-lock :duration-ms wait-ms})
   :checks (mapv (fn [check]
                   (let [name (:name check)
                         result (get results name)]
                     {:name name
                      :command ["bb" (:bb check)]
                      :scope (cond
                               (some #(= name (:name %)) scoped) :out-of-scope
                               (some #(= name (:name %)) manual) :manual-skip
                               :else :selected)
                      :status (get status name :missing)
                      :exit (:exit result)
                      :duration-ms (:duration-ms result)}))
                 (concat checks scoped manual))})


(defn complete-pass?
  "Consumer-side guard: skipped, missing or interrupted required jobs cannot
   be interpreted as a successful full report."
  [report required]
  (and (true? (:complete report))
       (zero? (or (:exit report) -1))
       (every? (fn [name]
                 (some #(and (= name (:name %))
                             (#{:passed :retried} (:status %))
                             (zero? (or (:exit %) -1)))
                       (:checks report)))
               required)))


(defn write-atomic!
  [path report]
  (let [target (Paths/get path (make-array String 0))
        parent (or (java.nio.file.Path/.getParent target) (Paths/get "." (make-array String 0)))
        _ (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
        temp (Files/createTempFile parent ".ci-report-" ".tmp"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (java.nio.file.Path/.toFile temp) (str (pr-str report) "\n"))
      (Files/move temp target (into-array StandardCopyOption
                                          [StandardCopyOption/REPLACE_EXISTING
                                           StandardCopyOption/ATOMIC_MOVE]))
      (finally (Files/deleteIfExists temp)))))


(defn read-report
  [path]
  (edn/read-string (slurp path)))
