;; The runtime classifier may exempt only coordinates whose alias has been
;; audited as tooling-only. Keep every other deps.edn value byte-independent
;; but semantically identical; any unreadable ref/form stays on the full gate.
(ns deps-scope
  (:require
    [clojure.edn :as edn]
    [clojure.java.shell :as shell]
    [clojure.string :as str])
  (:import
    (java.io
      PushbackReader
      StringReader)))


(def coordinate-path
  [:aliases :watson :extra-deps 'io.github.clj-holmes/clj-watson])


(defn read-deps
  [repo ref]
  (let [{:keys [exit out]} (shell/sh "git" "-C" repo "show" (str ref ":deps.edn"))]
    (when (zero? exit)
      (with-open [reader (PushbackReader. (StringReader. out))]
        (let [eof (Object.)
              value (edn/read {:eof eof} reader)]
          (when (and (map? value)
                     (identical? eof (edn/read {:eof eof} reader)))
            value))))))


(defn git-coordinate?
  [value]
  (and (map? value)
       (= #{:git/tag :git/sha} (set (keys value)))
       (string? (:git/tag value))
       (not (str/blank? (:git/tag value)))
       (string? (:git/sha value))
       (boolean (re-matches #"[0-9a-f]{7,40}" (:git/sha value)))))


(defn audited-change?
  [before after]
  (let [old-coordinate (get-in before coordinate-path)
        new-coordinate (get-in after coordinate-path)]
    (and before after
         (git-coordinate? old-coordinate)
         (git-coordinate? new-coordinate)
         (not= old-coordinate new-coordinate)
         (= (assoc-in before coordinate-path ::audited-coordinate)
            (assoc-in after coordinate-path ::audited-coordinate)))))


(try
  (let [[repo base candidate] *command-line-args*]
    (System/exit
      (if (and (= 3 (count *command-line-args*))
               (audited-change? (read-deps repo base) (read-deps repo candidate)))
        0
        1)))
  (catch Exception _
    (System/exit 1)))
