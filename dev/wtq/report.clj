(ns wtq.report
  "Secret-free, atomic receipt for one assembled merge train. The initial
   incomplete receipt survives a killed gate and cannot be read as a pass."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    (java.nio.file
      Files
      Paths
      StandardCopyOption)))


(defn- write-atomic!
  [path value]
  (let [target (Paths/get path (make-array String 0))
        parent (java.nio.file.Path/.getParent target)
        temp (Files/createTempFile parent ".wtq-report-" ".tmp"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (java.nio.file.Path/.toFile temp) (str (pr-str value) "\n"))
      (Files/move temp target (into-array StandardCopyOption
                                          [StandardCopyOption/REPLACE_EXISTING
                                           StandardCopyOption/ATOMIC_MOVE]))
      (finally (Files/deleteIfExists temp)))))


(def ^:private gate-summary-header "── where the gate spent its time ──")


(defn- gate-timings
  [output]
  (let [lines (str/split-lines output)
        header (last (keep-indexed (fn [index line]
                                     (when (= gate-summary-header line) index))
                                   lines))]
    (when-not header
      (throw (ex-info "Gate timing summary missing" {})))
    (let [[phase-lines after] (split-with #(not (re-matches #"\s+------" %))
                                          (drop (inc header) lines))
          total (some->> (second after)
                         (re-matches #"\s+(\d+)s\s+TOTAL")
                         second
                         parse-long)
          phases (into [] (keep (fn [line]
                                  (when-let [[_ seconds name]
                                             (re-matches #"\s+(\d+)s\s+(.+)" line)]
                                    {:name name :duration-ms (* 1000 (parse-long seconds))})))
                       phase-lines)]
      (when-not (and total (seq phases))
        (throw (ex-info "Gate timing summary incomplete" {})))
      {:duration-ms (* 1000 total) :phases phases})))


(defn- scope
  [output]
  (into {} (keep (fn [line]
                   (when-let [[_ name verdict reason]
                              (re-matches #"\s+(integration|image build|e2e|fleet e2e|visual):\s+(RUN|SKIPPED)(.*)" line)]
                     [(keyword (str/replace name " " "-"))
                      (cond
                        (= verdict "RUN") :selected
                        (str/includes? reason "--no-") :manual-skip
                        :else :out-of-scope)])))
        (str/split-lines output)))


(defn- image
  [output]
  (when-let [[_ id jar-sha]
             (re-find #"verified candidate image (\S+): executor-server.jar sha256 ([a-f0-9]{64})" output)]
    {:id id :jar-sha256 jar-sha}))


(defn- report
  [base output ci-path resources exit]
  (let [ci (when (java.io.File/.exists (io/file ci-path))
             (select-keys (edn/read-string (slurp ci-path))
                          [:complete :sha :exit :scope]))
        timings (gate-timings output)]
    (assoc base
           :complete true
           :exit exit
           :duration-ms (:duration-ms timings)
           :scope (scope output)
           :artifact {:image (image output)
                      :ci-report ci-path
                      :ci-selection (str/replace ci-path #"\.ci\.edn$" ".ci-expected.edn")
                      :resources resources}
           :ci ci
           :phases (:phases timings))))


(defn- base
  [sha queue-ms]
  (let [admission-ms (parse-long (or (System/getenv "WTQ_ADMISSION_MS") "0"))]
    {:schema 1 :complete false :sha sha
     :command ["bb" "wt" "merge"]
     :wait {:reason :merge-train-queue
            :duration-ms (parse-long queue-ms)
            :admission (when (pos? admission-ms)
                         {:reason (keyword (System/getenv "WTQ_ADMISSION_REASON"))
                          :duration-ms admission-ms})}}))


(defn -main
  [& args]
  (let [[mode path sha queue-ms & remaining] args]
    (case mode
      "init" (write-atomic! path (base sha queue-ms))
      "finish" (let [[gate-output ci-path resources exit] remaining]
                 (write-atomic! path (report (base sha queue-ms) (slurp gate-output)
                                             ci-path resources (parse-long exit))))
      ("landed" "landed-image") (let [receipt (edn/read-string (slurp path))]
                                  (when-not (and (= 1 (:schema receipt))
                                                 (true? (:complete receipt))
                                                 (zero? (or (:exit receipt) -1))
                                                 (string? (:sha receipt)))
                                    (throw (ex-info "Train did not complete successfully" {:path path})))
                                  (when (and (= mode "landed-image")
                                             (not (and (= :selected (get-in receipt [:scope :image-build]))
                                                       (re-matches #"sha256:[a-f0-9]{64}"
                                                                   (or (get-in receipt [:artifact :image :id]) ""))
                                                       (re-matches #"[a-f0-9]{64}"
                                                                   (or (get-in receipt [:artifact :image :jar-sha256]) "")))))
                                    (throw (ex-info "Train did not verify and promote an image" {:path path})))
                                  (println (:sha receipt)))
      (throw (ex-info "unknown WTQ report mode" {:mode mode})))))


(apply -main *command-line-args*)
