(ns heap-interruption
  "Qualify an exact validation checkpoint after integration exhausted the JVM heap."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]))


(def ^:private exhausted
  #"(?m)^Execution error \(OutOfMemoryError\)[^\n]*\.\r?\nJava heap space\r?$")


(def ^:private progress
  #"integration progress: (\d+)/(\d+) passed, (\d+) failed")


(defn verified?
  [candidate expected stages wave trace sha base]
  (let [{error-class :clojure.error/class cause :clojure.error/cause
         phase :clojure.error/phase} (:clojure.main/triage trace)
        checkpoints (re-seq progress wave)]
    (and (= 1 (:schema candidate) (:schema expected) (:schema stages))
         (= sha (:sha candidate) (:sha expected) (:sha stages))
         (= base (get-in expected [:scope :since]))
         (= :validate (:mode candidate)) (true? (:complete candidate)) (= 1 (:exit candidate))
         (= 0 (get-in candidate [:ci :exit])) (= sha (get-in candidate [:ci :sha]))
         (= :selected (get-in candidate [:scope :integration]))
         (= [:passed :passed :failed]
            (mapv #(get-in stages [:status %]) ["ci" "build" "priority"]))
         (= 1 (count (re-seq exhausted wave)))
         (seq checkpoints)
         (every? #(= "0" (nth % 3)) checkpoints)
         (let [[_ passed total] (last checkpoints)]
           (< 0 (parse-long passed) (parse-long total)))
         (not (re-find #"(?m)^\s*(?:FAIL in |ERROR in )" wave))
         (= 'java.lang.OutOfMemoryError error-class) (= "Java heap space" cause) (= :execution phase)
         (= "Java heap space" (get-in trace [:clojure.main/trace :cause]))
         (str/includes? wave "FAIL: automatic related integration"))))


(defn- evidence
  [prefix]
  (let [read-edn #(edn/read-string (slurp %))
        log (slurp (str prefix ".log"))
        wave (last (str/split log #"(?m)^>>> automatic related integration namespaces\b" 2))
        reports (re-seq #"(?m)^Full report at:\r?\n(/tmp/clojure-[0-9]+\.edn)\r?$" wave)]
    (when (and (not= wave log) (= 1 (count reports)))
      [(read-edn (str prefix ".report.edn"))
       (read-edn (str prefix ".ci-expected.edn"))
       (read-edn (str prefix ".stages.edn"))
       wave
       (read-edn (second (first reports)))])))


(defn -main
  [prefix sha base]
  (let [matched? (try
                   (when-let [receipt (evidence prefix)]
                     (apply verified? (into receipt [sha base])))
                   (catch Exception _ false))]
    (when-not matched? (System/exit 1))))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
