(ns graphden.util.json-safe
  "Make arbitrary ex-data safe to hand to a JSON encoder.

   An exception thrown anywhere under `/api/execute` carries its ex-data
   out twice: into the response envelope's `:error-data` and into the
   `fn-execution` row's jsonb column. ex-data is author-controlled — a
   base-fn, a validator, a library adapter may put anything in it — and a
   value cheshire cannot encode (a `java.lang.Class`, an atom, a function,
   a PGobject) turns an honest, well-typed failure into an opaque 500 with
   nothing but a log ref for the caller.

   The point is not to hide such values: they are rendered with `pr-str`,
   so the diagnostic survives in a form the client can display."
  (:require
    [clojure.walk :as walk]))


(defn- encodable?
  [v]
  (or (nil? v)
      (boolean? v) (string? v) (number? v)
      (keyword? v) (symbol? v) (uuid? v)
      (instance? java.util.Date v)
      (instance? java.time.Instant v)
      (map? v) (coll? v)))


(defn json-safe
  "`value` with every non-JSON-encodable leaf replaced by its `pr-str`
   form. Maps, vectors, lists and sets are walked; their keys are
   normalised the same way, since a map key is a leaf to the encoder."
  [value]
  (walk/postwalk (fn [v] (if (encodable? v) v (pr-str v))) value))
