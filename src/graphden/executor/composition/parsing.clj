(ns graphden.executor.composition.parsing
  "Parsing helpers shared between dep analysis and record prep.

   - `valid-identifier?` — string shape check for fn-ref detection.
   - `parse-fn-ref` — returns the keyword if it looks like a fn ref,
     else nil.")


(defn valid-identifier?
  "Returns true if s looks like a valid identifier.
   Allows dots for qualified namespace references (e.g. core.arithmetic.add)
   and the Clojure convention of trailing `?` / `!` (e.g. empty?, swap!)."
  [s]
  (when (and (string? s) (seq s))
    (and (not (re-find #"\s" s))
         (re-matches #"[a-zA-Z_\-][a-zA-Z0-9_.\-?!]*" s))))


(defn parse-fn-ref
  "Parses a keyword that might be a fn reference.
   Returns fn-name keyword or nil if not a fn ref."
  [value]
  (when (keyword? value)
    (let [kw-name (name value)]
      (when (valid-identifier? kw-name)
        value))))
