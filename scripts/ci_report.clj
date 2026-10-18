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
                      :group (:group check)
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


(defn gate-pass?
  "Compare a CI receipt with the selection computed before running CI. The
   report cannot choose its own required set or call a blocking check advisory."
  [report expected]
  (let [checks (:checks report)
        by-name (into {} (map (juxt :name identity)) checks)
        roster (concat (get-in expected [:scope :selected])
                       (get-in expected [:scope :out-of-scope])
                       (get-in expected [:scope :manual-skip]))
        required (remove #(= :info (get-in expected [:groups %]))
                         (get-in expected [:scope :selected]))]
    (and (= 1 (:schema report))
         (= (:sha expected) (:sha report))
         (= (:command expected) (:command report))
         (= (:scope expected) (:scope report))
         (= (count roster) (count checks) (count by-name))
         (= (set roster) (set (keys by-name)))
         (every? (fn [[name group]] (= group (:group (get by-name name))))
                 (:groups expected))
         (every? (fn [name]
                   (let [c (get by-name name)]
                     (and (number? (:exit c)) (number? (:duration-ms c)))))
                 (get-in expected [:scope :selected]))
         (every? (fn [name]
                   (let [c (get by-name name)]
                     (and (= :selected (:scope c))
                          (#{:passed :retried :warning} (:status c)))))
                 (filter #(= :info (get-in expected [:groups %]))
                         (get-in expected [:scope :selected])))
         (every? (fn [name]
                   (let [c (get by-name name)]
                     (and (= :out-of-scope (:scope c)) (= :scoped (:status c)))))
                 (get-in expected [:scope :out-of-scope]))
         (every? (fn [name]
                   (let [c (get by-name name)]
                     (and (= :manual-skip (:scope c)) (= :manual-skip (:status c)))))
                 (get-in expected [:scope :manual-skip]))
         (complete-pass? report required))))


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
