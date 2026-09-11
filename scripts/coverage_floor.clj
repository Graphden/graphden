#!/usr/bin/env bb
;; Grade the cloverage report PER LAYER, and fail on a regression in either.
;;
;; The single "ALL FILES" number stopped being a usable gate the day the
;; package layer joined the report (2026-09-10): 42 impls.clj namespaces,
;; ~17k forms that reported nothing before, landed at ~45% and pulled the
;; headline from 73.15% to 70.16% — a 3-point "drop" in which not one
;; src/ namespace had regressed. A floor on that aggregate either has to
;; be lowered (hiding a real regression behind the new denominator) or it
;; reds `main` on arithmetic.
;;
;; So grade the two populations separately, against the floor each was
;; calibrated on:
;;
;;   src/      — the historical floor, unchanged. 73.2% today.
;;   impls     — the package layer's own baseline, so it can only improve.
;;
;; Reads `target/coverage/index.html` (per-namespace covered-forms + form%;
;; no 0% namespace exists, so totals derive exactly). Run after `bb coverage`.
(ns coverage-floor
  (:require
    [clojure.string :as str]))


(def ^:private report-path "target/coverage/index.html")


(def ^:private floors
  "Per-layer FORM-coverage floors. Each carries ~3 points of headroom over
   the reading it was set from — the same margin the single floor always
   had. Raise a floor when its layer climbs; never lower one to make a
   run pass."
  {:src {:floor 70.0 :measured "73.50% on 2026-09-11"}
   :impls {:floor 41.0 :measured "44.1-45.0% across runs, 2026-09-11"}})


(defn- strip-tags
  [s]
  (-> s (str/replace #"<[^>]+>" " ") str/trim))


(defn- rows
  "`[namespace covered-forms form-%]` per namespace row of the report."
  [html]
  (keep (fn [tr]
          (let [cells (mapv strip-tags (map second (re-seq #"(?s)<td[^>]*>(.*?)</td>" tr)))]
            (when (and (>= (count cells) 3) (str/starts-with? (first cells) "graphden"))
              (let [covered (some-> (re-find #"\d+" (nth cells 1)) parse-double)
                    pct (some-> (re-find #"\d+\.\d+" (nth cells 2)) parse-double)]
                (when (and covered pct) [(first cells) covered pct])))))
        (map second (re-seq #"(?s)<tr>(.*?)</tr>" html))))


(defn- layer
  [ns-name]
  (if (str/ends-with? ns-name ".impls") :impls :src))


(defn- totals
  [rs]
  (reduce (fn [acc [ns-name covered pct]]
            (if (zero? pct)
              ;; A 0% namespace hides its own denominator — the report gives
              ;; covered forms and a percentage, nothing else. None exists
              ;; today; if one appears, say so rather than quietly skewing
              ;; the aggregate upward by dropping it.
              (update acc :unknown conj ns-name)
              (-> acc
                  (update-in [(layer ns-name) :covered] + covered)
                  (update-in [(layer ns-name) :total] + (/ covered (/ pct 100.0))))))
          {:src {:covered 0.0 :total 0.0} :impls {:covered 0.0 :total 0.0} :unknown []}
          rs))


(defn -main
  [& _]
  (let [html (try (slurp report-path)
                  (catch Exception _
                    (println (str "coverage-floor: no " report-path
                                  " — run `bb coverage` first"))
                    (System/exit 2)))
        t (totals (rows html))
        pct (fn [k]
              (let [{:keys [covered total]} (get t k)]
                (if (pos? total) (* 100.0 (/ covered total)) 0.0)))
        breached (atom false)]
    (when (seq (:unknown t))
      (println (str "coverage-floor: " (count (:unknown t))
                    " namespace(s) report 0% — their denominator is unknowable"
                    " from the report, so they are NOT in the aggregate: "
                    (str/join " " (:unknown t)))))
    (println (format "%-8s %9s %9s %9s" "layer" "forms%" "floor" "verdict"))
    (doseq [k [:src :impls]]
      (let [p (pct k) f (:floor (get floors k))
            ok? (>= p f)]
        (when-not ok? (reset! breached true))
        (println (format "%-8s %8.2f%% %8.1f%% %9s  (%s)"
                         (name k) p f (if ok? "ok" "BELOW")
                         (:measured (get floors k))))))
    (if @breached
      (do (println "coverage-floor: a layer regressed — add tests, do not lower the floor.")
          (System/exit 1))
      (println "coverage-floor: every layer at or above its floor"))))


(apply -main *command-line-args*)
