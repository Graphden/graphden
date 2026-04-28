(ns graphden.executor.composition.parsing
  "Parsing helpers shared between dep analysis and record prep.

   - `valid-identifier?` — string shape check for fn-ref detection.
   - `local-fn-name?` — fn-names starting with `_` are local (name=nil
     in storage; referenced by id only).
   - `parse-fn-ref` — returns the keyword if it looks like a fn ref,
     else nil."
  (:require
    [clojure.string :as str]))


(defn valid-identifier?
  "Returns true if s looks like a valid identifier.
   Allows dots for qualified namespace references (e.g. core.arithmetic.add)
   and the Clojure convention of trailing `?` / `!` (e.g. empty?, swap!)."
  [s]
  (when (and (string? s) (seq s))
    (and (not (re-find #"\s" s))
         (re-matches #"[a-zA-Z_\-][a-zA-Z0-9_.\-?!]*" s))))


(defn local-fn-name?
  "Returns true if fn-name starts with _ (local/unnamed fn).
   Local fns are stored with name=nil in DB and only referenced by id."
  [fn-name]
  (when fn-name
    (let [n (if (keyword? fn-name) (name fn-name) (str fn-name))]
      (str/starts-with? n "_"))))


(defn parse-fn-ref
  "Parses a keyword that might be a fn reference.
   Returns fn-name keyword or nil if not a fn ref."
  [value]
  (when (keyword? value)
    (let [kw-name (name value)]
      (when (valid-identifier? kw-name)
        value))))
