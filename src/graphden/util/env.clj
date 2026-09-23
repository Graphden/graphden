(ns graphden.util.env
  "Parsers for values that arrive through environment variables (directly,
   or via Aero `#env`, which collapses an unset var to `\"\"`)."
  (:require
    [clojure.string :as str]))


(defn env-truthy?
  "Parse a wire-friendly truthy flag. Accepts the EDN literal `true`,
   or any of `\"1\" \"true\" \"yes\" \"on\"` (case-insensitive) when
   the value came through an env var. Anything else (including the
   empty string from an unset env in `system-prod.edn`) is OFF.

   Used wherever an integrant arg can come from Aero `#env` (which
   collapses unset vars to `\"\"`, a truthy value in Clojure)."
  [raw]
  (cond
    (true? raw)                  true
    (or (false? raw) (nil? raw)) false
    (string? raw)                (contains? #{"1" "true" "yes" "on"}
                                            (str/lower-case raw))
    :else                        (boolean raw)))


(defn csv-list
  "`\" a , , b \"` → `[\"a\" \"b\"]` — trimmed, blanks dropped, order kept.
   nil / blank / all-blank → nil, so a caller's `or` falls through to its
   default."
  [s]
  (when-not (str/blank? s)
    (not-empty (into [] (comp (map str/trim) (remove str/blank?)) (str/split s #",")))))
