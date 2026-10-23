;; A bb.edn edit is tooling-only only when it changes a known CI task and no
;; shared task setup. Runtime/build tasks share :init and :requires, so either
;; changing those or adding an unknown task must retain the full gate.
(ns wtq.bb-scope
  (:require
    [clojure.edn :as edn]
    [clojure.java.shell :as sh])
  (:import
    (java.io
      PushbackReader
      StringReader)))


(def ci-tasks
  '#{ci ci-test wtq-test lint check lint-clj lint-web lint-infra
     lint-docs lint-sec ci-priority staged-biome-affected staged-biome-rest
     staged-js-affected staged-js-rest staged-status})


(defn read-bb
  [repo ref]
  (let [{:keys [exit out]} (sh/sh "git" "-C" repo "show" (str ref ":bb.edn"))]
    (when (zero? exit)
      (with-open [reader (PushbackReader. (StringReader. out))]
        (let [eof (Object.)
              value (edn/read {:eof eof} reader)]
          (when (and (map? value)
                     (map? (:tasks value))
                     (identical? eof (edn/read {:eof eof} reader)))
            value))))))


(defn ci-only-change?
  [before after]
  (when (and before after
             (= (dissoc before :tasks) (dissoc after :tasks)))
    (let [old-tasks (:tasks before)
          new-tasks (:tasks after)
          changed (into #{} (filter #(not= (get old-tasks %) (get new-tasks %)))
                        (into (set (keys old-tasks)) (keys new-tasks)))]
      (and (seq changed)
           (every? #(and (contains? old-tasks %)
                         (contains? new-tasks %)
                         (contains? ci-tasks %))
                   changed)))))


(try
  (let [[repo base candidate] *command-line-args*]
    (System/exit
      (if (and (= 3 (count *command-line-args*))
               (ci-only-change? (read-bb repo base) (read-bb repo candidate)))
        0
        1)))
  (catch Exception _
    (System/exit 1)))
